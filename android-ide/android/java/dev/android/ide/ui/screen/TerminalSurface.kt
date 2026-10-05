package dev.android.ide.ui.screen

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.android.ide.app.AppShellState
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.contracts.RuntimeAvailability
import dev.android.ide.contracts.SessionAvailability
import dev.android.ide.data.model.TerminalSettings
import dev.android.ide.data.ThemeResolver
import dev.android.ide.data.ResolvedTheme

@Composable
fun TerminalSurface(
    state: AppShellState,
    viewModel: AppShellViewModel,
    terminalSettings: TerminalSettings,
    onOpenNavigation: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onSettingsChanged: (TerminalSettings) -> Unit = {},
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
    val runtimeAvailable = state.runtimeCapabilities?.availability == RuntimeAvailability.AVAILABLE
    val context = LocalContext.current
    val themeResolver = remember { ThemeResolver(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val snackbarHostState = remember { SnackbarHostState() }
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
        state.terminalFeedback?.takeUnless(::requiresTerminalDialog)?.let { snackbarHostState.showSnackbar(it.message) }
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
            IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, "Open sidebar") }
            Column(Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.headlineSmall)
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.MoreVert, "Terminal settings") }
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
                    Text(if (runtimeAvailable) "Starting a fresh command-line session in the terminal home…" else "The terminal runtime is not available yet.")
                }
            }
        } else {
            DisposableEffect(selected.id) {
                onDispose { viewModel.unbindTerminalView(selected.id) }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                    TerminalView(context, null).also { terminalView ->
                        terminalView.setBackgroundColor(terminalTheme.background)
                        terminalView.setTextSize(terminalTextSize)
                        terminalView.isFocusable = true
                        terminalView.isFocusableInTouchMode = true
                        terminalView.setTerminalViewClient(
                            IdeTerminalViewClient(
                                readControl = { ctrlLatched },
                                readAlt = { altLatched },
                                clearModifiers = ::clearLatchedKeys,
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
                    view.setBackgroundColor(terminalTheme.background)
                    if (view.textSize != terminalTextSize) view.setTextSize(terminalTextSize)
                    view.setTerminalColors(terminalTheme.background, terminalTheme.foreground, terminalTheme.cursor)
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
                onSend = { key ->
                    viewModel.sendTerminalInput(encodeTerminalShortcut(key, ctrlLatched, altLatched, escapeLatched))
                    clearLatchedKeys()
                },
            )
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = { appearanceMenuOpen = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Default.MoreVert, "Terminal appearance")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = appearanceMenuOpen,
                    onDismissRequest = { appearanceMenuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Zoom out (${terminalTextSize}sp)") },
                        leadingIcon = { Icon(Icons.Default.ZoomOut, null) },
                        onClick = { terminalTextSize = (terminalTextSize - 1).coerceAtLeast(8); onSettingsChanged(terminalSettings.copy(textSize = terminalTextSize)); appearanceMenuOpen = false },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Zoom in (${terminalTextSize}sp)") },
                        leadingIcon = { Icon(Icons.Default.ZoomIn, null) },
                        onClick = { terminalTextSize = (terminalTextSize + 1).coerceAtMost(32); onSettingsChanged(terminalSettings.copy(textSize = terminalTextSize)); appearanceMenuOpen = false },
                    )
                }
            }
        }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
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

private fun requiresTerminalDialog(report: dev.android.ide.contracts.OperationReport): Boolean =
    report.message.contains("clipboard", ignoreCase = true).not() &&
        (report.outcome == dev.android.ide.contracts.OperationOutcome.BLOCKED ||
            report.outcome == dev.android.ide.contracts.OperationOutcome.FAILED)

private enum class TerminalTheme(val background: Int, val foreground: Int, val cursor: Int) {
    DARK(0xFF101216.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
    LIGHT(0xFFF4F5F7.toInt(), 0xFF202124.toInt(), 0xFF202124.toInt()),
}

private data class TerminalShortcut(val label: String, val sequence: String, val forceCtrl: Boolean = false)

private val TERMINAL_NAVIGATION_SHORTCUTS = listOf(
    TerminalShortcut("Esc key", "\u001B"),
    TerminalShortcut("Tab", "\t"),
    TerminalShortcut("Enter", "\r"),
    TerminalShortcut("Backspace", "\u007F"),
    TerminalShortcut("↑", "\u001B[A"),
    TerminalShortcut("↓", "\u001B[B"),
    TerminalShortcut("←", "\u001B[D"),
    TerminalShortcut("→", "\u001B[C"),
    TerminalShortcut("Home", "\u001B[H"),
    TerminalShortcut("End", "\u001B[F"),
    TerminalShortcut("PgUp", "\u001B[5~"),
    TerminalShortcut("PgDn", "\u001B[6~"),
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
                TerminalShortcutButton(shortcut.label, false) { onSend(shortcut) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TERMINAL_NAVIGATION_SHORTCUTS.forEach { shortcut ->
                TerminalShortcutButton(shortcut.label, false) { onSend(shortcut) }
            }
        }
    }
}

@Composable
private fun TerminalShortcutButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier
        .height(38.dp)
        .semantics { this.selected = selected }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    } else {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
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
        ctrl && raw.startsWith("\u001B[") -> ctrlModifiedCsi(raw).toByteArray(Charsets.UTF_8)
        else -> raw.toByteArray(Charsets.UTF_8)
    }
    val prefix = if (altLatched || escapeLatched) byteArrayOf(0x1B) else byteArrayOf()
    return prefix + payload
}

private fun ctrlModifiedCsi(sequence: String): String {
    val final = sequence.lastOrNull() ?: return sequence
    val parameter = when (final) {
        'A', 'B', 'C', 'D', 'H', 'F' -> "1;5"
        '~' -> sequence.substringAfter('[').substringBefore('~').ifBlank { "1" } + ";5"
        else -> return sequence
    }
    return "\u001B[${parameter}${final}"
}

private class IdeTerminalViewClient(
    private val readControl: () -> Boolean,
    private val readAlt: () -> Boolean,
    private val clearModifiers: () -> Unit,
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
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: com.termux.terminal.TerminalSession) = false
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
