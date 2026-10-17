// android-ide/android/java/dev/android/ide/saf/SafRepository.kt
//
// Storage Access Framework operations — Kotlin coroutine-based.
//
// Supports two URI schemes:
//   content://  — standard SAF tree/document URIs (from ACTION_OPEN_DOCUMENT_TREE).
//   file://     — app-local file URIs (workspace projects created without SAF picker).
//
// SAF URI shapes:
//   Tree URI:     content://com.android.externalstorage.documents/tree/primary%3AMyProject
//   Document URI: content://com.android.externalstorage.documents/document/primary%3AMyProject%2FMain.kt
//
// Usage:
//   SafRepository owns provider access; FileManagementService is the shared mutation boundary.
//   All methods are safe to call concurrently from Dispatchers.IO.

package dev.android.ide.saf

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.provider.DocumentsContract
import android.util.Log
import dev.android.ide.editor.EditorLanguageRegistry
import dev.android.ide.editor.FileContentKind
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.StorageCapabilities
import dev.android.ide.contracts.StorageLocation
import dev.android.ide.viewmodel.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class ExactCreateResult {
    data class Created(val documentUri: String) : ExactCreateResult()
    data class Partial(val documentUri: String, val recoveryHint: String) : ExactCreateResult()
    data object Duplicate : ExactCreateResult()
    data object InspectionFailed : ExactCreateResult()
    data object Failed : ExactCreateResult()
}



data class ArchiveWriteResult(
    val fileCount: Int,
    val totalBytes: Long,
)

sealed interface StagedDocumentResult {
    data class Staged(val file: File, val sizeBytes: Long) : StagedDocumentResult
    data object TooLarge : StagedDocumentResult
    data object Failed : StagedDocumentResult
}

enum class DocumentPresence {
    EXISTS,
    ABSENT,
    INACCESSIBLE,
}

data class StorageTreeInspection(
    val description: String,
    val creationTimeMs: Long?,
    val lastModifiedTimeMs: Long?,
    val storageProvider: String,
    val storagePath: String,
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val languageBytes: Map<String, Long>,
)

class SafRepository(private val context: Context) {

    companion object {
        private const val TAG      = "SafRepository"
        private const val MIME_DIR = "vnd.android.document/directory"
    }

    private val resolver get() = context.contentResolver

    // ── URI type helpers ───────────────────────────────────────────────────

    private fun isFileUri(uriString: String) = uriString.startsWith("file://")

    /** Resolve document identity from the provider contract, never from a path keyword. */
    private fun documentIdForUri(uri: Uri): String = when {
        DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
        DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
        else -> DocumentsContract.getDocumentId(uri)
    }

    /** Compare provider document identity, not URI spelling (tree/document URIs may differ). */
    private fun sameDocument(firstUri: String, secondUri: String): Boolean? {
        fun identity(value: String): String? = runCatching {
            if (isFileUri(value)) {
                "file:${fileFromUri(value)?.canonicalPath ?: return@runCatching null}"
            } else {
                val uri = Uri.parse(value)
                val authority = uri.authority ?: return@runCatching null
                "$authority:${documentIdForUri(uri)}"
            }
        }.getOrNull()
        val first = identity(firstUri) ?: return null
        val second = identity(secondUri) ?: return null
        return first == second
    }

    /** True only for a verified name collision; null means provider identity was ambiguous. */
    private fun hasNameConflict(
        children: List<FileNode>,
        requestedName: String,
        sourceUri: String? = null,
    ): Boolean? {
        for (child in children) {
            if (!namesMatchExactly(child.displayName, requestedName)) continue
            if (sourceUri == null) return true
            when (sameDocument(child.documentUri, sourceUri)) {
                true -> Unit
                false -> return true
                null -> return null
            }
        }
        return false
    }

    private fun fileFromUri(uriString: String): File? =
        Uri.parse(uriString).path?.let { File(it) }

