package dev.android.ide.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebExtension

/** Current implementation of the engine-neutral browser contract. */
class GeckoViewBrowserEngine(
    private val runtime: GeckoRuntime,
) : BrowserEngine {
    override val capabilities: Set<BrowserEngineCapability> = setOf(
        BrowserEngineCapability.HTTP,
        BrowserEngineCapability.HTTPS,
        BrowserEngineCapability.LOCAL_FILE,
        BrowserEngineCapability.CONTENT_URI,
        BrowserEngineCapability.DESKTOP_USER_AGENT,
        BrowserEngineCapability.VIEWPORT_WIDTH,
        BrowserEngineCapability.DOWNLOADS,
        BrowserEngineCapability.PERMISSIONS,
        BrowserEngineCapability.DEVTOOLS,
        BrowserEngineCapability.SITE_DATA_CLEARING,
    )

    override fun createSession(
        tabId: String,
        settings: BrowserSessionSettings,
        sink: BrowserEngineEventSink,
    ): BrowserEngineSession = GeckoBrowserEngineSession(runtime, tabId, settings, sink)

    override fun clearSiteData(onComplete: (Result<Unit>) -> Unit) {
        val clearRequest = try {
            runtime.storageController.clearData(StorageController.ClearFlags.SITE_DATA)
        } catch (error: Exception) {
            onComplete(Result.failure(error))
            return
        }
        clearRequest.accept(
            { onComplete(Result.success(Unit)) },
            { error -> onComplete(Result.failure(error ?: IllegalStateException("GeckoView could not clear site data"))) },
        )
    }
}

/** UI-side host wrapper. GeckoView remains confined to the adapter boundary. */
class GeckoViewBrowserSurfaceHost(internal val view: GeckoView) : BrowserSurfaceHost

@OptIn(ExperimentalCoroutinesApi::class)
private class GeckoBrowserEngineSession(
    private val runtime: GeckoRuntime,
    private val tabId: String,
    settings: BrowserSessionSettings,
    private val sink: BrowserEngineEventSink,
    ) : BrowserEngineSession {
    internal val geckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(if (settings.desktopSite) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build(),
    )
    private var serializedState: BrowserSerializedState? = null
    private var canGoBack = false
    private var canGoForward = false
    private var pendingHistoryAddress: String? = null
    private var lastKnownUrl: String? = null
    private var viewportWidthPx: Int? = null

    init {
        geckoSession.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (hasUserGesture && pendingHistoryAddress != null) pendingHistoryAddress = null
                lastKnownUrl = url
                sink.onNavigationChanged(BrowserNavigationChanged(tabId, url.orEmpty()))
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoBrowserEngineSession.canGoBack = canGoBack
                sink.onNavigationCapabilitiesChanged(BrowserNavigationCapabilitiesChanged(tabId, this@GeckoBrowserEngineSession.canGoBack, this@GeckoBrowserEngineSession.canGoForward))
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                this@GeckoBrowserEngineSession.canGoForward = canGoForward
                sink.onNavigationCapabilitiesChanged(BrowserNavigationCapabilitiesChanged(tabId, this@GeckoBrowserEngineSession.canGoBack, this@GeckoBrowserEngineSession.canGoForward))
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val engineSession = sink.onNewTabRequested(uri) as? GeckoBrowserEngineSession
                return GeckoResult.fromValue(engineSession?.geckoSession)
            }
        })
        geckoSession.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                lastKnownUrl = url
                sink.onNavigationChanged(BrowserNavigationChanged(tabId, url))
                sink.onProgressChanged(BrowserProgressChanged(tabId, 0))
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                sink.onProgressChanged(BrowserProgressChanged(tabId, progress))
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                sink.onProgressChanged(BrowserProgressChanged(tabId, 100))
                val historyAddress = pendingHistoryAddress ?: lastKnownUrl
                pendingHistoryAddress = null
                sink.onPageLoadCompleted(BrowserPageLoadCompleted(tabId, success, historyAddress))
            }

            override fun onSessionStateChange(session: GeckoSession, state: GeckoSession.SessionState) {
                serializedState = BrowserSerializedState(state.toString())
                sink.onSerializedStateChanged(tabId, serializedState!!)
            }
        })
        geckoSession.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                sink.onTitleChanged(BrowserTitleChanged(tabId, title.orEmpty()))
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
                sink.onContextMenuRequested(
                    BrowserContextMenuRequest(tabId, kind, link, media, element.title, element.altText, element.textContent),
                )
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                sink.onDownloadRequested(
                    BrowserDownloadRequest(
                        tabId = tabId,
                        url = response.uri,
                        mimeType = response.headers["Content-Type"] ?: "application/octet-stream",
                        fileName = response.uri.substringAfterLast('/').substringBefore('?').ifBlank { null },
                        body = response.body,
                        totalBytes = response.headers["Content-Length"]?.toLongOrNull() ?: -1L,
                    ),
                )
            }
        })
        geckoSession.setPermissionDelegate(object : PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                permission: PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                val decision = CompletableDeferred<Boolean>()
                sink.onPermissionRequested(
                    BrowserPermissionRequest(tabId, permission.uri, permissionName(permission.permission), decision),
                )
                val result = GeckoResult<Int>()
                decision.invokeOnCompletion { cause ->
                    val allowed = cause == null && runCatching { decision.getCompleted() }.getOrDefault(false)
                    result.complete(
                        if (allowed) PermissionDelegate.ContentPermission.VALUE_ALLOW
                        else PermissionDelegate.ContentPermission.VALUE_DENY,
                    )
                }
                return result
            }
        })
        geckoSession.setScrollDelegate(object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                sink.onScrollChanged(BrowserScrollChanged(tabId, scrollY.coerceAtLeast(0)))
            }
        })
    }

    override fun open() {
        geckoSession.open(runtime)
    }

    override fun attach(host: BrowserSurfaceHost) {
        val view = (host as? GeckoViewBrowserSurfaceHost)?.view ?: return
        if (view.session !== geckoSession) {
            view.session?.let { view.releaseSession() }
            view.setSession(geckoSession)
        }
    }

    override fun load(request: BrowserNavigation) {
        pendingHistoryAddress = request.historyAddress
        geckoSession.loadUri(request.value)
    }

    override fun goBack() = geckoSession.goBack()
    override fun goForward() = geckoSession.goForward()
    override fun reload() = geckoSession.reload()
    override fun stop() = geckoSession.stop()
    override fun clearHistory() = geckoSession.purgeHistory()
    override fun setViewport(widthPx: Int?) {
        if (viewportWidthPx == widthPx) return
        viewportWidthPx = widthPx
        if (lastKnownUrl != null) geckoSession.reload()
    }

    override fun setDesktopSite(enabled: Boolean) {
        geckoSession.settings.setUserAgentMode(
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        )
    }

    override fun saveState(): BrowserSerializedState? = serializedState

    override fun restoreState(state: BrowserSerializedState) {
        GeckoSession.SessionState.fromString(state.value)?.let(geckoSession::restoreState)
        serializedState = state
    }

    override fun close() {
        geckoSession.close()
    }

    private fun permissionName(permission: Int): String = when (permission) {
        PermissionDelegate.PERMISSION_GEOLOCATION -> "location"
        PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "notifications"
        PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "persistent storage"
        PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "autoplay"
        else -> "additional site access"
    }
}

