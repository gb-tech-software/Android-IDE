package dev.android.ide.browser

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

class AndroidBrowserDownloadService(
    context: Context,
    private val storage: BrowserStorageService,
    private val settings: BrowserSettingsService,
    private val scope: CoroutineScope,
) : BrowserDownloadService {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val mutableDownloads = MutableStateFlow(storage.readDownloads())
    private val mutableProgress = MutableStateFlow<BrowserDownloadProgress?>(null)
    private val mutableDuplicatePrompt = MutableStateFlow<DuplicateDownloadPrompt?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    private var pendingDuplicate: PendingDownload? = null

    override val downloads: StateFlow<List<BrowserDownload>> = mutableDownloads.asStateFlow()
    override val progress: StateFlow<BrowserDownloadProgress?> = mutableProgress.asStateFlow()
    override val duplicatePrompt: StateFlow<DuplicateDownloadPrompt?> = mutableDuplicatePrompt.asStateFlow()
    override val error: StateFlow<String?> = mutableError.asStateFlow()

    override fun accept(request: BrowserDownloadRequest) {
        val body = request.body
        if (body == null) {
            mutableError.value = "The download did not provide a readable response"
            return
        }
        val name = request.fileName?.takeIf(String::isNotBlank)
            ?: request.url.substringAfterLast('/').substringBefore('?').ifBlank { "download-${System.currentTimeMillis()}" }
        val mimeType = request.mimeType.ifBlank { "application/octet-stream" }
        val existing = mutableDownloads.value.firstOrNull { it.name == name && it.available }
        val pending = PendingDownload(name, request.url, mimeType, body, request.totalBytes)
        if (existing != null) {
            pendingDuplicate = pending
            mutableDuplicatePrompt.value = DuplicateDownloadPrompt(name, request.url, existing.uri)
            return
        }
        writeDownload(pending)
    }

    override fun resolveDuplicate(replace: Boolean) {
        val prompt = mutableDuplicatePrompt.value ?: return
        val pending = pendingDuplicate
        pendingDuplicate = null
        mutableDuplicatePrompt.value = null
        if (pending == null) return
        if (replace) {
            if (!deleteStoredFile(Uri.parse(prompt.existingUri))) {
                runCatching { pending.body.close() }
                mutableError.value = "The existing downloaded file could not be replaced"
                return
            }
        } else {
            val suffix = pending.name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            val stem = pending.name.removeSuffix(suffix)
            pending.name = "$stem (${System.currentTimeMillis()})$suffix"
        }
        writeDownload(pending)
    }

    override fun reconcile() {
        val current = mutableDownloads.value.map { item ->
            item.copy(available = isReadable(Uri.parse(item.uri)))
        }
        persist(current)
    }

    override fun open(downloadId: String): Intent? {
        val download = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return null
        if (!download.available || !isReadable(Uri.parse(download.uri))) {
            reconcile()
            return null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(download.uri), download.mimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun share(downloadId: String): Intent? {
        val download = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return null
        val uri = Uri.parse(download.uri)
        if (!download.available || !isReadable(uri)) {
            reconcile()
            return null
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = download.mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun delete(downloadId: String): Boolean {
        val download = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return false
        if (download.available && !deleteStoredFile(Uri.parse(download.uri))) {
            reconcile()
            mutableError.value = "The downloaded file could not be deleted"
            return false
        }
        persist(mutableDownloads.value.filterNot { it.id == downloadId })
        return true
    }

    override fun removeUnavailable(downloadId: String): Boolean {
        val download = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return false
        if (download.available) return false
        persist(mutableDownloads.value.filterNot { it.id == downloadId })
        return true
    }

    override fun clear() {
        pendingDuplicate?.body?.let { runCatching { it.close() } }
        pendingDuplicate = null
        mutableDuplicatePrompt.value = null
        mutableProgress.value = null
        mutableError.value = null
        persist(emptyList())
    }

    override fun clearError() { mutableError.value = null }

    private fun writeDownload(download: PendingDownload) {
        mutableProgress.value = BrowserDownloadProgress(download.name, 0L, download.totalBytes)
        scope.launch(Dispatchers.IO) {
            var destination: Uri? = null
            try {
                destination = createDestination(download.name, download.mimeType)
                    ?: error("Choose an available download folder in Browser settings")
                var copied = 0L
                resolver.openOutputStream(destination)?.use { output ->
                    download.body.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied == read.toLong() || copied % (256 * 1024) < read) {
                                mutableProgress.value = BrowserDownloadProgress(download.name, copied, download.totalBytes)
                            }
                        }
                    }
                } ?: error("Destination is not writable")
                if (settings.settings.value.downloadFolder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
                val record = BrowserDownload(
                    id = UUID.randomUUID().toString(),
                    name = download.name,
                    uri = destination.toString(),
                    sourceUrl = download.sourceUrl,
                    mimeType = download.mimeType,
                    size = querySize(destination),
                    timestamp = System.currentTimeMillis(),
                )
                withContext(Dispatchers.Main) { persist(mutableDownloads.value + record); mutableProgress.value = null }
            } catch (error: Throwable) {
                destination?.let { runCatching { resolver.delete(it, null, null) } }
                runCatching { download.body.close() }
                withContext(Dispatchers.Main) {
                    mutableProgress.value = null
                    mutableError.value = "Download failed: ${error.message ?: "unknown error"}"
                }
            }
        }
    }

    private fun createDestination(name: String, mimeType: String): Uri? {
        val configuredFolder = settings.settings.value.downloadFolder
        return runCatching {
            if (configuredFolder == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    })
                } else {
                    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                    val file = File(directory, name)
                    resolver.insert(MediaStore.Files.getContentUri("external"), ContentValues().apply {
                        put(MediaStore.MediaColumns.DATA, file.absolutePath)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    })
                }
            } else {
                val treeUri = Uri.parse(configuredFolder)
                val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                DocumentsContract.createDocument(resolver, parent, mimeType, name)
            }
        }.getOrNull()
    }

    private fun deleteStoredFile(uri: Uri): Boolean = runCatching {
        if (DocumentsContract.isDocumentUri(appContext, uri)) DocumentsContract.deleteDocument(resolver, uri)
        else resolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    private fun isReadable(uri: Uri): Boolean = runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } == true }.getOrDefault(false)

    private fun querySize(uri: Uri): Long = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }.getOrDefault(0L)

    private fun persist(downloads: List<BrowserDownload>) {
        mutableDownloads.value = downloads
        storage.saveDownloads(downloads)
    }

    private data class PendingDownload(
        var name: String,
        val sourceUrl: String,
        val mimeType: String,
        val body: InputStream,
        val totalBytes: Long,
    )
}
