package dev.android.ide.ui.screen

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.android.ide.browser.BrowserEngineSession
import dev.android.ide.browser.GeckoViewBrowserSurfaceHost
import org.mozilla.geckoview.GeckoView
import kotlin.math.min
import kotlin.math.roundToInt

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
    private var requestedViewportWidthPx: Int? = null
    private var activeViewBackend = GeckoView.BACKEND_SURFACE_VIEW
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
        viewportWidthPx: Int?,
        registerScrollListener: (String, ((Int) -> Unit)?) -> Unit,
        onScroll: (Int) -> Unit,
    ) {
        val sessionChanged = activeSession !== session
        if (activeTabId != tabId || sessionChanged) {
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

        val viewportChanged = activeViewportWidth != viewportWidthPx
        activeViewportWidth = viewportWidthPx
        requestedViewportWidthPx = viewportWidthPx
        val desiredBackend = if (viewportWidthPx == null) {
            GeckoView.BACKEND_SURFACE_VIEW
        } else {
            // SurfaceView cannot be transformed; use TextureView only while the
            // virtual viewport needs to be scaled into the fixed-size host.
            GeckoView.BACKEND_TEXTURE_VIEW
        }
        if (activeViewBackend != desiredBackend) {
            engineView.setViewBackend(desiredBackend)
            activeViewBackend = desiredBackend
        }
        applyViewportPresentation()

        if (viewportChanged || sessionChanged) {
            engineView.post {
                if (activeSession === session && activeViewportWidth == viewportWidthPx) {
                    session.setViewport(viewportWidthPx)
                }
            }
        }
        onScroll(trackedScrollY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyViewportPresentation()
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

    /**
     * Lay GeckoView out at the requested virtual width, while keeping the
     * SwipeRefreshLayout at device size. The virtual height follows the host's
     * aspect ratio so a single uniform scale fits the complete preview.
     */
    private fun applyViewportPresentation() {
        val viewportWidth = requestedViewportWidthPx
        val viewportHeight = if (viewportWidth != null && width > 0 && height > 0) {
            (height.toFloat() * viewportWidth / width).roundToInt().coerceAtLeast(1)
        } else {
            null
        }

        val params = engineView.layoutParams ?: ViewGroup.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        val layoutWidth = viewportWidth ?: LayoutParams.MATCH_PARENT
        val layoutHeight = viewportHeight ?: LayoutParams.MATCH_PARENT
        if (params.width != layoutWidth || params.height != layoutHeight) {
            params.width = layoutWidth
            params.height = layoutHeight
            engineView.layoutParams = params
            engineView.requestLayout()
            requestLayout()
        }

        engineView.pivotX = 0f
        engineView.pivotY = 0f
        if (viewportWidth == null || viewportHeight == null || width <= 0 || height <= 0) {
            engineView.scaleX = 1f
            engineView.scaleY = 1f
            engineView.translationX = 0f
            engineView.translationY = 0f
            return
        }

        val scale = min(width.toFloat() / viewportWidth, height.toFloat() / viewportHeight)
        engineView.scaleX = scale
        engineView.scaleY = scale
        engineView.translationX = (width - viewportWidth * scale) / 2f
        engineView.translationY = (height - viewportHeight * scale) / 2f
    }

    private companion object {
        // A value that cannot be confused with a valid viewport width; null means device width.
        val UNINITIALIZED_WIDTH: Int? = Int.MIN_VALUE
    }
}