/** Owns the bundled browser DevTools extension, installing it only on demand. */
class GeckoBrowserDeveloperToolsService(
    private val runtime: GeckoRuntime,
) : BrowserDeveloperToolsService {
    override val capabilities: Set<DeveloperToolCapability> = setOf(
        DeveloperToolCapability.CONSOLE,
        DeveloperToolCapability.DOM,
        DeveloperToolCapability.NETWORK,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val ports = linkedSetOf<WebExtension.Port>()
    private val listeners = linkedSetOf<(DeveloperToolEvent) -> Unit>()
    private var enabled = true
    private var opened = false
    private var installed = false
    private var installing = false

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) close()
    }

    override fun open() {
        if (!enabled) return
        opened = true
        installIfNeeded()
        postVisibility(true)
    }

    override fun close() {
        opened = false
        postVisibility(false)
    }

    override fun send(command: DeveloperToolCommand): Boolean = when (command) {
        DeveloperToolCommand.Open -> { open(); true }
        DeveloperToolCommand.Close -> { close(); true }
        is DeveloperToolCommand.ExecuteConsole -> false
    }

    override fun observe(listener: (DeveloperToolEvent) -> Unit) {
        listeners += listener
    }

    private fun installIfNeeded() {
        if (installed || installing || !enabled) return
        installing = true
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/devtools/",
            "android-ide-devtools@android-ide",
        ).accept({ extension ->
            handler.post {
                installing = false
                if (extension == null) return@post
                installed = true
                extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        if (port.name != "android-ide") return
                        ports += port
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onDisconnect(port: WebExtension.Port) {
                                ports -= port
                            }
                        })
                        postVisibility(opened, port)
                    }
                }, "android-ide")
            }
        }, {
            handler.post { installing = false; installed = false }
        })
    }

    private fun postVisibility(open: Boolean, only: WebExtension.Port? = null) {
        val message = JSONObject().put("type", "developer-tools").put("open", open)
        (only?.let(::listOf) ?: ports.toList()).forEach { port -> runCatching { port.postMessage(message) } }
    }
}
