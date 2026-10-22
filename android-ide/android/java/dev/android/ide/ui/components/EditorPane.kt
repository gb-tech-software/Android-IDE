// android-ide/android/java/dev/android/ide/ui/components/EditorPane.kt
//
// Monaco editor WebView + keyboard toolbar + symbol shortcut bar.
//
// Layout: Column
//   Content area (editor) — weight(1f)
//   SymbolBar    (optional, above keyboard toolbar) — horizontally scrollable symbol chips
//   KeyboardToolbar (optional, fixed five-item pages of icon buttons, NO horizontal scroll)
//
// Keyboard toolbar pages:
//
// *Paste reads from the Android ClipboardManager via Kotlin (onPasteFromClipboard)
//  instead of the WebView clipboard API, which is slow and permission-gated.
//
// Crash safety:
//
// Editor focus:
//   isFocusable / isFocusableInTouchMode are set on the WebView so tapping the
//   editor requests native focus, making the soft keyboard appear immediately.

package dev.android.ide.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import dev.android.ide.contracts.LanguageServerFeature
import dev.android.ide.data.model.EditorSettings
import dev.android.ide.editor.EditorBridge
import dev.android.ide.editor.EditorInbound
import dev.android.ide.editor.EditorOutbound
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.viewmodel.model.EditorTab
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

