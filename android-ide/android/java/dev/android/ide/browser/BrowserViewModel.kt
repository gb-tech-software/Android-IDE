package dev.android.ide.browser

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.android.ide.app.ApplicationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.net.URLDecoder
import java.util.UUID

private data class PendingDuplicateDownload(
    val name: String,
    val sourceUrl: String,
    val mimeType: String,
    val body: java.io.InputStream,
    val totalBytes: Long,
)

private val SEARCH_ENGINES = mapOf(
    "google" to "https://www.google.com/search?q=%s",
    "bing" to "https://www.bing.com/search?q=%s",
    "duckduckgo" to "https://duckduckgo.com/?q=%s",
    "brave" to "https://search.brave.com/search?q=%s",
    "startpage" to "https://www.startpage.com/sp/search?query=%s",
)

data class BrowserSettings(
    val searchEngine: String = DEFAULT_SEARCH,
    val customSearchUrl: String = "",
    val homePage: String = DEFAULT_HOME,
    val downloadFolder: String? = null,
    val theme: String = "application",
    val developerToolsEnabled: Boolean = true,
    val desktopSiteDefault: Boolean = false,
)

data class BrowserDownload(
    val id: String,
    val name: String,
    val uri: String,
    val sourceUrl: String,
    val mimeType: String,
    val size: Long,
    val timestamp: Long,
    val available: Boolean = true,
)

data class BrowserTabUi(
    val id: String,
    val url: String,
    val title: String,
    val host: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val viewportWidth: Int?,
    val previewPath: String?,
    val isTemporary: Boolean = false,
)

data class BrowserUiState(
    val tabs: List<BrowserTabUi> = emptyList(),
    val selectedTabId: String? = null,
    val address: String = "",
    val loading: Boolean = false,
    val loadProgress: Int = 0,
    val viewportWidth: Int? = null,
    val downloads: List<BrowserDownload> = emptyList(),
    val settings: BrowserSettings = BrowserSettings(),
    val history: List<String> = emptyList(),
    val developerToolsOpen: Boolean = false,
    val permissionPrompt: BrowserPermissionPrompt? = null,
    val duplicateDownload: DuplicateDownloadPrompt? = null,
    val downloadProgress: BrowserDownloadProgress? = null,
    val contextMenu: BrowserContextMenu? = null,
    val error: String? = null,
)

data class BrowserPermissionPrompt(val origin: String, val permission: String)
data class DuplicateDownloadPrompt(val name: String, val sourceUrl: String, val existingUri: String)
data class BrowserDownloadProgress(val name: String, val bytes: Long, val totalBytes: Long)

enum class BrowserContextKind { LINK, IMAGE, VIDEO, AUDIO, LINKED_MEDIA }

data class BrowserContextMenu(
    val kind: BrowserContextKind,
    val linkUri: String? = null,
    val mediaUri: String? = null,
    val title: String? = null,
    val altText: String? = null,
    val textContent: String? = null,
)

internal data class BrowserTabRuntime(
    val id: String,
    val session: GeckoSession,
    var url: String,
    var title: String,
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var sessionState: String? = null,
    var viewportWidth: Int? = null,
    var previewPath: String? = null,
    var scrollY: Int = 0,
    var scrollListener: ((Int) -> Unit)? = null,
    var isTemporary: Boolean = false,
)

