package dev.android.ide.saf

import dev.android.ide.viewmodel.model.FileNode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Verification strength for a completed file write. */
enum class WriteVerification {
    /** Trust a successful provider write; higher-level workflows verify their result. */
    NONE,
    /** Verify provider-reported byte length without buffering the file again. */
    SIZE,
    /** Read the resulting file and compare every byte with the requested content. */
    STRICT,
}

/**
 * Application-wide file and folder mutation boundary.
 *
 * Every domain that changes storage uses this service. Provider-specific URI
 * behavior remains inside [SafRepository], while this service enforces the
 * postconditions visible to callers: exact names and confirmed presence/absence.
 * Write verification is opt-in because the owning workflow is responsible for
 * deciding whether a file-level or operation-level comparison is appropriate.
 */
class FileManagementService(private val saf: SafRepository) {
    /**
     * Resolve normalized segments from a caller-selected storage location.
     *
     * The caller owns path meaning and normalization. This service owns the
     * generic directory-walk transaction and delegates individual provider
     * operations to SafRepository.
     */
    suspend fun resolvePath(
        scope: PathResolutionScope,
        segments: List<String>,
    ): PathResolutionResult {
        if (segments.isEmpty() || segments.any { !isValidLeafName(it) }) {
            return PathResolutionResult.EmptyPath
        }
        if (!isWritableLocalDirectory(scope.startUri)) return PathResolutionResult.BlockedByFile(emptyList())
        val boundaryUri = scope.boundaryUri ?: scope.startUri
        if (saf.isSameOrDescendant(boundaryUri, scope.startUri) != true) {
            return PathResolutionResult.BlockedByFile(emptyList())
        }
        var currentUri = scope.startUri
        val created = mutableListOf<String>()
        for (segment in segments.dropLast(1)) {
            val children = when (val inspection = saf.inspectChildren(currentUri)) {
                is ChildrenInspectionResult.Success -> inspection.children
                is ChildrenInspectionResult.Failed ->
                    return PathResolutionResult.IntermediateCreationFailed(created.toList())
            }
            val matching = children.firstOrNull { it.displayName.equals(segment, ignoreCase = true) }
            if (matching != null) {
                if (!matching.isDirectory || saf.isSameOrDescendant(boundaryUri, matching.documentUri) != true) {
                    return PathResolutionResult.BlockedByFile(created.toList())
                }
                currentUri = matching.documentUri
                continue
            }
            when (val result = saf.createFileWithExactName(currentUri, segment, DIRECTORY_MIME)) {
                is ExactCreateResult.Created -> {
                    val createdNode = when (val inspection = saf.inspectChildren(currentUri)) {
                        is ChildrenInspectionResult.Success -> inspection.children.firstOrNull {
                            it.documentUri == result.documentUri
                        }
                        is ChildrenInspectionResult.Failed -> {
                            val absent = delete(result.documentUri)
                            return PathResolutionResult.IntermediateNameMismatch(
                                if (absent) created.toList() else created + result.documentUri,
                            )
                        }
                    }
                    if (createdNode == null || !createdNode.isDirectory ||
                        createdNode.displayName != segment ||
                        saf.isSameOrDescendant(boundaryUri, result.documentUri) != true
                    ) {
                        val deleted = delete(result.documentUri)
                        return PathResolutionResult.IntermediateNameMismatch(
                            if (deleted) created.toList() else created + result.documentUri,
                        )
                    }
                    created += result.documentUri
                    currentUri = result.documentUri
                }
                ExactCreateResult.Duplicate,
                ExactCreateResult.InspectionFailed,
                ExactCreateResult.Failed ->
                    return PathResolutionResult.IntermediateCreationFailed(created.toList())
                is ExactCreateResult.Partial ->
                    return PathResolutionResult.IntermediateNameMismatch(created + result.documentUri)
            }
        }
        return PathResolutionResult.Resolved(currentUri, segments.last(), created.toList())
    }

    /** Roll back only empty directories created by this path operation. */
    suspend fun rollbackCreatedDirectories(documentUris: List<String>): Boolean {
        var allDeleted = true
        for (uri in documentUris.asReversed()) {
            val children = when (val inspection = saf.inspectChildren(uri)) {
                is ChildrenInspectionResult.Success -> inspection.children
                is ChildrenInspectionResult.Failed -> {
                    allDeleted = false
                    continue
                }
            }
            if (children.isNotEmpty()) {
                allDeleted = false
                continue
            }
            if (!delete(uri)) allDeleted = false
        }
        return allDeleted
    }