// SetJavaScriptEnabled: Monaco requires JS.
// JavascriptInterface: EditorBridge.onMessage IS annotated @JavascriptInterface; lint produces a
//   false-positive through the generic remember<T>{}.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "WebViewClientOnReceivedSslError")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorPane(
    activeTab: EditorTab?,
    activeTabContent: String? = null,
    isEditorReady: Boolean,
    editorBindRevision: Long = 0L,
    editorCommands: SharedFlow<EditorOutbound>,
    onEditorRendererGone: () -> Unit = {},
    onEditorMessage: (EditorInbound) -> Unit,
    onInsertText: (String) -> Unit,
    onInsertPair: (String, String) -> Unit,
    onInsertSnippet: (String, String) -> Unit,
    onExecuteCommand: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    hasEditorSelection: Boolean = false,
    languageServerFeatures: Set<LanguageServerFeature> = emptySet(),
    showKeyboardToolbar: Boolean = true,
    showSymbolBar: Boolean = true,
    keyboardToolbarOrder: List<String> = EditorSettings.DEFAULT_KEYBOARD_TOOLBAR_ORDER,
    tabCursorPositions: Map<String, Pair<Int, Int>> = emptyMap(),
    tabScrollPositions: Map<String, Int> = emptyMap(),
    bottomAccessory: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors  = LocalIdeColors.current
    val rendererGone by rememberUpdatedState(onEditorRendererGone)
    var keyboardToolbarPage by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(keyboardToolbarOrder) {
        keyboardToolbarPage = 0
    }

    // ── EditorBridge — survives recompositions ─────────────────────────────
    val editorBridge: EditorBridge = remember { EditorBridge() }
    var editorCrashed by remember { mutableStateOf(false) }
    val activeDocumentContent = when {
        activeTab == null -> null
        activeTab.content == null && !activeTab.isBlank -> null
        else -> activeTabContent ?: activeTab.content ?: ""
    }
    val latestActiveTabId by rememberUpdatedState(activeTab?.id)
    val loadGeneration = remember { AtomicInteger(0) }
    var loadedModelDocumentUri by remember { mutableStateOf<String?>(null) }
    var pendingLoadRequestId by remember { mutableStateOf<String?>(null) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var loadRetryNonce by remember { mutableIntStateOf(0) }
    val latestLoadRequestId by rememberUpdatedState(pendingLoadRequestId)
    val latestActiveDocumentUri by rememberUpdatedState(activeTab?.documentUri)
    val modelIsLoading = modelLoadError == null && activeTab != null && !activeTab.isImagePreview && (
        activeDocumentContent == null || (!isEditorReady && !editorCrashed) || loadedModelDocumentUri != activeTab.documentUri
    )

    DisposableEffect(onEditorMessage) {
        val mainHandler = Handler(Looper.getMainLooper())
        editorBridge.messageListener = { message ->
            mainHandler.post {
                when (message) {
                    is EditorInbound.FileLoaded -> {
                        if (message.requestId == latestLoadRequestId && message.path == latestActiveDocumentUri) {
                            loadedModelDocumentUri = message.path
                            modelLoadError = null
                        }
                    }
                    is EditorInbound.FileLoadFailed -> {
                        if (message.requestId == latestLoadRequestId) {
                            modelLoadError = message.reason
                        }
                    }
                    else -> Unit
                }
                onEditorMessage(message)
            }
        }
        onDispose { editorBridge.messageListener = null }
    }

    DisposableEffect(editorBridge) {
        onDispose {
            rendererGone()
            editorBridge.close()
        }
    }

    // ── Monaco WebView — created once, never recreated ─────────────────────
    val editorWebView = remember {
        WebView(context).apply {
            setBackgroundColor(colors.background.toArgb())
            // isFocusable / isFocusableInTouchMode: required so that tapping the
            // editor requests native focus and the soft keyboard appears immediately.
            isFocusable              = true
            isFocusableInTouchMode   = true
            isClickable              = true
            settings.apply {
                javaScriptEnabled                = true
                domStorageEnabled                = true
                allowFileAccessFromFileURLs      = false
                allowUniversalAccessFromFileURLs = false
                cacheMode                         = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
                useWideViewPort                  = false
                loadWithOverviewMode             = false
                setSupportZoom(false)
            }
            isScrollbarFadingEnabled     = false
            isVerticalScrollBarEnabled   = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(editorBridge, "AndroidBridge")
            webChromeClient = WebChromeClient()
            webViewClient   = object : WebViewClient() {
                // API 26+ — return true to prevent app termination when the Monaco
                // render process crashes (e.g. heavy syntax highlighting, large file, OOM).
                // After returning true the WebView object is still valid; loadUrl() starts
                // a new renderer process, restoring the editor without killing the app.
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    editorCrashed = true
                    rendererGone()
                    return true
                }
            }
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            var touchDownTime = 0L
            var touchDownX = 0f
            var touchDownY = 0f
            var touchMoved = false
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Keep vertical and horizontal editor scrolling inside WebView/Monaco.
                        // Do not let a parent drawer, pager, or sidebar consume the gesture.
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        touchDownTime = event.eventTime
                        touchDownX = event.x
                        touchDownY = event.y
                        touchMoved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - touchDownX
                        val dy = event.y - touchDownY
                        if ((dx * dx) + (dy * dy) > touchSlop * touchSlop) {
                            touchMoved = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        // Only a short, stationary tap should request focus and
                        // open the IME. Long-press and drag gestures belong to
                        // Android/WebView text selection and must not be
                        // interrupted by a second focus request.
                        val isTap = !touchMoved &&
                            event.eventTime - touchDownTime < longPressTimeout
                        if (isTap) {
                            v.requestFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                    as InputMethodManager
                            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        touchMoved = false
                    }
                }
                false   // do not consume the event — let WebView handle it
            }
            loadUrl("file:///android_asset/editor/index.html")
        }
    }

    SideEffect {
        editorWebView.setBackgroundColor(colors.background.toArgb())
        editorWebView.isEnabled = !modelIsLoading
        editorWebView.isFocusable = !modelIsLoading
        editorWebView.isFocusableInTouchMode = !modelIsLoading
    }
    LaunchedEffect(modelIsLoading) {
        if (modelIsLoading) {
            editorWebView.clearFocus()
            val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
            inputMethodManager?.hideSoftInputFromWindow(editorWebView.windowToken, 0)
        }
    }

    // A renderer that never emits Ready previously left the workspace behind an
    // indefinite spinner. Give the user the same recovery path as a failed model
    // load instead of requiring an app restart.
    LaunchedEffect(isEditorReady, editorCrashed, loadRetryNonce) {
        if (!isEditorReady && !editorCrashed) {
            delay(8_000)
            if (!isEditorReady && !editorCrashed) {
                modelLoadError = "The editor renderer did not become ready."
            }
        } else if (isEditorReady) {
            if (modelLoadError == "The editor renderer did not become ready.") modelLoadError = null
        }
    }

    // ── Editor view helper — shows editor WebView or crash placeholder ──────
    //
    // When editorCrashed is true, the Monaco renderer has terminated. The app
    // remains alive (onRenderProcessGone returned true), and the WebView object
    // is still valid. Tapping Reload calls loadUrl() to start a fresh renderer.
    val editorView: @Composable (Modifier) -> Unit = { mod ->
        val layoutAwareModifier = mod.onSizeChanged { size: IntSize ->
            if (isEditorReady && size.width > 0 && size.height > 0) {
                editorBridge.send(editorWebView, EditorOutbound.ForceLayout)
            }
        }
        if (editorCrashed) {
            EditorCrashedBox(
                modifier = layoutAwareModifier,
                onReload = {
                    editorCrashed = false
                    editorWebView.post {
                        editorWebView.loadUrl("file:///android_asset/editor/index.html")
                    }
                },
            )
        } else {
            AndroidView(factory = { editorWebView }, update = {}, modifier = layoutAwareModifier)
        }
    }

    // ── Forward ViewModel editor commands to Monaco ─────────────────────────
    LaunchedEffect(Unit) {
        editorCommands.collect { command ->
            editorBridge.send(editorWebView, command)
        }
    }

    // ── Load active file into Monaco when tab or readiness changes ──────────
    val configuration = LocalConfiguration.current
    val layoutKey = "${configuration.screenWidthDp}x${configuration.screenHeightDp}"

    LaunchedEffect(activeTab?.id, activeTab?.documentUri, activeDocumentContent == null, activeTab?.isReadOnly, isEditorReady, editorBindRevision, loadRetryNonce) {
        val content = activeDocumentContent
        if (!isEditorReady || activeTab == null || content == null || activeTab.isImagePreview) {
            loadedModelDocumentUri = null
            pendingLoadRequestId = null
            modelLoadError = null
            return@LaunchedEffect
        }
        loadedModelDocumentUri = null
        modelLoadError = null
        val requestGeneration = loadGeneration.incrementAndGet()
        val requestedTabId = activeTab.id
        val requestedDocumentUri = activeTab.documentUri
        val requestId = UUID.randomUUID().toString()
        pendingLoadRequestId = requestId
        if (isEditorReady && activeTab != null && content != null) {
            editorBridge.send(
                editorWebView,
                EditorOutbound.LoadFile(
                    requestId = requestId,
                    path     = activeTab.documentUri,
                    content  = content,
                    language = activeTab.language,
                    readOnly = activeTab.isReadOnly,
                ),
            )
            // Restore cursor position for this tab (if previously saved).
            val cursor = tabCursorPositions[activeTab.documentUri]
            if (cursor != null && (cursor.first > 1 || cursor.second > 1)) {
                editorBridge.send(editorWebView, EditorOutbound.SetCursorPosition(cursor.first, cursor.second))
            }
            // Restore scroll position last — overrides any scroll caused by revealCursor.
            val scroll = tabScrollPositions[activeTab.documentUri]
            if (scroll != null && scroll > 0) {
                editorBridge.send(editorWebView, EditorOutbound.SetScrollPosition(scroll))
            }
            delay(8_000)
            if (loadGeneration.get() == requestGeneration &&
                latestActiveTabId == requestedTabId &&
                loadedModelDocumentUri != requestedDocumentUri
            ) {
                modelLoadError = "The editor did not finish loading this file."
            }
        }
    }

    // Layout changes (rotation, IME, sidebar width) only require a viewport
    // relayout; they must not reload the model or reparse the file.
    LaunchedEffect(layoutKey, isEditorReady) {
        if (isEditorReady) editorBridge.send(editorWebView, EditorOutbound.ForceLayout)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (activeTab?.isImagePreview == true) {
                ImageDocumentPreview(activeTab.documentUri, activeTab.displayName, Modifier.fillMaxSize())
            } else {
                editorView(Modifier.fillMaxSize())
            }
            if ((modelIsLoading || modelLoadError != null) && activeTab != null) {
                Box(
                    Modifier.fillMaxSize()
                        .background(colors.background)
                        .pointerInput(activeTab.id) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (modelLoadError == null) CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text(modelLoadError ?: "Loading ${activeTab.displayName}…", color = if (modelLoadError == null) colors.textSecondary else MaterialTheme.colorScheme.error)
                        if (modelLoadError != null) {
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = {
                                if (!isEditorReady) {
                                    onEditorRendererGone()
                                    editorCrashed = false
                                    modelLoadError = null
                                    loadRetryNonce++
                                    editorWebView.stopLoading()
                                    editorWebView.loadUrl("file:///android_asset/editor/index.html")
                                } else {
                                    loadRetryNonce++
                                }
                            }) { Text("Retry") }
                        }
                    }
                }
            }
        }

        // Compact workspace controls belong to the same bottom stack as the
        // editor symbol and keyboard bars, never as floating overlays.
        bottomAccessory?.invoke()

        // Symbol shortcut bar — shown above keyboard toolbar when a tab is active
        if (activeTab != null && activeTab.content != null && !modelIsLoading && showSymbolBar) {
            HorizontalDivider(thickness = 1.dp, color = colors.separator)
            val cursor = tabCursorPositions[activeTab.documentUri] ?: (1 to 1)
            val content = activeTabContent ?: activeTab.content.orEmpty()
            val cursorLine = remember(activeTab.id, content, cursor.first) {
                content.lineSequence().drop((cursor.first - 1).coerceAtLeast(0)).firstOrNull().orEmpty()
            }
            val cursorOffset = (cursor.second - 1).coerceIn(0, cursorLine.length)
            val shortcuts = editorSymbolBarContent(
                languageId = activeTab.language,
                linePrefix = cursorLine.take(cursorOffset),
                lineSuffix = cursorLine.drop(cursorOffset),
                hasSelection = hasEditorSelection,
            )
            SymbolBar(
                shortcuts = shortcuts,
                hasSelection = hasEditorSelection,
                onInsertSymbol = onInsertText,
                onInsertPair = onInsertPair,
                onInsertSnippet = onInsertSnippet,
                onShowCompletions = { onExecuteCommand("editor.action.triggerSuggest") },
                languageIntelligenceAvailable = LanguageServerFeature.COMPLETION in languageServerFeatures,
            )
        }

        // Keyboard toolbar — fixed five-item pages, no horizontal scrolling
        if (activeTab != null && activeTab.content != null && !modelIsLoading && showKeyboardToolbar) {
            HorizontalDivider(thickness = 1.dp, color = colors.separator)
            KeyboardToolbar(
                onExecuteCommand     = onExecuteCommand,
                onPasteFromClipboard = onPasteFromClipboard,
                hasEditorSelection   = hasEditorSelection,
                languageServerFeatures = languageServerFeatures,
                actionOrder          = keyboardToolbarOrder,
                selectedPage          = keyboardToolbarPage,
                onSelectedPageChange  = { keyboardToolbarPage = it },
                onToggleKeyboard     = { shouldShow ->
                    editorWebView.requestFocus()
                    val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
                    if (shouldShow) {
                        inputMethodManager?.showSoftInput(editorWebView, InputMethodManager.SHOW_IMPLICIT)
                    } else {
                        inputMethodManager?.hideSoftInputFromWindow(editorWebView.windowToken, 0)
                    }
                },
            )
        }
    }
}

