package dev.android.ide.ui.screen

import android.Manifest
import android.os.Build
import android.widget.Toast
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.android.ide.app.AppShellState
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.contracts.RuntimeAvailability
import dev.android.ide.contracts.SessionAvailability
import dev.android.ide.data.model.TerminalSettings
import dev.android.ide.data.ThemeResolver
import dev.android.ide.data.ResolvedTheme
import dev.android.ide.ui.components.EditorToolbarGlyph

@Composable
fun TerminalSurface(
    state: AppShellState,
    viewModel: AppShellViewModel,
    terminalSettings: TerminalSettings,
    onOpenNavigation: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onSettingsChanged: (TerminalSettings) -> Unit = {},
    floating: Boolean = false,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    var escapeLatched by remember { mutableStateOf(false) }
    var terminalTextSize by remember { mutableStateOf(terminalSettings.textSize) }
    var appearanceMenuOpen by remember { mutableStateOf(false) }
    var initialSessionRequested by remember { mutableStateOf(false) }
    val sessions = state.terminalSessions
    val selected = sessions.firstOrNull { it.id == state.selectedTerminalSessionId && it.availability == SessionAvailability.AVAILABLE }
    var activeTerminalView by remember { mutableStateOf<TerminalView?>(null) }
    val runtimeAvailable = state.runtimeCapabilities?.availability == RuntimeAvailability.AVAILABLE
    val context = LocalContext.current
    val themeResolver = remember { ThemeResolver(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val terminalTheme = when (themeResolver.getTerminalTheme(terminalSettings.theme)) {
        ResolvedTheme.DARK -> TerminalTheme.DARK
        ResolvedTheme.LIGHT -> TerminalTheme.LIGHT
    }
    val blockingFeedback = state.terminalFeedback?.takeIf(::requiresTerminalDialog)
    LaunchedEffect(terminalSettings.textSize) { terminalTextSize = terminalSettings.textSize }

    fun clearLatchedKeys() {
        ctrlLatched = false
        altLatched = false
        escapeLatched = false
    }

    LaunchedEffect(state.terminalFeedback?.message) {
        state.terminalFeedback?.takeUnless(::requiresTerminalDialog)?.let { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
    }

    LaunchedEffect(state.terminalFeedback) {
        if (state.terminalFeedback?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) {
            delay(2_500)
            viewModel.clearTerminalFeedback()
        }
    }

    LaunchedEffect(runtimeAvailable) {
        if (runtimeAvailable && !initialSessionRequested && sessions.none { it.availability == SessionAvailability.AVAILABLE }) {
            initialSessionRequested = true
            viewModel.createTerminalSession(workingDirectory = null)
        }
    }
    LaunchedEffect(runtimeAvailable) {
        if (runtimeAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (!floating) IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, "Open sidebar") }
            Column(Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.titleMedium)
            }
            if (floating) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close floating terminal") }
            Box {
                IconButton(onClick = { appearanceMenuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Terminal menu")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = appearanceMenuOpen,
                    onDismissRequest = { appearanceMenuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Terminal settings") },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = { appearanceMenuOpen = false; onOpenSettings() },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            onClick = {
                            terminalTextSize = (terminalTextSize - 1).coerceAtLeast(8)
                            onSettingsChanged(terminalSettings.copy(textSize = terminalTextSize))
                            },
                            enabled = terminalTextSize > 8,
                        ) { Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out") }
                        Text("Zoom", style = MaterialTheme.typography.bodyMedium)
                        IconButton(
                            onClick = {
                            terminalTextSize = (terminalTextSize + 1).coerceAtMost(32)
                            onSettingsChanged(terminalSettings.copy(textSize = terminalTextSize))
                            },
                            enabled = terminalTextSize < 32,
                        ) { Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in") }
                    }
                }
            }
        }
        state.terminalProgress?.let { progress ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (selected == null || selected.availability != dev.android.ide.contracts.SessionAvailability.AVAILABLE) {
            Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No active terminal session", style = MaterialTheme.typography.titleMedium)
                    Text(if (runtimeAvailable) "Start a fresh command-line session from the sidebar." else "The terminal runtime is not available yet.")
                }
            }
        } else {
            DisposableEffect(selected.id) {
                onDispose {
                    viewModel.unbindTerminalView(selected.id)
                    activeTerminalView = null
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                    TerminalView(context, null).also { terminalView ->
                        activeTerminalView = terminalView
                        terminalView.setBackgroundColor(terminalTheme.background)
                        terminalView.setTextSize(terminalTextSize)
                        terminalView.isFocusable = true
                        terminalView.isFocusableInTouchMode = true
                        terminalView.setTerminalViewClient(
                            IdeTerminalViewClient(
                                readControl = { ctrlLatched },
                                readAlt = { altLatched },
                                readEscape = { escapeLatched },
                                clearModifiers = ::clearLatchedKeys,
                                handleNavigationKey = { keyCode, keyMod -> terminalView.handleKeyCode(keyCode, keyMod) },
                                onScaleChanged = {
                                    terminalView.post {
                                        terminalTextSize = terminalView.textSize
                                        onSettingsChanged(terminalSettings.copy(textSize = terminalView.textSize))
                                    }
                                },
                            ),
                        )
                        var downAt = 0L
                        var downX = 0f
                        var downY = 0f
                        var moved = false
                        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                        terminalView.setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> { downAt = event.eventTime; downX = event.x; downY = event.y; moved = false }
                                MotionEvent.ACTION_MOVE -> {
                                    val dx = event.x - downX
                                    val dy = event.y - downY
                                    moved = moved || dx * dx + dy * dy > slop * slop
                                }
                                MotionEvent.ACTION_UP -> if (!moved && event.eventTime - downAt < android.view.ViewConfiguration.getLongPressTimeout()) {
                                    view.requestFocusFromTouch()
                                    (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                                        ?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                }
                                MotionEvent.ACTION_CANCEL -> moved = false
                            }
                            // Termux TerminalView must receive the event for scrolling,
                            // cursor, selection, and PTY input handling.
                            false
                        }
                        viewModel.bindTerminalView(selected.id) { terminalView.postInvalidateOnAnimation() }
                        viewModel.terminalSession(selected.id)?.let(terminalView::attachSession)
                    }
                },
                update = { view ->
                    activeTerminalView = view
                    view.setBackgroundColor(terminalTheme.background)
                    if (view.textSize != terminalTextSize) view.setTextSize(terminalTextSize)
                    view.setTerminalColors(terminalTheme.background, terminalTheme.foreground, terminalTheme.cursor)
                    viewModel.bindTerminalView(selected.id) { view.postInvalidateOnAnimation() }
                    val live = viewModel.terminalSession(selected.id)
                    if (live != null && view.mTermSession !== live) view.attachSession(live)
                },
            )
            if (terminalSettings.shortcutToolbarEnabled) TerminalShortcutToolbar(
                ctrlLatched = ctrlLatched,
                altLatched = altLatched,
                escapeLatched = escapeLatched,
                onCtrlToggle = { ctrlLatched = !ctrlLatched },
                onAltToggle = { altLatched = !altLatched },
                onEscapeToggle = { escapeLatched = !escapeLatched },
                onClearModifiers = ::clearLatchedKeys,
                onSend = { key ->
                    if (key.keyCode != null) {
                        activeTerminalView?.handleKeyCode(key.keyCode, terminalKeyModifiers(ctrlLatched, altLatched, escapeLatched))
                    } else {
                        viewModel.sendTerminalInput(encodeTerminalShortcut(key, ctrlLatched, altLatched, escapeLatched))
                    }
                },
            )
        }
    }
    blockingFeedback?.let { report ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Terminal could not start") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(report.message)
                    report.recoveryHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::clearTerminalFeedback) { Text("OK") }
            },
        )
    }
    }
}