    private fun isValidLeafName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }

    suspend fun createFolder(parentUri: String, name: String): ExactCreateResult =
        if (isWritableLocalDirectory(parentUri)) saf.createFileWithExactName(parentUri, name, DIRECTORY_MIME)
        else ExactCreateResult.Failed

    suspend fun createFile(parentUri: String, name: String, mimeType: String): ExactCreateResult =
        if (isWritableLocalDirectory(parentUri)) saf.createFileWithExactName(parentUri, name, mimeType)
        else ExactCreateResult.Failed

    suspend fun copy(sourceUri: String, targetParentUri: String, newName: String? = null): SafeMutationResult =
        if (isReadableResource(sourceUri) && isWritableLocalDirectory(targetParentUri)) {
            saf.copyDocumentWithExactName(sourceUri, targetParentUri, newName)
        } else SafeMutationResult.Failed

    suspend fun move(sourceUri: String, sourceParentUri: String, targetParentUri: String): SafeMutationResult =
        if (isWritableLocalResource(sourceUri) && isWritableLocalDirectory(sourceParentUri) && isWritableLocalDirectory(targetParentUri)) {
            saf.moveDocumentWithExactName(sourceUri, sourceParentUri, targetParentUri)
        } else SafeMutationResult.Failed

    suspend fun moveAndRename(
        sourceUri: String,
        sourceParentUri: String,
        targetParentUri: String,
        newName: String,
    ): SafeMutationResult = if (
        isWritableLocalResource(sourceUri) && isWritableLocalDirectory(sourceParentUri) && isWritableLocalDirectory(targetParentUri)
    ) {
        saf.moveAndRenameDocumentWithExactName(sourceUri, sourceParentUri, targetParentUri, newName)
    } else SafeMutationResult.Failed

    suspend fun rename(sourceUri: String, newName: String): String? =
        if (isWritableLocalResource(sourceUri)) {
            saf.renameDocument(sourceUri, newName)?.takeIf { renamed -> saf.getDisplayName(renamed) == newName }
        } else null

    suspend fun delete(sourceUri: String): Boolean =
        isWritableLocalResource(sourceUri) && saf.deleteDocument(sourceUri)

    suspend fun deleteChildIfPresent(parentUri: String, childName: String): Boolean =
        isWritableLocalDirectory(parentUri) && saf.deleteChildIfPresent(parentUri, childName)

    suspend fun exists(sourceUri: String): Boolean = saf.documentExists(sourceUri)

    suspend fun presence(sourceUri: String): DocumentPresence = saf.documentPresence(sourceUri)

    suspend fun isSameOrDescendant(rootUri: String, candidateUri: String): Boolean? =
        saf.isSameOrDescendant(rootUri, candidateUri)

    suspend fun inspectStorage(location: dev.android.ide.contracts.ProjectLocation): dev.android.ide.contracts.ProjectStorageCapabilities =
        saf.inspectProjectStorage(location)

    fun localFilesystemPath(uri: String): String? = saf.localFilesystemPath(uri)

    fun isLocalWorkspaceUri(uri: String): Boolean = localFile(uri) != null

    suspend fun forEachTextLine(uri: String, action: (String) -> Unit): Boolean =
        isConfirmedLocalStorageUri(uri) && saf.forEachTextLine(uri, action)

    suspend fun stageDocument(uri: String, maxBytes: Long): StagedDocumentResult =
        saf.stageDocumentBounded(uri, maxBytes)

    suspend fun writeDocumentFromStreamVerified(
        uri: String,
        input: java.io.InputStream,
        maxBytes: Long,
        expectedBytes: Long,
    ): Boolean = isWritableLocalResource(uri) && saf.writeDocumentFromStreamVerified(uri, input, maxBytes, expectedBytes)

    suspend fun observeChanges(
        project: dev.android.ide.contracts.ProjectIdentity,
        listener: (dev.android.ide.contracts.ProjectRelativePath) -> Unit,
    ): AutoCloseable? = saf.observeProjectChanges(project.location.stableId, listener)

    suspend fun displayName(sourceUri: String): String? = saf.getDisplayName(sourceUri)

    suspend fun listChildren(parentUri: String): List<FileNode> = saf.listChildren(parentUri)
    suspend fun inspectTree(rootUri: String): StorageTreeInspection? = saf.inspectTree(rootUri)

    suspend fun writeDirectoryArchive(sourceUri: String, destinationUri: String): ArchiveWriteResult? =
        if (isConfirmedLocalStorageUri(sourceUri) && isReadableResource(sourceUri)) {
            saf.writeDirectoryArchive(sourceUri, destinationUri)
        } else null

    suspend fun inspectChildren(parentUri: String): ChildrenInspectionResult =
        saf.inspectChildren(parentUri)

    suspend fun read(sourceUri: String): ByteArray? =
        if (isConfirmedLocalStorageUri(sourceUri)) saf.readFile(sourceUri) else null

    suspend fun write(
        sourceUri: String,
        content: ByteArray,
        verification: WriteVerification = WriteVerification.NONE,
    ): Boolean {
        if (!isConfirmedLocalStorageUri(sourceUri)) return false
        if (!saf.writeFile(sourceUri, content)) return false
        return when (verification) {
            WriteVerification.NONE -> true
            WriteVerification.SIZE ->
                // Providers that do not expose size metadata cannot offer a
                // stronger non-buffering check; STRICT is available for those
                // workflows that require byte-for-byte confirmation.
                saf.documentSize(sourceUri)?.let { it == content.size.toLong() } ?: true
            WriteVerification.STRICT ->
                saf.readFile(sourceUri)?.contentEquals(content) == true
        }
    }

    /** A native path is optional; provider-backed SAF operations remain available on confirmed-local roots. */
    private fun localFile(uri: String): File? = saf.localFilesystemPath(uri)?.let(::File)

    private suspend fun isConfirmedLocalStorageUri(uri: String): Boolean = withContext(Dispatchers.IO) {
        saf.isConfirmedLocalStorageUri(uri)
    }

    private suspend fun isWritableLocalDirectory(uri: String): Boolean =
        isConfirmedLocalStorageUri(uri) && saf.inspectChildren(uri) is ChildrenInspectionResult.Success

    private suspend fun isReadableResource(uri: String): Boolean =
        saf.documentPresence(uri) == DocumentPresence.EXISTS

    private suspend fun isWritableLocalResource(uri: String): Boolean =
        isConfirmedLocalStorageUri(uri) && saf.documentPresence(uri) == DocumentPresence.EXISTS

    suspend fun writeText(
        sourceUri: String,
        content: String,
        verification: WriteVerification = WriteVerification.NONE,
    ): Boolean = write(sourceUri, content.toByteArray(Charsets.UTF_8), verification)

    private companion object {
        const val DIRECTORY_MIME = "vnd.android.document/directory"
    }
}
