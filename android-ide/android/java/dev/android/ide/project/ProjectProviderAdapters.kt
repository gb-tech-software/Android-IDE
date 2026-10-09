// ProjectProviderAdapters isolate SAF and preference details so project services
// can operate on stable contracts and preserve one source of project truth.
package dev.android.ide.project

import dev.android.ide.data.ProjectRepository
import dev.android.ide.data.model.Project
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.ProjectStorageCapabilities
import dev.android.ide.contracts.LocationOperationRequest
import dev.android.ide.contracts.MutationPreflight
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.ProjectMutationIntent
import dev.android.ide.contracts.ProjectMutationRequest
import dev.android.ide.contracts.ProjectRegistryAdapter
import dev.android.ide.contracts.ProjectRelativePath
import dev.android.ide.contracts.ProjectStorageAdapter
import dev.android.ide.saf.SafRepository
import dev.android.ide.saf.ExactCreateResult
import dev.android.ide.saf.DocumentPresence
import dev.android.ide.viewmodel.model.FileNode
import dev.android.ide.saf.SafeMutationResult
import dev.android.ide.saf.FileManagementService
import dev.android.ide.saf.StorageTreeInspection
import dev.android.ide.saf.ArchiveWriteResult
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProjectRegistryStore(
    private val repository: ProjectRepository,
    private val storage: ProjectStorageAdapter,
) : ProjectRegistryAdapter {
    private val registrationMutex = Mutex()

    override suspend fun listRegistered(): List<ProjectIdentity> = repository.getAll().map(::toIdentity)

    fun warning(): String? = repository.readAll().warning

    override suspend fun preflightRegistration(project: ProjectIdentity): MutationPreflight =
        registrationMutex.withLock { projectContainmentPreflight(project) }

    override suspend fun register(project: ProjectIdentity): OperationReport = registrationMutex.withLock {
        val capabilities = storage.inspectProjectStorage(project.location)
        if (capabilities.state != CapabilityState.SUPPORTED || !capabilities.readable || !capabilities.writable) {
            return@withLock blocked(
                capabilities.explanation ?: "Project files must be readable and writable to register this location",
                project.id,
            )
        }
        val preflight = projectContainmentPreflight(project)
        if (!preflight.allowed) return@withLock blocked(preflight.message, project.id)
        repository.upsert(project.toProject())
        complete("Project registered", project.id)
    }

    private suspend fun projectContainmentPreflight(project: ProjectIdentity): MutationPreflight {
        val existing = repository.getAll().filterNot { it.stableLocationId == project.location.stableId }
        for (other in existing) {
            val projectContainsOther = storage.isSameOrDescendant(
                project.location.stableId,
                other.stableLocationId,
            )
            val otherContainsProject = storage.isSameOrDescendant(
                other.stableLocationId,
                project.location.stableId,
            )
            if (projectContainsOther == null || otherContainsProject == null) {
                return MutationPreflight(
                    allowed = false,
                    message = "The new project cannot be registered until its location is verified against ${other.name}",
                    errorCategory = ErrorCategory.PERMISSION_LOST,
                )
            }
            if (projectContainsOther || otherContainsProject) {
                return MutationPreflight(
                    allowed = false,
                    message = "The project location overlaps the registered project ${other.name}",
                    errorCategory = ErrorCategory.DESTINATION_CONFLICT,
                )
            }
        }
        return MutationPreflight(true, "Project containment checks passed")
    }

    override suspend fun markUnavailable(
        projectId: String,
        reason: String,
        capabilityState: CapabilityState,
    ): OperationReport = registrationMutex.withLock {
        val project = repository.getAll().firstOrNull { it.stableLocationId == projectId }
            ?: return@withLock blocked("Project is not registered", projectId)
        repository.upsert(project.copy(
            capabilityState = capabilityState,
            capabilityMessage = reason,
        ))
        complete("Project marked unavailable", projectId)
    }

    override suspend fun remove(projectId: String): OperationReport = registrationMutex.withLock {
        if (!repository.remove(projectId)) blocked("Project is not registered", projectId)
        else complete("Project removed from registry", projectId)
    }

    private fun toIdentity(project: Project) = ProjectIdentity(
        id = project.stableLocationId,
        name = project.name,
        description = project.description,
        location = ProjectLocation(
            stableId = project.stableLocationId,
            displayLabel = project.locationLabel,
            userVisiblePath = project.uri,
            capabilityState = project.capabilityState,
            capabilityExplanation = project.capabilityMessage,
        ),
        registeredAt = Instant.ofEpochMilli(project.createdMs),
        lastOpenedAt = Instant.ofEpochMilli(project.lastOpenedMs),
    )

    private fun ProjectIdentity.toProject() = Project(
        name = name,
        description = description,
        uri = location.userVisiblePath ?: location.stableId,
        lastOpenedMs = lastOpenedAt?.toEpochMilli() ?: System.currentTimeMillis(),
        createdMs = registeredAt.toEpochMilli(),
        stableLocationId = location.stableId,
        locationLabel = location.displayLabel,
        capabilityState = location.capabilityState,
        capabilityMessage = location.capabilityExplanation,
    )
}

