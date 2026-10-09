// ProjectStorageAdapterImpl owns project-relative path semantics and delegates
// generic resource operations to FileManagementService.
package dev.android.ide.project

import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.StorageCapabilities
import dev.android.ide.contracts.LocationOperationRequest
import dev.android.ide.contracts.MutationPreflight
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.StorageLocation
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
import dev.android.ide.saf.ArchiveWriteResult

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

    override suspend fun inspectProjectStorage(location: ProjectLocation): StorageCapabilities = files.inspectStorage(StorageLocation(location.stableId, location.displayLabel, location.userVisiblePath))

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