/**
 * Terminal-owned presentation host for use above another domain surface.
 * The PTY, selected session, output, settings, and redraw binding remain
 * exactly the same as [TerminalSurface]; only the presentation frame changes.
 */
@Composable
fun TerminalFloatingPanel(
    state: AppShellState,
    viewModel: AppShellViewModel,
    terminalSettings: TerminalSettings,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        TerminalSurface(
            state = state,
            viewModel = viewModel,
            terminalSettings = terminalSettings,
            onOpenNavigation = {},
            onOpenSettings = onOpenSettings,
            onSettingsChanged = viewModel::setTerminalSettings,
            floating = true,
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun requiresTerminalDialog(report: dev.android.ide.contracts.OperationReport): Boolean =
    report.message.contains("clipboard", ignoreCase = true).not() &&
        (report.outcome == dev.android.ide.contracts.OperationOutcome.BLOCKED ||
            report.outcome == dev.android.ide.contracts.OperationOutcome.FAILED)

private enum class TerminalTheme(val background: Int, val foreground: Int, val cursor: Int) {
    DARK(0xFF101216.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
    LIGHT(0xFFF4F5F7.toInt(), 0xFF202124.toInt(), 0xFF202124.toInt()),
}

private data class TerminalShortcut(
    val label: String,
    val sequence: String,
    val forceCtrl: Boolean = false,
    val keyCode: Int? = null,
    val repeatable: Boolean = false,
    val glyphActionId: String? = null,
)

private val TERMINAL_NAVIGATION_SHORTCUTS = listOf(
    TerminalShortcut("Esc key", "\u001B"),
    TerminalShortcut("Tab", "\t"),
    TerminalShortcut("Enter", "\r"),
    TerminalShortcut("Backspace", "\u007F", repeatable = true),
    TerminalShortcut("Up", "", keyCode = KeyEvent.KEYCODE_DPAD_UP, repeatable = true, glyphActionId = "cursorUp"),
    TerminalShortcut("Down", "", keyCode = KeyEvent.KEYCODE_DPAD_DOWN, repeatable = true, glyphActionId = "cursorDown"),
    TerminalShortcut("Left", "", keyCode = KeyEvent.KEYCODE_DPAD_LEFT, repeatable = true, glyphActionId = "cursorLeft"),
    TerminalShortcut("Right", "", keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, repeatable = true, glyphActionId = "cursorRight"),
    TerminalShortcut("Home", "", keyCode = KeyEvent.KEYCODE_MOVE_HOME, repeatable = true),
    TerminalShortcut("End", "", keyCode = KeyEvent.KEYCODE_MOVE_END, repeatable = true),
    TerminalShortcut("Ins", "", keyCode = KeyEvent.KEYCODE_INSERT),
    TerminalShortcut("Del", "", keyCode = KeyEvent.KEYCODE_FORWARD_DEL, repeatable = true),
    TerminalShortcut("PgUp", "", keyCode = KeyEvent.KEYCODE_PAGE_UP, repeatable = true),
    TerminalShortcut("PgDn", "", keyCode = KeyEvent.KEYCODE_PAGE_DOWN, repeatable = true),
)

private val TERMINAL_CONTROL_SHORTCUTS = listOf(
    TerminalShortcut("Ctrl+C", "c", forceCtrl = true),
    TerminalShortcut("Ctrl+D", "d", forceCtrl = true),
    TerminalShortcut("Ctrl+Z", "z", forceCtrl = true),
    TerminalShortcut("Ctrl+L", "l", forceCtrl = true),
)

@Composable
private fun TerminalShortcutToolbar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    escapeLatched: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onEscapeToggle: () -> Unit,
    onClearModifiers: () -> Unit,
    onSend: (TerminalShortcut) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalShortcutButton("Ctrl", ctrlLatched, onCtrlToggle)
            TerminalShortcutButton("Alt", altLatched, onAltToggle)
            TerminalShortcutButton("Esc mod", escapeLatched, onEscapeToggle)
            TERMINAL_CONTROL_SHORTCUTS.forEach { shortcut ->
                TerminalShortcutButton(
                    label = shortcut.label,
                    selected = false,
                    onClick = { onSend(shortcut); onClearModifiers() },
                    repeatable = shortcut.repeatable,
                    onRepeat = { onSend(shortcut) },
                    onRepeatEnd = onClearModifiers,
                    glyphActionId = shortcut.glyphActionId,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TERMINAL_NAVIGATION_SHORTCUTS.forEach { shortcut ->
                TerminalShortcutButton(
                    label = shortcut.label,
                    onClick = { onSend(shortcut); onClearModifiers() },
                    repeatable = shortcut.repeatable,
                    onRepeat = { onSend(shortcut) },
                    onRepeatEnd = onClearModifiers,
                    glyphActionId = shortcut.glyphActionId,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TerminalShortcutButton(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    repeatable: Boolean = false,
    onRepeat: (() -> Unit)? = null,
    onRepeatEnd: (() -> Unit)? = null,
    glyphActionId: String? = null,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    val currentOnRepeatEnd by rememberUpdatedState(onRepeatEnd)
    var suppressNextClick by remember(label) { mutableStateOf(false) }
    val modifier = Modifier
        .height(38.dp)
        .widthIn(min = if (glyphActionId != null) 42.dp else 38.dp)
        .pointerInput(label, repeatable) {
            if (!repeatable) return@pointerInput
            kotlinx.coroutines.coroutineScope {
                val gestureScope = this
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var repeated = false
                        val repeatJob = gestureScope.launch {
                            delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
                            repeated = true
                            suppressNextClick = true
                            (currentOnRepeat ?: currentOnClick).invoke()
                            while (true) {
                                delay(70L)
                                (currentOnRepeat ?: currentOnClick).invoke()
                            }
                        }
                        val released = waitForUpOrCancellation(pass = PointerEventPass.Initial) != null
                        repeatJob.cancel()
                        if (repeated) {
                            currentOnRepeatEnd?.invoke()
                            suppressNextClick = released
                        }
                    }
                }
            }
        }
        .combinedClickable(
            onClick = { if (suppressNextClick) suppressNextClick = false else currentOnClick() },
            onLongClick = null,
        )
        .semantics {
            this.selected = selected
            contentDescription = label
        }
    Box(
        modifier = modifier
            .padding(vertical = 1.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (glyphActionId != null) {
            EditorToolbarGlyph(
                actionId = glyphActionId,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun encodeTerminalShortcut(
    shortcut: TerminalShortcut,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    escapeLatched: Boolean,
): ByteArray {
    val ctrl = ctrlLatched || shortcut.forceCtrl
    val raw = shortcut.sequence
    val payload = when {
        ctrl && raw.length == 1 && raw[0].code in 0x20..0x7E -> byteArrayOf((raw[0].code and 0x1F).toByte())
        else -> raw.toByteArray(Charsets.UTF_8)
    }
    val prefix = if (altLatched || escapeLatched) byteArrayOf(0x1B) else byteArrayOf()
    return prefix + payload
}

private fun terminalKeyModifiers(ctrl: Boolean, alt: Boolean, escape: Boolean): Int {
    var modifiers = 0
    if (ctrl) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
    if (alt || escape) modifiers = modifiers or KeyHandler.KEYMOD_ALT
    return modifiers
}

private class IdeTerminalViewClient(
    private val readControl: () -> Boolean,
    private val readAlt: () -> Boolean,
    private val readEscape: () -> Boolean,
    private val clearModifiers: () -> Unit,
    private val handleNavigationKey: (Int, Int) -> Boolean,
    private val onScaleChanged: () -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        val bounded = scale.coerceIn(0.57f, 2.28f)
        onScaleChanged()
        return bounded
    }
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: com.termux.terminal.TerminalSession): Boolean {
        if (keyCode !in TERMINAL_KEYBOARD_NAVIGATION_KEYS) return false
        var modifiers = 0
        if (e.isCtrlPressed() || readControl()) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (e.isAltPressed() || readAlt() || readEscape()) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (e.isShiftPressed()) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        return handleNavigationKey(keyCode, modifiers)
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        clearModifiers()
        return false
    }
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = readControl()
    override fun readAltKey() = readAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession): Boolean {
        clearModifiers()
        return false
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}

private val TERMINAL_KEYBOARD_NAVIGATION_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_MOVE_HOME,
    KeyEvent.KEYCODE_MOVE_END,
    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_PAGE_DOWN,
    KeyEvent.KEYCODE_INSERT,
    KeyEvent.KEYCODE_FORWARD_DEL,
)
