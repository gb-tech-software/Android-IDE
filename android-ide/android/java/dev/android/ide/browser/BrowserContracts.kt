package dev.android.ide.browser

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred
import java.io.InputStream

internal const val DEFAULT_HOME = "https://www.google.com"
internal const val DEFAULT_SEARCH = "google"
internal const val MIN_BROWSER_VIEWPORT_WIDTH_PX = 320
internal const val MAX_BROWSER_VIEWPORT_WIDTH_PX = 2400
internal val SEARCH_ENGINES = mapOf(
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
    val defaultViewportWidthPx: Int? = null,
    val autoRenameDuplicateDownloads: Boolean = false,
)

/** Browser-domain navigation request. The origin workflow is intentionally absent. */
data class BrowserNavigation(
    val value: String,
    val openInNewTab: Boolean = false,
    val historyAddress: String? = null,
)

data class BrowserSessionSettings(
    val desktopSite: Boolean = false,
)

data class BrowserSerializedState(val value: String)

data class BrowserTabDescriptor(
    val id: String,
    val url: String,
    val title: String,
    val reuseKey: String? = null,
    val serializedState: BrowserSerializedState? = null,
    val selected: Boolean = false,
    val viewportWidth: Int? = null,
    val scrollY: Int = 0,
    val thumbnail: BrowserTabThumbnail? = null,
)

data class BrowserTabThumbnail(val path: String)

data class BrowserNavigationChanged(val tabId: String, val url: String)
data class BrowserNavigationCapabilitiesChanged(val tabId: String, val canGoBack: Boolean, val canGoForward: Boolean)
data class BrowserTitleChanged(val tabId: String, val title: String)
data class BrowserProgressChanged(val tabId: String, val progress: Int)
data class BrowserPageLoadCompleted(val tabId: String, val successful: Boolean, val historyAddress: String?)
data class BrowserNavigationFailure(
    val tabId: String,
    val address: String,
    val title: String,
    val message: String,
)
enum class BrowserWebsitePromptKind {
    ALERT, CONFIRM, TEXT, BEFORE_UNLOAD, REPOST, AUTH, POPUP, CHOICE, COLOR, DATE_TIME, FILE,
}
data class BrowserWebsitePromptChoice(
    val id: String,
    val label: String,
    val selected: Boolean,
    val disabled: Boolean,
)
data class BrowserWebsitePromptAnswer(
    val accepted: Boolean,
    val value: String? = null,
    val username: String? = null,
    val password: String? = null,
    val selectedIds: List<String> = emptyList(),
    val selectedFileUris: List<String> = emptyList(),
)
data class BrowserWebsitePromptRequest(
    val id: String,
    val tabId: String,
    val kind: BrowserWebsitePromptKind,
    val origin: String,
    val title: String? = null,
    val message: String? = null,
    val defaultValue: String? = null,
    val defaultUsername: String? = null,
    val defaultPassword: String? = null,
    val passwordOnly: Boolean = false,
    val targetUri: String? = null,
    val choices: List<BrowserWebsitePromptChoice> = emptyList(),
    val multiple: Boolean = false,
    val mimeTypes: List<String> = emptyList(),
    val inputType: String? = null,
    val decision: CompletableDeferred<BrowserWebsitePromptAnswer>,
)
enum class BrowserDownloadStatus { DOWNLOADING, PAUSED, COMPLETED, FAILED, INTERRUPTED, CANCELED }

data class BrowserDownload(
    val id: String,
    val name: String,
    val uri: String,
    val sourceUrl: String,
    val mimeType: String,
    val size: Long,
    val timestamp: Long,
    val available: Boolean = true,
    val status: BrowserDownloadStatus = BrowserDownloadStatus.COMPLETED,
    val downloadedBytes: Long = size,
    val totalBytes: Long = -1L,
    val folderUri: String? = null,
    val error: String? = null,
)
data class BrowserDownloadRequest(
    val tabId: String,
    val url: String,
    val mimeType: String,
    val fileName: String?,
    val body: InputStream? = null,
    val totalBytes: Long = -1L,
)
data class BrowserPermissionRequest(
    val tabId: String,
    val origin: String,
    val permission: String,
    val decision: CompletableDeferred<Boolean>,
)
enum class BrowserContextKind { LINK, IMAGE, VIDEO, AUDIO, LINKED_MEDIA }
data class BrowserContextMenuRequest(
    val tabId: String,
    val kind: BrowserContextKind,
    val linkUri: String? = null,
    val mediaUri: String? = null,
    val title: String? = null,
    val altText: String? = null,
    val textContent: String? = null,
)
data class BrowserScrollChanged(val tabId: String, val scrollY: Int)

interface BrowserEngineEventSink {
    fun onNavigationChanged(event: BrowserNavigationChanged)
    fun onNavigationCapabilitiesChanged(event: BrowserNavigationCapabilitiesChanged)
    fun onSerializedStateChanged(tabId: String, state: BrowserSerializedState)
    fun onTitleChanged(event: BrowserTitleChanged)
    fun onProgressChanged(event: BrowserProgressChanged)
    fun onPageLoadCompleted(event: BrowserPageLoadCompleted)
    fun onNavigationFailure(event: BrowserNavigationFailure)
    fun onDownloadRequested(event: BrowserDownloadRequest)
    fun onPermissionRequested(event: BrowserPermissionRequest)
    fun onWebsitePromptRequested(event: BrowserWebsitePromptRequest)
    fun onContextMenuRequested(event: BrowserContextMenuRequest)
    fun onScrollChanged(event: BrowserScrollChanged)
    fun onNewTabRequested(url: String): BrowserEngineSession?
}

