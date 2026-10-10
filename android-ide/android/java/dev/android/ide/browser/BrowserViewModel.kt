package dev.android.ide.browser

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.android.ide.app.ApplicationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

data class BrowserTabUi(
    val id: String,
    val url: String,
    val title: String,
    val host: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val viewportWidth: Int?,
    val thumbnailPath: String?,
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
    val clearingBrowserData: Boolean = false,
    val browserDataClearMessage: String? = null,
)

data class BrowserPermissionPrompt(val origin: String, val permission: String)
data class DuplicateDownloadPrompt(val name: String, val sourceUrl: String, val existingUri: String)
data class BrowserDownloadProgress(val name: String, val bytes: Long, val totalBytes: Long)

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
    var session: BrowserEngineSession?,
    var url: String,
    var title: String,
    var reuseKey: String? = null,
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var loading: Boolean = false,
    var loadProgress: Int = 0,
    var sessionState: String? = null,
    var viewportWidth: Int? = null,
    var thumbnailPath: String? = null,
    var scrollY: Int = 0,
    var scrollListener: ((Int) -> Unit)? = null,
    var visualRevision: Int = 0,
    var thumbnailRevision: Int = -1,
)

/** Application-owned browser domain. It survives navigation away from the Browser surface. */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val applicationServices = ApplicationServices.from(application)
    private val browserServices = applicationServices.browserApplicationService
    private val storageService = browserServices.storage
    private val historyService = browserServices.history
    private val settingsService = browserServices.settings
    private val viewportService = browserServices.viewport
    private val navigationService = browserServices.navigation
    private val tabService = browserServices.tabs
    private val permissionService = browserServices.permissions
    private val sessionService = browserServices.sessions
    private val developerToolsService = browserServices.developerTools
    private val downloadService = browserServices.downloads
    private val sessions = linkedMapOf<String, BrowserTabRuntime>()
    private val _uiState = MutableStateFlow(
        BrowserUiState(
            downloads = downloadService.downloads.value,
            settings = settingsService.settings.value,
            history = historyService.entries.value,
            developerToolsOpen = storageService.developerToolsOpen() && settingsService.settings.value.developerToolsEnabled,
            downloadProgress = downloadService.progress.value,
            duplicateDownload = downloadService.duplicatePrompt.value,
        ),
    )
    private val persistence = browserServices.persistence
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        restoreTabs()
        reconcileDownloads()
        developerToolsService.setEnabled(_uiState.value.settings.developerToolsEnabled)
        if (_uiState.value.developerToolsOpen) developerToolsService.open()
        viewModelScope.launch {
            permissionService.pending.collect { request ->
                _uiState.value = _uiState.value.copy(
                    permissionPrompt = request?.let { BrowserPermissionPrompt(it.origin, it.permission) },
                )
            }
        }
        viewModelScope.launch { downloadService.downloads.collect { _uiState.value = _uiState.value.copy(downloads = it) } }
        viewModelScope.launch { downloadService.progress.collect { _uiState.value = _uiState.value.copy(downloadProgress = it) } }
        viewModelScope.launch { downloadService.duplicatePrompt.collect { _uiState.value = _uiState.value.copy(duplicateDownload = it) } }
        viewModelScope.launch { downloadService.error.collect { error -> if (error != null) setError(error) } }
    }

    private fun restoreTabs() {
        val stored = runCatching { storageService.readTabs() }.getOrDefault(emptyList())
        if (stored.isEmpty()) {
            createTab()
            return
        }
        tabService.restore(stored, storageService.selectedTabId())
        tabService.tabs.value.forEach { descriptor ->
            val tab = BrowserTabRuntime(
                id = descriptor.id,
                session = null,
                url = descriptor.url,
                title = descriptor.title,
                reuseKey = descriptor.reuseKey,
                sessionState = descriptor.serializedState?.value,
                viewportWidth = descriptor.viewportWidth,
                thumbnailPath = descriptor.thumbnail?.path,
                scrollY = descriptor.scrollY,
            )
            if (tab.thumbnailPath?.let(::File)?.isFile == true) tab.thumbnailRevision = tab.visualRevision
            sessions[descriptor.id] = tab
        }
        val selected = tabService.selectedTabId.value ?: sessions.keys.first()
        selectTab(selected)
    }

    fun createTab(url: String = DEFAULT_HOME, reuseKey: String? = null) {
        val id = tabService.create(url, reuseKey)
        val descriptor = tabService.tabs.value.firstOrNull { it.id == id } ?: return
        val tab = BrowserTabRuntime(id, null, descriptor.url, descriptor.title, reuseKey = descriptor.reuseKey, viewportWidth = descriptor.viewportWidth)
        sessions[id] = tab
        sessionFor(tab)
        selectTab(id)
        persistTabs()
    }

    fun duplicateTab(tabId: String) {
        val id = tabService.duplicate(tabId) ?: return
        val descriptor = tabService.tabs.value.firstOrNull { it.id == id } ?: return
        val tab = BrowserTabRuntime(id, null, descriptor.url, descriptor.title, reuseKey = descriptor.reuseKey, viewportWidth = descriptor.viewportWidth)
        sessions[id] = tab
        sessionFor(tab)
        selectTab(id)
        persistTabs()
    }

    private fun sessionFor(tab: BrowserTabRuntime): BrowserEngineSession {
        tab.session?.let { return it }
        val restoredState = tab.sessionState?.let(::BrowserSerializedState)
        val session = sessionService.ensureSession(
            tabId = tab.id,
            settings = BrowserSessionSettings(_uiState.value.settings.desktopSiteDefault),
            state = restoredState,
            sink = engineEventSink(tab),
        )
        tab.session = session
        if (restoredState == null || tab.url == DEFAULT_HOME) session.load(BrowserNavigation(tab.url))
        return session
    }

    private fun engineEventSink(tab: BrowserTabRuntime) = object : BrowserEngineEventSink {
        override fun onNavigationChanged(event: BrowserNavigationChanged) {
            val changedUrl = event.url.ifBlank { DEFAULT_HOME }
            if (tab.url != changedUrl) tab.visualRevision++
            tab.url = changedUrl
            tabService.update(tab.id) { it.copy(url = changedUrl) }
            publish()
            persistTabs()
        }

        override fun onNavigationCapabilitiesChanged(event: BrowserNavigationCapabilitiesChanged) {
            tab.canGoBack = event.canGoBack
            tab.canGoForward = event.canGoForward
            publish()
        }

        override fun onSerializedStateChanged(tabId: String, state: BrowserSerializedState) {
            tab.sessionState = state.value
            tabService.update(tabId) { it.copy(serializedState = state) }
            persistTabs()
        }

        override fun onTitleChanged(event: BrowserTitleChanged) {
            val changedTitle = event.title.takeIf(String::isNotBlank) ?: tab.url.hostOrNewTab()
            if (tab.title != changedTitle) tab.visualRevision++
            tab.title = changedTitle
            tabService.update(tab.id) { it.copy(title = changedTitle) }
            publish()
            persistTabs()
        }

        override fun onProgressChanged(event: BrowserProgressChanged) {
            val progress = event.progress.coerceIn(0, 100)
            val previousProgress = tab.loadProgress
            val wasLoading = tab.loading
            tab.loadProgress = progress
            tab.loading = event.progress < 100
            val current = _uiState.value
            val milestone = progress == 100 || progress == 0 || progress < previousProgress ||
                progress - previousProgress >= 5
            if (current.selectedTabId == event.tabId && (milestone || wasLoading != tab.loading)) {
                _uiState.value = current.copy(loading = tab.loading, loadProgress = progress)
            }
        }

        override fun onPageLoadCompleted(event: BrowserPageLoadCompleted) {
            tab.loading = false
            tab.loadProgress = 100
            val historyAddress = event.historyAddress
            val completedHistory = if (event.successful && !historyAddress.isNullOrBlank() && !historyAddress.startsWith("about:")) {
                historyService.addOrUpdate(historyAddress)
            } else null
            val current = _uiState.value
            _uiState.value = if (current.selectedTabId == event.tabId) {
                current.copy(loading = false, loadProgress = 100, history = completedHistory ?: current.history)
            } else if (completedHistory != null) {
                current.copy(history = completedHistory)
            } else current
            persistTabs()
        }

        override fun onDownloadRequested(event: BrowserDownloadRequest) = beginDownload(event)

        override fun onPermissionRequested(event: BrowserPermissionRequest) {
            permissionService.request(event)
        }

        override fun onContextMenuRequested(event: BrowserContextMenuRequest) {
            _uiState.value = _uiState.value.copy(
                contextMenu = BrowserContextMenu(
                    event.kind, event.linkUri, event.mediaUri, event.title, event.altText, event.textContent,
                ),
            )
        }

        override fun onConsoleEvent(event: BrowserConsoleEvent) = Unit
        override fun onNetworkEvent(event: BrowserNetworkEvent) = Unit

        override fun onScrollChanged(event: BrowserScrollChanged) {
            val scrollY = event.scrollY.coerceAtLeast(0)
            if (tab.scrollY != scrollY) {
                tab.scrollY = scrollY
                tab.visualRevision++
                tabService.update(tab.id) { it.copy(scrollY = scrollY) }
                persistTabs()
            }
            tab.scrollListener?.invoke(tab.scrollY)
        }

        override fun onNewTabRequested(url: String): BrowserEngineSession {
            createTab(url)
            val id = tabService.selectedTabId.value ?: error("The new browser tab was not selected")
            return requireNotNull(sessions[id]?.session)
        }
    }

    fun answerPermission(allow: Boolean) {
        permissionService.answer(allow)
        _uiState.value = _uiState.value.copy(permissionPrompt = null)
        if (!allow) setError("The site permission was denied")
    }

    private fun beginDownload(request: BrowserDownloadRequest) {
        downloadService.accept(request)
    }

    fun confirmDuplicateDownload(replace: Boolean) {
        downloadService.resolveDuplicate(replace)
    }

    fun reconcileDownloads() = downloadService.reconcile()

    /** Returns an ACTION_VIEW intent only for a file that is currently present. */
    fun openDownload(downloadId: String): Intent? = downloadService.open(downloadId)
    fun shareDownload(downloadId: String): Intent? = downloadService.share(downloadId)

    /** Deletes the local file for an available download, then removes its record. */
    fun deleteDownload(downloadId: String): Boolean = downloadService.delete(downloadId)

    /** Removes an unavailable record without attempting to delete a missing file. */
    fun removeUnavailableDownload(downloadId: String): Boolean = downloadService.removeUnavailable(downloadId)

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
        navigationService.navigate(BrowserNavigation(url))
    }
    fun loadContextMediaInCurrentTab() {
        val url = _uiState.value.contextMenu?.mediaUri ?: return
        dismissContextMenu()
        navigationService.navigate(BrowserNavigation(url))
    }
    /** Starts a resource request; Gecko routes downloadable responses to beginDownload. */
    fun downloadContextResource() {
        val url = _uiState.value.contextMenu?.mediaUri ?: _uiState.value.contextMenu?.linkUri ?: return
        dismissContextMenu()
        navigationService.navigate(BrowserNavigation(url))
    }

    fun selectTab(tabId: String) {
        if (!sessions.containsKey(tabId)) return
        sessionFor(sessions.getValue(tabId))
        tabService.select(tabId)
        storageService.saveSelectedTab(tabId)
        publish(selectedId = tabId)
    }

    /** Returns true when closing this tab created a replacement because it was the last tab. */
    fun closeTab(tabId: String): Boolean {
        if (tabId !in sessions) return false
        persistence.flush()
        val closedTab = sessions.remove(tabId)
        sessionService.closeSession(tabId)
        val replacement = tabService.close(tabId)
        closedTab?.session = null
        closedTab?.thumbnailPath?.let(::File)?.delete()
        val createdReplacement = replacement != null
        if (replacement != null) {
            val tab = BrowserTabRuntime(
                id = replacement.id,
                session = null,
                url = replacement.url,
                title = replacement.title,
                reuseKey = replacement.reuseKey,
                viewportWidth = replacement.viewportWidth,
            )
            sessions[replacement.id] = tab
            selectTab(replacement.id)
        }
        else if (_uiState.value.selectedTabId == tabId) selectTab(sessions.keys.last())
        persistTabs()
        persistence.flush()
        publish()
        return createdReplacement
    }

    fun navigate(address: String) {
        val tab = sessions[_uiState.value.selectedTabId] ?: return
        val entered = address.trim()
        if (entered.isEmpty()) return
        val target = navigationService.resolve(entered)
        tab.url = target
        tabService.update(tab.id) { it.copy(url = target, scrollY = 0) }
        tab.scrollY = 0
        tab.scrollListener?.invoke(0)
        sessionFor(tab)
        navigationService.navigate(BrowserNavigation(target, historyAddress = entered))
        _uiState.value = _uiState.value.copy(address = target, error = null)
        persistTabs()
    }

    /** Open an ordinary browser tab for a URL supplied by another application workflow. */
    fun openUrlInNewTab(url: String) {
        val entered = url.trim()
        if (entered.isEmpty()) return
        createTab(navigationService.resolve(entered))
    }

    /** Navigate to a logical browser destination, reusing its tab key when one is supplied. */
    fun navigateRequest(url: String, openInNewTab: Boolean = false, reuseKey: String? = null) {
        val value = url.trim()
        if (value.isEmpty()) return
        val key = reuseKey?.trim()?.takeIf(String::isNotEmpty)
        if (key != null) {
            val existingTabId = tabService.findByReuseKey(key)
            if (existingTabId != null && existingTabId in sessions) {
                selectTab(existingTabId)
                navigate(value)
            } else {
                createTab(navigationService.resolve(value), key)
            }
        } else if (openInNewTab) {
            openUrlInNewTab(value)
        } else {
            navigate(value)
        }
    }

    fun goHome() { navigate(_uiState.value.settings.homePage) }
    fun goBack() = navigationService.goBack()
    fun goForward() = navigationService.goForward()
    fun reload() = navigationService.reload()
    fun stop() = navigationService.stop()

    fun setViewportWidth(width: Int) {
        applyViewportWidth(width)
    }

    fun setViewportPreset(widthPx: Int?) {
        applyViewportWidth(widthPx)
    }

    fun setDefaultViewportWidth(widthPx: Int?) {
        val validated = if (widthPx == null) null else viewportService.validate(widthPx) ?: return
        updateSettings { it.copy(defaultViewportWidthPx = validated) }
    }

    fun viewportRange(): IntRange = viewportService.minimumWidthPx..viewportService.maximumWidthPx

    fun viewportPresets(): List<Pair<String, Int?>> = buildList {
        add("Device" to viewportService.device())
        viewportService.mobile()?.let { width -> viewportService.validate(width)?.let { add("Mobile" to it) } }
        viewportService.tablet()?.let { width -> viewportService.validate(width)?.let { add("Tablet" to it) } }
        viewportService.desktop()?.let { width -> viewportService.validate(width)?.let { add("Desktop" to it) } }
    }

    private fun applyViewportWidth(requestedWidth: Int?) {
        val width = if (requestedWidth == null) viewportService.device() else viewportService.validate(requestedWidth)
        if (requestedWidth != null && width == null) return
        sessions[_uiState.value.selectedTabId]?.let { tab ->
            tab.viewportWidth = width
            tabService.update(tab.id) { it.copy(viewportWidth = tab.viewportWidth) }
            sessionFor(tab).setViewport(width)
            persistTabs()
            publish()
        }
    }

    fun resetViewport() = setViewportPreset(settingsService.settings.value.defaultViewportWidthPx)

    fun toggleDeveloperTools() {
        if (!_uiState.value.settings.developerToolsEnabled) {
            storageService.saveDeveloperToolsOpen(false)
            developerToolsService.close()
            _uiState.value = _uiState.value.copy(developerToolsOpen = false)
            return
        }
        val open = !_uiState.value.developerToolsOpen
        storageService.saveDeveloperToolsOpen(open)
        _uiState.value = _uiState.value.copy(developerToolsOpen = open)
        if (open) developerToolsService.open() else developerToolsService.close()
    }

    fun setDeveloperToolsEnabled(enabled: Boolean) {
        updateSettings { it.copy(developerToolsEnabled = enabled) }
        developerToolsService.setEnabled(enabled)
        if (!enabled) {
            storageService.saveDeveloperToolsOpen(false)
            _uiState.value = _uiState.value.copy(developerToolsOpen = false)
        }
    }

    fun setSearchEngine(engine: String) { updateSettings { it.copy(searchEngine = engine) } }
    fun setCustomSearchUrl(url: String) { updateSettings { it.copy(customSearchUrl = url) } }
    fun setHomePage(url: String) { updateSettings { it.copy(homePage = url.ifBlank { DEFAULT_HOME }) } }
    fun setTheme(theme: String) { updateSettings { it.copy(theme = theme.takeIf { it in setOf("dark", "light", "application", "device") } ?: "application") } }
    fun setDesktopSiteDefault(enabled: Boolean) {
        updateSettings { it.copy(desktopSiteDefault = enabled) }
        navigationService.setDesktopSite(enabled)
        navigationService.reload()
    }
    fun clearHistoryEntry(value: String) {
        historyService.remove(value)
        val next = historyService.entries.value
        _uiState.value = _uiState.value.copy(history = next)
    }
    fun historySuggestions(query: String, excludedAddress: String? = null): List<String> =
        historyService.suggestions(query, excludedAddress)
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
        if (_uiState.value.clearingBrowserData) return
        persistence.flush()
        _uiState.value = _uiState.value.copy(clearingBrowserData = true, browserDataClearMessage = null)
        val activeTabId = tabService.selectedTabId.value
        sessionService.closeAll()
        sessions.values.forEach { tab ->
            tab.session = null
            tab.sessionState = null
            tab.canGoBack = false
            tab.canGoForward = false
            tabService.update(tab.id) { it.copy(serializedState = null) }
        }
        val complete: (Result<Unit>) -> Unit = { result ->
            viewModelScope.launch {
                val message: String
                if (result.isSuccess) {
                    storageService.clearTabs()
                    storageService.clearHistoryAndDownloads()
                    historyService.clear()
                    downloadService.clear()
                    storageService.saveDeveloperToolsOpen(false)
                    developerToolsService.close()
                    _uiState.value = _uiState.value.copy(
                        downloads = emptyList(),
                        history = emptyList(),
                        developerToolsOpen = false,
                        error = null,
                    )
                    message = "Browser data cleared"
                } else {
                    message = "Could not clear browser data: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    setError(message)
                }
                activeTabId?.takeIf(sessions::containsKey)?.let(::selectTab)
                _uiState.value = _uiState.value.copy(clearingBrowserData = false, browserDataClearMessage = message)
            }
        }
        runCatching { storageService.clearSiteData(complete) }
            .onFailure { complete(Result.failure(it)) }
    }
    fun clearError() { downloadService.clearError(); _uiState.value = _uiState.value.copy(error = null) }
    fun engineSession(tabId: String): BrowserEngineSession? = sessions[tabId]?.let(::sessionFor)

    fun setScrollListener(tabId: String, listener: ((Int) -> Unit)?) {
        sessions[tabId]?.let { tab ->
            tab.scrollListener = listener
            listener?.invoke(tab.scrollY)
        }
    }

    /** Captures the currently rendered tab when the tab switcher is opened. */
    fun captureTabThumbnail(tabId: String, view: View?) {
        val tab = sessions[tabId] ?: return
        if (view == null || view.width <= 0 || view.height <= 0) return
        if (tab.thumbnailRevision == tab.visualRevision && tab.thumbnailPath?.let(::File)?.isFile == true) return
        runCatching {
            val targetWidth = view.width.coerceAtMost(720)
            val scale = targetWidth.toFloat() / view.width.toFloat()
            val targetHeight = (view.height * scale).toInt().coerceIn(1, 1280)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val file = try {
                Canvas(bitmap).apply {
                    scale(scale, scale)
                    view.draw(this)
                }
                val directory = java.io.File(context.cacheDir, "browser_tab_previews").apply { mkdirs() }
                java.io.File(directory, "${tab.id}.jpg").also { target ->
                    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                }
            } finally {
                bitmap.recycle()
            }
            tab.thumbnailPath = file.absolutePath
            tab.thumbnailRevision = tab.visualRevision
            tabService.update(tab.id) { it.copy(thumbnail = BrowserTabThumbnail(file.absolutePath)) }
            persistTabs()
            publish()
        }
    }

    private fun publish(selectedId: String? = _uiState.value.selectedTabId) {
        val activeId = selectedId?.takeIf(sessions::containsKey) ?: tabService.selectedTabId.value
        val selected = activeId?.let(sessions::get)
        val descriptors = tabService.tabs.value
        _uiState.value = _uiState.value.copy(
            tabs = descriptors.map { descriptor ->
                val runtime = sessions[descriptor.id]
                BrowserTabUi(descriptor.id, descriptor.url, descriptor.title, descriptor.url.hostOrNewTab(), runtime?.canGoBack ?: false, runtime?.canGoForward ?: false, descriptor.viewportWidth, descriptor.thumbnail?.path)
            },
            selectedTabId = activeId,
            address = selected?.url?.takeUnless { it == DEFAULT_HOME }.orEmpty(),
            loading = selected?.loading ?: false,
            loadProgress = selected?.loadProgress ?: 0,
            viewportWidth = selected?.viewportWidth,
            history = historyService.entries.value,
        )
    }

    private fun persistTabs() {
        persistence.markDirty(::persistTabsNow)
    }

    private fun persistTabsNow() {
        storageService.saveTabs(tabService.tabs.value)
    }

    private fun updateSettings(transform: (BrowserSettings) -> BrowserSettings) {
        settingsService.update(transform)
        val next = settingsService.settings.value
        _uiState.value = _uiState.value.copy(settings = next)
    }

    private fun setError(message: String) { _uiState.value = _uiState.value.copy(error = message) }

    override fun onCleared() {
        persistence.flush()
        persistTabsNow()
        sessionService.closeAll()
        super.onCleared()
    }
}

private fun String.hostOrNewTab(): String = runCatching { Uri.parse(this).host?.takeIf(String::isNotBlank) ?: "New tab" }.getOrDefault("New tab")