@Composable
private fun ImageDocumentPreview(documentUri: String, displayName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, documentUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(documentUri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
        }
    }
    Box(modifier.background(LocalIdeColors.current.background), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Image preview unavailable", color = LocalIdeColors.current.textSecondary)
                Text(displayName, color = LocalIdeColors.current.textDisabled, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Editor crash placeholder ──────────────────────────────────────────────────
//
// Shown in place of the Monaco WebView when its renderer process has terminated.
// The Reload button calls loadUrl() on the existing WebView object to start a
// fresh renderer — the app remains alive throughout.

@Composable
private fun EditorCrashedBox(modifier: Modifier = Modifier, onReload: () -> Unit) {
    val colors = LocalIdeColors.current
    Column(
        modifier         = modifier.background(colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text  = "Editor unavailable.\nThe render process terminated.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textDisabled,
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onReload) {
            Icon(
                imageVector        = Icons.Default.Refresh,
                contentDescription = null,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Reload Editor")
        }
    }
}

// ── Symbol shortcut bar ───────────────────────────────────────────────────────

@Composable
private fun SymbolBar(
    shortcuts: EditorSymbolBarContent,
    hasSelection: Boolean,
    onInsertSymbol: (String) -> Unit,
    onInsertPair: (String, String) -> Unit,
    onInsertSnippet: (String, String) -> Unit,
    onShowCompletions: () -> Unit,
    languageIntelligenceAvailable: Boolean,
) {
    val colors = LocalIdeColors.current
    Row(
        modifier = Modifier
            .height(48.dp)
            .fillMaxWidth()
            .background(colors.surface)
            .horizontalScroll(rememberScrollState())
            .semantics {
                contentDescription = "${shortcuts.languageLabel} ${shortcuts.contextLabel} editor shortcuts"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        shortcuts.pairs.forEach { pair ->
            TextButton(
                onClick        = { onInsertPair(pair.opening, pair.closing) },
                modifier       = Modifier.height(44.dp).widthIn(min = 44.dp).semantics {
                    contentDescription = if (hasSelection) {
                        "Wrap selection with ${pair.opening} and ${pair.closing}"
                    } else {
                        "Insert ${pair.opening} and ${pair.closing} with cursor between"
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text  = pair.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        shortcuts.punctuation.forEach { symbol ->
            TextButton(
                onClick = { onInsertSymbol(symbol) },
                modifier = Modifier.height(44.dp).widthIn(min = 40.dp).semantics {
                    contentDescription = "Insert $symbol"
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(symbol, style = MaterialTheme.typography.titleMedium, color = colors.primary)
            }
        }
        if (shortcuts.snippets.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            shortcuts.snippets.forEach { template ->
                TextButton(
                    onClick = { onInsertSnippet(template.trigger, template.body) },
                    modifier = Modifier.height(44.dp).widthIn(min = 44.dp).semantics {
                        contentDescription = "${shortcuts.languageLabel} ${template.label} quick suggestion"
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(template.label, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }
        }
        if (languageIntelligenceAvailable) {
            IconButton(
                onClick = onShowCompletions,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "Show language-server suggestions for ${shortcuts.languageLabel} at the cursor"
                },
            ) {
                EditorToolbarGlyph(
                    actionId = "triggerSuggest",
                    tint = colors.secondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

// ── Keyboard toolbar — one ordered list, rendered five actions per page ────────

@Composable
private fun KeyboardToolbar(
    onExecuteCommand: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    hasEditorSelection: Boolean,
    languageServerFeatures: Set<LanguageServerFeature>,
    actionOrder: List<String>,
    selectedPage: Int,
    onSelectedPageChange: (Int) -> Unit,
    onToggleKeyboard: (Boolean) -> Unit,
) {
    val colors = LocalIdeColors.current
    val actionCatalog = EDITOR_TOOLBAR_ACTIONS.associateBy { it.id }
    val orderedActions = normalizeEditorToolbarOrder(actionOrder)
        .mapNotNull(actionCatalog::get)
        .filter { it.requiredServerFeature == null || it.requiredServerFeature in languageServerFeatures }
    val pages = orderedActions.chunked(KEYBOARD_TOOLBAR_PAGE_SIZE)
    val pageIndex = selectedPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
    val density = LocalDensity.current
    val keyboardShowing = WindowInsets.ime.getBottom(density) > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        if (pages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (normalizeEditorToolbarOrder(actionOrder).any { actionCatalog[it]?.requiredServerFeature != null })
                        "Language tools are unavailable for this project"
                    else "No toolbar actions enabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onSelectedPageChange((pageIndex - 1).coerceAtLeast(0)) },
                    enabled = pageIndex > 0,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "Previous toolbar page" },
                ) {
                    EditorToolbarGlyph(
                        actionId = "toolbarPagePrevious",
                        tint = if (pageIndex > 0) colors.textSecondary else colors.textDisabled,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    pages.getOrElse(pageIndex) { emptyList() }.forEach { action ->
                        val disabledReason = when {
                            action.requiresSelection && !hasEditorSelection -> "Select text first"
                            else -> null
                        }
                        key(action.id) {
                            ToolbarIconButton(
                                action = action,
                                keyboardShowing = keyboardShowing,
                                enabled = disabledReason == null,
                                disabledReason = disabledReason,
                                onExecuteAction = { actionId ->
                                    editorToolbarCommandId(actionId)?.let(onExecuteCommand)
                                },
                                onPaste = onPasteFromClipboard,
                                onCustomClick = if (action.isKeyboardToggle) {
                                    { onToggleKeyboard(!keyboardShowing) }
                                } else null,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { onSelectedPageChange((pageIndex + 1).coerceAtMost(pages.lastIndex)) },
                    enabled = pageIndex < pages.lastIndex,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "Next toolbar page" },
                ) {
                    EditorToolbarGlyph(
                        actionId = "toolbarPageNext",
                        tint = if (pageIndex < pages.lastIndex) colors.textSecondary else colors.textDisabled,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarIconButton(
    action: EditorToolbarAction,
    keyboardShowing: Boolean,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onExecuteAction: (String) -> Unit,
    onPaste: () -> Unit,
    onCustomClick: (() -> Unit)? = null,
) {
    val colors = LocalIdeColors.current
    val tooltipState = rememberTooltipState()
    val currentAction by rememberUpdatedState(action)
    val currentExecuteAction by rememberUpdatedState(onExecuteAction)
    val currentPaste by rememberUpdatedState(onPaste)
    val currentCustomClick by rememberUpdatedState(onCustomClick)
    var suppressNextClick by remember(action.id) { mutableStateOf(false) }
    val description = buildList {
        add(action.label)
        add(action.description)
        disabledReason?.let(::add)
    }.joinToString(". ")

    fun performAction() {
        when {
            currentCustomClick != null -> currentCustomClick?.invoke()
            currentAction.isPaste -> currentPaste()
            else -> currentExecuteAction(currentAction.id)
        }
    }

    val buttonModifier = Modifier
        .size(44.dp)
        .semantics { contentDescription = description }
    if (action.repeatable) {
        IconButton(
            onClick = {
                if (suppressNextClick) suppressNextClick = false else performAction()
            },
            enabled = enabled,
            modifier = buttonModifier.pointerInput(action.id, enabled, action.repeatable) {
                if (!enabled || !currentAction.repeatable) return@pointerInput
                kotlinx.coroutines.coroutineScope {
                    val gestureScope = this
                    awaitPointerEventScope {
                        while (true) {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var repeated = false
                            val repeatJob = gestureScope.launch {
                                delay(ViewConfiguration.getLongPressTimeout().toLong())
                                repeated = true
                                suppressNextClick = true
                                performAction()
                                while (true) {
                                    delay(70L)
                                    performAction()
                                }
                            }
                            val released = waitForUpOrCancellation(pass = PointerEventPass.Initial) != null
                            repeatJob.cancel()
                            if (repeated) suppressNextClick = released
                        }
                    }
                }
            },
        ) {
            EditorToolbarGlyph(
                actionId = action.id,
                tint = if (enabled) colors.textSecondary else colors.textDisabled,
                modifier = Modifier.size(24.dp),
                keyboardShowing = action.isKeyboardToggle && keyboardShowing,
            )
        }
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(description, style = MaterialTheme.typography.labelSmall)
                }
            },
            state = tooltipState,
        ) {
            IconButton(
                onClick = { performAction() },
                enabled = enabled,
                modifier = buttonModifier,
            ) {
                EditorToolbarGlyph(
                    actionId = action.id,
                    tint = if (enabled) colors.textSecondary else colors.textDisabled,
                    modifier = Modifier.size(24.dp),
                    keyboardShowing = action.isKeyboardToggle && keyboardShowing,
                )
            }
        }
    }
}
