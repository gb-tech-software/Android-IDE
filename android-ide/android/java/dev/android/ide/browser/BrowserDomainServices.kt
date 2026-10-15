package dev.android.ide.browser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

class StoredBrowserHistoryService(
    private val storage: BrowserStorageService,
    private val maximumEntries: Int = 30,
) : BrowserHistoryService {
    private val mutableEntries = MutableStateFlow(storage.readHistory())
    override val entries: StateFlow<List<BrowserHistoryEntry>> = mutableEntries.asStateFlow()

    override fun addVisit(address: String, title: String): List<BrowserHistoryEntry> {
        val value = address.trim().takeIf(String::isNotBlank) ?: return mutableEntries.value
        val next = (listOf(
            BrowserHistoryEntry(
                id = UUID.randomUUID().toString(),
                url = value,
                title = title.trim().ifBlank { value },
                visitedAt = System.currentTimeMillis(),
            ),
        ) + mutableEntries.value).take(maximumEntries)
        storage.saveHistory(next)
        mutableEntries.value = next
        return next
    }

    override fun suggestions(query: String, excludedAddress: String?, limit: Int): List<String> {
        val normalizedQuery = query.trim()
        return mutableEntries.value
            .asSequence()
            .filterNot { it.url == excludedAddress }
            .filter { normalizedQuery.isBlank() || it.url.contains(normalizedQuery, ignoreCase = true) || it.title.contains(normalizedQuery, ignoreCase = true) }
            .map { it.url }
            .distinct()
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    override fun remove(id: String) {
        val next = mutableEntries.value.filterNot { it.id == id }
        storage.saveHistory(next)
        mutableEntries.value = next
    }

    override fun removeUrl(address: String) {
        val next = mutableEntries.value.filterNot { it.url == address }
        storage.saveHistory(next)
        mutableEntries.value = next
    }

    override fun clear() {
        storage.saveHistory(emptyList())
        mutableEntries.value = emptyList()
    }
}

class StoredBrowserSettingsService(
    private val storage: BrowserStorageService,
) : BrowserSettingsService {
    private val mutableSettings = MutableStateFlow(storage.readSettings())
    override val settings: StateFlow<BrowserSettings> = mutableSettings.asStateFlow()

    override fun update(transform: (BrowserSettings) -> BrowserSettings) {
        val next = transform(mutableSettings.value)
        storage.saveSettings(next)
        mutableSettings.value = next
    }
}

class BoundedBrowserViewportService(
    override val minimumWidthPx: Int = MIN_BROWSER_VIEWPORT_WIDTH_PX,
    override val maximumWidthPx: Int = MAX_BROWSER_VIEWPORT_WIDTH_PX,
) : BrowserViewportService {
    init {
        require(minimumWidthPx > 0 && maximumWidthPx >= minimumWidthPx)
    }

    override fun validate(widthPx: Int?): Int? = widthPx
        ?.takeIf { it > 0 }
        ?.takeIf { it in minimumWidthPx..maximumWidthPx }
}

class BrowserSessionRegistry(
    private val engine: BrowserEngine,
) : BrowserSessionService {
    private val sessions = linkedMapOf<String, BrowserEngineSession>()

    override fun createUnopenedSession(
        tabId: String,
        settings: BrowserSessionSettings,
        sink: BrowserEngineEventSink,
    ): BrowserEngineSession {
        check(tabId !in sessions) { "A popup session must use a fresh tab ID" }
        return engine.createSession(tabId, settings, sink).also { sessions[tabId] = it }
    }

    override fun ensureSession(
        tabId: String,
        settings: BrowserSessionSettings,
        state: BrowserSerializedState?,
        sink: BrowserEngineEventSink,
    ): BrowserEngineSession {
        sessions[tabId]?.let { return it }
        val session = engine.createSession(tabId, settings, sink)
        sessions[tabId] = session
        try {
            session.open()
            state?.let(session::restoreState)
        } catch (error: Throwable) {
            sessions.remove(tabId, session)
            runCatching { session.close() }
            throw error
        }
        return session
    }

    override fun existingSession(tabId: String): BrowserEngineSession? = sessions[tabId]

    override fun saveState(tabId: String): BrowserSerializedState? = sessions[tabId]?.saveState()

    override fun closeSession(tabId: String) {
        sessions.remove(tabId)?.close()
    }

    override fun closeAll() {
        sessions.values.forEach(BrowserEngineSession::close)
        sessions.clear()
    }
}

class BrowserTabRegistry(
    private val defaultViewportWidth: () -> Int? = { null },
) : BrowserTabService {
    private val mutableTabs = MutableStateFlow<List<BrowserTabDescriptor>>(emptyList())
    private val mutableSelectedTabId = MutableStateFlow<String?>(null)
    override val tabs: StateFlow<List<BrowserTabDescriptor>> = mutableTabs.asStateFlow()
    override val selectedTabId: StateFlow<String?> = mutableSelectedTabId.asStateFlow()

    override fun create(url: String, reuseKey: String?): String {
        val id = UUID.randomUUID().toString()
        add(BrowserTabDescriptor(id = id, url = url, title = "New tab", reuseKey = reuseKey, viewportWidth = defaultViewportWidth()), select = true)
        return id
    }

    override fun findByReuseKey(reuseKey: String): String? =
        mutableTabs.value.firstOrNull { it.reuseKey == reuseKey }?.id

    override fun add(tab: BrowserTabDescriptor, select: Boolean) {
        if (mutableTabs.value.any { it.id == tab.id }) return
        val shouldSelect = select || mutableSelectedTabId.value == null
        mutableTabs.value = mutableTabs.value + tab.copy(selected = shouldSelect)
        if (shouldSelect) select(tab.id)
    }

    override fun update(tabId: String, transform: (BrowserTabDescriptor) -> BrowserTabDescriptor) {
        mutableTabs.value = mutableTabs.value.map { if (it.id == tabId) transform(it) else it }
    }

    override fun restore(tabs: List<BrowserTabDescriptor>, selectedTabId: String?) {
        val unique = tabs.distinctBy { it.id }
        val active = selectedTabId?.takeIf { id -> unique.any { it.id == id } } ?: unique.firstOrNull()?.id
        mutableSelectedTabId.value = active
        mutableTabs.value = unique.map { it.copy(selected = it.id == active) }
    }

    override fun select(tabId: String) {
        if (mutableTabs.value.any { it.id == tabId }) {
            mutableSelectedTabId.value = tabId
            mutableTabs.value = mutableTabs.value.map { it.copy(selected = it.id == tabId) }
        }
    }

    override fun duplicate(tabId: String): String? {
        val source = mutableTabs.value.firstOrNull { it.id == tabId } ?: return null
        val id = UUID.randomUUID().toString()
        add(source.copy(id = id, reuseKey = null, selected = false, serializedState = null, thumbnail = null), select = true)
        return id
    }

    override fun close(tabId: String): BrowserTabDescriptor? {
        if (mutableTabs.value.none { it.id == tabId }) return null
        val remaining = mutableTabs.value.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            val replacement = BrowserTabDescriptor(
                id = UUID.randomUUID().toString(),
                url = DEFAULT_HOME,
                title = "New tab",
                selected = true,
                viewportWidth = defaultViewportWidth(),
            )
            mutableSelectedTabId.value = replacement.id
            mutableTabs.value = listOf(replacement)
            return replacement
        }
        val active = if (mutableSelectedTabId.value == tabId) remaining.lastOrNull()?.id else mutableSelectedTabId.value
        mutableSelectedTabId.value = active
        mutableTabs.value = remaining.map { it.copy(selected = it.id == active) }
        return null
    }
}

