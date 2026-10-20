package dev.android.ide.ui.screen

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.android.ide.browser.BrowserEngineSession
import dev.android.ide.browser.GeckoViewBrowserSurfaceHost
import org.mozilla.geckoview.GeckoView

/** Owns a disposable Android host for one selected browser session. */
class BrowserSurfaceBinding(context: Context) : FrameLayout(context) {
    val engineView = GeckoView(context)
    val pageScrollY: Int get() = trackedScrollY
    private val host = GeckoViewBrowserSurfaceHost(engineView)
    private var trackedScrollY = 0
    private var activeTabId: String? = null
    private var pendingSession: BrowserEngineSession? = null
    private var onScrollChanged: ((Int) -> Unit)? = null
    private var scrollListenerOwner: String? = null
    private var setScrollListener: ((String, ((Int) -> Unit)?) -> Unit)? = null
    private val stableScrollListener: (Int) -> Unit = { scrollY ->
        trackedScrollY = scrollY.coerceAtLeast(0)
        onScrollChanged?.invoke(trackedScrollY)
    }

    init {
        // Compose drawers and floating DevTools controls must be composited above
        // the page. GeckoView defaults to SurfaceView, which is a separate layer
        // and can paint over or blank Compose overlays while they animate.
        engineView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        addView(engineView, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(
        tabId: String,
        session: BrowserEngineSession,
        registerScrollListener: (String, ((Int) -> Unit)?) -> Unit,
        onScroll: (Int) -> Unit,
    ) {
        if (activeTabId != tabId) {
            activeTabId?.let { previous -> setScrollListener?.invoke(previous, null) }
            trackedScrollY = 0
        }
        activeTabId = tabId
        pendingSession = session
        onScrollChanged = onScroll

        if (scrollListenerOwner != tabId || setScrollListener !== registerScrollListener) {
            scrollListenerOwner?.let { previous -> setScrollListener?.invoke(previous, null) }
            scrollListenerOwner = tabId
            setScrollListener = registerScrollListener
            registerScrollListener(tabId, stableScrollListener)
        }

        attachSessionWhenReady()
        onScroll(trackedScrollY)
    }

    private fun attachSessionWhenReady() {
        if (!isAttachedToWindow) return
        pendingSession?.attach(host)
    }

    override fun onDetachedFromWindow() {
        scrollListenerOwner?.let { setScrollListener?.invoke(it, null) }
        scrollListenerOwner = null
        // Detach only the view host. The tab-owned GeckoSession remains open so
        // history, page state, and navigation survive surface recreation.
        if (engineView.session != null) engineView.releaseSession()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // AndroidView may deliver update before the GeckoView is attached. Bind
        // after attachment and once more on the next UI turn so a fresh host is
        // never left with only the browser chrome rendered.
        attachSessionWhenReady()
        post { attachSessionWhenReady() }
        val tabId = activeTabId
        if (tabId != null && scrollListenerOwner == null) {
            scrollListenerOwner = tabId
            setScrollListener?.invoke(tabId, stableScrollListener)
        }
    }

    fun thumbnailView(): View = engineView

}