class ProjectStorageAdapterImpl(
    private val saf: SafRepository,
    private val files: FileManagementService = FileManagementService(saf),
) : ProjectStorageAdapter {
    suspend fun stageDocument(uri: String, maxBytes: Long): dev.android.ide.saf.StagedDocumentResult =
        files.stageDocument(uri, maxBytes)

    suspend fun writeDocumentFromStreamVerified(
        uri: String,
        input: java.io.InputStream,
        maxBytes: Long,
        expectedBytes: Long,
    ): Boolean = files.writeDocumentFromStreamVerified(uri, input, maxBytes, expectedBytes)

    suspend fun createDirectoryWithExactName(parentUri: String, name: String): ExactCreateResult =
        files.createFolder(parentUri, name)

    suspend fun deleteDocument(uri: String): Boolean = files.delete(uri)

    suspend fun getDisplayName(uri: String): String? = files.displayName(uri)

    suspend fun readDocument(uri: String): ByteArray? = files.read(uri)

    suspend fun writeDocument(uri: String, content: ByteArray): Boolean = files.write(uri, content)

    suspend fun findChild(parentUri: String, name: String): FileNode? =
        (files.inspectChildren(parentUri) as? dev.android.ide.saf.ChildrenInspectionResult.Success)
            ?.children
            ?.firstOrNull { it.displayName == name }

    suspend fun listChildren(parentUri: String): List<FileNode> = files.listChildren(parentUri)

    suspend fun inspectChildren(parentUri: String): dev.android.ide.saf.ChildrenInspectionResult =
        files.inspectChildren(parentUri)

    suspend fun deleteChildIfPresent(parentUri: String, name: String): Boolean =
        files.deleteChildIfPresent(parentUri, name)

    suspend fun createFileWithExactName(
        parentUri: String,
        name: String,
        mimeType: String,
    ): ExactCreateResult = files.createFile(parentUri, name, mimeType)

    override suspend fun isSameOrDescendant(rootUri: String, candidateUri: String): Boolean? =
        files.isSameOrDescendant(rootUri, candidateUri)

    suspend fun inspectTree(location: ProjectLocation): StorageTreeInspection? =
        files.inspectTree(location.stableId)

    suspend fun copyExact(sourceUri: String, targetParentUri: String, newName: String? = null): SafeMutationResult =
        files.copy(sourceUri, targetParentUri, newName)

    suspend fun moveExact(sourceUri: String, sourceParentUri: String, targetParentUri: String): SafeMutationResult =
        files.move(sourceUri, sourceParentUri, targetParentUri)

    suspend fun moveAndRenameExact(
        sourceUri: String,
        sourceParentUri: String,
        targetParentUri: String,
        newName: String,
    ): SafeMutationResult = files.moveAndRename(
        sourceUri,
        sourceParentUri,
        targetParentUri,
        newName,
    )

    suspend fun renameDocument(uri: String, newName: String): String? = files.rename(uri, newName)

    suspend fun writeDirectoryArchive(sourceUri: String, destinationUri: String): ArchiveWriteResult? =
        files.writeDirectoryArchive(sourceUri, destinationUri)

    suspend fun documentExists(uri: String): Boolean = files.exists(uri)
    suspend fun documentPresence(uri: String): DocumentPresence = files.presence(uri)

    override suspend fun inspectProjectStorage(location: ProjectLocation): ProjectStorageCapabilities = files.inspectStorage(location)

    private fun relativeSegments(path: ProjectRelativePath, allowRoot: Boolean = false): List<String>? {
        if (path.isEmpty()) return if (allowRoot) emptyList() else null
        if (path.startsWith('/') || path.startsWith('\\') || Regex("^[A-Za-z]:").containsMatchIn(path)) return null
        val segments = path.replace('\\', '/').split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) return null
        return segments
    }

    private suspend fun resolveExisting(project: ProjectIdentity, segments: List<String>): FileNode? {
        var currentUri = project.location.stableId
        var node: FileNode? = null
        for ((index, segment) in segments.withIndex()) {
            val children = files.inspectChildren(currentUri) as? dev.android.ide.saf.ChildrenInspectionResult.Success
                ?: return null
            node = children.children.firstOrNull { it.displayName == segment } ?: return null
            if (index < segments.lastIndex && !node.isDirectory) return null
            if (files.isSameOrDescendant(project.location.stableId, node.documentUri) != true) return null
            currentUri = node.documentUri
        }
        return node
    }

    private suspend fun resolveParent(project: ProjectIdentity, segments: List<String>): String? {
        if (segments.size == 1) return project.location.stableId
        val parent = resolveExisting(project, segments.dropLast(1)) ?: return null
        return parent.documentUri.takeIf { parent.isDirectory }
    }

    override suspend fun list(project: ProjectIdentity, path: ProjectRelativePath): List<ProjectRelativePath>? {
        val segments = relativeSegments(path, allowRoot = true) ?: return null
        val directoryUri = if (segments.isEmpty()) project.location.stableId
            else resolveExisting(project, segments)?.takeIf { it.isDirectory }?.documentUri ?: return null
        val children = files.inspectChildren(directoryUri) as? dev.android.ide.saf.ChildrenInspectionResult.Success
            ?: return null
        return children.children.map { child -> (segments + child.displayName).joinToString("/") }
    }

    override suspend fun read(project: ProjectIdentity, path: ProjectRelativePath): ByteArray? {
        val segments = relativeSegments(path) ?: return null
        val node = resolveExisting(project, segments) ?: return null
        if (node.isDirectory) return null
        return files.read(node.documentUri)
    }

    override suspend fun write(project: ProjectIdentity, path: ProjectRelativePath, content: ByteArray): OperationReport {
        val preflight = preflightMutation(ProjectMutationRequest(project, path, ProjectMutationIntent.WRITE))
        if (!preflight.allowed) return OperationReport(OperationOutcome.BLOCKED, preflight.message, preflight.errorCategory)
        val segments = relativeSegments(path) ?: return failed("The project-relative path is invalid", path, ErrorCategory.DESTINATION_CONFLICT)
        val node = resolveExisting(project, segments) ?: return failed("The project file is no longer available", path, ErrorCategory.PERMISSION_LOST)
        return if (!node.isDirectory && files.write(node.documentUri, content)) complete("Project file written", path)
        else failed("Project file could not be written", path, ErrorCategory.PERMISSION_LOST)
    }

    override suspend fun preflightMutation(request: ProjectMutationRequest): MutationPreflight {
        val segments = relativeSegments(request.path) ?: return MutationPreflight(
            false, "The project-relative path is invalid or escapes the project root", ErrorCategory.DESTINATION_CONFLICT,
        )
        val capabilities = inspectProjectStorage(request.project.location)
        if (capabilities.state != CapabilityState.SUPPORTED) return MutationPreflight(
            false,
            capabilities.explanation ?: "The project location is unavailable for mutation",
            ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY,
        )
        if (request.intent != ProjectMutationIntent.WRITE && !capabilities.localStorageConfirmed) return MutationPreflight(
            false,
            capabilities.explanation ?: "Cloud or remote project locations are not supported.",
            ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY,
        )
        if (request.intent == ProjectMutationIntent.CREATE && !capabilities.canCreate ||
            request.intent == ProjectMutationIntent.WRITE && !capabilities.writable ||
            request.intent == ProjectMutationIntent.DELETE && !capabilities.canDelete
        ) return MutationPreflight(false, "The provider does not support this project mutation", ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY)
        var parentUri = resolveParent(request.project, segments)
        if (parentUri == null && request.intent == ProjectMutationIntent.CREATE) {
            var currentUri = request.project.location.stableId
            for (segment in segments.dropLast(1)) {
                val children = files.inspectChildren(currentUri) as? dev.android.ide.saf.ChildrenInspectionResult.Success
                    ?: return MutationPreflight(false, "The selected project parent could not be inspected", ErrorCategory.PERMISSION_LOST)
                val existingParent = children.children.firstOrNull { it.displayName == segment }
                if (existingParent == null) {
                    return MutationPreflight(
                        true,
                        "The destination is contained by the project root; the calling surface may create missing parent folders",
                    )
                }
                if (!existingParent.isDirectory || files.isSameOrDescendant(request.project.location.stableId, existingParent.documentUri) != true) {
                    return MutationPreflight(false, "A file or out-of-project item blocks the destination path", ErrorCategory.DESTINATION_CONFLICT)
                }
                currentUri = existingParent.documentUri
            }
            parentUri = currentUri
        }
        parentUri = parentUri ?: return MutationPreflight(
            false, "The selected project parent is unavailable", ErrorCategory.PERMISSION_LOST,
        )
        val leaf = segments.last()
        val children = files.inspectChildren(parentUri) as? dev.android.ide.saf.ChildrenInspectionResult.Success
            ?: return MutationPreflight(false, "The selected project parent could not be inspected", ErrorCategory.PERMISSION_LOST)
        val existing = children.children.firstOrNull { it.displayName.equals(leaf, ignoreCase = true) }
        return when (request.intent) {
            ProjectMutationIntent.CREATE -> if (existing == null) MutationPreflight(true, "The exact project destination is available")
                else MutationPreflight(false, "An item with that name already exists", ErrorCategory.DESTINATION_CONFLICT)
            ProjectMutationIntent.WRITE -> if (existing != null && !existing.isDirectory) MutationPreflight(true, "The existing project file is available for writing")
                else MutationPreflight(false, "The project file does not exist or is not a file", ErrorCategory.DESTINATION_CONFLICT)
            ProjectMutationIntent.DELETE -> if (existing != null) MutationPreflight(true, "The exact project item is available for deletion")
                else MutationPreflight(false, "The project item no longer exists", ErrorCategory.EXTERNAL_FILE_CHANGE)
        }
    }

    override suspend fun preflightLocationOperation(request: LocationOperationRequest): MutationPreflight =
        MutationPreflight(
            allowed = false,
            message = "This storage adapter does not execute location transfers; use the project-management service, which performs operation-specific provider and containment checks",
            errorCategory = ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY,
        )

    override suspend fun executeLocationOperation(request: LocationOperationRequest): OperationReport =
        blocked("Location transfers must be executed by the project-management operation service after destination review", request.projectId)

    override suspend fun verifyLocationOperation(request: LocationOperationRequest): OperationReport =
        blocked("Project relocation and transfer verification requires the selected provider to report the resulting location and state", request.projectId)

    override suspend fun observeChanges(
        project: ProjectIdentity,
        listener: (ProjectRelativePath) -> Unit,
    ): AutoCloseable? = files.observeChanges(project, listener)
}

private fun complete(message: String, id: String) = OperationReport(
    outcome = OperationOutcome.COMPLETE,
    message = message,
    affectedIds = listOf(id),
)

private fun blocked(message: String, id: String) = OperationReport(
    outcome = OperationOutcome.BLOCKED,
    message = message,
    affectedIds = listOf(id),
)

private fun failed(message: String, id: String, category: ErrorCategory) = OperationReport(
    outcome = OperationOutcome.FAILED,
    message = message,
    errorCategory = category,
    affectedIds = listOf(id),
)
