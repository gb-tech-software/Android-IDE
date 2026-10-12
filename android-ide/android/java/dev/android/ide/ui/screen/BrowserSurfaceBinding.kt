package dev.android.ide.ui.screen

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.android.ide.browser.BrowserEngineSession
import dev.android.ide.browser.GeckoViewBrowserSurfaceHost
import org.mozilla.geckoview.GeckoView

/**
 * Owns the one Android view host for the currently selected browser session.
 * Session attachment is repeated only when the tab/session identity changes;
 * scroll callback updates are independently diffed.
 */
class BrowserSurfaceBinding(context: Context) : SwipeRefreshLayout(context) {
    val engineView = GeckoView(context)
    val pageScrollY: Int get() = trackedScrollY
    var devToolsInteractionActive: Boolean = false

    private val host = GeckoViewBrowserSurfaceHost(engineView)
    private var trackedScrollY = 0
    private var activeTabId: String? = null
    private var activeSession: BrowserEngineSession? = null
    private var multiTouchGestureActive = false
    private var onScrollChanged: ((Int) -> Unit)? = null
    private var scrollListenerOwner: String? = null
    private var setScrollListener: ((String, ((Int) -> Unit)?) -> Unit)? = null
    private val stableScrollListener: (Int) -> Unit = { scrollY ->
        trackedScrollY = scrollY.coerceAtLeast(0)
        onScrollChanged?.invoke(trackedScrollY)
    }

    init {
        addView(engineView, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnChildScrollUpCallback { _, _ -> trackedScrollY > 0 || engineView.canScrollVertically(-1) }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (devToolsInteractionActive) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) multiTouchGestureActive = false
        if (ev.pointerCount > 1) multiTouchGestureActive = true

        val intercepted = if (multiTouchGestureActive) false else super.onInterceptTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            multiTouchGestureActive = false
        }
        return intercepted
    }

    fun bind(
        tabId: String,
        session: BrowserEngineSession,
        registerScrollListener: (String, ((Int) -> Unit)?) -> Unit,
        onScroll: (Int) -> Unit,
    ) {
        val sessionChanged = activeSession !== session
        if (activeTabId != tabId || sessionChanged) {
            activeTabId?.takeIf { it != tabId }?.let { previous -> setScrollListener?.invoke(previous, null) }
            if (engineView.session != null) engineView.releaseSession()
            activeTabId = tabId
            activeSession = session
            trackedScrollY = 0
            onScrollChanged = onScroll
            session.attach(host)
        } else {
            onScrollChanged = onScroll
        }

        if (scrollListenerOwner != tabId || setScrollListener !== registerScrollListener) {
            scrollListenerOwner?.let { previous -> setScrollListener?.invoke(previous, null) }
            scrollListenerOwner = tabId
            setScrollListener = registerScrollListener
            registerScrollListener(tabId, stableScrollListener)
        }

        onScroll(trackedScrollY)
    }

    override fun onDetachedFromWindow() {
        scrollListenerOwner?.let { setScrollListener?.invoke(it, null) }
        scrollListenerOwner = null
        // A detached host must not retain the previously visible tab's GeckoView
        // attachment. The selected session is reattached in onAttachedToWindow;
        // releasing here prevents stale page pixels during host reuse or branch
        // changes between the content view and an inline navigation-error page.
        if (engineView.session != null) engineView.releaseSession()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activeSession?.attach(host)
        val tabId = activeTabId
        if (tabId != null && scrollListenerOwner == null) {
            scrollListenerOwner = tabId
            setScrollListener?.invoke(tabId, stableScrollListener)
        }
    }

    fun thumbnailView(): View = engineView

}
