package dev.android.ide.ui.screen

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.android.ide.browser.BrowserEngineSession
import dev.android.ide.browser.GeckoViewBrowserSurfaceHost
import org.mozilla.geckoview.GeckoView

/**
 * Owns the one Android view host for the currently selected browser session.
 * Session attachment is repeated only when the tab/session identity changes;
 * viewport layout and scroll callback updates are independently diffed.
 */
class BrowserSurfaceBinding(context: Context) : SwipeRefreshLayout(context) {
    val engineView = GeckoView(context)
    val pageScrollY: Int get() = trackedScrollY

    private val host = GeckoViewBrowserSurfaceHost(engineView)
    private var trackedScrollY = 0
    private var activeTabId: String? = null
    private var activeSession: BrowserEngineSession? = null
    private var activeViewportWidth: Int? = UNINITIALIZED_WIDTH
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

    fun bind(
        tabId: String,
        session: BrowserEngineSession,
        viewportWidthPx: Int?,
        registerScrollListener: (String, ((Int) -> Unit)?) -> Unit,
        onScroll: (Int) -> Unit,
    ) {
        if (activeTabId != tabId || activeSession !== session) {
            activeTabId?.takeIf { it != tabId }?.let { previous -> setScrollListener?.invoke(previous, null) }
            activeTabId = tabId
            activeSession = session
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

        if (activeViewportWidth != viewportWidthPx) {
            activeViewportWidth = viewportWidthPx
            val params = engineView.layoutParams
            val width = viewportWidthPx ?: LayoutParams.MATCH_PARENT
            if (params.width != width) {
                params.width = width
                engineView.layoutParams = params
                engineView.requestLayout()
                requestLayout()
            }
            engineView.post {
                if (activeSession === session && activeViewportWidth == viewportWidthPx) {
                    session.setViewport(viewportWidthPx)
                }
            }
        }
        onScroll(trackedScrollY)
    }

    override fun onDetachedFromWindow() {
        scrollListenerOwner?.let { setScrollListener?.invoke(it, null) }
        scrollListenerOwner = null
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

    private companion object {
        // A value that cannot be confused with a valid viewport width; null means device width.
        val UNINITIALIZED_WIDTH: Int? = Int.MIN_VALUE
    }
}