class ActiveBrowserNavigationService(
    private val tabs: BrowserTabService,
    private val sessions: BrowserSessionService,
    private val settings: BrowserSettingsService,
) : BrowserNavigationService {
    override fun navigate(request: BrowserNavigation) {
        selectedSession()?.load(request)
    }

    override fun resolve(address: String): String {
        val value = address.trim()
        val hasScheme = value.contains("://") || value.startsWith("about:") || value.startsWith("file:")
        val looksLikeHost = value == "localhost" || value.startsWith("localhost:") ||
            value.matches(Regex("^[0-9.]+(:[0-9]+)?(/.*)?$")) || value.contains('.')
        if (hasScheme) return value
        if (looksLikeHost) return "https://$value"
        val currentSettings = settings.settings.value
        val template = SEARCH_ENGINES[currentSettings.searchEngine] ?: currentSettings.customSearchUrl
        return (template.ifBlank { SEARCH_ENGINES.getValue(DEFAULT_SEARCH) }).replace("%s", Uri.encode(value))
    }

    override fun goBack() { selectedSession()?.goBack() }
    override fun goForward() { selectedSession()?.goForward() }
    override fun reload() { selectedSession()?.reload() }
    override fun stop() { selectedSession()?.stop() }
    override fun setDesktopSite(enabled: Boolean) { selectedSession()?.setDesktopSite(enabled) }

    private fun selectedSession(): BrowserEngineSession? = tabs.selectedTabId.value?.let(sessions::existingSession)
}

