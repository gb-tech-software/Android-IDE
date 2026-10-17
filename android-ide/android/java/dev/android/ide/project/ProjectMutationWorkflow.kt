// ProjectMutationWorkflow owns Phase 2 mutations and lifecycle transitions.
// Every destructive path uses exact-name preflight, provider verification, and
// registry updates only after the authoritative storage operation succeeds.
package dev.android.ide.project

import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.ProjectRegistryAdapter
import dev.android.ide.saf.DocumentPresence
import dev.android.ide.saf.FileManagementService
import dev.android.ide.saf.SafeMutationResult
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProjectMutationWorkflow(
    private val registry: ProjectRegistryAdapter,
    private val storage: ProjectStorageAdapterImpl,
    private val metadata: ProjectMetadataStore,
    private val files: FileManagementService,
) {
    suspend fun removeFromRegistry(projectId: String): OperationReport {
        val project = registered(projectId) ?: return blocked("Project is not registered")
        val capabilities = storage.inspectProjectStorage(project.location)
        if (capabilities.localStorageConfirmed && capabilities.canDelete) {
            val metadataRemoved = metadata.deletePortableState(project)
            if (metadataRemoved.outcome != OperationOutcome.COMPLETE) {
                return OperationReport(
                    outcome = OperationOutcome.PARTIAL,
                    message = "Project remains registered because its Android IDE metadata could not be removed",
                    errorCategory = metadataRemoved.errorCategory ?: ErrorCategory.PERMISSION_LOST,
                    affectedIds = listOf(project.id),
                    recoveryHint = "Restore access to the project folder and retry Remove from Registry",
                )
            }
        }
        val removed = registry.remove(project.id)
        return if (removed.outcome == OperationOutcome.COMPLETE) {
            complete("Project removed from Android IDE", project.id)
        } else {
            OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Android IDE metadata was removed, but the registry record remains",
                errorCategory = removed.errorCategory ?: ErrorCategory.MALFORMED_METADATA,
                affectedIds = listOf(project.id),
                recoveryHint = "Refresh the project registry and retry removal",
            )
        }
    }

    suspend fun permanentlyDelete(projectId: String): OperationReport {
        val project = registered(projectId) ?: return blocked("Project is not registered")
        val registeredChildren = mutableListOf<ProjectIdentity>()
        registry.listRegistered().filter { it.id != project.id }.forEach { candidate ->
            when (storage.isSameOrDescendant(project.location.stableId, candidate.location.stableId)) {
                true -> registeredChildren += candidate
                false -> Unit
                null -> return blocked(
                    "The project cannot be safely deleted until containment against ${candidate.name} is verified",
                    ErrorCategory.PERMISSION_LOST,
                )
            }
        }
        if (registeredChildren.isNotEmpty()) {
            return blocked(
                "Delete the registered child project(s) before deleting this parent: " +
                    registeredChildren.joinToString { it.name },
                ErrorCategory.DESTINATION_CONFLICT,
            )
        }
        val capabilities = storage.inspectProjectStorage(project.location)
        if (!capabilities.localStorageConfirmed || !capabilities.canDelete) {
            return blocked(
                capabilities.localWorkspaceExplanation ?: capabilities.explanation ?: "The project location cannot be deleted",
                ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY,
            )
        }
        files.delete(project.location.stableId)
        when (presenceAfterDelete(project.location.stableId)) {
            DocumentPresence.EXISTS -> return failed(
                "The project still exists after the delete request",
                ErrorCategory.EXTERNAL_FILE_CHANGE,
            )
            DocumentPresence.INACCESSIBLE -> return OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "The project deletion could not be verified; its registry entry was kept",
                errorCategory = ErrorCategory.PERMISSION_LOST,
                affectedIds = listOf(project.id),
                recoveryHint = "Restore access to the project location and verify whether it still exists",
            )
            DocumentPresence.ABSENT -> Unit
        }
        val removed = registry.remove(project.id)
        return if (removed.outcome == OperationOutcome.COMPLETE) {
            complete("Project permanently deleted", project.id)
        } else {
            OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Project data was deleted but the registry could not be updated",
                errorCategory = ErrorCategory.MALFORMED_METADATA,
                recoveryHint = "Refresh the project registry before using this project again",
            )
        }
    }

    suspend fun duplicateProject(
        projectId: String,
        destinationParentUri: String,
        newName: String,
        description: String? = null,
    ): OperationReport {
        val source = registered(projectId) ?: return blocked("Project is not registered")
        val sourceCapabilities = storage.inspectProjectStorage(source.location)
        if (sourceCapabilities.state != dev.android.ide.contracts.CapabilityState.SUPPORTED || !sourceCapabilities.localStorageConfirmed) {
            return blocked(sourceCapabilities.localWorkspaceExplanation ?: sourceCapabilities.explanation ?: "The source project is not available for full project operations", ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        }
        val cleanName = validName(newName) ?: return blocked("Project name is invalid")
        val preflight = preflightDestination(destinationParentUri)
        if (!preflight.allowed) return preflight.report
        val copied = files.copy(source.location.stableId, destinationParentUri, cleanName)
        val rootUri = when (copied) {
            is SafeMutationResult.Created -> copied.documentUri
            SafeMutationResult.Duplicate -> return blocked("The destination already contains that project", ErrorCategory.DESTINATION_CONFLICT)
            SafeMutationResult.InspectionFailed -> return failed("The destination could not be inspected", ErrorCategory.PERMISSION_LOST)
            SafeMutationResult.Failed -> return failed("The project could not be duplicated", ErrorCategory.PERMISSION_LOST)
            is SafeMutationResult.Partial -> return partialMutationReport(copied)
        }
        val identity = identity(
            rootUri,
            cleanName,
            description ?: source.description,
        )
        val containment = registry.preflightRegistration(identity)
        if (!containment.allowed) {
            return cleanupUnregisteredDestination(
                source.location.stableId,
                rootUri,
                blocked(containment.message, containment.errorCategory ?: ErrorCategory.DESTINATION_CONFLICT),
            )
        }
        val initialized = metadata.ensurePortableState(identity)
        if (initialized.outcome != OperationOutcome.COMPLETE) {
            return cleanupUnregisteredDestination(source.location.stableId, rootUri, initialized)
        }
        val written = metadata.writeIdentity(identity)
        if (written.outcome != OperationOutcome.COMPLETE) {
            return cleanupUnregisteredDestination(source.location.stableId, rootUri, written)
        }
        val verified = storage.inspectProjectStorage(identity.location)
        if (verified.state != dev.android.ide.contracts.CapabilityState.SUPPORTED ||
            !verified.readable || !verified.writable
        ) {
            return cleanupUnregisteredDestination(
                source.location.stableId,
                rootUri,
                failed("The duplicated project files could not be read and written", ErrorCategory.PERMISSION_LOST),
            )
        }
        if (!metadataIdentityMatches(identity)) {
            return cleanupUnregisteredDestination(
                source.location.stableId,
                rootUri,
                failed("The duplicate identity could not be read back", ErrorCategory.MALFORMED_METADATA),
            )
        }
        val registered = registry.register(identity)
        if (registered.outcome == OperationOutcome.COMPLETE) return registered
        return cleanupUnregisteredDestination(source.location.stableId, rootUri, registered)
    }

    suspend fun renameProject(projectId: String, newName: String): OperationReport {
        val source = registered(projectId) ?: return blocked("Project is not registered")
        val sourceCapabilities = storage.inspectProjectStorage(source.location)
        if (sourceCapabilities.state != dev.android.ide.contracts.CapabilityState.SUPPORTED || !sourceCapabilities.localStorageConfirmed || !sourceCapabilities.canRename) {
            return blocked(sourceCapabilities.localWorkspaceExplanation ?: sourceCapabilities.explanation ?: "The project location cannot be renamed", ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        }
        val cleanName = validName(newName) ?: return blocked("Project name is invalid")
        if (cleanName == source.name) return blocked("The project already has that name")
        val renamedUri = files.rename(source.location.stableId, cleanName)
            ?: return failed("The project could not be renamed", ErrorCategory.PERMISSION_LOST)
        if (files.displayName(renamedUri) != cleanName) {
            val restored = files.rename(renamedUri, source.name)
            return if (restored != null && files.displayName(restored) == source.name) {
                failed("The provider did not confirm the project rename; the original name was restored", ErrorCategory.EXTERNAL_FILE_CHANGE)
            } else OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "The provider returned an unexpected name and rollback could not be verified",
                errorCategory = ErrorCategory.EXTERNAL_FILE_CHANGE,
                affectedIds = listOf(source.location.stableId, renamedUri),
                recoveryHint = "Inspect the project folder and restore its original name before retrying",
            )
        }
        val renamedIdentity = source.copy(
            id = renamedUri,
            name = cleanName,
            location = source.location.copy(
                stableId = renamedUri,
                displayLabel = cleanName,
                userVisiblePath = renamedUri,
            ),
            lastOpenedAt = Instant.now(),
        )
        val metadataResult = metadata.writeIdentity(renamedIdentity)
        if (metadataResult.outcome != OperationOutcome.COMPLETE) {
            val restored = files.rename(renamedUri, source.name)
            if (restored == null || files.displayName(restored) != source.name) {
                return OperationReport(
                    outcome = OperationOutcome.PARTIAL,
                    message = "Project rename metadata failed and the original folder name could not be restored",
                    errorCategory = metadataResult.errorCategory ?: ErrorCategory.MALFORMED_METADATA,
                    affectedIds = listOf(source.location.stableId, renamedUri),
                    recoveryHint = "Inspect the project folder and portable metadata; restore the original name before retrying",
                )
            }
            val restoredMetadata = metadata.writeIdentity(source)
            return if (restoredMetadata.outcome == OperationOutcome.COMPLETE && metadataIdentityMatches(source)) {
                metadataResult
            } else OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Project folder name was restored but the previous portable identity could not be verified",
                errorCategory = ErrorCategory.MALFORMED_METADATA,
                affectedIds = listOf(source.location.stableId),
                recoveryHint = "Inspect project.json and restore the registered project identity before continuing",
            )
        }
        if (!metadataIdentityMatches(renamedIdentity)) {
            return OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "The project was renamed but its new portable identity could not be verified",
                errorCategory = ErrorCategory.MALFORMED_METADATA,
                affectedIds = listOf(source.location.stableId, renamedUri),
                recoveryHint = "Inspect project.json and the project registry before opening or renaming this project again",
            )
        }
        val removed = registry.remove(source.id)
        if (removed.outcome != OperationOutcome.COMPLETE) {
            return OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Project was renamed but the previous registry entry remains",
                errorCategory = ErrorCategory.MALFORMED_METADATA,
                recoveryHint = "Refresh the project registry and remove the stale entry",
            )
        }
        val registered = registry.register(renamedIdentity)
        return if (registered.outcome == OperationOutcome.COMPLETE) {
            complete("Project renamed", renamedUri)
        } else {
            OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Project was renamed but the new registry entry could not be written",
                errorCategory = ErrorCategory.MALFORMED_METADATA,
                recoveryHint = "Refresh the registry and import the renamed project if necessary",
            )
        }
    }

    suspend fun relocateProject(
        projectId: String,
        destinationParentUri: String,
        newName: String,
    ): OperationReport {
        val source = registered(projectId) ?: return blocked("Project is not registered")
        val sourceCapabilities = storage.inspectProjectStorage(source.location)
        if (sourceCapabilities.state != dev.android.ide.contracts.CapabilityState.SUPPORTED || !sourceCapabilities.localStorageConfirmed || !sourceCapabilities.canDelete) {
            return blocked(sourceCapabilities.localWorkspaceExplanation ?: sourceCapabilities.explanation ?: "The source project cannot be safely removed after relocation", ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        }
        val cleanName = validName(newName) ?: return blocked("Project name is invalid")
        when (storage.isSameOrDescendant(source.location.stableId, destinationParentUri)) {
            true -> return blocked("A project cannot be relocated inside itself", ErrorCategory.DESTINATION_CONFLICT)
            null -> return blocked("The relocation destination cannot be verified against the source project", ErrorCategory.PERMISSION_LOST)
            false -> Unit
        }
        val preflight = preflightDestination(destinationParentUri, projectId)
        if (!preflight.allowed) return preflight.report
        val copied = files.copy(source.location.stableId, destinationParentUri, cleanName)
        val rootUri = when (copied) {
            is SafeMutationResult.Created -> copied.documentUri
            SafeMutationResult.Duplicate -> return blocked("The destination already contains that project", ErrorCategory.DESTINATION_CONFLICT)
            SafeMutationResult.InspectionFailed -> return failed("The destination could not be inspected", ErrorCategory.PERMISSION_LOST)
            SafeMutationResult.Failed -> return failed("The project could not be copied for relocation", ErrorCategory.PERMISSION_LOST)
            is SafeMutationResult.Partial -> return partialMutationReport(copied)
        }
        val identity = identity(rootUri, cleanName, source.description)
        val containment = registry.preflightRegistration(identity)
        if (!containment.allowed) {
            return cleanupUnregisteredDestination(
                source.location.stableId,
                rootUri,
                blocked(containment.message, containment.errorCategory ?: ErrorCategory.DESTINATION_CONFLICT),
            )
        }
        val initialized = metadata.ensurePortableState(identity)
        if (initialized.outcome != OperationOutcome.COMPLETE) {
            return cleanupUnregisteredDestination(source.location.stableId, rootUri, initialized)
        }
        val written = metadata.writeIdentity(identity)
        if (written.outcome != OperationOutcome.COMPLETE) {
            return cleanupUnregisteredDestination(source.location.stableId, rootUri, written)
        }
        val verified = storage.inspectProjectStorage(identity.location)
        if (verified.state != dev.android.ide.contracts.CapabilityState.SUPPORTED ||
            !verified.readable || !verified.writable
        ) {
            return cleanupUnregisteredDestination(
                source.location.stableId,
                rootUri,
                failed("The relocation destination files could not be read and written", ErrorCategory.PERMISSION_LOST),
            )
        }
        if (!metadataIdentityMatches(identity)) {
            return cleanupUnregisteredDestination(
                source.location.stableId,
                rootUri,
                failed("The relocation identity could not be read back", ErrorCategory.MALFORMED_METADATA),
            )
        }
        val destinationRegistered = registry.register(identity)
        if (destinationRegistered.outcome != OperationOutcome.COMPLETE) {
            return cleanupUnregisteredDestination(source.location.stableId, rootUri, destinationRegistered)
        }
        files.delete(source.location.stableId)
        when (presenceAfterDelete(source.location.stableId)) {
            DocumentPresence.ABSENT -> Unit
            DocumentPresence.INACCESSIBLE -> return OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "The destination is registered, but source deletion could not be verified",
                errorCategory = ErrorCategory.PERMISSION_LOST,
                affectedIds = listOf(source.location.stableId, rootUri),
                recoveryHint = "Inspect both registered project locations before removing either copy",
            )
            DocumentPresence.EXISTS -> {
                val destinationUnregistered = registry.remove(identity.id)
                if (destinationUnregistered.outcome != OperationOutcome.COMPLETE) {
                    return OperationReport(
                        outcome = OperationOutcome.PARTIAL,
                        message = "The original project remains; the verified destination copy is also registered",
                        errorCategory = destinationUnregistered.errorCategory ?: ErrorCategory.MALFORMED_METADATA,
                        affectedIds = listOf(source.location.stableId, rootUri),
                        recoveryHint = "Keep the original project and inspect the registered copy before retrying relocation",
                    )
                }
                files.delete(rootUri)
                val destinationAbsent = presenceAfterDelete(rootUri) == DocumentPresence.ABSENT
                if (destinationAbsent) {
                    return failed("The original project was retained because source deletion failed", ErrorCategory.PERMISSION_LOST)
                }
                val restoredRegistration = registry.register(identity)
                return OperationReport(
                    outcome = OperationOutcome.PARTIAL,
                    message = if (restoredRegistration.outcome == OperationOutcome.COMPLETE)
                        "The original project remains and the verified destination copy was retained"
                    else "The original project remains, but cleanup of the destination copy could not be verified",
                    errorCategory = restoredRegistration.errorCategory ?: ErrorCategory.PERMISSION_LOST,
                    affectedIds = listOf(source.location.stableId, rootUri),
                    recoveryHint = "Inspect both project locations before retrying relocation",
                )
            }
        }
        val removed = registry.remove(source.id)
        if (removed.outcome != OperationOutcome.COMPLETE) {
            return OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "The project was relocated but the old registry entry remains",
                errorCategory = ErrorCategory.MALFORMED_METADATA,
                recoveryHint = "Refresh the registry and remove the stale entry",
            )
        }
        return complete("Project relocated", rootUri)
    }

    suspend fun exportProject(projectId: String, destinationFileUri: String): OperationReport {
        val project = registered(projectId) ?: return blocked("Project is not registered")
        val capabilities = storage.inspectProjectStorage(project.location)
        if (!capabilities.localStorageConfirmed) {
            return blocked(capabilities.localWorkspaceExplanation ?: "The project is not available for export from this storage location", ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        }
        val result = storage.writeDirectoryArchive(project.location.stableId, destinationFileUri)
            ?: return failed("The project could not be exported as ZIP", ErrorCategory.PERMISSION_LOST)
        return complete("Project exported (${result.fileCount} files, ${result.totalBytes} bytes)", project.id)
    }

    suspend fun exportProjects(projectIds: List<String>, destinationParentUri: String): OperationReport {
        if (projectIds.isEmpty()) return blocked("No projects were selected")
        val preflight = preflightDestination(destinationParentUri)
        if (!preflight.allowed) return preflight.report
        val exported = mutableListOf<String>()
        val failedIds = mutableListOf<String>()
        val orphanedUris = mutableListOf<String>()
        projectIds.forEach { projectId ->
            val project = registered(projectId)
            if (project == null) {
                failedIds += projectId
                return@forEach
            }
            if (!files.isLocalWorkspaceUri(project.location.stableId)) {
                failedIds += projectId
                return@forEach
            }
            val fileName = validName(project.name)?.let { "$it.zip" }
                ?: "project-${project.id.hashCode().toString().replace('-', 'n')}.zip"
            val destination = when (val created = files.createFile(
                destinationParentUri,
                fileName,
                "application/zip",
            )) {
                is dev.android.ide.saf.ExactCreateResult.Created -> created.documentUri
                is dev.android.ide.saf.ExactCreateResult.Partial -> {
                    files.delete(created.documentUri)
                    if (presenceAfterDelete(created.documentUri) != DocumentPresence.ABSENT) {
                        orphanedUris += created.documentUri
                    }
                    failedIds += projectId
                    return@forEach
                }
                else -> {
                    failedIds += projectId
                    return@forEach
                }
            }
            if (storage.writeDirectoryArchive(project.location.stableId, destination) == null) {
                files.delete(destination)
                if (presenceAfterDelete(destination) != DocumentPresence.ABSENT) orphanedUris += destination
                failedIds += projectId
            } else {
                exported += projectId
            }
        }
        return if (failedIds.isEmpty()) {
            OperationReport(
                OperationOutcome.COMPLETE,
                "Exported ${exported.size} project archive(s)",
                affectedIds = exported,
            )
        } else {
            OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Exported ${exported.size} project archive(s); ${failedIds.size} failed",
                errorCategory = if (orphanedUris.isEmpty()) ErrorCategory.DESTINATION_CONFLICT else ErrorCategory.PERMISSION_LOST,
                affectedIds = exported + orphanedUris,
                recoveryHint = if (orphanedUris.isEmpty()) {
                    "Choose an empty destination or resolve the archive-name conflicts before retrying"
                } else {
                    "Inspect and remove these incomplete export files before retrying: ${orphanedUris.joinToString()}"
                },
            )
        }
    }

    suspend fun copyEntry(sourceUri: String, targetParentUri: String, newName: String? = null): OperationReport =
        if (!files.isLocalWorkspaceUri(sourceUri) || !files.isLocalWorkspaceUri(targetParentUri)) {
            blocked(editOnlyNotice, ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        } else mutationReport(files.copy(sourceUri, targetParentUri, newName), "copied")

    suspend fun moveEntry(sourceUri: String, sourceParentUri: String, targetParentUri: String): OperationReport =
        if (!files.isLocalWorkspaceUri(sourceUri) || !files.isLocalWorkspaceUri(sourceParentUri) || !files.isLocalWorkspaceUri(targetParentUri)) {
            blocked(editOnlyNotice, ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        } else mutationReport(files.move(sourceUri, sourceParentUri, targetParentUri), "moved")

    suspend fun renameEntry(sourceUri: String, newName: String): OperationReport {
        if (validName(newName) == null) return blocked("The new name is invalid")
        if (!files.isLocalWorkspaceUri(sourceUri)) return blocked(editOnlyNotice, ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        val renamed = files.rename(sourceUri, newName)
            ?: return failed("The item could not be renamed", ErrorCategory.PERMISSION_LOST)
        return if (files.displayName(renamed) == newName) {
            complete("Item renamed", renamed)
        } else {
            failed("The provider did not confirm the new name", ErrorCategory.EXTERNAL_FILE_CHANGE)
        }
    }

    suspend fun deleteEntry(uri: String): OperationReport {
        if (!files.isLocalWorkspaceUri(uri)) return blocked(editOnlyNotice, ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        files.delete(uri)
        if (presenceAfterDelete(uri) != DocumentPresence.ABSENT) {
            return failed("The item could not be deleted", ErrorCategory.PERMISSION_LOST)
        }
        return complete("Item deleted", uri)
    }

    suspend fun batchDelete(uris: List<String>): OperationReport {
        if (uris.isEmpty()) return blocked("No items were selected")
        if (uris.any { !files.isLocalWorkspaceUri(it) }) return blocked(editOnlyNotice, ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        val failedIds = mutableListOf<String>()
        uris.forEach { uri ->
            files.delete(uri)
            if (presenceAfterDelete(uri) != DocumentPresence.ABSENT) failedIds += uri
        }
        return if (failedIds.isEmpty()) {
            OperationReport(OperationOutcome.COMPLETE, "Deleted ${uris.size} item(s)", affectedIds = uris)
        } else {
            OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Deleted ${uris.size - failedIds.size} item(s); ${failedIds.size} failed",
                errorCategory = ErrorCategory.PERMISSION_LOST,
                affectedIds = uris - failedIds.toSet(),
                recoveryHint = "Review the remaining selected items and retry individually",
            )
        }
    }

    suspend fun batchRemoveFromRegistry(projectIds: List<String>): OperationReport {
        if (projectIds.isEmpty()) return blocked("No projects were selected")
        val removed = mutableListOf<String>()
        val failedIds = mutableListOf<String>()
        projectIds.forEach { projectId ->
            if (removeFromRegistry(projectId).outcome == OperationOutcome.COMPLETE) removed += projectId
            else failedIds += projectId
        }
        return if (failedIds.isEmpty()) {
            OperationReport(
                OperationOutcome.COMPLETE,
                "Removed ${removed.size} project(s) from the registry",
                affectedIds = removed,
            )
        } else {
            OperationReport(
                outcome = OperationOutcome.PARTIAL,
                message = "Removed ${removed.size} project(s); ${failedIds.size} failed",
                affectedIds = removed,
                recoveryHint = "Review the projects that remain registered and retry individually",
            )
        }
    }

    suspend fun batchPermanentlyDelete(projectIds: List<String>): OperationReport {
        if (projectIds.isEmpty()) return blocked("No projects were selected")
        val pending = projectIds.toMutableSet()
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val registered = registry.listRegistered()
            val candidate = pending.firstOrNull { id ->
                val project = registered.firstOrNull { it.id == id } ?: return@firstOrNull true
                registered.none { child ->
                    child.id != project.id &&
                        storage.isSameOrDescendant(project.location.stableId, child.location.stableId) == true
                }
            }
            if (candidate == null) {
                failed += pending
                break
            }
            val report = permanentlyDelete(candidate)
            pending.remove(candidate)
            if (report.outcome == OperationOutcome.COMPLETE) deleted += candidate else failed += candidate
        }
        return when {
            failed.isEmpty() -> OperationReport(
                OperationOutcome.COMPLETE,
                "Permanently deleted ${deleted.size} project(s)",
                affectedIds = deleted,
            )
            deleted.isEmpty() -> OperationReport(
                OperationOutcome.FAILED,
                "No selected projects could be permanently deleted",
                errorCategory = ErrorCategory.DESTINATION_CONFLICT,
                recoveryHint = "Delete registered child projects before their parents",
            )
            else -> OperationReport(
                OperationOutcome.PARTIAL,
                "Permanently deleted ${deleted.size} project(s); ${failed.size} failed",
                errorCategory = ErrorCategory.DESTINATION_CONFLICT,
                affectedIds = deleted,
                recoveryHint = "Delete registered child projects before retrying their parents",
            )
        }
    }

    private suspend fun registered(projectId: String): ProjectIdentity? =
        registry.listRegistered().firstOrNull { it.id == projectId }

    /**
     * Project mutations decide success from the project-storage postcondition,
     * not a provider's delete return value. Some local DocumentsProviders throw
     * FileNotFoundException when queried after deletion, so use the adapter's
     * approved native path when available; unrelated SAF consumers keep the
     * generic provider semantics.
     */
    private suspend fun presenceAfterDelete(uri: String): DocumentPresence {
        val localPath = files.localFilesystemPath(uri) ?: return storage.documentPresence(uri)
        return withContext(Dispatchers.IO) {
            val path = File(localPath).toPath()
            when {
                Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> DocumentPresence.EXISTS
                Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> DocumentPresence.ABSENT
                else -> DocumentPresence.INACCESSIBLE
            }
        }
    }

    private suspend fun cleanupUnregisteredDestination(
        sourceUri: String,
        destinationUri: String,
        failure: OperationReport,
    ): OperationReport {
        files.delete(destinationUri)
        val absent = presenceAfterDelete(destinationUri) == DocumentPresence.ABSENT
        return if (absent) failure else OperationReport(
            outcome = OperationOutcome.PARTIAL,
            message = "The operation failed and cleanup of its unregistered destination could not be verified",
            errorCategory = failure.errorCategory ?: ErrorCategory.PERMISSION_LOST,
            affectedIds = listOf(sourceUri, destinationUri),
            recoveryHint = "Inspect both locations; the source remains authoritative and the copied destination may still exist",
        )
    }

    private suspend fun metadataIdentityMatches(expected: ProjectIdentity): Boolean {
        val actual = metadata.readIdentity(expected.location) ?: return false
        return actual.id == expected.id && actual.name == expected.name &&
            actual.description == expected.description &&
            actual.location.stableId == expected.location.stableId
    }

    private suspend fun preflightDestination(
        destinationUri: String,
        excludedProjectId: String? = null,
    ): DestinationPreflight {
        val location = ProjectLocation(
            stableId = destinationUri,
            displayLabel = destinationUri,
            userVisiblePath = destinationUri,
        )
        val capabilities = storage.inspectProjectStorage(location)
        if (!capabilities.localStorageConfirmed || !capabilities.canCreate || !capabilities.writable) {
            return DestinationPreflight(false, blocked(
                capabilities.localWorkspaceExplanation ?: capabilities.explanation ?: "The destination is not writable",
                ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY,
            ))
        }
        // This value is the selected parent, not the final project directory.
        // A normal projects folder contains registered sibling projects and is
        // therefore valid. The exact target is checked after the reviewed name
        // is applied by copyExact() and registry.preflightRegistration().
        for (project in registry.listRegistered().filter { it.id != excludedProjectId }) {
            when (storage.isSameOrDescendant(project.location.stableId, destinationUri)) {
                true -> return DestinationPreflight(false, blocked(
                    "The destination conflicts with an existing registered project",
                    ErrorCategory.DESTINATION_CONFLICT,
                ))
                false -> Unit
                null -> return DestinationPreflight(false, blocked(
                    "The destination cannot be verified against registered project ${project.name}",
                    ErrorCategory.PERMISSION_LOST,
                ))
            }
        }
        return DestinationPreflight(true, complete("Destination preflight passed", destinationUri))
    }

    private data class DestinationPreflight(val allowed: Boolean, val report: OperationReport)

    private fun identity(uri: String, name: String, description: String) = ProjectIdentity(
        id = uri,
        name = name,
        description = description.trim(),
        location = ProjectLocation(uri, uri, uri),
        registeredAt = Instant.now(),
        lastOpenedAt = Instant.now(),
    )

    private fun validName(value: String): String? {
        val name = value.trim()
        return name.takeIf {
            it.isNotEmpty() && it != "." && it != ".." &&
                !it.contains('/') && !it.contains('\\') && !it.any(Char::isISOControl)
        }
    }

    private fun partialMutationReport(result: SafeMutationResult.Partial) = OperationReport(
        outcome = OperationOutcome.PARTIAL,
        message = "The operation partially completed; source and destination may both remain",
        errorCategory = ErrorCategory.PERMISSION_LOST,
        affectedIds = listOf(result.sourceUri, result.destinationUri),
        recoveryHint = result.recoveryHint,
    )

    private fun mutationReport(result: SafeMutationResult, verb: String): OperationReport = when (result) {
        is SafeMutationResult.Created -> complete("Item $verb", result.documentUri)
        is SafeMutationResult.Partial -> partialMutationReport(result)
        SafeMutationResult.Duplicate -> blocked("The destination already contains an item with that name", ErrorCategory.DESTINATION_CONFLICT)
        SafeMutationResult.InspectionFailed -> failed("The destination could not be inspected", ErrorCategory.PERMISSION_LOST)
        SafeMutationResult.Failed -> failed("The item could not be $verb", ErrorCategory.PERMISSION_LOST)
    }

    private fun complete(message: String, id: String? = null) = OperationReport(
        outcome = OperationOutcome.COMPLETE,
        message = message,
        affectedIds = id?.let(::listOf) ?: emptyList(),
    )

    private fun blocked(message: String, category: ErrorCategory? = null) = OperationReport(
        outcome = OperationOutcome.BLOCKED,
        message = message,
        errorCategory = category,
    )

    private fun failed(message: String, category: ErrorCategory) = OperationReport(
        outcome = OperationOutcome.FAILED,
        message = message,
        errorCategory = category,
    )

    private companion object {
        const val editOnlyNotice = "This location supports editing existing files only. Move the project to Android IDE storage or Android external storage for project management."
    }
}
