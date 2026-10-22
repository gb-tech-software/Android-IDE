package dev.android.ide.filemanager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.android.ide.contracts.FileSystemAdapter
import dev.android.ide.contracts.FileSystemNode
import dev.android.ide.contracts.FileSystemLocation
import dev.android.ide.saf.ExactCreateResult
import dev.android.ide.saf.FileManagementService
import dev.android.ide.saf.SafeMutationResult
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.UUID

/** Filesystem adapter dedicated to File Manager discovery and directory traversal. */
class FileManagerStorageAdapter(private val fileSystem: FileSystemAdapter) : FileManagerStorageService {
    override suspend fun roots(): List<FileSystemNode> = fileSystem.mountedRoots()
    override suspend fun children(parent: FileSystemNode): List<FileSystemNode> = fileSystem.children(parent)
    override suspend fun resolveLocation(resourceUri: String, displayLabel: String): FileSystemNode? =
        fileSystem.resolve(FileSystemLocation(resourceUri, displayLabel)).node
}

/** File Manager commands use shared safe mutations and Android's provider streams. */
class FileManagerMutationAdapter(
    private val context: Context,
    private val operations: FileManagementService,
    private val storage: FileManagerStorageService,
) : FileManagerMutationService {
    private val app = context.applicationContext

    override suspend fun createFolder(parent: FileSystemNode, name: String): Boolean =
        operations.createFolder(providerUri(parent), name) is ExactCreateResult.Created
    override suspend fun rename(node: FileSystemNode, name: String): Boolean = operations.rename(providerUri(node), name) != null
    override suspend fun delete(node: FileSystemNode): Boolean = operations.delete(providerUri(node))
    override suspend fun copy(node: FileSystemNode, destination: FileSystemNode): Boolean =
        operations.copy(providerUri(node), providerUri(destination)) is SafeMutationResult.Created
    override suspend fun move(node: FileSystemNode, destination: FileSystemNode): Boolean {
        val parent = node.parentVirtualPath ?: return false
        return operations.move(providerUri(node), providerUri(parent), providerUri(destination)) is SafeMutationResult.Created
    }
    override suspend fun import(sourceUri: String, destination: FileSystemNode): Boolean =
        operations.copy(sourceUri, providerUri(destination)) is SafeMutationResult.Created

    override suspend fun export(nodes: List<FileSystemNode>, destinationUri: String): Boolean = runCatching {
        require(nodes.isNotEmpty())
        val destination = Uri.parse(destinationUri)
        val output = if (destination.scheme == "file") FileOutputStream(File(requireNotNull(destination.path)))
            else app.contentResolver.openOutputStream(destination, "wt")
        requireNotNull(output) { "The chosen destination could not be opened" }.use { raw ->
            if (nodes.size > 1 || nodes.single().isDirectory) ZipOutputStream(raw).use { zip ->
                nodes.forEach { node ->
                    val name = safeArchiveName(node.displayLabel)
                    if (node.isDirectory) addDirectory(node, "$name/", zip) else addFile(node, name, zip)
                }
            } else app.contentResolver.openInputStream(Uri.parse(providerUri(nodes.single())))?.use { it.copyTo(raw) }
                ?: throw IllegalStateException("The selected file could not be read")
        }
        true
    }.getOrDefault(false)

    private suspend fun addDirectory(node: FileSystemNode, path: String, zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry(path)); zip.closeEntry()
        storage.children(node).forEach { child ->
            val childPath = path + safeArchiveName(child.displayLabel)
            if (child.isDirectory) addDirectory(child, "$childPath/", zip)
            else {
                addFile(child, childPath, zip)
            }
        }
    }

    private fun addFile(node: FileSystemNode, path: String, zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry(path))
        app.contentResolver.openInputStream(Uri.parse(providerUri(node)))?.use { it.copyTo(zip) }
            ?: throw IllegalStateException("Could not read ${node.displayLabel}")
        zip.closeEntry()
    }

    private fun safeArchiveName(value: String): String = value
        .replace('/', '_')
        .replace('\\', '_')
        .filterNot { it.isISOControl() }
        .take(200)
        .takeUnless { it.isBlank() || it == "." || it == ".." }
        ?: "item"

    override suspend fun details(node: FileSystemNode): FileManagerItemDetails {
        val uri = Uri.parse(providerUri(node))
        var size: Long? = null
        var modified: Long? = null
        var mime: String? = app.contentResolver.getType(uri)
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            if (!node.isDirectory) size = file.length()
            modified = file.lastModified().takeIf { it > 0 }
            if (mime == null) mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        } else {
            runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        if (mi >= 0 && !c.isNull(mi)) modified = c.getLong(mi)
                    }
                }
            }
        }
        return FileManagerItemDetails(
            node.displayLabel,
            if (node.isDirectory) "Folder" else "File",
            mime,
            size,
            modified,
            providerDisplayPath(node, uri),
        )
    }

    override suspend fun prepareShare(nodes: List<FileSystemNode>): List<String> {
        val dir = File(app.cacheDir, "file_manager_share").apply { mkdirs() }
        return nodes.map { node ->
            val name = if (node.isDirectory) "${node.displayLabel}.zip" else node.displayLabel
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "shared-file" }
            val file = File(dir, "${UUID.randomUUID()}_$safeName")
            check(export(listOf(node), Uri.fromFile(file).toString())) { "Could not prepare ${node.displayLabel} for sharing" }
            FileProvider.getUriForFile(app, "${app.packageName}.browser.fileprovider", file).toString()
        }
    }

    /** Human-readable provider path; encoded virtualPath values never reach the UI. */
    private fun providerDisplayPath(node: FileSystemNode, uri: Uri): String = when (uri.scheme) {
        "file" -> uri.path ?: node.displayLabel
        "content" -> {
            val authority = uri.authority ?: "storage provider"
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            if (documentId.isNullOrBlank()) "$authority · ${node.displayLabel}"
            else "$authority · ${documentId.replace(':', '/') }"
        }
        else -> node.displayLabel
    }

    private fun providerUri(node: FileSystemNode): String = providerUri(node.virtualPath)
    private fun providerUri(path: String): String {
        val uri = Uri.parse(path)
        require(uri.scheme == "androidide" && uri.host == "filesystem") { "Unsupported File Manager resource" }
        return Uri.decode(uri.encodedPath?.removePrefix("/") ?: error("Missing resource URI"))
    }
}

