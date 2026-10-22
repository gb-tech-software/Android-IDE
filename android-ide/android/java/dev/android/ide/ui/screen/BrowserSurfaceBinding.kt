package dev.android.ide.ui.screen

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.android.ide.browser.BrowserEngineSession
import dev.android.ide.browser.GeckoViewBrowserSurfaceHost
import org.mozilla.geckoview.GeckoView

/** A single reusable Android host whose attached GeckoSession follows the selected tab. */
class BrowserSurfaceBinding(context: Context) : FrameLayout(context) {
    val engineView = GeckoView(context)
    val pageScrollY: Int get() = trackedScrollY

    private val host = GeckoViewBrowserSurfaceHost(engineView)
    private var trackedScrollY = 0
    private var activeTabId: String? = null
    private var pendingSession: BrowserEngineSession? = null
    private var presentedTabId: String? = null
    private var presentedSession: BrowserEngineSession? = null
    private var bindingGeneration = 0L
    private var onPresentedTabChanged: ((String?, String?) -> Boolean)? = null
    private var onScrollChanged: ((Int) -> Unit)? = null
    private var scrollListenerOwner: String? = null
    private var setScrollListener: ((String, ((Int) -> Unit)?) -> Unit)? = null
    private val stableScrollListener: (Int) -> Unit = { scrollY ->
        trackedScrollY = scrollY.coerceAtLeast(0)
        onScrollChanged?.invoke(trackedScrollY)
    }

    init {
        // Compose overlays must be composited above the page during tab transitions.
        engineView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        addView(engineView, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(
        tabId: String,
        session: BrowserEngineSession,
        registerScrollListener: (String, ((Int) -> Unit)?) -> Unit,
        onPresentedTabChanged: (String?, String?) -> Boolean,
        onScroll: (Int) -> Unit,
    ) {
        if (activeTabId != tabId) {
            activeTabId?.let { previous -> setScrollListener?.invoke(previous, null) }
            activeTabId = tabId
            trackedScrollY = 0
            bindingGeneration++
        }
        pendingSession = session
        this.onPresentedTabChanged = onPresentedTabChanged
        onScrollChanged = onScroll

        if (scrollListenerOwner != tabId || setScrollListener !== registerScrollListener) {
            scrollListenerOwner?.let { previous -> setScrollListener?.invoke(previous, null) }
            scrollListenerOwner = tabId
            setScrollListener = registerScrollListener
            registerScrollListener(tabId, stableScrollListener)
        }

        attachSelectedSession()
        onScroll(trackedScrollY)
    }

    private fun attachSelectedSession() {
        if (!isAttachedToWindow) return
        val tabId = activeTabId ?: return
        val session = pendingSession ?: return
        if (presentedTabId != tabId || presentedSession !== session) {
            val expectedPreviousTabId = presentedTabId?.takeIf { it != tabId }
            if (onPresentedTabChanged?.invoke(tabId, expectedPreviousTabId) != true) return
            presentedTabId = tabId
            presentedSession = session
        }
        // The adapter releases any old GeckoView association before setting the new session.
        session.attach(host)
    }

    override fun onDetachedFromWindow() {
        val detachedTabId = presentedTabId
        scrollListenerOwner?.let { setScrollListener?.invoke(it, null) }
        scrollListenerOwner = null
        if (engineView.session != null) engineView.releaseSession()
        if (detachedTabId != null) {
            onPresentedTabChanged?.invoke(null, detachedTabId)
            presentedTabId = null
            presentedSession = null
        }
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val generation = bindingGeneration
        attachSelectedSession()
        // AndroidView can update before the view is attached; retry only if this binding
        // still represents the same selected tab when the view reaches the next UI turn.
        post {
            if (isAttachedToWindow && generation == bindingGeneration) attachSelectedSession()
        }
        val tabId = activeTabId
        if (tabId != null && scrollListenerOwner == null) {
            scrollListenerOwner = tabId
            setScrollListener?.invoke(tabId, stableScrollListener)
        }
    }

    fun thumbnailView(): View = engineView
}
