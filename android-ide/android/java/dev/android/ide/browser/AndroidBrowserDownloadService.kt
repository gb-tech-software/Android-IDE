package dev.android.ide.browser

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AndroidBrowserDownloadService(
    context: Context,
    private val storage: BrowserStorageService,
    private val settings: BrowserSettingsService,
    private val scope: CoroutineScope,
) : BrowserDownloadService {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val lock = Any()
    private val mutableDownloads = MutableStateFlow(storage.readDownloads())
    private val mutableDuplicatePrompt = MutableStateFlow<DuplicateDownloadPrompt?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    private val activeTransfers = ConcurrentHashMap<String, ActiveTransfer>()
    private val retryHints = ConcurrentHashMap<String, RetryMetadata>()
    private var pendingDuplicate: PendingDownload? = null

    override val downloads: StateFlow<List<BrowserDownload>> = mutableDownloads.asStateFlow()
    override val duplicatePrompt: StateFlow<DuplicateDownloadPrompt?> = mutableDuplicatePrompt.asStateFlow()
    override val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        // BrowserStateStore maps an in-flight row from a dead process to INTERRUPTED.
        // Preserve only its URL/path metadata; no stream or worker is restored.
        mutableDownloads.value.filter { it.status == BrowserDownloadStatus.INTERRUPTED }
            .forEach { item -> item.uri.takeIf(String::isNotBlank)?.let { publishPartialIfNeeded(Uri.parse(it)) } }
        storage.saveDownloads(mutableDownloads.value)
    }

    override fun accept(request: BrowserDownloadRequest) {
        val body = request.body
        if (body == null) {
            mutableError.value = "The download did not provide a readable response"
            return
        }
        val name = request.fileName?.takeIf(String::isNotBlank)
            ?: request.url.substringAfterLast('/').substringBefore('?').ifBlank { "download-${System.currentTimeMillis()}" }
        val mimeType = request.mimeType.ifBlank { "application/octet-stream" }
        val retryHint = retryHints.remove(request.url)?.takeIf { it.expiresAt > System.currentTimeMillis() }
        val pending = PendingDownload(
            name = retryHint?.name ?: name,
            sourceUrl = request.url,
            mimeType = mimeType,
            body = body,
            totalBytes = request.totalBytes,
            folderUriOverride = retryHint?.folderUri,
            preserveFolderChoice = retryHint != null,
        )
        val folderUri = destinationFolder(pending)
        if (settings.settings.value.autoRenameDuplicateDownloads) {
            pending.name = uniqueAvailableName(pending.name, folderUri)
        } else if (hasDestinationConflict(pending.name, folderUri)) {
            pendingDuplicate?.body?.let { runCatching { it.close() } }
            pendingDuplicate = pending
            mutableDuplicatePrompt.value = DuplicateDownloadPrompt(pending.name, request.url)
            return
        }
        startDownload(pending)
    }

    override fun resolveDuplicate(name: String?, autoRenameFutureConflicts: Boolean) {
        val pending = pendingDuplicate
        if (pending == null) return
        if (name == null) {
            pendingDuplicate = null
            mutableDuplicatePrompt.value = null
            runCatching { pending.body.close() }
            return
        }

        val requestedName = name.trim()
        if (requestedName.isBlank() || requestedName == "." || requestedName == ".." ||
            requestedName.any { it == '/' || it == '\\' || it.code < 0x20 }
        ) {
            mutableDuplicatePrompt.value = mutableDuplicatePrompt.value?.copy(
                validationError = "Enter a valid file name without path separators.",
            )
            return
        }
        pending.name = requestedName
        val folderUri = destinationFolder(pending)
        if (hasDestinationConflict(requestedName, folderUri)) {
            if (autoRenameFutureConflicts) {
                pending.name = uniqueAvailableName(requestedName, folderUri)
            } else {
                mutableDuplicatePrompt.value = mutableDuplicatePrompt.value?.copy(
                    name = requestedName,
                    validationError = "A file with this name already exists in the selected download folder.",
                )
                return
            }
        }
        if (autoRenameFutureConflicts && !settings.settings.value.autoRenameDuplicateDownloads) {
            settings.update { it.copy(autoRenameDuplicateDownloads = true) }
        }
        pendingDuplicate = null
        mutableDuplicatePrompt.value = null
        startDownload(pending)
    }

    override fun pause(downloadId: String): Boolean {
        val transfer = activeTransfers[downloadId] ?: return false
        if (mutableDownloads.value.firstOrNull { it.id == downloadId }?.status != BrowserDownloadStatus.DOWNLOADING) return false
        if (!transfer.control.pause()) return false
        updateEntry(downloadId) { it.copy(status = BrowserDownloadStatus.PAUSED, error = null) }
        return true
    }

    override fun resume(downloadId: String): Boolean {
        val transfer = activeTransfers[downloadId] ?: return false
        if (mutableDownloads.value.firstOrNull { it.id == downloadId }?.status != BrowserDownloadStatus.PAUSED) return false
        if (!transfer.control.resume()) return false
        updateEntry(downloadId) { it.copy(status = BrowserDownloadStatus.DOWNLOADING, error = null) }
        return true
    }

    override fun cancel(downloadId: String): Boolean {
        val transfer = activeTransfers[downloadId] ?: return false
        transfer.cancelled = true
        transfer.control.resume()
        runCatching { transfer.pending.body.close() }
        transfer.job?.cancel()
        updateEntry(downloadId) {
            it.copy(status = BrowserDownloadStatus.CANCELED, available = false, error = null)
        }
        return true
    }

    override fun prepareRetry(downloadId: String): String? {
        val item = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return null
        if (item.sourceUrl.isBlank() || activeTransfers.containsKey(downloadId)) return null
        if (item.status != BrowserDownloadStatus.COMPLETED && item.uri.isNotBlank()) {
            runCatching { deleteStoredFile(Uri.parse(item.uri)) }
            updateEntry(downloadId) { it.copy(uri = "", downloadedBytes = 0L) }
        }
        retryHints[item.sourceUrl] = RetryMetadata(item.name, item.folderUri, System.currentTimeMillis() + RETRY_HINT_TTL_MS)
        return item.sourceUrl
    }

    override fun reconcile() {
        scope.launch(Dispatchers.IO) {
            mutableDownloads.value.filter { it.status == BrowserDownloadStatus.COMPLETED }.forEach { item ->
                val available = item.uri.isNotBlank() && isReadable(Uri.parse(item.uri))
                updateEntry(item.id) { current ->
                    if (current.status == BrowserDownloadStatus.COMPLETED) current.copy(available = available) else current
                }
            }
        }
    }

    override fun open(downloadId: String): Intent? {
        val download = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return null
        if (download.status != BrowserDownloadStatus.COMPLETED || !download.available || !isReadable(Uri.parse(download.uri))) {
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
        if (download.status != BrowserDownloadStatus.COMPLETED || !download.available || !isReadable(uri)) {
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
        val transfer = activeTransfers[downloadId]
        if (transfer != null) cancel(downloadId)
        val download = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return false
        if (download.uri.isNotBlank() && !deleteStoredFile(Uri.parse(download.uri))) {
            reconcile()
            mutableError.value = "The downloaded file could not be deleted"
            return false
        }
        removeEntry(downloadId)
        return true
    }

    override fun removeUnavailable(downloadId: String): Boolean {
        val download = mutableDownloads.value.firstOrNull { it.id == downloadId } ?: return false
        if (download.available || activeTransfers.containsKey(downloadId)) return false
        if (download.status != BrowserDownloadStatus.COMPLETED && download.uri.isNotBlank()) {
            val partial = Uri.parse(download.uri)
            if (isReadable(partial) && !deleteStoredFile(partial)) {
                mutableError.value = "The partial download could not be deleted"
                return false
            }
        }
        removeEntry(downloadId)
        return true
    }

    override fun clear() {
        pendingDuplicate?.body?.let { runCatching { it.close() } }
        pendingDuplicate = null
        mutableDuplicatePrompt.value = null
        activeTransfers.keys.toList().forEach(::cancel)
        persist(emptyList())
        mutableError.value = null
    }

    override fun clearError() { mutableError.value = null }

    private fun startDownload(download: PendingDownload) {
        val id = UUID.randomUUID().toString()
        val transfer = ActiveTransfer(id, download)
        activeTransfers[id] = transfer
        val folder = destinationFolder(download)
        val entry = BrowserDownload(
            id = id,
            name = download.name,
            uri = "",
            sourceUrl = download.sourceUrl,
            mimeType = download.mimeType,
            size = 0L,
            timestamp = System.currentTimeMillis(),
            available = false,
            status = BrowserDownloadStatus.DOWNLOADING,
            downloadedBytes = 0L,
            totalBytes = download.totalBytes,
            folderUri = folder,
        )
        synchronized(lock) { persistLocked(mutableDownloads.value + entry) }
        transfer.job = scope.launch(Dispatchers.IO) { writeDownload(transfer, folder) }
    }

    private fun destinationFolder(download: PendingDownload): String? =
        if (download.preserveFolderChoice) download.folderUriOverride else settings.settings.value.downloadFolder

    /** Checks both tracked transfers and actual contents of the chosen destination folder. */
    private fun hasDestinationConflict(name: String, folderUri: String?): Boolean {
        val tracked = mutableDownloads.value.any { item ->
            if (item.folderUri != folderUri || item.name != name) return@any false
            when (item.status) {
                BrowserDownloadStatus.DOWNLOADING, BrowserDownloadStatus.PAUSED -> true
                BrowserDownloadStatus.COMPLETED, BrowserDownloadStatus.INTERRUPTED ->
                    item.uri.isNotBlank() && isReadable(Uri.parse(item.uri))
                BrowserDownloadStatus.FAILED, BrowserDownloadStatus.CANCELED -> false
            }
        }
        if (tracked) return true

        return if (folderUri == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val args = arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/")
                runCatching {
                    resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)
                        ?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).exists()
            }
        } else {
            runCatching {
                val tree = Uri.parse(folderUri)
                val parentId = DocumentsContract.getTreeDocumentId(tree)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                resolver.query(children, projection, null, null, null)?.use { cursor ->
                    val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    nameColumn >= 0 && generateSequence { if (cursor.moveToNext()) cursor.getString(nameColumn) else null }
                        .any { it == name }
                } == true
            }.getOrDefault(false)
        }
    }

    private fun uniqueAvailableName(name: String, folderUri: String?): String {
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            ?.let { ".$it" }.orEmpty()
        val stem = name.removeSuffix(extension)
        var index = 1
        while (index < 100_000) {
            val candidate = "$stem ($index)$extension"
            if (!hasDestinationConflict(candidate, folderUri)) return candidate
            index++
        }
        return "$stem (${System.currentTimeMillis()})$extension"
    }

    private suspend fun writeDownload(transfer: ActiveTransfer, folderUri: String?) {
        var destination: Uri? = null
        var copied = 0L
        try {
            val targetUri = createDestination(transfer.pending.name, transfer.pending.mimeType, folderUri)
                ?: error("Choose an available download folder in Browser settings")
            destination = targetUri
            if (transfer.cancelled) {
                deleteStoredFile(targetUri)
                throw CancellationException("Download canceled")
            }
            transfer.destination = targetUri
            updateEntry(transfer.id) { it.copy(uri = targetUri.toString()) }
            resolver.openOutputStream(targetUri, "w")?.use { output ->
                transfer.pending.body.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (transfer.cancelled) throw CancellationException("Download canceled")
                        transfer.control.awaitIfPaused()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (transfer.cancelled) throw CancellationException("Download canceled")
                        transfer.control.awaitIfPaused()
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied == read.toLong() || copied % (256 * 1024) < read) {
                            val progress = copied
                            updateEntry(transfer.id) { it.copy(downloadedBytes = progress) }
                        }
                    }
                }
            } ?: error("Destination is not writable")
            if (transfer.cancelled) throw CancellationException("Download canceled")
            if (folderUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(targetUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            val size = querySize(targetUri).takeIf { it > 0L } ?: copied
            updateEntry(transfer.id) {
                if (transfer.cancelled) throw CancellationException("Download canceled")
                it.copy(
                    uri = targetUri.toString(),
                    size = size,
                    downloadedBytes = copied,
                    status = BrowserDownloadStatus.COMPLETED,
                    available = true,
                    error = null,
                )
            }
        } catch (error: CancellationException) {
            destination?.let(::publishPartialIfNeeded)
            if (!transfer.cancelled) markFailed(transfer, destination, copied, error)
        } catch (error: Throwable) {
            destination?.let(::publishPartialIfNeeded)
            markFailed(transfer, destination, copied, error)
        } finally {
            activeTransfers.remove(transfer.id, transfer)
            if (destination == null) runCatching { transfer.pending.body.close() }
        }
    }

    private fun markFailed(transfer: ActiveTransfer, destination: Uri?, copied: Long, error: Throwable) {
        if (transfer.cancelled) {
            updateEntry(transfer.id) {
                it.copy(
                    uri = destination?.toString().orEmpty(),
                    downloadedBytes = copied,
                    status = BrowserDownloadStatus.CANCELED,
                    available = false,
                )
            }
        } else {
            updateEntry(transfer.id) {
                it.copy(
                    uri = destination?.toString().orEmpty(),
                    downloadedBytes = copied,
                    status = BrowserDownloadStatus.FAILED,
                    available = false,
                    error = "${error.message ?: "The transfer could not be completed"}",
                )
            }
        }
    }

    private fun createDestination(name: String, mimeType: String, configuredFolder: String?): Uri? = runCatching {
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

    private fun publishPartialIfNeeded(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == "media") {
            runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
        }
    }

    private fun deleteStoredFile(uri: Uri): Boolean = runCatching {
        if (DocumentsContract.isDocumentUri(appContext, uri)) DocumentsContract.deleteDocument(resolver, uri)
        else resolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    private fun isReadable(uri: Uri): Boolean = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    private fun querySize(uri: Uri): Long = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)

    private fun updateEntry(id: String, transform: (BrowserDownload) -> BrowserDownload) {
        synchronized(lock) {
            val current = mutableDownloads.value
            val next = current.map { item -> if (item.id == id) transform(item) else item }
            if (next != current) persistLocked(next)
        }
    }

    private fun removeEntry(id: String) {
        synchronized(lock) { persistLocked(mutableDownloads.value.filterNot { it.id == id }) }
    }

    private fun persist(downloads: List<BrowserDownload>) {
        synchronized(lock) { persistLocked(downloads) }
    }

    private fun persistLocked(downloads: List<BrowserDownload>) {
        mutableDownloads.value = downloads
        storage.saveDownloads(downloads)
    }

    private data class PendingDownload(
        var name: String,
        val sourceUrl: String,
        val mimeType: String,
        val body: InputStream,
        val totalBytes: Long,
        val folderUriOverride: String? = null,
        val preserveFolderChoice: Boolean = false,
    )

    private data class RetryMetadata(val name: String, val folderUri: String?, val expiresAt: Long)

    private class ActiveTransfer(val id: String, val pending: PendingDownload) {
        val control = TransferPauseGate()
        @Volatile var destination: Uri? = null
        @Volatile var job: Job? = null
        @Volatile var cancelled: Boolean = false
    }

    /** Cooperative pause between stream reads/writes; process death always interrupts the transfer. */
    private class TransferPauseGate {
        private val lock = Any()
        private var paused = false
        private var gate = CompletableDeferred<Unit>().apply { complete(Unit) }

        fun pause(): Boolean = synchronized(lock) {
            if (paused) return false
            paused = true
            gate = CompletableDeferred()
            true
        }

        fun resume(): Boolean = synchronized(lock) {
            if (!paused) return false
            paused = false
            gate.complete(Unit)
            true
        }

        suspend fun awaitIfPaused() {
            while (true) {
                val waitFor = synchronized(lock) { if (paused) gate else null } ?: return
                waitFor.await()
            }
        }
    }

    companion object {
        private const val RETRY_HINT_TTL_MS = 5 * 60 * 1000L
    }
}