/** A host for rendered browser content; concrete view and engine types stay behind the adapter. */
interface BrowserSurfaceHost

interface BrowserEngineSession {
    fun open()
    fun attach(host: BrowserSurfaceHost)
    fun load(request: BrowserNavigation)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    fun clearHistory()
    fun setViewport(widthPx: Int?)
    fun setDesktopSite(enabled: Boolean)
    fun saveState(): BrowserSerializedState?
    fun restoreState(state: BrowserSerializedState)
    fun close()
}

interface BrowserEngine {
    val capabilities: Set<BrowserEngineCapability>
    fun createSession(tabId: String, settings: BrowserSessionSettings, sink: BrowserEngineEventSink): BrowserEngineSession
    fun clearSiteData(onComplete: (Result<Unit>) -> Unit)
}

enum class BrowserEngineCapability { HTTP, HTTPS, LOCAL_FILE, CONTENT_URI, DESKTOP_USER_AGENT, VIEWPORT_WIDTH, DOWNLOADS, PERMISSIONS, SITE_DATA_CLEARING }

interface BrowserNavigationService {
    fun navigate(request: BrowserNavigation)
    fun resolve(address: String): String
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    fun setDesktopSite(enabled: Boolean)
}

interface BrowserTabService {
    val tabs: StateFlow<List<BrowserTabDescriptor>>
    val selectedTabId: StateFlow<String?>
    fun create(url: String = "about:blank", reuseKey: String? = null): String
    fun findByReuseKey(reuseKey: String): String?
    fun add(tab: BrowserTabDescriptor, select: Boolean = false)
    fun update(tabId: String, transform: (BrowserTabDescriptor) -> BrowserTabDescriptor)
    fun restore(tabs: List<BrowserTabDescriptor>, selectedTabId: String?)
    fun select(tabId: String)
    fun duplicate(tabId: String): String?
    /** Returns a replacement descriptor when closing the last tab requires one. */
    fun close(tabId: String): BrowserTabDescriptor?
}

interface BrowserSessionService {
    fun ensureSession(
        tabId: String,
        settings: BrowserSessionSettings,
        state: BrowserSerializedState?,
        sink: BrowserEngineEventSink,
    ): BrowserEngineSession
    fun existingSession(tabId: String): BrowserEngineSession?
    fun saveState(tabId: String): BrowserSerializedState?
    fun closeSession(tabId: String)
    fun closeAll()
}

interface BrowserDownloadService {
    val downloads: StateFlow<List<BrowserDownload>>
    val duplicatePrompt: StateFlow<DuplicateDownloadPrompt?>
    val error: StateFlow<String?>
    fun accept(request: BrowserDownloadRequest)
    fun resolveDuplicate(name: String?, autoRenameFutureConflicts: Boolean)
    fun pause(downloadId: String): Boolean
    fun resume(downloadId: String): Boolean
    fun cancel(downloadId: String): Boolean
    fun prepareRetry(downloadId: String): String?
    fun reconcile()
    fun open(downloadId: String): android.content.Intent?
    fun share(downloadId: String): android.content.Intent?
    fun delete(downloadId: String): Boolean
    fun removeUnavailable(downloadId: String): Boolean
    fun clear()
    fun clearError()
}

interface BrowserHistoryService {
    val entries: StateFlow<List<String>>
    fun addOrUpdate(address: String): List<String>
    fun suggestions(query: String, excludedAddress: String? = null, limit: Int = 8): List<String>
    fun remove(address: String)
    fun clear()
}

interface BrowserSettingsService {
    val settings: StateFlow<BrowserSettings>
    fun update(transform: (BrowserSettings) -> BrowserSettings)
}

interface BrowserStorageService {
    fun readTabs(): List<BrowserTabDescriptor>
    fun selectedTabId(): String?
    fun saveTabs(tabs: List<BrowserTabDescriptor>)
    fun clearTabs()
    fun saveSelectedTab(id: String)
    fun readHistory(): List<String>
    fun saveHistory(history: List<String>)
    fun readSettings(): BrowserSettings
    fun saveSettings(settings: BrowserSettings)
    fun readDownloads(): List<BrowserDownload>
    fun saveDownloads(downloads: List<BrowserDownload>)
    fun clearHistoryAndDownloads()
    fun clearSiteData(onComplete: (Result<Unit>) -> Unit)
    fun developerToolsOpen(): Boolean
    fun saveDeveloperToolsOpen(open: Boolean)
}

interface BrowserViewportService {
    val minimumWidthPx: Int
    val maximumWidthPx: Int
    fun validate(widthPx: Int?): Int?
    fun device(): Int? = null
    fun mobile(): Int? = 390
    fun tablet(): Int? = 768
    fun desktop(): Int? = 1280
}

interface BrowserPermissionService {
    val pending: StateFlow<BrowserPermissionRequest?>
    fun request(request: BrowserPermissionRequest)
    fun answer(allow: Boolean)
    fun cancel()
}

interface BrowserDeveloperToolsService {
    fun open()
    fun close()
    fun setEnabled(enabled: Boolean)
}
