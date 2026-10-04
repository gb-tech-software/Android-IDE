package dev.android.ide.saf

import android.database.Cursor
import android.database.MatrixCursor
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.text.format.Formatter
import com.termux.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

/**
 * Exposes only Android IDE's durable user-files root through SAF.
 *
 * The Termux bootstrap, package database, PTYs, and runtime state live outside
 * this root and are intentionally never returned by this provider.
 */
class AndroidIdeDocumentsProvider : DocumentsProvider() {
    private val rootDirectory: File
        get() = requireNotNull(context).filesDir.resolve(USER_FILES_DIRECTORY)

    override fun onCreate(): Boolean {
        if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) return false
        // DocumentsUI may cache provider roots. Notify it when the provider is
        // first brought up so the Android IDE root is not omitted after install
        // or after the app's private storage is initialized.
        requireNotNull(context).contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(AUTHORITY),
            null,
        )
        return rootDirectory.isDirectory
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = resolveProjection(projection, ROOT_PROJECTION)
        val result = MatrixCursor(columns)
        if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) {
            throw IOException("Android IDE storage root could not be created")
        }
        result.addProjectedRow(columns, mapOf(
            DocumentsContract.Root.COLUMN_ROOT_ID to ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID to ROOT_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE to "Android IDE",
            DocumentsContract.Root.COLUMN_SUMMARY to availableSpaceSummary(),
            DocumentsContract.Root.COLUMN_FLAGS to (
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                    DocumentsContract.Root.FLAG_LOCAL_ONLY
            ),
            DocumentsContract.Root.COLUMN_MIME_TYPES to "*/*\n${DocumentsContract.Document.MIME_TYPE_DIR}",
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES to rootDirectory.usableSpace,
            DocumentsContract.Root.COLUMN_ICON to R.mipmap.ic_launcher,
        ))
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = fileForDocumentId(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val columns = resolveProjection(projection, DOCUMENT_PROJECTION)
        return MatrixCursor(columns).also { cursor ->
            appendDocument(cursor, columns, documentId, file)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = fileForDocumentId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val columns = resolveProjection(projection, DOCUMENT_PROJECTION)
        val result = MatrixCursor(columns)
        val children = parent.listFiles() ?: throw IOException("Android IDE storage directory could not be listed")
        children.asSequence()
            .filterNot { child -> runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true) }
            .filter { child -> runCatching { child.canonicalFile.toPath().startsWith(rootDirectory.canonicalFile.toPath()) }.getOrDefault(false) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { child -> appendDocument(result, columns, documentIdForFile(child), child) }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileForDocumentId(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, modeFlags(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileForDocumentId(parentDocumentId)
        if (!parent.isDirectory || displayName.isBlank() || displayName.contains('/') || displayName.contains('\\')) {
            throw FileNotFoundException("Invalid document name")
        }
        val target = File(parent, displayName)
        if (target.exists()) throw FileNotFoundException("Document already exists: $displayName")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        if (!created) throw IOException("Could not create document: $displayName")
        return documentIdForFile(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileForDocumentId(documentId)
        if (file == rootDirectory || !file.exists() || !file.deleteRecursively()) {
            throw FileNotFoundException("Could not delete document: $documentId")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileForDocumentId(documentId)
        if (file == rootDirectory || displayName.isBlank() || displayName.contains('/') || displayName.contains('\\')) {
            throw FileNotFoundException("Invalid document name")
        }
        val target = File(file.parentFile, displayName)
        if (target.exists() || !file.renameTo(target)) throw IOException("Could not rename document")
        return documentIdForFile(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parent = fileForDocumentId(parentDocumentId).canonicalFile.toPath()
        val child = fileForDocumentId(documentId).canonicalFile.toPath()
        child.startsWith(parent) && child != parent
    }.getOrDefault(false)

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(resolveProjection(projection, DOCUMENT_PROJECTION))

    private fun appendDocument(cursor: MatrixCursor, columns: Array<String>, documentId: String, file: File) {
        val isDirectory = file.isDirectory
        val isRoot = file.canonicalFile == rootDirectory.canonicalFile
        var flags = 0
        if (isDirectory && file.canWrite() && file.canExecute()) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        if (!isRoot && file.parentFile?.canWrite() == true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }
        if (!isDirectory && file.canWrite()) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        cursor.addProjectedRow(columns, mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name.ifBlank { "Android IDE" },
            DocumentsContract.Document.COLUMN_MIME_TYPE to
                (if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeType(file.name)),
            DocumentsContract.Document.COLUMN_FLAGS to flags,
            DocumentsContract.Document.COLUMN_SIZE to (if (isDirectory) 0L else file.length()),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            DocumentsContract.Document.COLUMN_ICON to R.mipmap.ic_launcher,
        ))
    }

    private fun fileForDocumentId(documentId: String): File {
        val root = rootDirectory.canonicalFile
        val relative = when {
            documentId == ROOT_DOCUMENT_ID -> ""
            documentId.startsWith(FILE_DOCUMENT_ID_PREFIX) -> documentId.removePrefix(FILE_DOCUMENT_ID_PREFIX)
            else -> documentId // Accept existing pre-prefix child IDs while older projects migrate.
        }
        if (documentId.startsWith(FILE_DOCUMENT_ID_PREFIX) && relative.isBlank()) {
            throw FileNotFoundException("Empty child document ID")
        }
        val file = if (relative.isEmpty()) root else File(root, relative).canonicalFile
        if (!file.toPath().startsWith(root.toPath())) throw FileNotFoundException("Document is outside provider root")
        return file
    }

    private fun documentIdForFile(file: File): String {
        val relative = file.canonicalFile.toPath()
            .let { rootDirectory.canonicalFile.toPath().relativize(it).toString() }
            .replace(File.separatorChar, '/')
        return if (relative.isBlank()) ROOT_DOCUMENT_ID else FILE_DOCUMENT_ID_PREFIX + relative
    }

    private fun modeFlags(mode: String): Int = when (mode) {
        "r" -> ParcelFileDescriptor.MODE_READ_ONLY
        "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        else -> throw IllegalArgumentException("Unsupported document mode: $mode")
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs", "cjs" -> "text/javascript"
        "kt", "kts" -> "text/x-kotlin"
        "java" -> "text/x-java"
        "py" -> "text/x-python"
        "md" -> "text/markdown"
        "xml" -> "text/xml"
        else -> "text/plain"
    }

    private fun resolveProjection(projection: Array<out String>?, defaults: Array<String>): Array<String> =
        projection?.toList()?.toTypedArray() ?: defaults

    private fun availableSpaceSummary(): String =
        "${Formatter.formatFileSize(requireNotNull(context), rootDirectory.usableSpace)} available"

    private fun MatrixCursor.addProjectedRow(columns: Array<String>, values: Map<String, Any?>) {
        addRow(columns.map { column -> values[column] }.toTypedArray())
    }

    companion object {
        const val AUTHORITY = "com.termux.documents"
        const val ROOT_ID = "home"
        const val ROOT_DOCUMENT_ID = "root"
        private const val FILE_DOCUMENT_ID_PREFIX = "file:"
        const val USER_FILES_DIRECTORY = "home"

        fun isProviderUri(uriString: String): Boolean =
            Uri.parse(uriString).authority == AUTHORITY

        /** Resolve only provider-owned document URIs; never accept an arbitrary path. */
        fun localFileForUri(context: Context, uriString: String): File? = runCatching {
            val uri = Uri.parse(uriString)
            if (uri.authority != AUTHORITY) return@runCatching null
            val documentId = when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> return@runCatching null
            }
            val root = context.filesDir.resolve(USER_FILES_DIRECTORY).canonicalFile
            val relative = when {
                documentId == ROOT_DOCUMENT_ID -> ""
                documentId.startsWith(FILE_DOCUMENT_ID_PREFIX) -> documentId.removePrefix(FILE_DOCUMENT_ID_PREFIX)
                else -> documentId // Existing project URIs use the legacy relative ID form.
            }
            if (documentId.startsWith(FILE_DOCUMENT_ID_PREFIX) && relative.isBlank()) return@runCatching null
            val candidate = if (relative.isEmpty()) root else File(root, relative).canonicalFile
            candidate.takeIf { it.toPath().startsWith(root.toPath()) }
        }.getOrNull()

        fun rootTreeUri(): String = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOCUMENT_ID).toString()

        fun createLocalDocument(context: Context, parentUriString: String, mimeType: String, displayName: String): String? = runCatching {
            if (displayName.isBlank() || displayName == "." || displayName == ".." ||
                displayName.any(Char::isISOControl) || '/' in displayName || '\\' in displayName
            ) return@runCatching null
            val parent = localFileForUri(context, parentUriString)?.canonicalFile
                ?.takeIf { it.isDirectory }
                ?: return@runCatching null
            val target = File(parent, displayName).canonicalFile
            val root = context.filesDir.resolve(USER_FILES_DIRECTORY).canonicalFile
            if (!target.toPath().startsWith(root.toPath()) || target.exists()) return@runCatching null
            val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
            if (!created) return@runCatching null
            val relative = root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
            DocumentsContract.buildDocumentUri(AUTHORITY, FILE_DOCUMENT_ID_PREFIX + relative).toString()
        }.getOrNull()

        val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_ICON,
        )
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_ICON,
        )

    }
}
