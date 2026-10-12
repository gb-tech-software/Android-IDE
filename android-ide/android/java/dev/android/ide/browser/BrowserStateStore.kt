package dev.android.ide.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Sole persistence boundary for browser tabs, settings, history, and downloads. */
class BrowserStateStore(
    context: Context,
    private val engine: BrowserEngine,
) : BrowserStorageService {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun readTabs(): List<BrowserTabDescriptor> = runCatching {
        val array = JSONArray(preferences.getString(TABS_KEY, "[]"))
        List(array.length()) { index -> array.optJSONObject(index) }.mapNotNull { item ->
            item?.let {
                BrowserTabDescriptor(
                    id = it.optString("id").ifBlank { UUID.randomUUID().toString() },
                    url = it.optString("url", DEFAULT_HOME).ifBlank { DEFAULT_HOME },
                    title = it.optString("title", "New tab").ifBlank { "New tab" },
                    reuseKey = it.optString("reuseKey").takeIf(String::isNotBlank),
                    serializedState = it.optString("state").takeIf(String::isNotBlank)?.let(::BrowserSerializedState),
                    viewportWidth = it.optInt("viewportWidth", 0).takeIf { width -> width in 320..2400 },
                    scrollY = it.optInt("scrollY", 0).coerceAtLeast(0),
                    thumbnail = it.optString("thumbnailPath").takeIf { path -> path.isNotBlank() && File(path).isFile }
                        ?.let(::BrowserTabThumbnail),
                )
            }
        }
    }.getOrDefault(emptyList())

    override fun selectedTabId(): String? = preferences.getString(SELECTED_TAB_KEY, null)

    override fun saveTabs(tabs: List<BrowserTabDescriptor>) {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(JSONObject().apply {
                put("id", tab.id)
                put("url", tab.url)
                put("title", tab.title)
                tab.reuseKey?.let { put("reuseKey", it) }
                put("viewportWidth", tab.viewportWidth ?: 0)
                put("scrollY", tab.scrollY)
                tab.thumbnail?.path?.takeIf { File(it).isFile }?.let { put("thumbnailPath", it) }
                tab.serializedState?.value?.let { put("state", it) }
            })
        }
        preferences.edit().putString(TABS_KEY, array.toString()).apply()
    }

    override fun clearTabs() {
        preferences.edit().remove(TABS_KEY).remove(SELECTED_TAB_KEY).apply()
    }

    override fun saveSelectedTab(id: String) {
        preferences.edit().putString(SELECTED_TAB_KEY, id).apply()
    }

    override fun readHistory(): List<BrowserHistoryEntry> = runCatching {
        val array = JSONArray(preferences.getString(HISTORY_KEY, "[]"))
        List(array.length()) { index ->
            val item = array.opt(index)
            when (item) {
                is JSONObject -> BrowserHistoryEntry(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    url = item.optString("url").trim(),
                    title = item.optString("title").ifBlank { item.optString("url") },
                    visitedAt = item.optLong("visitedAt", 0L),
                )
                is String -> BrowserHistoryEntry(
                    id = UUID.randomUUID().toString(),
                    url = item.trim(),
                    title = item.trim(),
                    visitedAt = 0L,
                )
                else -> null
            }
        }.mapNotNull { entry -> entry?.takeIf { it.url.isNotBlank() } }
    }.getOrDefault(emptyList())

    override fun saveHistory(history: List<BrowserHistoryEntry>) {
        val array = JSONArray()
        history.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("url", entry.url)
                put("title", entry.title)
                put("visitedAt", entry.visitedAt)
            })
        }
        preferences.edit().putString(HISTORY_KEY, array.toString()).apply()
    }

    override fun readSettings(): BrowserSettings = BrowserSettings(
        searchEngine = preferences.getString(SEARCH_ENGINE_KEY, DEFAULT_SEARCH) ?: DEFAULT_SEARCH,
        customSearchUrl = preferences.getString(CUSTOM_SEARCH_KEY, "") ?: "",
        homePage = preferences.getString(HOME_PAGE_KEY, DEFAULT_HOME) ?: DEFAULT_HOME,
        downloadFolder = preferences.getString(DOWNLOAD_FOLDER_KEY, null),
        theme = preferences.getString(THEME_KEY, "application")
            ?.let { if (it == "system") "application" else it } ?: "application",
        developerToolsEnabled = preferences.getBoolean(DEVTOOLS_KEY, true),
        desktopSiteDefault = preferences.getBoolean(DESKTOP_SITE_KEY, false),
        defaultViewportWidthPx = preferences.getInt(DEFAULT_VIEWPORT_WIDTH_KEY, 0)
            .takeIf { it in MIN_BROWSER_VIEWPORT_WIDTH_PX..MAX_BROWSER_VIEWPORT_WIDTH_PX },
        autoRenameDuplicateDownloads = preferences.getBoolean(AUTO_RENAME_DUPLICATE_DOWNLOADS_KEY, false),
    )

    override fun saveSettings(settings: BrowserSettings) {
        preferences.edit()
            .putString(SEARCH_ENGINE_KEY, settings.searchEngine)
            .putString(CUSTOM_SEARCH_KEY, settings.customSearchUrl)
            .putString(HOME_PAGE_KEY, settings.homePage)
            .putString(THEME_KEY, settings.theme)
            .putBoolean(DEVTOOLS_KEY, settings.developerToolsEnabled)
            .putBoolean(DESKTOP_SITE_KEY, settings.desktopSiteDefault)
            .putInt(DEFAULT_VIEWPORT_WIDTH_KEY, settings.defaultViewportWidthPx ?: 0)
            .putBoolean(AUTO_RENAME_DUPLICATE_DOWNLOADS_KEY, settings.autoRenameDuplicateDownloads)
            .apply()
        if (settings.downloadFolder == null) {
            preferences.edit().remove(DOWNLOAD_FOLDER_KEY).apply()
        } else {
            preferences.edit().putString(DOWNLOAD_FOLDER_KEY, settings.downloadFolder).apply()
        }
    }

    override fun readDownloads(): List<BrowserDownload> = runCatching {
        val array = JSONArray(preferences.getString(DOWNLOADS_KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val storedStatus = runCatching {
                    BrowserDownloadStatus.valueOf(item.optString("status", BrowserDownloadStatus.COMPLETED.name))
                }.getOrDefault(BrowserDownloadStatus.COMPLETED)
                val status = when (storedStatus) {
                    BrowserDownloadStatus.DOWNLOADING, BrowserDownloadStatus.PAUSED -> BrowserDownloadStatus.INTERRUPTED
                    else -> storedStatus
                }
                val uri = item.optString("uri")
                if (uri.isBlank() && status == BrowserDownloadStatus.COMPLETED) continue
                add(
                    BrowserDownload(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name").ifBlank { "download" },
                        uri = uri,
                        sourceUrl = item.optString("sourceUrl"),
                        mimeType = item.optString("mimeType", "application/octet-stream"),
                        size = item.optLong("size").coerceAtLeast(0L),
                        timestamp = item.optLong("timestamp").coerceAtLeast(0L),
                        available = item.optBoolean("available", status == BrowserDownloadStatus.COMPLETED) &&
                            status == BrowserDownloadStatus.COMPLETED,
                        status = status,
                        downloadedBytes = item.optLong("downloadedBytes").coerceAtLeast(0L),
                        totalBytes = item.optLong("totalBytes", -1L),
                        folderUri = item.optString("folderUri").takeIf(String::isNotBlank),
                        error = item.optString("error").takeIf(String::isNotBlank)
                            ?: if (status == BrowserDownloadStatus.INTERRUPTED) "The app stopped before this download finished" else null,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    override fun saveDownloads(downloads: List<BrowserDownload>) {
        val array = JSONArray()
        downloads.forEach { download ->
            array.put(JSONObject().apply {
                put("id", download.id)
                put("name", download.name)
                put("uri", download.uri)
                put("sourceUrl", download.sourceUrl)
                put("mimeType", download.mimeType)
                put("size", download.size)
                put("timestamp", download.timestamp)
                put("available", download.available)
                put("status", download.status.name)
                put("downloadedBytes", download.downloadedBytes)
                put("totalBytes", download.totalBytes)
                download.folderUri?.let { put("folderUri", it) }
                download.error?.let { put("error", it) }
            })
        }
        preferences.edit().putString(DOWNLOADS_KEY, array.toString()).apply()
    }

    override fun clearHistoryAndDownloads() {
        preferences.edit().remove(DOWNLOADS_KEY).remove(HISTORY_KEY).apply()
    }

    override fun clearSiteData(onComplete: (Result<Unit>) -> Unit) {
        engine.clearSiteData(onComplete)
    }

    override fun developerToolsOpen(): Boolean = preferences.getBoolean(DEVTOOLS_OPEN_KEY, false)

    override fun saveDeveloperToolsOpen(open: Boolean) {
        preferences.edit().putBoolean(DEVTOOLS_OPEN_KEY, open).apply()
    }

    companion object {
        private const val PREFS = "browser_state"
        private const val TABS_KEY = "tabs"
        private const val SELECTED_TAB_KEY = "selected_tab"
        private const val DOWNLOADS_KEY = "downloads"
        private const val SEARCH_ENGINE_KEY = "search_engine"
        private const val CUSTOM_SEARCH_KEY = "custom_search"
        private const val HOME_PAGE_KEY = "home_page"
        private const val DOWNLOAD_FOLDER_KEY = "download_folder"
        private const val THEME_KEY = "theme"
        private const val DEVTOOLS_KEY = "developer_tools_enabled"
        private const val DEVTOOLS_OPEN_KEY = "developer_tools_open"
        private const val DESKTOP_SITE_KEY = "desktop_site_default"
        private const val DEFAULT_VIEWPORT_WIDTH_KEY = "default_viewport_width"
        private const val AUTO_RENAME_DUPLICATE_DOWNLOADS_KEY = "auto_rename_duplicate_downloads"
        private const val HISTORY_KEY = "navigation_history"
    }
}