class BrowserPermissionPromptService(
    private val storage: BrowserStorageService,
) : BrowserPermissionService {
    private data class VisitGrant(val tabId: String, val origin: String, val permission: String)

    private val mutablePending = MutableStateFlow<BrowserPermissionRequest?>(null)
    private val mutableSitePermissions = MutableStateFlow(storage.readSitePermissions())
    private val visitGrants = mutableSetOf<VisitGrant>()
    private val tabOrigins = mutableMapOf<String, String>()
    private val queued = ArrayDeque<BrowserPermissionRequest>()
    override val pending: StateFlow<BrowserPermissionRequest?> = mutablePending.asStateFlow()
    override val sitePermissions: StateFlow<List<BrowserSitePermission>> = mutableSitePermissions.asStateFlow()

    override fun request(request: BrowserPermissionRequest) {
        val origin = permissionOrigin(request.origin)
        if (visitGrants.contains(VisitGrant(request.tabId, origin, request.permission))) {
            request.decision.complete(true)
            return
        }
        mutableSitePermissions.value.firstOrNull {
            permissionOrigin(it.origin) == origin && it.permission == request.permission
        }?.let { stored ->
            request.decision.complete(stored.decision == BrowserPermissionDecision.ALLOW)
            return
        }
        if (mutablePending.value?.id == request.id || queued.any { it.id == request.id }) return
        queued.addLast(request)
        showNext()
    }

    override fun answer(requestId: String, answer: BrowserPermissionAnswer) {
        val request = mutablePending.value ?: return
        if (request.id != requestId) return
        val allow = answer in setOf(
            BrowserPermissionAnswer.ALLOW_ONCE,
            BrowserPermissionAnswer.ALLOW_WHILE_VISITING,
            BrowserPermissionAnswer.ALLOW_ALWAYS,
        )
        if (answer == BrowserPermissionAnswer.ALLOW_WHILE_VISITING) {
            visitGrants += VisitGrant(request.tabId, permissionOrigin(request.origin), request.permission)
        }
        val persists = answer == BrowserPermissionAnswer.ALLOW_ALWAYS || answer == BrowserPermissionAnswer.DENY_ALWAYS
        if (persists) {
            val record = BrowserSitePermission(
                origin = request.origin,
                permission = request.permission,
                decision = if (allow) BrowserPermissionDecision.ALLOW else BrowserPermissionDecision.DENY,
            )
            val next = (mutableSitePermissions.value.filterNot {
                it.origin == record.origin && it.permission == record.permission
            } + record).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.origin })
            try {
                storage.saveSitePermissions(next)
                mutableSitePermissions.value = next
            } catch (_: Exception) {
                // Resolve the current request below even if a remembered decision cannot be saved.
            }
        }
        request.decision.complete(allow)
        mutablePending.value = null
        showNext()
    }

    override fun revoke(origin: String, permission: String?) {
        val next = mutableSitePermissions.value.filterNot {
            permissionOrigin(it.origin) == permissionOrigin(origin) && (permission == null || it.permission == permission)
        }
        storage.saveSitePermissions(next)
        mutableSitePermissions.value = next
    }

    override fun cancel() {
        mutablePending.value?.let { answer(it.id, BrowserPermissionAnswer.DENY_ONCE) }
    }

    override fun cancelAll() {
        mutablePending.value?.decision?.complete(false)
        mutablePending.value = null
        queued.forEach { it.decision.complete(false) }
        queued.clear()
    }

    override fun onTabDeselected(tabId: String) {
        val hiddenQueued = queued.filter { it.tabId == tabId }
        queued.removeAll(hiddenQueued.toSet())
        hiddenQueued.forEach { it.decision.complete(false) }
        mutablePending.value?.takeIf { it.tabId == tabId }?.let { pending ->
            pending.decision.complete(false)
            mutablePending.value = null
        }
        showNext()
    }

    override fun onTabNavigated(tabId: String, url: String) {
        val nextOrigin = permissionOrigin(url)
        val previousOrigin = tabOrigins.put(tabId, nextOrigin)
        if (previousOrigin != null && previousOrigin != nextOrigin) {
            visitGrants.removeAll { it.tabId == tabId }
            val staleQueued = queued.filter { it.tabId == tabId && permissionOrigin(it.origin) != nextOrigin }
            queued.removeAll(staleQueued.toSet())
            staleQueued.forEach { it.decision.complete(false) }
            mutablePending.value?.takeIf {
                it.tabId == tabId && permissionOrigin(it.origin) != nextOrigin
            }?.let { pending ->
                pending.decision.complete(false)
                mutablePending.value = null
            }
            showNext()
        }
    }

    override fun onTabClosed(tabId: String) {
        tabOrigins.remove(tabId)
        visitGrants.removeAll { it.tabId == tabId }
        val closedQueued = queued.filter { it.tabId == tabId }
        queued.removeAll(closedQueued.toSet())
        closedQueued.forEach { it.decision.complete(false) }
        mutablePending.value?.takeIf { it.tabId == tabId }?.let { pending ->
            pending.decision.complete(false)
            mutablePending.value = null
        }
        showNext()
    }

    private fun showNext() {
        if (mutablePending.value == null && queued.isNotEmpty()) {
            mutablePending.value = queued.removeFirst()
        }
    }

    private fun permissionOrigin(value: String): String = runCatching {
        val uri = Uri.parse(value)
        val host = uri.host ?: return value.trimEnd('/')
        val authority = if (uri.port >= 0) "$host:${uri.port}" else host
        "${uri.scheme ?: "https"}://$authority"
    }.getOrDefault(value.trimEnd('/'))
}

/** Browser composition root: shared ownership of browser-specific services and persistence. */
class BrowserApplicationService internal constructor(
    context: Context,
    val storage: BrowserStorageService,
    val persistence: BrowserPersistenceCoordinator,
    val engine: BrowserEngine,
    val developerTools: BrowserDeveloperToolsService,
    applicationScope: CoroutineScope,
) {
    val history: BrowserHistoryService = StoredBrowserHistoryService(storage)
    val settings: BrowserSettingsService = StoredBrowserSettingsService(storage)
    val viewport: BrowserViewportService = BoundedBrowserViewportService()
    val tabs: BrowserTabService = BrowserTabRegistry { settings.settings.value.defaultViewportWidthPx }
    val permissions: BrowserPermissionService = BrowserPermissionPromptService(storage)
    val sessions: BrowserSessionService = BrowserSessionRegistry(engine)
    val navigation: BrowserNavigationService = ActiveBrowserNavigationService(tabs, sessions, settings)
    val downloads: BrowserDownloadService = AndroidBrowserDownloadService(context, storage, settings, applicationScope)
}