/** Application-owned browser domain. It survives navigation away from the Browser surface. */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val stateStore = ApplicationServices.from(application).browserStateStore
    private val runtime = ApplicationServices.from(application).browserRuntime
    private val sessions = linkedMapOf<String, BrowserTabRuntime>()
    private var pendingDuplicateBody: PendingDuplicateDownload? = null
    private var pendingPermissionResult: GeckoResult<Int>? = null
    private val devtoolsPorts = linkedSetOf<WebExtension.Port>()
    private val _uiState = MutableStateFlow(BrowserUiState(settings = readSettings(), history = readHistory()))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        installDeveloperTools()
        restoreTabs()
        reconcileDownloads()
    }

    private fun installDeveloperTools() {
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/devtools/",
            "android-ide-devtools@android-ide",
        ).accept({ extension ->
            Handler(Looper.getMainLooper()).post {
                extension?.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        if (port.name != "android-ide") return
                        devtoolsPorts += port
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onDisconnect(port: WebExtension.Port) { devtoolsPorts -= port }
                        })
                        val shouldOpen = _uiState.value.settings.developerToolsEnabled && _uiState.value.developerToolsOpen
                        runCatching { port.postMessage(JSONObject().put("type", "developer-tools").put("open", shouldOpen)) }
                    }
                }, "android-ide")
            }
        }, { error -> setError("Developer tools could not be installed: ${error?.message ?: error}") })
    }

    private fun restoreTabs() {
        val stored = runCatching { stateStore.readTabs() }.getOrDefault(JSONArray())
        if (stored.length() == 0) {
            createTab()
            return
        }
        for (index in 0 until stored.length()) {
            val item = stored.optJSONObject(index) ?: continue
            createTab(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                url = item.optString("url", DEFAULT_HOME),
                title = item.optString("title", "New tab"),
                savedState = item.optString("state").takeIf(String::isNotBlank),
                viewportWidth = item.optInt("viewportWidth", 0).takeIf { it in 320..2400 },
                previewPath = item.optString("previewPath").takeIf { it.isNotBlank() },
                select = false,
            )
        }
        val selected = stateStore.selectedTabId()
        selectTab(selected?.takeIf(sessions::containsKey) ?: sessions.keys.first())
    }

    fun createTab(url: String = DEFAULT_HOME) {
        createTab(UUID.randomUUID().toString(), url, "New tab", null, null, null, true)
    }

    private fun createTab(
        id: String,
        url: String,
        title: String,
        savedState: String?,
        viewportWidth: Int?,
        previewPath: String?,
        select: Boolean,
        temporary: Boolean = false,
    ) {
        val sessionSettings = GeckoSessionSettings.Builder()
            .userAgentMode(if (_uiState.value.settings.desktopSiteDefault) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build()
        val session = GeckoSession(sessionSettings)
        val tab = BrowserTabRuntime(id, session, url, title, sessionState = savedState, viewportWidth = viewportWidth, previewPath = previewPath, isTemporary = temporary)
        sessions[id] = tab
        attachDelegates(tab)
        savedState?.let { GeckoSession.SessionState.fromString(it)?.let(session::restoreState) }
        session.open(runtime)
        if (savedState == null || url == DEFAULT_HOME) session.loadUri(url)
        if (select || _uiState.value.selectedTabId == null) selectTab(id) else publish()
    }

    private fun attachDelegates(tab: BrowserTabRuntime) {
        tab.session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                tab.url = url ?: DEFAULT_HOME
                publish()
                persistTabs()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack; publish() }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward; publish() }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val id = UUID.randomUUID().toString()
                createTab(id, uri, "New tab", null, null, null, true)
                return GeckoResult.fromValue(sessions.getValue(id).session)
            }
        })
        tab.session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.url = url
                _uiState.value = _uiState.value.copy(loading = true, loadProgress = 0)
                publish()
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                val current = _uiState.value
                val milestone = progress >= 100 || progress == 0 || progress < current.loadProgress ||
                    progress - current.loadProgress >= 5
                if (milestone || current.loading != (progress < 100)) {
                    _uiState.value = current.copy(loading = progress < 100, loadProgress = progress)
                }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _uiState.value = _uiState.value.copy(loading = false, loadProgress = 100)
                persistTabs()
            }
            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                tab.sessionState = sessionState.toString()
                persistTabs()
            }
        })
        tab.session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title?.takeIf(String::isNotBlank) ?: tab.url.hostOrNewTab()
                publish()
                persistTabs()
            }
            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                val link = element.linkUri?.takeIf(String::isNotBlank)
                val media = element.srcUri?.takeIf(String::isNotBlank)
                val kind = when {
                    link != null && media != null -> BrowserContextKind.LINKED_MEDIA
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> BrowserContextKind.IMAGE
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> BrowserContextKind.VIDEO
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> BrowserContextKind.AUDIO
                    link != null -> BrowserContextKind.LINK
                    else -> return
                }
                _uiState.value = _uiState.value.copy(
                    contextMenu = BrowserContextMenu(kind, link, media, element.title, element.altText, element.textContent),
                )
            }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) { beginDownload(response) }
        })
        tab.session.setPermissionDelegate(object : PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession, permission: PermissionDelegate.ContentPermission): GeckoResult<Int> {
                val origin = permission.uri
                val name = permissionName(permission.permission)
                val result = GeckoResult<Int>()
                pendingPermissionResult = result
                _uiState.value = _uiState.value.copy(permissionPrompt = BrowserPermissionPrompt(origin, name))
                return result
            }
        })
        tab.session.setScrollDelegate(object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                tab.scrollY = scrollY.coerceAtLeast(0)
                tab.scrollListener?.invoke(tab.scrollY)
            }
        })
    }

    fun answerPermission(allow: Boolean) {
        pendingPermissionResult?.complete(if (allow) PermissionDelegate.ContentPermission.VALUE_ALLOW else PermissionDelegate.ContentPermission.VALUE_DENY)
        pendingPermissionResult = null
        _uiState.value = _uiState.value.copy(permissionPrompt = null)
        if (!allow) setError("The site permission was denied")
    }

    private fun beginDownload(response: WebResponse) {
        val body = response.body
        if (body == null) { setError("The download did not provide a readable response"); return }
        val name = response.uri.substringAfterLast('/').substringBefore('?').ifBlank { "download-${System.currentTimeMillis()}" }
        val existing = _uiState.value.downloads.firstOrNull { it.name == name && it.available }
        if (existing != null) {
            pendingDuplicateBody = PendingDuplicateDownload(name, response.uri, response.headers["Content-Type"] ?: "application/octet-stream", body, response.headers["Content-Length"]?.toLongOrNull() ?: -1L)
            _uiState.value = _uiState.value.copy(duplicateDownload = DuplicateDownloadPrompt(name, response.uri, existing.uri))
            return
        }
        writeDownload(name, response.uri, response.headers["Content-Type"] ?: "application/octet-stream", body, response.headers["Content-Length"]?.toLongOrNull() ?: -1L)
    }

    fun confirmDuplicateDownload(replace: Boolean) {
        val prompt = _uiState.value.duplicateDownload ?: return
        val pending = pendingDuplicateBody
        pendingDuplicateBody = null
        _uiState.value = _uiState.value.copy(duplicateDownload = null)
        if (pending == null) return
        if (replace) {
            if (!deleteStoredFile(Uri.parse(prompt.existingUri))) {
                pending.body.close()
                setError("The existing downloaded file could not be replaced")
                return
            }
            writeDownload(pending.name, pending.sourceUrl, pending.mimeType, pending.body, pending.totalBytes)
        } else {
            val suffix = prompt.name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".${it}" }
            val stem = prompt.name.removeSuffix(suffix)
            writeDownload("$stem (${System.currentTimeMillis()})$suffix", pending.sourceUrl, pending.mimeType, pending.body, pending.totalBytes)
        }
    }

    private fun writeDownload(name: String, sourceUrl: String, mimeType: String, body: java.io.InputStream, totalBytes: Long) {
        val resolver = context.contentResolver
        val configuredFolder = _uiState.value.settings.downloadFolder
        val destination = runCatching {
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
        if (destination == null) { body.close(); setError("Choose an available download folder in Browser settings"); return }
        _uiState.value = _uiState.value.copy(downloadProgress = BrowserDownloadProgress(name, 0L, totalBytes))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var copied = 0L
                resolver.openOutputStream(destination)?.use { output ->
                    body.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied == read.toLong() || copied % (256 * 1024) < read) {
                                withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(downloadProgress = BrowserDownloadProgress(name, copied, totalBytes)) }
                            }
                        }
                    }
                } ?: error("Destination is not writable")
                if (configuredFolder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                val download = BrowserDownload(UUID.randomUUID().toString(), name, destination.toString(), sourceUrl, mimeType, querySize(destination), System.currentTimeMillis())
                withContext(Dispatchers.Main) {
                    val updated = _uiState.value.downloads + download
                    _uiState.value = _uiState.value.copy(downloads = updated, downloadProgress = null)
                    persistDownloads(updated)
                }
            } catch (error: Throwable) {
                runCatching { resolver.delete(destination, null, null) }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(downloadProgress = null)
                    setError("Download failed: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    fun reconcileDownloads() {
        val stored = readDownloads().map { it.copy(available = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(it.uri), "r")?.use { true } ?: false }.getOrDefault(false)) }
        _uiState.value = _uiState.value.copy(downloads = stored)
    }

    /** Returns an ACTION_VIEW intent only for a file that is currently present. */
    fun openDownload(downloadId: String): Intent? {
        val download = _uiState.value.downloads.firstOrNull { it.id == downloadId } ?: return null
        if (!download.available) return null
        val uri = Uri.parse(download.uri)
        val present = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true }.getOrDefault(false)
        if (!present) {
            reconcileDownloads()
            return null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, download.mimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Deletes the local file for an available download, then removes its record. */
    fun deleteDownload(downloadId: String): Boolean {
        val download = _uiState.value.downloads.firstOrNull { it.id == downloadId } ?: return false
        if (download.available) {
            val deleted = deleteStoredFile(Uri.parse(download.uri))
            if (!deleted) {
                reconcileDownloads()
                setError("The downloaded file could not be deleted")
                return false
            }
        }
        removeDownloadRecord(downloadId)
        return true
    }

    /** Removes an unavailable record without attempting to delete a missing file. */
    fun removeUnavailableDownload(downloadId: String): Boolean {
        val download = _uiState.value.downloads.firstOrNull { it.id == downloadId } ?: return false
        if (download.available) return false
        removeDownloadRecord(downloadId)
        return true
    }

    fun reportError(message: String) { setError(message) }
    fun dismissContextMenu() { _uiState.value = _uiState.value.copy(contextMenu = null) }
    fun openContextLinkInNewTab() {
        val url = _uiState.value.contextMenu?.linkUri ?: _uiState.value.contextMenu?.mediaUri ?: return
        dismissContextMenu()
        createTab(url)
    }
    fun openContextMediaInNewTab() {
        val url = _uiState.value.contextMenu?.mediaUri ?: return
        dismissContextMenu()
        createTab(url)
    }
    fun loadContextUrlInCurrentTab() {
        val url = _uiState.value.contextMenu?.linkUri ?: _uiState.value.contextMenu?.mediaUri ?: return
        dismissContextMenu()
        sessions[_uiState.value.selectedTabId]?.session?.loadUri(url)
    }
    fun loadContextMediaInCurrentTab() {
        val url = _uiState.value.contextMenu?.mediaUri ?: return
        dismissContextMenu()
        sessions[_uiState.value.selectedTabId]?.session?.loadUri(url)
    }
    /** Starts a resource request; Gecko routes downloadable responses to beginDownload. */
    fun downloadContextResource() {
        val url = _uiState.value.contextMenu?.mediaUri ?: _uiState.value.contextMenu?.linkUri ?: return
        dismissContextMenu()
        sessions[_uiState.value.selectedTabId]?.session?.loadUri(url)
    }

    private fun removeDownloadRecord(downloadId: String) {
        val updated = _uiState.value.downloads.filterNot { it.id == downloadId }
        _uiState.value = _uiState.value.copy(downloads = updated)
        persistDownloads(updated)
    }

    private fun deleteStoredFile(uri: Uri): Boolean = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.getOrDefault(false)

    private fun querySize(uri: Uri): Long = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }.getOrDefault(0L)

    fun selectTab(tabId: String) {
        if (!sessions.containsKey(tabId)) return
        if (sessions[tabId]?.isTemporary != true) {
            stateStore.saveSelectedTab(tabId)
        }
        publish(selectedId = tabId)
    }

    /** Returns true when closing this tab created a replacement because it was the last tab. */
    fun closeTab(tabId: String): Boolean {
        if (tabId !in sessions) return false
        sessions.remove(tabId)?.session?.close()
        val createdReplacement = sessions.isEmpty()
        if (createdReplacement) createTab()
        else if (_uiState.value.selectedTabId == tabId) selectTab(sessions.keys.last())
        persistTabs()
        publish()
        return createdReplacement
    }

    fun navigate(address: String) {
        val tab = sessions[_uiState.value.selectedTabId] ?: return
        val entered = address.trim()
        if (entered.isEmpty()) return
        // Deliberate address-bar navigation turns a disposable preview into a normal tab.
        if (tab.isTemporary) {
            tab.isTemporary = false
            stateStore.saveSelectedTab(tab.id)
        }
        val target = resolveAddress(entered)
        val history = (listOf(entered) + _uiState.value.history.filterNot { it == entered }).take(30)
        stateStore.saveHistory(history)
        tab.url = target
        tab.scrollY = 0
        tab.scrollListener?.invoke(0)
        tab.session.loadUri(target)
        _uiState.value = _uiState.value.copy(address = target, error = null)
        persistTabs()
    }

    /** Open a project-local file in a disposable tab, replacing the prior disposable preview. */
    fun openTemporaryTab(url: String) {
        val entered = url.trim()
        if (entered.isEmpty()) return
        val target = resolveAddress(entered)
        val temporary = sessions.values.lastOrNull { it.isTemporary }
        if (temporary == null) {
            createTab(UUID.randomUUID().toString(), target, "Preview", null, null, null, true, temporary = true)
            return
        }
        temporary.url = target
        temporary.title = "Preview"
        temporary.canGoBack = false
        temporary.canGoForward = false
        temporary.scrollY = 0
        temporary.scrollListener?.invoke(0)
        selectTab(temporary.id)
        temporary.session.loadUri(target)
        _uiState.value = _uiState.value.copy(address = target, error = null)
        publish(selectedId = temporary.id)
    }

    fun goHome() { navigate(_uiState.value.settings.homePage) }
    fun goBack() { sessions[_uiState.value.selectedTabId]?.session?.goBack() }
    fun goForward() { sessions[_uiState.value.selectedTabId]?.session?.goForward() }
    fun reload() { sessions[_uiState.value.selectedTabId]?.session?.reload() }
    fun stop() { sessions[_uiState.value.selectedTabId]?.session?.stop() }

    fun setViewportWidth(width: Int) {
        sessions[_uiState.value.selectedTabId]?.let { tab ->
            tab.viewportWidth = width.takeIf { it in 320..2400 }
            persistTabs()
            publish()
            tab.session.reload()
        }
    }

    fun resetViewport() = setViewportWidth(0)

    fun toggleDeveloperTools() {
        if (!_uiState.value.settings.developerToolsEnabled) {
            _uiState.value = _uiState.value.copy(developerToolsOpen = false)
            return
        }
        val open = !_uiState.value.developerToolsOpen
        _uiState.value = _uiState.value.copy(developerToolsOpen = open)
        devtoolsPorts.toList().forEach { port ->
            runCatching { port.postMessage(JSONObject().put("type", "developer-tools").put("open", open)) }
        }
    }

    fun setDeveloperToolsEnabled(enabled: Boolean) {
        updateSettings { it.copy(developerToolsEnabled = enabled) }
        if (!enabled) _uiState.value = _uiState.value.copy(developerToolsOpen = false)
        if (!enabled) devtoolsPorts.toList().forEach { port -> runCatching { port.postMessage(JSONObject().put("type", "developer-tools").put("open", false)) } }
    }

    fun setSearchEngine(engine: String) { updateSettings { it.copy(searchEngine = engine) } }
    fun setCustomSearchUrl(url: String) { updateSettings { it.copy(customSearchUrl = url) } }
    fun setHomePage(url: String) { updateSettings { it.copy(homePage = url.ifBlank { DEFAULT_HOME }) } }
    fun setTheme(theme: String) { updateSettings { it.copy(theme = theme.takeIf { it in setOf("dark", "light", "application", "device") } ?: "application") } }
    fun setDesktopSiteDefault(enabled: Boolean) {
        updateSettings { it.copy(desktopSiteDefault = enabled) }
        sessions[_uiState.value.selectedTabId]?.let { tab ->
            tab.session.settings.setUserAgentMode(
                if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
            )
            tab.session.reload()
        }
    }
    fun clearHistoryEntry(value: String) {
        val next = _uiState.value.history.filterNot { it == value }
        stateStore.saveHistory(next)
        _uiState.value = _uiState.value.copy(history = next)
    }
    /**
     * Returns the value that should be offered first when the address field is focused.
     * Search results are recognized from the active search template, so no extra
     * per-navigation origin state is needed.
     */
    fun addressEditSuggestion(): String? {
        val current = sessions[_uiState.value.selectedTabId]?.url?.trim()
            ?.takeUnless { it.isNullOrBlank() || it == DEFAULT_HOME }
            ?: _uiState.value.address.trim().takeUnless { it.isBlank() || it == DEFAULT_HOME }
            ?: return null
        val template = SEARCH_ENGINES[_uiState.value.settings.searchEngine]
            ?: _uiState.value.settings.customSearchUrl
        val marker = "%s"
        val markerIndex = template.indexOf(marker)
        if (markerIndex >= 0) {
            val prefix = template.substring(0, markerIndex)
            val suffix = template.substring(markerIndex + marker.length)
            if (current.startsWith(prefix) && (suffix.isEmpty() || current.endsWith(suffix))) {
                val encoded = current.substring(prefix.length, current.length - suffix.length.coerceAtMost(current.length - prefix.length))
                return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }
                    .getOrElse { Uri.decode(encoded) }
                    .replace('+', ' ')
                    .takeIf { it.isNotBlank() }
            }
        }
        return current
    }
    fun setDownloadFolder(uri: String?) { updateSettings { it.copy(downloadFolder = uri) } }
    fun clearBrowserData() {
        sessions.values.forEach { it.session.purgeHistory() }
        stateStore.clearTabs(); stateStore.clearHistoryAndDownloads()
        _uiState.value = _uiState.value.copy(downloads = emptyList(), history = emptyList())
    }
    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
    fun session(tabId: String): GeckoSession? = sessions[tabId]?.session
    fun bind(view: GeckoView, tabId: String, onScroll: (Int) -> Unit = {}) {
        sessions[tabId]?.let { tab ->
            // The Android view itself does not expose Gecko's document scroll
            // position. Preserve that position per tab and hand it to the surface
            // immediately, before SwipeRefreshLayout decides whether to intercept.
            tab.scrollListener = onScroll
            val attachedSession = view.session
            if (attachedSession !== tab.session) {
                if (attachedSession != null) view.releaseSession()
                view.setSession(tab.session)
            }
            onScroll(tab.scrollY)
        }
    }

    /** Captures the currently rendered tab when the tab switcher is opened. */
    fun captureTabPreview(tabId: String, view: View?) {
        val tab = sessions[tabId] ?: return
        if (view == null || view.width <= 0 || view.height <= 0) return
        runCatching {
            val targetWidth = view.width.coerceAtMost(720)
            val scale = targetWidth.toFloat() / view.width.toFloat()
            val targetHeight = (view.height * scale).toInt().coerceIn(1, 1280)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                scale(scale, scale)
                view.draw(this)
            }
            val directory = java.io.File(context.cacheDir, "browser_tab_previews").apply { mkdirs() }
            val file = java.io.File(directory, "${tab.id}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 85, it) }
            bitmap.recycle()
            tab.previewPath = file.absolutePath
            persistTabs()
            publish()
        }
    }

    private fun resolveAddress(value: String): String {
        val hasScheme = value.contains("://") || value.startsWith("about:") || value.startsWith("file:")
        val looksLikeHost = value == "localhost" || value.startsWith("localhost:") || value.matches(Regex("^[0-9.]+(:[0-9]+)?(/.*)?$")) || value.contains('.')
        return if (hasScheme) value else if (looksLikeHost) "https://$value" else {
            val template = SEARCH_ENGINES[_uiState.value.settings.searchEngine] ?: _uiState.value.settings.customSearchUrl
            (template.ifBlank { SEARCH_ENGINES.getValue(DEFAULT_SEARCH) }).replace("%s", Uri.encode(value))
        }
    }

    private fun publish(selectedId: String? = _uiState.value.selectedTabId) {
        val selected = sessions[selectedId]
        _uiState.value = _uiState.value.copy(
            tabs = sessions.values.map { BrowserTabUi(it.id, it.url, it.title, it.url.hostOrNewTab(), it.canGoBack, it.canGoForward, it.viewportWidth, it.previewPath, it.isTemporary) },
            selectedTabId = selectedId,
            address = selected?.url?.takeUnless { it == DEFAULT_HOME }.orEmpty(),
            viewportWidth = selected?.viewportWidth,
            history = _uiState.value.history,
        )
    }

    private fun persistTabs() {
        val array = JSONArray()
        sessions.values.filterNot { it.isTemporary }.forEach { tab -> array.put(JSONObject().apply {
            put("id", tab.id); put("url", tab.url); put("title", tab.title); put("viewportWidth", tab.viewportWidth ?: 0); tab.previewPath?.let { put("previewPath", it) }; tab.sessionState?.let { put("state", it) }
        }) }
        stateStore.saveTabs(array)
    }

    private fun readSettings() = stateStore.readSettings()

    private fun readHistory(): List<String> = stateStore.readHistory()

    private fun updateSettings(transform: (BrowserSettings) -> BrowserSettings) {
        val next = transform(_uiState.value.settings)
        stateStore.saveSettings(next)
        _uiState.value = _uiState.value.copy(settings = next)
    }

    private fun readDownloads(): List<BrowserDownload> = runCatching {
        val array = stateStore.readDownloads()
        List(array.length()) { index -> array.getJSONObject(index).let { BrowserDownload(it.getString("id"), it.getString("name"), it.getString("uri"), it.getString("sourceUrl"), it.optString("mimeType", "application/octet-stream"), it.optLong("size"), it.optLong("timestamp")) } }
    }.getOrDefault(emptyList())

    private fun persistDownloads(downloads: List<BrowserDownload>) {
        val array = JSONArray(); downloads.forEach { item -> array.put(JSONObject().apply { put("id", item.id); put("name", item.name); put("uri", item.uri); put("sourceUrl", item.sourceUrl); put("mimeType", item.mimeType); put("size", item.size); put("timestamp", item.timestamp) }) }
        stateStore.saveDownloads(array)
    }

    private fun setError(message: String) { _uiState.value = _uiState.value.copy(error = message) }

    private fun permissionName(permission: Int): String = when (permission) {
        PermissionDelegate.PERMISSION_GEOLOCATION -> "location"
        PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "notifications"
        PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "persistent storage"
        PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "autoplay"
        else -> "additional site access"
    }

    override fun onCleared() {
        persistTabs(); persistDownloads(_uiState.value.downloads)
        sessions.values.forEach { it.session.close() }
        super.onCleared()
    }
}

private fun String.hostOrNewTab(): String = runCatching { Uri.parse(this).host?.takeIf(String::isNotBlank) ?: "New tab" }.getOrDefault("New tab")
