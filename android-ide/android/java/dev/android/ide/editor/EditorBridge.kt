// android-ide/android/java/dev/android/ide/editor/EditorBridge.kt
//
// JavaScript ↔ Kotlin bridge for the Monaco editor WebView.
//
// Setup in EditorPane.kt:
//   val bridge = remember { EditorBridge() }
//   bridge.messageListener = viewModel::onEditorMessage
//   webView.addJavascriptInterface(bridge, "AndroidBridge")
//   webView.loadUrl("file:///android_asset/editor/index.html")
//
// Thread safety:
//   onMessage() is called on a background thread by the WebView JS engine.
//   The messageListener callback must not touch UI directly — post to the main
//   thread via Compose's side-effect APIs or Handler.main if needed.
//   send() always posts to the WebView's UI thread via webView.post().

package dev.android.ide.editor

import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class EditorBridge {
    private val serializer: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "editor-bridge-serializer").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    /**
     * Callback invoked when Monaco sends a message to the native layer.
     * Called on a BACKGROUND thread — do NOT access UI here.
     *
     * Register this in the composable that owns the WebView:
     *   bridge.messageListener = viewModel::onEditorMessage
     */
    var messageListener: ((EditorInbound) -> Unit)? = null

    // ── Inbound (Monaco → Kotlin) ──────────────────────────────────────────

    /**
     * Called by Monaco via window.AndroidBridge.onMessage(jsonString).
     * Runs on a background thread managed by the WebView — do NOT touch UI.
     */
    @JavascriptInterface
    fun onMessage(json: String) {
        val message = EditorInbound.fromJson(json) ?: run {
            return
        }
        messageListener?.invoke(message)
    }

    // ── Outbound (Kotlin → Monaco) ─────────────────────────────────────────

    /**
     * Serialize payloads off the UI thread, in order, then evaluate them on WebView's UI thread.
     */
    fun send(webView: WebView, message: EditorOutbound, onEvaluated: (() -> Unit)? = null) {
        if (closed) return
        try {
            serializer.execute {
                val js = message.toJs()
                if (!closed) webView.post {
                    if (!closed) {
                        if (onEvaluated == null) webView.evaluateJavascript(js, null)
                        else webView.evaluateJavascript(js) { onEvaluated() }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // A queued message can race with disposal of the owning editor surface.
        }
    }

    fun close() {
        closed = true
        serializer.shutdownNow()
        messageListener = null
    }
}