/** File Manager domain composition root. */
class FileManagerApplicationService internal constructor(
    private val storage: FileManagerStorageService,
    private val mutations: FileManagerMutationService,
) : FileManagerStorageService, FileManagerMutationService {
    override suspend fun roots() = storage.roots()
    override suspend fun children(parent: FileSystemNode) = storage.children(parent)
    override suspend fun resolveLocation(resourceUri: String, displayLabel: String) = storage.resolveLocation(resourceUri, displayLabel)
    override suspend fun createFolder(parent: FileSystemNode, name: String) = mutations.createFolder(parent, name)
    override suspend fun rename(node: FileSystemNode, name: String) = mutations.rename(node, name)
    override suspend fun delete(node: FileSystemNode) = mutations.delete(node)
    override suspend fun copy(node: FileSystemNode, destination: FileSystemNode) = mutations.copy(node, destination)
    override suspend fun move(node: FileSystemNode, destination: FileSystemNode) = mutations.move(node, destination)
    override suspend fun import(sourceUri: String, destination: FileSystemNode) = mutations.import(sourceUri, destination)
    override suspend fun export(nodes: List<FileSystemNode>, destinationUri: String) = mutations.export(nodes, destinationUri)
    override suspend fun details(node: FileSystemNode) = mutations.details(node)
    override suspend fun prepareShare(nodes: List<FileSystemNode>) = mutations.prepareShare(nodes)
}