    /**
     * Inspect a selected provider by its granted access and declared document
     * capabilities, not by its brand. Runtime access remains a separate check.
     */
    suspend fun inspectStorage(location: StorageLocation): StorageCapabilities =
        withContext(Dispatchers.IO) {
            if (isFileUri(location.stableId)) return@withContext inspectLocalFileCapabilities(location.stableId)
            val uri = Uri.parse(location.stableId)
            if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
                return@withContext StorageCapabilities(
                    state = CapabilityState.UNSUPPORTED,
                    readable = false,
                    writable = false,
                    canCreate = false,
                    canRename = false,
                    canDelete = false,
                    canObserveChanges = false,
                    explanation = "The selected location is not a supported file or document-provider URI",
                )
            }
            val localStorageConfirmed = isKnownLocalDocumentProvider(uri) || isProviderRootLocalOnly(uri)
            if (!localStorageConfirmed) {
                return@withContext unsupportedCapabilities(
                    "Cloud or remote project locations are not supported. Import or move the project to local device storage.",
                )
            }
            val rootUri = mutationDocumentUri(location.stableId).toString()
            val flags = queryLongColumn(rootUri, DocumentsContract.Document.COLUMN_FLAGS)?.toInt()
                ?: return@withContext permissionLostCapabilities("Android could not inspect the selected project folder")
            val listing = inspectChildren(location.stableId)
            if (listing !is ChildrenInspectionResult.Success) {
                return@withContext permissionLostCapabilities("Android no longer grants access to the selected project folder")
            }
            val mappedRoot = localFilesystemPath(location.stableId)?.let(::File)?.canonicalFile
            val localObservation = mappedRoot?.let(::localFileCapabilities)
            val localWorkspaceAvailable = mappedRoot?.let {
                it.isDirectory && it.canRead() && it.canWrite()
            } == true
            val providerWritable = (flags and (
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                )) != 0
            // A successful directory listing proves provider read access. Do not
            // require a POSIX path mapping here: SAF remains authoritative for
            // project/editor operations even when a terminal cannot use it.
            val providerReadable = true
            val providerReady = providerReadable && providerWritable
            StorageCapabilities(
                state = if (providerReady) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED,
                readable = providerReadable,
                writable = providerWritable,
                canCreate = flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0,
                canRename = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0,
                canDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
                canObserveChanges = localObservation?.observed == true,
                localStorageConfirmed = true,
                explanation = when {
                    !providerReadable -> "The selected project location cannot currently be read"
                    !providerWritable -> "The selected project location cannot currently be written"
                    localObservation?.observed != true -> "Project files are accessible, but automatic change observation is unavailable"
                    else -> null
                },
                localWorkspaceAvailable = localWorkspaceAvailable,
                localWorkspaceExplanation = if (localWorkspaceAvailable) null else
                    "This local location can be edited and managed through Android storage access, but has no native path for terminal, Git, language tools, or development-server preview. Move the project to Android IDE storage or Android shared storage for those features.",
            )
        }

    private fun isKnownLocalDocumentProvider(uri: Uri): Boolean =
        AndroidIdeDocumentsProvider.isProviderUri(uri.toString()) ||
            uri.authority == "com.android.externalstorage.documents" ||
            uri.authority == "com.android.providers.downloads.documents"

    /** Unknown providers are accepted only when their SAF root explicitly promises local-only data. */
    private fun isProviderRootLocalOnly(treeUri: Uri): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !DocumentsContract.isTreeUri(treeUri)) return false
        val authority = treeUri.authority ?: return false
        val selectedPath = DocumentsContract.findDocumentPath(resolver, treeUri) ?: return false
        val rootDocumentId = selectedPath.path.firstOrNull() ?: return false
        val rootsUri = DocumentsContract.buildRootsUri(authority)
        val projection = arrayOf(
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
        )
        resolver.query(rootsUri, projection, null, null, null)?.use { cursor ->
            val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)
            if (documentIdIndex < 0 || flagsIndex < 0) return@use false
            while (cursor.moveToNext()) {
                if (cursor.getString(documentIdIndex) == rootDocumentId) {
                    return@use cursor.getInt(flagsIndex) and DocumentsContract.Root.FLAG_LOCAL_ONLY != 0
                }
            }
            false
        } ?: false
    }.getOrDefault(false)

    private data class LocalCapabilities(
        val readable: Boolean,
        val writable: Boolean,
        val observed: Boolean,
    )

    private suspend fun inspectLocalFileCapabilities(uri: String): StorageCapabilities {
        val selectedRoot = fileFromUri(uri)
        if (selectedRoot != null && Files.isSymbolicLink(selectedRoot.toPath())) {
            return unsupportedCapabilities("A symbolic link cannot be registered as a project root")
        }
        val root = selectedRoot?.canonicalFile
            ?: return permissionLostCapabilities("The selected local project folder is unavailable")
        val local = localFileCapabilities(root)
        val providerReady = root.isDirectory && local.readable && local.writable
        val localWorkspaceAvailable = providerReady && isApprovedLocalWorkspaceLocation(uri, root)
        return StorageCapabilities(
            state = if (providerReady) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED,
            readable = local.readable,
            writable = local.writable,
            canCreate = local.writable,
            canRename = local.writable,
            canDelete = local.writable,
            canObserveChanges = local.observed,
            localStorageConfirmed = providerReady && isApprovedLocalWorkspaceLocation(uri, root),
            explanation = when {
                !root.isDirectory || !local.readable -> "The selected project folder cannot currently be read"
                !local.writable -> "The selected project folder cannot currently be written"
                !local.observed -> "Project files are accessible, but automatic external-change observation is unavailable"
                else -> null
            },
            localWorkspaceAvailable = localWorkspaceAvailable,
            localWorkspaceExplanation = if (localWorkspaceAvailable) null else
                "This location supports direct file editing only. Move the project to Android IDE storage or Android shared storage for terminal, preview, language-server, and project-management access.",
        )
    }

    private fun localFileCapabilities(root: File): LocalCapabilities {
        val directory = root.isDirectory
        val readable = directory && root.canRead()
        val writable = directory && root.canWrite()
        val observed = readable && canObserveRecursively(root)
        return LocalCapabilities(readable, writable, observed)
    }

    private fun canObserveRecursively(root: File): Boolean {
        val observation = observeLocalTree(root) {} ?: return false
        return runCatching { observation.close(); true }.getOrDefault(false)
    }

    private fun observeLocalTree(root: File, onChanged: (String) -> Unit): AutoCloseable? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        if (!canonicalRoot.isDirectory || !canonicalRoot.canRead()) return null
        val watchers = linkedMapOf<String, FileObserver>()
        val lock = Any()
        lateinit var addDirectory: (File) -> Unit
        fun removeTree(directory: File) {
            val path = runCatching { directory.canonicalPath }.getOrNull() ?: return
            val removed = synchronized(lock) {
                watchers.keys.filter { it == path || it.startsWith(path + File.separator) }.mapNotNull { key ->
                    watchers.remove(key)
                }
            }
            removed.forEach { it.stopWatching() }
        }
        addDirectory = { candidate ->
            val directory = runCatching { candidate.canonicalFile }.getOrNull()
            if (directory != null && directory.isDirectory && directory.canRead() &&
                runCatching { directory.toPath().startsWith(canonicalRoot.toPath()) }.getOrDefault(false)
            ) {
                val path = directory.path
                val shouldAdd = synchronized(lock) { path !in watchers }
                if (shouldAdd) {
                    val observer = object : FileObserver(
                        path,
                        CLOSE_WRITE or CREATE or DELETE or MOVED_FROM or MOVED_TO or MODIFY,
                    ) {
                        override fun onEvent(event: Int, eventPath: String?) {
                            if (eventPath == null) return
                            val child = File(directory, eventPath)
                            val childPath = runCatching { child.canonicalPath }.getOrNull() ?: return
                            if (childPath != canonicalRoot.path && !childPath.startsWith(canonicalRoot.path + File.separator)) return
                            val relative = childPath.removePrefix(canonicalRoot.path)
                                .trimStart(File.separatorChar).replace(File.separatorChar, '/')
                            onChanged(relative)
                            if (event and (CREATE or MOVED_TO) != 0 && child.isDirectory) addDirectory(child)
                            if (event and (DELETE or MOVED_FROM) != 0 && watchers.containsKey(childPath)) removeTree(child)
                        }
                    }
                    synchronized(lock) {
                        if (path !in watchers) {
                            watchers[path] = observer
                            observer.startWatching()
                        } else observer.stopWatching()
                    }
                }
                directory.listFiles()?.filter { it.isDirectory }?.forEach(addDirectory)
            }
        }
        return try {
            addDirectory(canonicalRoot)
            if (synchronized(lock) { watchers.isEmpty() }) return null
            AutoCloseable {
                val all = synchronized(lock) { watchers.values.toList().also { watchers.clear() } }
                all.forEach { it.stopWatching() }
            }
        } catch (_: Exception) {
            val all = synchronized(lock) { watchers.values.toList().also { watchers.clear() } }
            all.forEach { it.stopWatching() }
            null
        }
    }

    private fun localFileForExternalDocument(uriString: String): File? = runCatching {
        val documentId = documentIdForUri(Uri.parse(uriString))
        val volumeId = documentId.substringBefore(':')
        val relative = Uri.decode(documentId.substringAfter(':', ""))
        val volumeRoot = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            val marker = "/Android/data/${context.packageName}/files"
            context.getExternalFilesDirs(null).filterNotNull().firstNotNullOfOrNull { externalFiles ->
                val path = externalFiles.canonicalPath
                val index = path.indexOf(marker)
                if (index < 0) null else File(path.substring(0, index)).takeIf {
                    it.name.equals(volumeId, ignoreCase = true)
                }
            }
        } ?: return null
        val canonicalRoot = volumeRoot.canonicalFile
        val candidate = File(canonicalRoot, relative).canonicalFile
        candidate.takeIf { it.toPath().startsWith(canonicalRoot.toPath()) }
    }.getOrNull()

    suspend fun observeProjectChanges(
        rootUriString: String,
        listener: (String) -> Unit,
    ): AutoCloseable? = withContext(Dispatchers.IO) {
        val root = localFilesystemPath(rootUriString)?.let(::File)
        root?.let { observeLocalTree(it, listener) }
    }

    fun localFilesystemPath(uriString: String): String? = runCatching {
        val file = if (isFileUri(uriString)) {
            fileFromUri(uriString)
        } else if (AndroidIdeDocumentsProvider.isProviderUri(uriString)) {
            AndroidIdeDocumentsProvider.localFileForUri(context, uriString)
        } else if (Uri.parse(uriString).authority == "com.android.externalstorage.documents") {
            localFileForExternalDocument(uriString)
        } else null
        file?.canonicalFile?.takeIf { isApprovedLocalWorkspaceLocation(uriString, it) }?.canonicalPath
    }.getOrNull()

    /** True when a URI is backed by confirmed device-local storage, even without a POSIX path. */
    fun isConfirmedLocalStorageUri(uriString: String): Boolean = runCatching {
        if (isFileUri(uriString)) {
            val file = fileFromUri(uriString)?.canonicalFile ?: return false
            isApprovedLocalWorkspaceLocation(uriString, file)
        } else {
            val uri = Uri.parse(uriString)
            uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
                (isKnownLocalDocumentProvider(uri) || isProviderRootLocalOnly(uri))
        }
    }.getOrDefault(false)

    private fun isApprovedLocalWorkspaceLocation(uriString: String, file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return when {
            AndroidIdeDocumentsProvider.isProviderUri(uriString) ->
                isWithin(canonical, File(context.filesDir, "home"))
            Uri.parse(uriString).authority == "com.android.externalstorage.documents" ->
                localFileForExternalDocument(uriString)?.let { runCatching { it.canonicalFile == canonical }.getOrDefault(false) } == true
            isFileUri(uriString) ->
                isWithin(canonical, File(context.filesDir, "home")) ||
                    androidExternalStorageRoots().any { isWithin(canonical, it) }
            else -> false
        }
    }

    private fun androidExternalStorageRoots(): List<File> = buildList {
        runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()?.let(::add)
        val marker = "/Android/data/${context.packageName}/files"
        context.getExternalFilesDirs(null).filterNotNull().forEach { externalFiles ->
            runCatching {
                val path = externalFiles.canonicalPath
                val index = path.indexOf(marker)
                if (index >= 0) File(path.substring(0, index)).canonicalFile else null
            }.getOrNull()?.let(::add)
        }
    }.distinctBy { it.absolutePath }

    private fun isWithin(candidate: File, root: File): Boolean = runCatching {
        val canonicalRoot = root.canonicalFile.toPath()
        candidate.canonicalFile.toPath().startsWith(canonicalRoot)
    }.getOrDefault(false)

    private fun appOwnedLocalFile(uriString: String): File? =
        if (AndroidIdeDocumentsProvider.isProviderUri(uriString)) {
            AndroidIdeDocumentsProvider.localFileForUri(context, uriString)
        } else {
            null
        }

    private fun unsupportedCapabilities(message: String) = StorageCapabilities(
        state = CapabilityState.UNSUPPORTED,
        readable = false,
        writable = false,
        canCreate = false,
        canRename = false,
        canDelete = false,
        canObserveChanges = false,
        explanation = message,
    )

    private fun permissionLostCapabilities(message: String) = StorageCapabilities(
        state = CapabilityState.PERMISSION_LOST,
        readable = false,
        writable = false,
        canCreate = false,
        canRename = false,
        canDelete = false,
        canObserveChanges = false,
        explanation = message,
    )

    // ── Directory listing ──────────────────────────────────────────────────

    /** Read children for mutation paths; failures are never represented as an empty directory. */
    suspend fun inspectChildren(parentUriString: String): ChildrenInspectionResult = withContext(Dispatchers.IO) {
        if (isFileUri(parentUriString)) return@withContext listChildrenFileResult(parentUriString)
        try {
            val parentUri = Uri.parse(parentUriString)

            val treeUri: Uri
            val docId: String
            if (DocumentsContract.isTreeUri(parentUri)) {
                treeUri = if (DocumentsContract.isDocumentUri(context, parentUri)) {
                    DocumentsContract.buildTreeDocumentUri(
                        parentUri.authority!!,
                        DocumentsContract.getTreeDocumentId(parentUri),
                    )
                } else parentUri
                docId = documentIdForUri(parentUri)
            } else {
                docId = documentIdForUri(parentUri)
                treeUri = DocumentsContract.buildTreeDocumentUri(parentUri.authority!!, docId)
            }

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

            val nodes = mutableListOf<FileNode>()

            val cursor = resolver.query(childrenUri, projection, null, null, null)
                ?: return@withContext ChildrenInspectionResult.Failed("SAF returned no directory listing")
            cursor.use { cursor ->
                val idIdx   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childDocId  = cursor.getString(idIdx) ?: continue
                    val displayName = cursor.getString(nameIdx) ?: ""
                    val mimeType    = cursor.getString(mimeIdx) ?: "application/octet-stream"
                    val size        = if (sizeIdx < 0 || cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                    val lastModifiedMs = if (modifiedIdx >= 0 && !cursor.isNull(modifiedIdx)) {
                        cursor.getLong(modifiedIdx)
                    } else {
                        null
                    }

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)

                    nodes += FileNode(
                        documentUri       = docUri.toString(),
                        displayName       = displayName,
                        mimeType          = mimeType,
                        size              = size,
                        lastModifiedMs    = lastModifiedMs,
                        parentDocumentUri = parentUriString,
                    )
                }
            }

            ChildrenInspectionResult.Success(nodes.sortedWith(
                compareByDescending<FileNode> { it.isDirectory }.thenBy { it.displayName.lowercase() }
            ))
        } catch (e: Exception) {
            Log.e(TAG, "listChildren failed for $parentUriString: ${e.message}", e)
            ChildrenInspectionResult.Failed(e.message)
        }
    }

    /** List children for UI/read-only callers; mutation paths must use inspectChildren. */
    suspend fun listChildren(parentUriString: String): List<FileNode> =
        (inspectChildren(parentUriString) as? ChildrenInspectionResult.Success)?.children ?: emptyList()

    private fun listChildrenFile(parentUriString: String): List<FileNode> {
        return try {
            val dir = fileFromUri(parentUriString) ?: return emptyList()
            if (Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) return emptyList()
            (dir.listFiles() ?: return emptyList()).mapNotNull { file ->
                if (Files.isSymbolicLink(file.toPath())) return@mapNotNull null
                FileNode(
                    documentUri       = Uri.fromFile(file).toString(),
                    displayName       = file.name,
                    mimeType          = if (file.isDirectory) MIME_DIR else "application/octet-stream",
                    size              = if (file.isFile) file.length() else 0L,
                    lastModifiedMs    = file.takeIf { it.isFile }?.lastModified(),
                    parentDocumentUri = parentUriString,
                )
            }.sortedWith(
                compareByDescending<FileNode> { it.isDirectory }.thenBy { it.displayName.lowercase() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "listChildrenFile failed for $parentUriString: ${e.message}", e)
            emptyList()
        }
    }

    private fun listChildrenFileResult(parentUriString: String): ChildrenInspectionResult {
        return try {
            val dir = fileFromUri(parentUriString)
                ?: return ChildrenInspectionResult.Failed("Invalid file URI")
            if (Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) {
                return ChildrenInspectionResult.Failed("Parent is not a safe directory")
            }
            val files = dir.listFiles() ?: return ChildrenInspectionResult.Failed("Directory listing failed")
            ChildrenInspectionResult.Success(files.mapNotNull { file ->
                if (Files.isSymbolicLink(file.toPath())) return@mapNotNull null
                FileNode(
                    documentUri = Uri.fromFile(file).toString(),
                    displayName = file.name,
                    mimeType = if (file.isDirectory) MIME_DIR else "application/octet-stream",
                    size = if (file.isFile) file.length() else 0L,
                    lastModifiedMs = file.lastModified(),
                    parentDocumentUri = parentUriString,
                )
            }.sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.displayName.lowercase() }))
        } catch (e: Exception) {
            Log.e(TAG, "listChildrenFile failed for $parentUriString: ${e.message}", e)
            ChildrenInspectionResult.Failed(e.message)
        }
    }

    // ── File reading ───────────────────────────────────────────────────────

    /** Read a SAF/file URI; when [maxBytes] is set, read at most one byte beyond it. */
    suspend fun readFile(documentUriString: String, maxBytes: Int? = null): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val boundedMax = maxBytes?.coerceIn(0, Int.MAX_VALUE - 1)
            val readLimit = boundedMax?.plus(1)
            val localFile = appOwnedLocalFile(documentUriString)
                ?: if (isFileUri(documentUriString)) fileFromUri(documentUriString) else null
            if (localFile != null) {
                val safeFile = localFile.takeUnless { Files.isSymbolicLink(it.toPath()) }
                when {
                    safeFile == null -> null
                    boundedMax != null && safeFile.length() > boundedMax.toLong() -> ByteArray(readLimit!!)
                    readLimit != null -> safeFile.inputStream().use { it.readBytesAtMost(readLimit) }
                    else -> safeFile.readBytes()
                }
            } else {
                resolver.openInputStream(Uri.parse(documentUriString))?.use { input ->
                    if (readLimit != null) input.readBytesAtMost(readLimit) else input.readAllBytesCompat()
                } ?: run {
                    Log.e(TAG, "readFile: openInputStream returned null for $documentUriString")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readFile failed for $documentUriString: ${e.message}", e)
            null
        }
    }

    private fun InputStream.readBytesAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    /** Stream content line-by-line; file extensions are never used as an allow-list. */
    suspend fun forEachTextLine(documentUriString: String, onLine: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = openInputStream(documentUriString) ?: return@runCatching false
                BufferedInputStream(raw).use { input ->
                    val probe = ByteArray(TextDocumentCodec.PROBE_BYTES)
                    input.mark(probe.size + 1)
                    var count = 0
                    while (count < probe.size) {
                        val read = input.read(probe, count, probe.size - count)
                        if (read <= 0) break
                        count += read
                    }
                    val encoding = TextDocumentCodec.detectEncoding(probe, count)
                        ?: return@runCatching false
                    input.reset()
                    repeat(encoding.byteOrderMark.size) {
                        if (input.read() < 0) return@runCatching false
                    }
                    InputStreamReader(input, TextDocumentCodec.decoder(encoding.charset))
                        .buffered()
                        .useLines { lines -> lines.forEach(onLine) }
                    true
                }
            }.getOrDefault(false)
        }

    /** Stage a selected document to app-private cache with a strict byte ceiling. */
    suspend fun stageDocumentBounded(documentUriString: String, maxBytes: Long): StagedDocumentResult =
        withContext(Dispatchers.IO) {
            if (maxBytes < 0L) return@withContext StagedDocumentResult.Failed
            val staged = runCatching { File.createTempFile("android-ide-import-", ".zip", context.cacheDir) }
                .getOrNull() ?: return@withContext StagedDocumentResult.Failed
            try {
                val input = openInputStream(documentUriString) ?: run {
                    staged.delete()
                    return@withContext StagedDocumentResult.Failed
                }
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                input.use { source ->
                    staged.outputStream().buffered().use { output ->
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxBytes) {
                                output.flush()
                                staged.delete()
                                return@withContext StagedDocumentResult.TooLarge
                            }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                StagedDocumentResult.Staged(staged, total)
            } catch (error: Exception) {
                staged.delete()
                Log.e(TAG, "stageDocumentBounded failed: ${error.message}", error)
                StagedDocumentResult.Failed
            }
        }

    /** Stream an entry into a new document, then verify size and CRC from the provider. */
    suspend fun writeDocumentFromStreamVerified(
        documentUriString: String,
        input: InputStream,
        maxBytes: Long,
        expectedBytes: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            var written = 0L
            val writeCrc = CRC32()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val localFile = appOwnedLocalFile(documentUriString)
                ?: if (isFileUri(documentUriString)) fileFromUri(documentUriString) else null
            val output = if (localFile != null) {
                localFile
                    ?.takeUnless { Files.isSymbolicLink(it.toPath()) }
                    ?.outputStream()
            } else {
                resolver.openOutputStream(Uri.parse(documentUriString), "wt")
            } ?: return@withContext false
            output.use { sink ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > maxBytes) return@withContext false
                    writeCrc.update(buffer, 0, count)
                    sink.write(buffer, 0, count)
                }
                sink.flush()
            }
            var verifiedBytes = 0L
            val readCrc = CRC32()
            val verifyInput = openInputStream(documentUriString) ?: return@withContext false
            verifyInput.use { source ->
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    verifiedBytes += count
                    if (verifiedBytes > maxBytes) return@withContext false
                    readCrc.update(buffer, 0, count)
                }
            }
            written == expectedBytes && written == verifiedBytes && writeCrc.value == readCrc.value
        } catch (error: Exception) {
            Log.e(TAG, "writeDocumentFromStreamVerified failed for $documentUriString: ${error.message}", error)
            false
        }
    }

    // ── File writing ───────────────────────────────────────────────────────

    /**
     * Write [data] to a document, replacing its entire content.
     *
     * @param documentUriString  SAF document URI or file:// URI
     * @param data               Bytes to write
     * @return                   true on success, false on failure.
     */
    suspend fun writeFile(documentUriString: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val localFile = appOwnedLocalFile(documentUriString)
                ?: if (isFileUri(documentUriString)) fileFromUri(documentUriString) else null
            if (localFile != null) {
                val file = localFile
                if (Files.isSymbolicLink(file.toPath())) return@withContext false
                file.parentFile?.mkdirs()
                file.writeBytes(data)
                true
            } else {
                resolver.openOutputStream(Uri.parse(documentUriString), "w")?.use { stream ->
                    stream.write(data)
                    stream.flush()
                    true
                } ?: run {
                    Log.e(TAG, "writeFile: openOutputStream returned null for $documentUriString")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeFile failed for $documentUriString: ${e.message}", e)
            false
        }
    }

    // ── Document creation ──────────────────────────────────────────────────

    /**
     * Create a new document or directory inside a parent.
     *
     * [parentUriString] may be a SAF tree URI, a SAF document URI, or a file:// URI.
     * Pass mimeType = "vnd.android.document/directory" to create a sub-folder.
     *
     * @return  Document URI of the created file, or null on failure.
     */
    suspend fun createFile(
        parentUriString: String,
        displayName: String,
        mimeType: String,
    ): String? = withContext(Dispatchers.IO) {
        if (!isValidLeafName(displayName)) return@withContext null
        try {
            val createdUri = if (AndroidIdeDocumentsProvider.isProviderUri(parentUriString)) {
                AndroidIdeDocumentsProvider.createLocalDocument(context, parentUriString, mimeType, displayName)
            } else if (isFileUri(parentUriString)) {
                val parentDir = fileFromUri(parentUriString)?.canonicalFile ?: return@withContext null
                if (!parentDir.isDirectory) return@withContext null
                val newFile = File(parentDir, displayName).canonicalFile
                if (newFile.parentFile != parentDir) return@withContext null
                if (Files.exists(newFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withContext null
                val success = if (mimeType == MIME_DIR) {
                    newFile.mkdir()
                } else {
                    newFile.createNewFile()
                }
                if (success) Uri.fromFile(newFile).toString() else null
            } else {
                val parentUri = Uri.parse(parentUriString)
                val docUri = if (DocumentsContract.isTreeUri(parentUri)) {
                    mutationDocumentUri(parentUriString)
                } else parentUri
                DocumentsContract.createDocument(resolver, docUri, mimeType, displayName)?.toString()
            }
            if (createdUri == null) {
                null
            } else if (isFileUri(parentUriString)) {
                createdUri
            } else {
                val parentUri = Uri.parse(parentUriString)
                if (DocumentsContract.isTreeUri(parentUri)) {
                    val createdDocumentId = documentIdForUri(Uri.parse(createdUri))
                    DocumentsContract.buildDocumentUriUsingTree(parentUri, createdDocumentId).toString()
                } else {
                    createdUri
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createFile failed (parent=$parentUriString, name=$displayName): ${e.message}", e)
            null
        }
    }

    /**
     * Create a child only when [displayName] is still available in the live
     * parent directory. Some SAF providers silently rename collisions, so the
     * created display name is verified and an unexpected entry is removed.
     */
    suspend fun createFileWithExactName(
        parentUriString: String,
        displayName: String,
        mimeType: String,
    ): ExactCreateResult = withContext(Dispatchers.IO) {
        if (!isValidLeafName(displayName)) return@withContext ExactCreateResult.Failed
        val existing = (inspectChildren(parentUriString) as? ChildrenInspectionResult.Success)?.children
            ?: return@withContext ExactCreateResult.InspectionFailed
        if (existing.any { existingNode ->
                namesMatchExactly(existingNode.displayName, displayName) &&
                    conflictsWithRequestedEntry(existingNode, mimeType)
            }) {
            return@withContext ExactCreateResult.Duplicate
        }

        val createdUri = createFile(parentUriString, displayName, mimeType)
            ?: return@withContext ExactCreateResult.Failed
        val createdName = getDisplayName(createdUri)
        val siblingsAfterCreate = when (val inspection = inspectChildren(parentUriString)) {
            is ChildrenInspectionResult.Success -> inspection.children
            is ChildrenInspectionResult.Failed -> return@withContext cleanupCreatedEntry(
                createdUri,
                ExactCreateResult.InspectionFailed,
                parentUriString,
            )
        }
        if (createdName == null) {
            return@withContext cleanupCreatedEntry(createdUri, ExactCreateResult.InspectionFailed, parentUriString)
        }
        if (createdName != displayName) {
            return@withContext cleanupCreatedEntry(createdUri, ExactCreateResult.Failed, parentUriString)
        }
        when (hasNameConflict(siblingsAfterCreate, displayName, createdUri)) {
            true -> return@withContext cleanupCreatedEntry(createdUri, ExactCreateResult.Duplicate, parentUriString)
            null -> return@withContext cleanupCreatedEntry(createdUri, ExactCreateResult.InspectionFailed, parentUriString)
            false -> Unit
        }
        ExactCreateResult.Created(createdUri)
    }

    private suspend fun cleanupCreatedEntry(
        uri: String,
        cleanResult: ExactCreateResult,
        parentUriString: String,
    ): ExactCreateResult {
        deleteDocument(uri)
        val absent = when (documentPresence(uri)) {
            DocumentPresence.ABSENT -> true
            DocumentPresence.EXISTS -> false
            DocumentPresence.INACCESSIBLE -> documentExistsInVerified(parentUriString, uri) == false
        }
        return if (absent) cleanResult else ExactCreateResult.Partial(
            uri,
            "The provider created an unexpected item that could not be removed; inspect the destination before retrying",
        )
    }

    /** File names are compared as complete names; prefixes such as `.env` and `.env.example` never match. */
    private fun namesMatchExactly(existingName: String, requestedName: String): Boolean =
        existingName.equals(requestedName, ignoreCase = true)

    private fun isValidLeafName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && !name.any(Char::isISOControl)

    /** Every exact child-name collision is unsafe, regardless of file type. */
    private fun conflictsWithRequestedEntry(existing: FileNode, requestedMimeType: String): Boolean { return true }

    // ── Copy ───────────────────────────────────────────────────────────────

    suspend fun copyDocumentWithExactName(
        sourceUriString: String,
        targetParentUriString: String,
        newName: String? = null,
    ): SafeMutationResult = withContext(Dispatchers.IO) {
        val displayName = newName ?: getDisplayName(sourceUriString)
            ?: return@withContext SafeMutationResult.Failed
        val sourceIsDirectory = if (isFileUri(sourceUriString)) {
            fileFromUri(sourceUriString)?.isDirectory == true
        } else {
            queryStringColumn(sourceUriString, DocumentsContract.Document.COLUMN_MIME_TYPE) == MIME_DIR
        }
        if (sourceIsDirectory) {
            return@withContext copyDirectoryWithExactName(sourceUriString, targetParentUriString, displayName)
        }
        when (val created = createFileWithExactName(
            targetParentUriString,
            displayName,
            if (isFileUri(sourceUriString)) "application/octet-stream"
            else queryStringColumn(sourceUriString, DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "text/plain",
        )) {
            is ExactCreateResult.Created -> {
                if (copyDocumentContents(sourceUriString, created.documentUri) &&
                    verifyDocument(sourceUriString, created.documentUri)
                ) {
                    SafeMutationResult.Created(created.documentUri)
                } else {
                    deleteDocument(created.documentUri)
                    if (verifiedAbsentIn(created.documentUri, targetParentUriString)) {
                        SafeMutationResult.Failed
                    } else {
                        SafeMutationResult.Partial(
                            sourceUriString,
                            created.documentUri,
                            "The source remains unchanged; inspect and remove the incomplete copy before retrying",
                        )
                    }
                }
            }
            is ExactCreateResult.Partial -> SafeMutationResult.Partial(
                sourceUriString,
                created.documentUri,
                created.recoveryHint,
            )
            ExactCreateResult.Duplicate -> SafeMutationResult.Duplicate
            ExactCreateResult.InspectionFailed -> SafeMutationResult.InspectionFailed
            ExactCreateResult.Failed -> SafeMutationResult.Failed
        }
    }

    private fun openInputStream(documentUriString: String): InputStream? {
        val localFile = appOwnedLocalFile(documentUriString)
            ?: if (isFileUri(documentUriString)) fileFromUri(documentUriString) else null
        return if (localFile != null) {
            localFile
                ?.takeUnless { Files.isSymbolicLink(it.toPath()) }
                ?.inputStream()
        } else {
            resolver.openInputStream(Uri.parse(documentUriString))
        }
    }

    private fun openOutputStream(documentUriString: String): OutputStream? {
        val localFile = appOwnedLocalFile(documentUriString)
            ?: if (isFileUri(documentUriString)) fileFromUri(documentUriString) else null
        return if (localFile != null) {
            localFile
                ?.takeUnless { Files.isSymbolicLink(it.toPath()) }
                ?.outputStream()
        } else {
            resolver.openOutputStream(Uri.parse(documentUriString), "w")
        }
    }

    private suspend fun copyDocumentContents(sourceUriString: String, targetUriString: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                openInputStream(sourceUriString)?.use { input ->
                    openOutputStream(targetUriString)?.use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    } ?: return@withContext false
                } ?: return@withContext false
                true
            } catch (e: Exception) {
                Log.e(TAG, "copyDocumentContents failed: $sourceUriString → $targetUriString", e)
                false
            }
        }

    private suspend fun contentEquals(sourceUriString: String, targetUriString: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val sourceBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val targetBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val sourceStream = openInputStream(sourceUriString) ?: return@withContext false
                val targetStream = openInputStream(targetUriString) ?: run {
                    // A stream that was opened successfully must still be closed when the
                    // second side cannot be opened; otherwise the descriptor leaks.
                    sourceStream.use { }
                    return@withContext false
                }
                sourceStream.use { source ->
                    targetStream.use { target ->
                        while (true) {
                            val sourceCount = source.read(sourceBuffer)
                            val targetCount = target.read(targetBuffer)
                            // Unequal read counts (including early EOF on one side) end the
                            // comparison as a mismatch instead of a verified copy.
                            if (sourceCount != targetCount) return@withContext false
                            if (sourceCount == -1) break
                            for (index in 0 until sourceCount) {
                                if (sourceBuffer[index] != targetBuffer[index]) return@withContext false
                            }
                        }
                        // Both streams reached EOF with identical length and content.
                        true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "contentEquals failed: $sourceUriString ↔ $targetUriString", e)
                false
            }
        }

    /** Verify a completed copy using provider content rather than trusting write success. */
    private suspend fun verifyDocument(sourceUriString: String, targetUriString: String): Boolean {
        val sourceMime = if (isFileUri(sourceUriString)) {
            if (fileFromUri(sourceUriString)?.isDirectory == true) MIME_DIR else "application/octet-stream"
        } else queryStringColumn(sourceUriString, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val targetMime = if (isFileUri(targetUriString)) {
            if (fileFromUri(targetUriString)?.isDirectory == true) MIME_DIR else "application/octet-stream"
        } else queryStringColumn(targetUriString, DocumentsContract.Document.COLUMN_MIME_TYPE)
        if ((sourceMime == MIME_DIR) != (targetMime == MIME_DIR)) return false
        if (sourceMime != MIME_DIR) {
            return contentEquals(sourceUriString, targetUriString)
        }
        val sourceChildren = (inspectChildren(sourceUriString) as? ChildrenInspectionResult.Success)?.children
            ?: return false
        val targetChildren = (inspectChildren(targetUriString) as? ChildrenInspectionResult.Success)?.children
            ?: return false
        if (sourceChildren.size != targetChildren.size) return false
        return sourceChildren.all { sourceChild ->
            targetChildren.firstOrNull { it.displayName == sourceChild.displayName }?.let { targetChild ->
                verifyDocument(sourceChild.documentUri, targetChild.documentUri)
            } == true
        }
    }

    /**
     * Build the complete metadata used by project search, sorting, and details.
     * The traversal is provider-backed and fails closed if any directory cannot
     * be inspected, so a partial scan is never presented as authoritative.
     */
    suspend fun inspectTree(rootUriString: String): StorageTreeInspection? =
        withContext(Dispatchers.IO) {
            // Accumulate only generic tree metrics while walking the provider.
            // Domain readers interpret special directories separately.
            val languageBytes = mutableMapOf<String, Long>()
            var fileCount = 0
            var folderCount = 0
            var totalBytes = 0L
            var latestModifiedMs: Long? = null

            suspend fun walk(directoryUri: String, prefix: String, localDirectory: File? = null): Boolean {
                val children = if (localDirectory != null) {
                    val localChildren = localDirectory.listFiles()
                        ?.filterNot { Files.isSymbolicLink(it.toPath()) }
                        ?: return false
                    localChildren.map { file ->
                        FileNode(
                            documentUri = Uri.fromFile(file).toString(),
                            displayName = file.name,
                            mimeType = if (file.isDirectory) MIME_DIR else "application/octet-stream",
                            size = if (file.isFile) file.length() else 0L,
                            lastModifiedMs = file.lastModified(),
                            parentDocumentUri = directoryUri,
                        )
                    }
                } else when (val inspection = inspectChildren(directoryUri)) {
                    is ChildrenInspectionResult.Success -> inspection.children
                    is ChildrenInspectionResult.Failed -> return false
                }
                children.forEach { child ->
                    val relativePath = if (prefix.isEmpty()) {
                        child.displayName
                    } else {
                        "$prefix/${child.displayName}"
                    }
                    child.lastModifiedMs?.let { latestModifiedMs = maxOf(latestModifiedMs ?: it, it) }
                    if (child.isDirectory) {
                        folderCount++
                    } else {
                        fileCount++
                        val size = child.size.coerceAtLeast(0L)
                        totalBytes += size
                        val language = languageLabel(relativePath)
                        languageBytes[language] = (languageBytes[language] ?: 0L) + size
                    }
                    val childLocalDirectory = localDirectory?.resolve(child.displayName)?.takeIf { it.isDirectory }
                    if (child.isDirectory && !walk(child.documentUri, relativePath, childLocalDirectory)) {
                        return false
                    }
                }
                return true
            }

            val localRoot = localFilesystemPath(rootUriString)?.let(::File)?.takeIf { it.isDirectory }
            if (!walk(rootUriString, "", localRoot)) return@withContext null

            val rootTimes = rootTimes(rootUriString)
            StorageTreeInspection(
                description = "",
                creationTimeMs = rootTimes.first,
                lastModifiedTimeMs = listOfNotNull(
                    rootTimes.second,
                    latestModifiedMs,
                ).maxOrNull(),
                storageProvider = storageProvider(rootUriString),
                storagePath = storagePath(rootUriString),
                fileCount = fileCount,
                folderCount = folderCount,
                totalBytes = totalBytes,
                languageBytes = languageBytes.toSortedMap(),
            )
        }

    /**
     * Returns true when [candidateUriString] is the same directory as, or
     * appears below, [rootUriString]. Null means the provider could not be
     * inspected safely.
     *
     * This is a storage primitive: project services use it for containment
     * policy, while SAF remains responsible for resolving provider identity
     * and inspecting descendants.
     */
    suspend fun isSameOrDescendant(
        rootUriString: String,
        candidateUriString: String,
    ): Boolean? = withContext(Dispatchers.IO) {
        val rootPath = localFilesystemPath(rootUriString)
        val candidatePath = localFilesystemPath(candidateUriString)
        if (rootPath != null && candidatePath != null) {
            val root = runCatching { File(rootPath).canonicalFile }.getOrNull()
                ?: return@withContext null
            val candidate = runCatching { File(candidatePath).canonicalFile }.getOrNull()
                ?: return@withContext null
            return@withContext candidate == root || candidate.toPath().startsWith(root.toPath())
        }

        val rootUri = Uri.parse(rootUriString)
        val candidateUri = Uri.parse(candidateUriString)
        if (rootUri.scheme != candidateUri.scheme || rootUri.authority != candidateUri.authority) {
            return@withContext false
        }

        fun identity(uriString: String): String? = runCatching {
            val uri = Uri.parse(uriString)
            val authority = uri.authority ?: return@runCatching null
            val documentId = when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> DocumentsContract.getDocumentId(uri)
            }
            "$authority:$documentId"
        }.getOrNull()

        val candidateIdentity = identity(candidateUriString) ?: return@withContext null
        val visited = mutableSetOf<String>()

        suspend fun contains(directoryUri: String): Boolean? {
            val directoryIdentity = identity(directoryUri) ?: return null
            if (!visited.add(directoryIdentity)) return false
            if (directoryIdentity == candidateIdentity) return true
            val children = when (val inspection = inspectChildren(directoryUri)) {
                is ChildrenInspectionResult.Success -> inspection.children
                is ChildrenInspectionResult.Failed -> return null
            }
            for (child in children) {
                if (identity(child.documentUri) == candidateIdentity) return true
                if (child.isDirectory) {
                    when (val found = contains(child.documentUri)) {
                        true -> return true
                        null -> return null
                        false -> Unit
                    }
                }
            }
            return false
        }

        contains(rootUriString)
    }

    /**
     * Calculate recursive statistics for a directory. A failed provider
     * inspection returns null instead of reporting a misleading partial total.
     */
    suspend fun writeDirectoryArchive(
        sourceUriString: String,
        destinationUriString: String,
    ): ArchiveWriteResult? = withContext(Dispatchers.IO) {
        val temporaryArchive = try {
            File.createTempFile("android-ide-export-", ".zip", context.cacheDir)
        } catch (e: IOException) {
            Log.e(TAG, "Could not create temporary export archive", e)
            return@withContext null
        }
        try {
            var fileCount = 0
            var totalBytes = 0L
            ZipOutputStream(temporaryArchive.outputStream()).use { zip ->
                suspend fun addDirectory(directoryUri: String, prefix: String): Boolean {
                    val children = when (val inspection = inspectChildren(directoryUri)) {
                        is ChildrenInspectionResult.Success -> inspection.children
                        is ChildrenInspectionResult.Failed -> return false
                    }
                    for (child in children) {
                        val name = child.displayName
                        if (!isValidLeafName(name) ||
                            (prefix.isEmpty() && Regex("^[A-Za-z]:").containsMatchIn(name))
                        ) return false
                        val entryName = prefix + name
                        if (child.isDirectory) {
                            zip.putNextEntry(ZipEntry("$entryName/"))
                            zip.closeEntry()
                            if (!addDirectory(child.documentUri, "$entryName/")) return false
                        } else {
                            zip.putNextEntry(ZipEntry(entryName))
                            val input = openInputStream(child.documentUri) ?: return false
                            input.buffered().use { source ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    zip.write(buffer, 0, count)
                                    totalBytes += count.toLong()
                                }
                            }
                            zip.closeEntry()
                            fileCount++
                        }
                    }
                    return true
                }

                if (!addDirectory(sourceUriString, "")) return@withContext null
            }
            val destination = if (isFileUri(destinationUriString)) {
                fileFromUri(destinationUriString)?.outputStream()
            } else {
                resolver.openOutputStream(Uri.parse(destinationUriString), "w")
            } ?: return@withContext null
            destination.use { output ->
                temporaryArchive.inputStream().use { input -> input.copyTo(output) }
            }
            ArchiveWriteResult(fileCount, totalBytes)
        } catch (e: Exception) {
            Log.e(TAG, "exportZip failed: $sourceUriString → $destinationUriString", e)
            null
        } finally {
            temporaryArchive.delete()
        }
    }

    private fun languageLabel(path: String): String {
        val fileName = path.substringAfterLast('/')
        return if (EditorLanguageRegistry.contentKindForFileName(fileName) == FileContentKind.RASTER_IMAGE) {
            "Images"
        } else {
            when (EditorLanguageRegistry.languageForFileName(fileName)) {
                EditorLanguageRegistry.PLAIN_TEXT -> "Text"
                "javascript" -> "JavaScript"
                "typescript" -> "TypeScript"
                "markdown" -> "Markdown"
                "dockerfile" -> "Dockerfile"
                "make" -> "Make"
                "shell" -> "Shell"
                else -> EditorLanguageRegistry.languageForFileName(fileName)
                    .replaceFirstChar { it.uppercase() }
            }
        }
    }

    private fun rootTimes(rootUriString: String): Pair<Long?, Long?> {
        if (isFileUri(rootUriString)) {
            val file = fileFromUri(rootUriString) ?: return null to null
            return runCatching {
                val attributes = Files.readAttributes(
                    file.toPath(),
                    BasicFileAttributes::class.java,
                )
                attributes.creationTime().toMillis() to attributes.lastModifiedTime().toMillis()
            }.getOrElse { null to file.lastModified().takeIf { it > 0L } }
        }
        val modified = queryLongColumn(rootUriString, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return null to modified
    }

    private fun storageProvider(uriString: String): String {
        if (isFileUri(uriString)) return "App-local file system"
        return Uri.parse(uriString).authority
            ?.substringBefore(".documents")
            ?.replace('.', ' ')
            ?.replaceFirstChar { it.uppercase() }
            ?: "Android Storage Access Framework"
    }

    private fun storagePath(uriString: String): String {
        if (isFileUri(uriString)) return fileFromUri(uriString)?.absolutePath ?: uriString
        return runCatching {
            val uri = Uri.parse(uriString)
            val documentId = documentIdForUri(uri)
            Uri.decode(documentId)
        }.getOrElse { uriString }
    }

    private suspend fun copyDirectoryWithExactName(
        sourceUriString: String,
        targetParentUriString: String,
        displayName: String,
    ): SafeMutationResult {
        val createdRoot = when (val created = createFileWithExactName(
            targetParentUriString,
            displayName,
            MIME_DIR,
        )) {
            is ExactCreateResult.Created -> created.documentUri
            is ExactCreateResult.Partial -> return SafeMutationResult.Partial(sourceUriString, created.documentUri, created.recoveryHint)
            ExactCreateResult.Duplicate -> return SafeMutationResult.Duplicate
            ExactCreateResult.InspectionFailed -> return SafeMutationResult.InspectionFailed
            ExactCreateResult.Failed -> return SafeMutationResult.Failed
        }
        val children = when (val inspection = inspectChildren(sourceUriString)) {
            is ChildrenInspectionResult.Success -> inspection.children
            is ChildrenInspectionResult.Failed -> {
                return cleanupCreatedCopy(sourceUriString, createdRoot, targetParentUriString, SafeMutationResult.InspectionFailed)
            }
        }
        for (child in children) {
            val childCopy = copyDocumentWithExactName(child.documentUri, createdRoot)
            when (childCopy) {
                is SafeMutationResult.Created -> Unit
                else -> {
                    return cleanupCreatedCopy(sourceUriString, createdRoot, targetParentUriString, childCopy)
                }
            }
        }
        return if (verifyDocument(sourceUriString, createdRoot)) {
            SafeMutationResult.Created(createdRoot)
        } else {
            cleanupCreatedCopy(sourceUriString, createdRoot, targetParentUriString, SafeMutationResult.Failed)
        }
    }

    private suspend fun cleanupCreatedCopy(
        sourceUri: String,
        destinationUri: String,
        destinationParentUri: String,
        cleanResult: SafeMutationResult,
    ): SafeMutationResult {
        deleteDocument(destinationUri)
        return if (verifiedAbsentIn(destinationUri, destinationParentUri)) {
            if (cleanResult is SafeMutationResult.Partial) SafeMutationResult.Failed else cleanResult
        }
        else SafeMutationResult.Partial(
            sourceUri,
            destinationUri,
            "The source remains unchanged; inspect and remove the incomplete destination before retrying",
        )
    }

    suspend fun moveDocumentWithExactName(
        sourceUriString: String,
        sourceParentUriString: String,
        targetParentUriString: String,
    ): SafeMutationResult = withContext(Dispatchers.IO) {
        val name = getDisplayName(sourceUriString) ?: return@withContext SafeMutationResult.Failed
        val sourceMime = if (isFileUri(sourceUriString)) {
            if (fileFromUri(sourceUriString)?.isDirectory == true) MIME_DIR else "application/octet-stream"
        } else {
            queryStringColumn(sourceUriString, DocumentsContract.Document.COLUMN_MIME_TYPE)
        }
        val targetChildren = when (val inspection = inspectChildren(targetParentUriString)) {
            is ChildrenInspectionResult.Success -> inspection.children
            is ChildrenInspectionResult.Failed -> return@withContext SafeMutationResult.InspectionFailed
        }
        when (hasNameConflict(targetChildren, name, sourceUriString)) {
            true -> return@withContext SafeMutationResult.Duplicate
            null -> return@withContext SafeMutationResult.InspectionFailed
            false -> Unit
        }
        if (sourceMime != MIME_DIR) {
            return@withContext when (val copied = copyDocumentWithExactName(
                sourceUriString,
                targetParentUriString,
                name,
            )) {
                is SafeMutationResult.Created -> {
                    deleteDocument(sourceUriString)
                    if (verifiedAbsentIn(sourceUriString, sourceParentUriString)) {
                        copied
                    } else {
                        deleteDocument(copied.documentUri)
                        val cleaned = verifiedAbsentIn(copied.documentUri, targetParentUriString)
                        if (cleaned) SafeMutationResult.Failed else SafeMutationResult.Partial(
                            sourceUriString,
                            copied.documentUri,
                            "Verify both locations before retrying the move",
                        )
                    }
                }
                else -> copied
            }
        }
        val moved = moveDocument(sourceUriString, sourceParentUriString, targetParentUriString)
            ?: return@withContext when (val copied = copyDocumentWithExactName(
                sourceUriString,
                targetParentUriString,
                name,
            )) {
                is SafeMutationResult.Created -> {
                    deleteDocument(sourceUriString)
                    if (verifiedAbsentIn(sourceUriString, sourceParentUriString)) {
                        copied
                    } else {
                        deleteDocument(copied.documentUri)
                        val cleaned = verifiedAbsentIn(copied.documentUri, targetParentUriString)
                        if (cleaned) SafeMutationResult.Failed else SafeMutationResult.Partial(
                            sourceUriString,
                            copied.documentUri,
                            "Verify both locations before retrying the move",
                        )
                    }
                }
                else -> copied
            }
        val movedName = getDisplayName(moved)
        if (movedName != name || documentExistsIn(sourceParentUriString, sourceUriString)) {
            val restored = moveDocument(moved, targetParentUriString, sourceParentUriString)
            return@withContext if (
                restored != null && getDisplayName(restored) == name &&
                documentExistsInVerified(sourceParentUriString, restored) == true &&
                documentExistsInVerified(targetParentUriString, moved) == false
            ) SafeMutationResult.Failed else SafeMutationResult.Partial(
                sourceUriString,
                moved,
                "The move result could not be verified or restored; inspect both source and destination folders",
            )
        }
        SafeMutationResult.Created(moved)
    }

    private suspend fun documentExistsIn(parentUriString: String, documentUriString: String): Boolean =
        documentExistsInVerified(parentUriString, documentUriString) ?: true

    private suspend fun documentExistsInVerified(parentUriString: String, documentUriString: String): Boolean? {
        return when (val inspection = inspectChildren(parentUriString)) {
            is ChildrenInspectionResult.Success -> {
                var ambiguous = false
                for (child in inspection.children) {
                    when (sameDocument(child.documentUri, documentUriString)) {
                        true -> return true
                        false -> Unit
                        null -> ambiguous = true
                    }
                }
                if (ambiguous) null else false
            }
            is ChildrenInspectionResult.Failed -> null
        }
    }

    /** An inaccessible item URI is absent only when an accessible parent proves its identity is not listed. */
    private suspend fun verifiedAbsentIn(documentUriString: String, parentUriString: String): Boolean =
        when (documentPresence(documentUriString)) {
            DocumentPresence.ABSENT -> true
            DocumentPresence.EXISTS -> false
            DocumentPresence.INACCESSIBLE -> documentExistsInVerified(parentUriString, documentUriString) == false
        }

    suspend fun moveAndRenameDocumentWithExactName(
        sourceUriString: String,
        sourceParentUriString: String,
        targetParentUriString: String,
        newName: String,
    ): SafeMutationResult = withContext(Dispatchers.IO) {
        val originalName = getDisplayName(sourceUriString)
            ?: return@withContext SafeMutationResult.Failed
        val targetChildren = when (val inspection = inspectChildren(targetParentUriString)) {
            is ChildrenInspectionResult.Success -> inspection.children
            is ChildrenInspectionResult.Failed -> return@withContext SafeMutationResult.InspectionFailed
        }
        when (hasNameConflict(targetChildren, newName, sourceUriString)) {
            true -> return@withContext SafeMutationResult.Duplicate
            null -> return@withContext SafeMutationResult.InspectionFailed
            false -> Unit
        }

        when (sameDocument(sourceParentUriString, targetParentUriString)) {
            true -> {
                val renamed = renameDocument(sourceUriString, newName)
                    ?: return@withContext SafeMutationResult.Failed
                return@withContext if (getDisplayName(renamed) == newName) {
                    SafeMutationResult.Created(renamed)
                } else {
                    val restored = renameDocument(renamed, originalName)
                    if (restored != null && getDisplayName(restored) == originalName &&
                        documentExistsInVerified(sourceParentUriString, restored) == true
                    ) SafeMutationResult.Failed else SafeMutationResult.Partial(
                        sourceUriString,
                        renamed,
                        "The rename result could not be verified or restored; inspect the project folder",
                    )
                }
            }
            false -> Unit
            null -> return@withContext SafeMutationResult.InspectionFailed
        }

        val moved = moveDocument(sourceUriString, sourceParentUriString, targetParentUriString)
            ?: return@withContext when (val copied = copyDocumentWithExactName(sourceUriString, targetParentUriString, newName)) {
                is SafeMutationResult.Created -> {
                    deleteDocument(sourceUriString)
                    if (verifiedAbsentIn(sourceUriString, sourceParentUriString)) {
                        copied
                    } else {
                        deleteDocument(copied.documentUri)
                        if (verifiedAbsentIn(copied.documentUri, targetParentUriString)) SafeMutationResult.Failed
                        else SafeMutationResult.Partial(
                            sourceUriString,
                            copied.documentUri,
                            "Verify both folders before retrying the move and rename",
                        )
                    }
                }
                else -> copied
            }
        val renamed = renameDocument(moved, newName)
            ?: run {
                val restored = moveDocument(moved, targetParentUriString, sourceParentUriString)
                return@withContext if (restored != null && getDisplayName(restored) == originalName &&
                    !documentExistsIn(targetParentUriString, moved)
                ) SafeMutationResult.Failed else SafeMutationResult.Partial(
                    sourceUriString,
                    moved,
                    "The item may remain in the destination under its original name; inspect both folders before retrying",
                )
            }
        if (getDisplayName(renamed) == newName && !documentExistsIn(sourceParentUriString, sourceUriString)) {
            SafeMutationResult.Created(renamed)
        } else {
            val restored = renameDocument(renamed, originalName)
            val returned = restored?.let { moveDocument(it, targetParentUriString, sourceParentUriString) }
            if (returned != null && getDisplayName(returned) == originalName &&
                documentExistsInVerified(sourceParentUriString, returned) == true &&
                documentExistsInVerified(targetParentUriString, renamed) == false
            ) SafeMutationResult.Failed else SafeMutationResult.Partial(
                sourceUriString,
                renamed,
                "Rename rollback could not be verified; inspect both folders before retrying",
            )
        }
    }

    // ── Move ───────────────────────────────────────────────────────────────

    /**
     * Attempt only a provider-native or same-filesystem move. Copy-and-delete
     * fallbacks belong to typed mutation workflows that can verify both locations
     * and report partial outcomes without hiding orphaned copies.
     *
     * @return The provider's destination URI, or null when no native move was made.
     */
    suspend fun moveDocument(
        sourceUriString: String,
        sourceParentUriString: String,
        targetParentUriString: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (isFileUri(sourceUriString)) {
                val src = fileFromUri(sourceUriString) ?: return@withContext null
                val targetParent = fileFromUri(targetParentUriString) ?: return@withContext null
                val dst = File(targetParent, src.name)
                if (!Files.notExists(dst.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withContext null
                return@withContext if (src.renameTo(dst)) Uri.fromFile(dst).toString() else null
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext null
            runCatching {
                DocumentsContract.moveDocument(
                    resolver,
                    mutationDocumentUri(sourceUriString),
                    mutationDocumentUri(sourceParentUriString),
                    mutationDocumentUri(targetParentUriString),
                )?.toString()
            }.getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "moveDocument failed: $sourceUriString → $targetParentUriString", e)
            null
        }
    }

    // ── Deletion ───────────────────────────────────────────────────────────

    /**
     * Delete exactly [documentUriString], never its parent.
     *
     * Some document providers reject deleting a non-empty directory even when
     * the directory itself is writable. In that case we inspect and delete
     * only the directory's current children, retry the directory delete, and
     * verify that the selected document is absent. If a provider accepts the
     * delete but makes the old URI inaccessible, the result remains uncertain;
     * only a provider-confirmed absent state is reported as success.
     */
    suspend fun deleteDocument(documentUriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isFileUri(documentUriString)) {
                val file = fileFromUri(documentUriString) ?: return@withContext false
                val path = file.toPath()
                if (Files.isSymbolicLink(path)) {
                    Files.delete(path)
                    return@withContext documentPresence(documentUriString) == DocumentPresence.ABSENT
                }
                Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
                return@withContext documentPresence(documentUriString) == DocumentPresence.ABSENT
            }

            val documentUri = mutationDocumentUri(documentUriString)
            val beforeDelete = documentPresence(documentUriString)
            when (beforeDelete) {
                DocumentPresence.ABSENT -> return@withContext true
                // An inaccessible URI before the mutation is a genuine
                // permission/availability failure, not proof of deletion.
                DocumentPresence.INACCESSIBLE -> return@withContext false
                DocumentPresence.EXISTS -> Unit
            }
            runCatching {
                DocumentsContract.deleteDocument(resolver, documentUri)
            }
            when (documentPresence(documentUriString)) {
                DocumentPresence.ABSENT -> return@withContext true
                // A revoked URI is not proof that the resource is absent.
                DocumentPresence.INACCESSIBLE -> return@withContext false
                DocumentPresence.EXISTS -> Unit
            }

            if (!deleteChildrenRecursively(documentUriString)) return@withContext false
            runCatching {
                DocumentsContract.deleteDocument(resolver, documentUri)
            }
            when (documentPresence(documentUriString)) {
                DocumentPresence.ABSENT -> true
                DocumentPresence.EXISTS, DocumentPresence.INACCESSIBLE -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteDocument failed for $documentUriString: ${e.message}", e)
            false
        }
    }

    /** Deletes descendants only; it never receives or deletes a parent URI. */
    private suspend fun deleteChildrenRecursively(directoryUriString: String): Boolean {
        val children = when (val inspection = inspectChildren(directoryUriString)) {
            is ChildrenInspectionResult.Success -> inspection.children
            is ChildrenInspectionResult.Failed -> return false
        }
        for (child in children) {
            deleteDocument(child.documentUri)
            if (!verifiedAbsentIn(child.documentUri, directoryUriString)) return false
        }
        return (inspectChildren(directoryUriString) as? ChildrenInspectionResult.Success)
            ?.children
            ?.isEmpty() == true
    }

    suspend fun deleteChildIfPresent(parentUriString: String, childName: String): Boolean {
        val child = when (val inspection = inspectChildren(parentUriString)) {
            is ChildrenInspectionResult.Success -> inspection.children.firstOrNull { it.displayName == childName }
            is ChildrenInspectionResult.Failed -> return false
        } ?: return true
        deleteDocument(child.documentUri)
        return verifiedAbsentIn(child.documentUri, parentUriString)
    }

    // ── Rename ─────────────────────────────────────────────────────────────

    /**
     * Rename a document.
     *
     * IMPORTANT (SAF): Android may assign a different URI after renaming. Always use
     * the returned URI for subsequent operations, not the original.
     *
     * @param documentUriString  SAF document URI or file:// URI
     * @param newDisplayName     New display name
     * @return                   New document URI, or null on failure.
     */
    suspend fun renameDocument(documentUriString: String, newDisplayName: String): String? =
        withContext(Dispatchers.IO) {
            if (!isValidLeafName(newDisplayName)) return@withContext null
            try {
                if (isFileUri(documentUriString)) {
                    val file = fileFromUri(documentUriString) ?: return@withContext null
                    val parent = file.parentFile?.canonicalFile ?: return@withContext null
                    val newFile = File(parent, newDisplayName).canonicalFile
                    if (newFile.parentFile != parent) return@withContext null
                    if (file.renameTo(newFile)) Uri.fromFile(newFile).toString() else null
                } else {
                    DocumentsContract.renameDocument(
                        resolver,
                        mutationDocumentUri(documentUriString),
                        newDisplayName,
                    )?.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "renameDocument failed for $documentUriString: ${e.message}", e)
                null
            }
        }

    // ── Metadata ───────────────────────────────────────────────────────────

    /**
     * Get the display name of a document.
     */
    suspend fun getDisplayName(documentUriString: String): String? =
        withContext(Dispatchers.IO) {
            if (isFileUri(documentUriString)) {
                fileFromUri(documentUriString)?.name
            } else {
                queryStringColumn(documentUriString, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            }
        }

    suspend fun documentExists(documentUriString: String): Boolean =
        getDisplayName(documentUriString) != null

    /** Returns provider-reported byte length without opening and buffering the file. */
    suspend fun documentSize(documentUriString: String): Long? = withContext(Dispatchers.IO) {
        if (isFileUri(documentUriString)) {
            fileFromUri(documentUriString)?.takeIf { it.isFile }?.length()
        } else {
            queryLongColumn(documentUriString, DocumentsContract.Document.COLUMN_SIZE)
                ?.takeIf { it >= 0L }
        }
    }

    suspend fun isDirectoryDocument(documentUriString: String): Boolean = withContext(Dispatchers.IO) {
        if (isFileUri(documentUriString)) {
            fileFromUri(documentUriString)?.isDirectory == true
        } else {
            queryStringColumn(
                mutationDocumentUri(documentUriString).toString(),
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ) == MIME_DIR
        }
    }

    suspend fun documentPresence(documentUriString: String): DocumentPresence =
        withContext(Dispatchers.IO) {
            try {
                if (isFileUri(documentUriString)) {
                    val file = fileFromUri(documentUriString)
                        ?: return@withContext DocumentPresence.INACCESSIBLE
                    val path = file.toPath()
                    return@withContext when {
                        Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> DocumentPresence.EXISTS
                        Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> DocumentPresence.ABSENT
                        else -> DocumentPresence.INACCESSIBLE
                    }
                }
                resolver.query(
                    mutationDocumentUri(documentUriString),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) DocumentPresence.EXISTS
                    else DocumentPresence.ABSENT
                } ?: DocumentPresence.INACCESSIBLE
            } catch (e: Exception) {
                Log.e(TAG, "documentPresence failed for $documentUriString: ${e.message}", e)
                DocumentPresence.INACCESSIBLE
            }
        }

    // ── Private helpers ────────────────────────────────────────────────────

    private suspend fun queryStringColumn(documentUriString: String, column: String): String? =
        withContext(Dispatchers.IO) {
            try {
                resolver.query(
                    Uri.parse(documentUriString),
                    arrayOf(column),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "queryStringColumn($column) failed for $documentUriString: ${e.message}", e)
                null
            }
        }

    /**
     * Return the document URI for a mutation without replacing a child ID
     * with the tree root ID. Child URIs have both `/tree/` and `/document/`
     * path segments, so [DocumentsContract.getTreeDocumentId] is only valid
     * for the picker-selected root URI.
     */
    private fun mutationDocumentUri(documentUriString: String): Uri {
        val uri = Uri.parse(documentUriString)
        if (!DocumentsContract.isTreeUri(uri)) return uri
        val documentId = documentIdForUri(uri)
        return DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
    }

    private fun queryLongColumn(documentUriString: String, column: String): Long? {
        return try {
            resolver.query(
                Uri.parse(documentUriString),
                arrayOf(column),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryLongColumn($column) failed for $documentUriString: ${e.message}", e)
            null
        }
    }


}

// ── InputStream extension ─────────────────────────────────────────────────────

/**
 * Read all bytes from the stream. Compatible with API 26+.
 */
@Throws(IOException::class)
private fun InputStream.readAllBytesCompat(): ByteArray {
    val buf = ByteArrayOutputStream(8192)
    val chunk = ByteArray(8192)
    var n: Int
    while (read(chunk).also { n = it } != -1) {
        buf.write(chunk, 0, n)
    }
    return buf.toByteArray()
}
