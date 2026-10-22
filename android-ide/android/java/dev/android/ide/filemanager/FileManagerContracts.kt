package dev.android.ide.filemanager

import dev.android.ide.contracts.FileSystemNode
import kotlinx.coroutines.flow.StateFlow

/** Read-only filesystem operations used by File Manager browsing and root discovery. */
interface FileManagerStorageService {
    suspend fun roots(): List<FileSystemNode>
    suspend fun children(parent: FileSystemNode): List<FileSystemNode>
    /** Resolves a folder returned by OpenDocumentTree into a safe virtual node. */
    suspend fun resolveLocation(resourceUri: String, displayLabel: String = "Selected folder"): FileSystemNode? = null
    /** Resolves a folder returned by OpenDocumentTree into a safe virtual node. */
}

/** Mutation operations intentionally remain behind the shared filesystem mutation boundary. */
interface FileManagerMutationService {
    suspend fun createFolder(parent: FileSystemNode, name: String): Boolean
    suspend fun rename(node: FileSystemNode, name: String): Boolean
    suspend fun delete(node: FileSystemNode): Boolean
    suspend fun copy(node: FileSystemNode, destination: FileSystemNode): Boolean
    suspend fun move(node: FileSystemNode, destination: FileSystemNode): Boolean
    suspend fun import(sourceUri: String, destination: FileSystemNode): Boolean
    suspend fun export(nodes: List<FileSystemNode>, destinationUri: String): Boolean
    suspend fun details(node: FileSystemNode): FileManagerItemDetails
    suspend fun prepareShare(nodes: List<FileSystemNode>): List<String>
}

data class FileManagerItemDetails(
    val name: String,
    val kind: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
    val location: String,
)

data class FileManagerSortMetadata(
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
)

data class FileManagerState(
    val roots: List<FileSystemNode> = emptyList(),
    val current: FileSystemNode? = null,
    val ancestors: List<FileSystemNode> = emptyList(),
    val entries: List<FileSystemNode> = emptyList(),
    val sortMetadata: Map<String, FileManagerSortMetadata> = emptyMap(),
    val sortMetadataLoading: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val operationLabel: String? = null,
    val itemDetails: FileManagerItemDetails? = null,
    val pendingShareUris: List<String>? = null,
    val pendingOpenUri: String? = null,
    val pendingOpenMimeType: String? = null,
    val feedback: String? = null,
    val error: String? = null,
) {
    val breadcrumbs: List<FileSystemNode> get() = ancestors + listOfNotNull(current)
}

/** Public state contract for the independently routed file-manager surface. */
interface FileManagerStateSource {
    val state: StateFlow<FileManagerState>
}
