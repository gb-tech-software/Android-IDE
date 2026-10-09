package dev.android.ide.browser

import android.content.Context
import org.json.JSONArray

internal const val DEFAULT_HOME = "https://www.google.com"
internal const val DEFAULT_SEARCH = "google"

/** Sole persistence boundary for browser tabs, settings, history, and downloads. */
class BrowserStateStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readTabs(): JSONArray = runCatching {
        JSONArray(preferences.getString(TABS_KEY, "[]"))
    }.getOrDefault(JSONArray())

    fun selectedTabId(): String? = preferences.getString(SELECTED_TAB_KEY, null)

    fun saveTabs(tabs: JSONArray) {
        preferences.edit().putString(TABS_KEY, tabs.toString()).apply()
    }

    fun clearTabs() {
        preferences.edit().remove(TABS_KEY).remove(SELECTED_TAB_KEY).apply()
    }

    fun saveSelectedTab(id: String) {
        preferences.edit().putString(SELECTED_TAB_KEY, id).apply()
    }

    fun readHistory(): List<String> = runCatching {
        val array = JSONArray(preferences.getString(HISTORY_KEY, "[]"))
        List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
    }.getOrDefault(emptyList())

    fun saveHistory(history: List<String>) {
        preferences.edit().putString(HISTORY_KEY, JSONArray(history).toString()).apply()
    }

    fun readSettings(): BrowserSettings = BrowserSettings(
        searchEngine = preferences.getString(SEARCH_ENGINE_KEY, DEFAULT_SEARCH) ?: DEFAULT_SEARCH,
        customSearchUrl = preferences.getString(CUSTOM_SEARCH_KEY, "") ?: "",
        homePage = preferences.getString(HOME_PAGE_KEY, DEFAULT_HOME) ?: DEFAULT_HOME,
        downloadFolder = preferences.getString(DOWNLOAD_FOLDER_KEY, null),
        theme = preferences.getString(THEME_KEY, "application")
            ?.let { if (it == "system") "application" else it } ?: "application",
        developerToolsEnabled = preferences.getBoolean(DEVTOOLS_KEY, true),
        desktopSiteDefault = preferences.getBoolean(DESKTOP_SITE_KEY, false),
    )

    fun saveSettings(settings: BrowserSettings) {
        preferences.edit()
            .putString(SEARCH_ENGINE_KEY, settings.searchEngine)
            .putString(CUSTOM_SEARCH_KEY, settings.customSearchUrl)
            .putString(HOME_PAGE_KEY, settings.homePage)
            .putString(THEME_KEY, settings.theme)
            .putBoolean(DEVTOOLS_KEY, settings.developerToolsEnabled)
            .putBoolean(DESKTOP_SITE_KEY, settings.desktopSiteDefault)
            .apply()
        if (settings.downloadFolder == null) {
            preferences.edit().remove(DOWNLOAD_FOLDER_KEY).apply()
        } else {
            preferences.edit().putString(DOWNLOAD_FOLDER_KEY, settings.downloadFolder).apply()
        }
    }

    fun readDownloads(): JSONArray = runCatching {
        JSONArray(preferences.getString(DOWNLOADS_KEY, "[]"))
    }.getOrDefault(JSONArray())

    fun saveDownloads(downloads: JSONArray) {
        preferences.edit().putString(DOWNLOADS_KEY, downloads.toString()).apply()
    }

    fun clearHistoryAndDownloads() {
        preferences.edit().remove(DOWNLOADS_KEY).remove(HISTORY_KEY).apply()
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
        private const val DESKTOP_SITE_KEY = "desktop_site_default"
        private const val HISTORY_KEY = "navigation_history"
    }
}
