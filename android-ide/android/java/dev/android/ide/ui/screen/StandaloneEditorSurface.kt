package dev.android.ide.ui.screen

import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.android.ide.data.model.EditorSettings
import dev.android.ide.editor.EditorInbound
import dev.android.ide.editor.EditorOutbound
import dev.android.ide.editor.StandaloneEditorViewModel
import dev.android.ide.ui.components.EditorPane
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandaloneEditorSurface(
    viewModel: StandaloneEditorViewModel,
    editorSettings: EditorSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activeTab = state.activeTab
    val openDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.openDocuments(
                uris,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
    }
    fun requestBack() {
        if (state.hasUnsavedChanges) viewModel.requestLeave() else onBack()
    }
    BackHandler(enabled = true, onBack = ::requestBack)
    DisposableEffect(viewModel) {
        onDispose { viewModel.onSurfaceDisposed() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Standalone editor")
                        activeTab?.let { tab ->
                            Text(
                                text = if (tab.isReadOnly) "${tab.displayName} · Read only" else tab.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { openDocuments.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Open files")
                    }
                    IconButton(
                        onClick = { activeTab?.let { viewModel.saveTab(it.id) } },
                        enabled = activeTab?.let { it.isDirty && !it.isReadOnly && !it.isSaving } == true,
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save source file")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            if (state.loadingCount > 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.tabs.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(state.tabs, key = { it.id }) { tab ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.selectTab(tab.id) }) {
                                val marker = if (tab.isDirty) "● " else ""
                                val readOnly = if (tab.isReadOnly) " (read only)" else ""
                                Text(marker + tab.displayName + readOnly, maxLines = 1)
                            }
                            IconButton(onClick = { viewModel.requestCloseTab(tab.id) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close ${tab.displayName}", modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                HorizontalDivider()
            }
            if (activeTab == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Open a text or code file to edit it without importing it into a project.", style = MaterialTheme.typography.bodyLarge)
                        Text("Files open from their original URI. Providers without write access are shown read-only.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { openDocuments.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(" Open files")
                        }
                    }
                }
            } else {
                if (activeTab.isReadOnly) {
                    Text(
                        "Read-only: the source provider does not grant write access. Editing and saving are disabled.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                EditorPane(
                    activeTab = activeTab,
                    activeTabContent = activeTab.content,
                    isEditorReady = state.editorReady,
                    editorBindRevision = state.editorBindRevision,
                    editorCommands = viewModel.editorCommands,
                    onEditorRendererGone = viewModel::onEditorRendererGone,
                    onEditorMessage = viewModel::onEditorMessage,
                    onInsertText = { viewModel.sendEditorCommand(EditorOutbound.InsertText(it)) },
                    onInsertPair = { open, close -> viewModel.sendEditorCommand(EditorOutbound.InsertPair(open, close)) },
                    onInsertSnippet = { trigger, snippet -> viewModel.sendEditorCommand(EditorOutbound.InsertSnippet(trigger, snippet)) },
                    onExecuteCommand = { viewModel.sendEditorCommand(EditorOutbound.ExecuteCommand(it)) },
                    onPasteFromClipboard = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrEmpty()) viewModel.sendEditorCommand(EditorOutbound.InsertText(text))
                    },
                    hasEditorSelection = state.hasEditorSelection,
                    showKeyboardToolbar = !activeTab.isReadOnly && editorSettings.showKeyboardToolbar,
                    showSymbolBar = !activeTab.isReadOnly && editorSettings.showSymbolBar,
                    keyboardToolbarOrder = editorSettings.keyboardToolbarOrder,
                    tabCursorPositions = state.tabCursorPositions,
                    tabScrollPositions = state.tabScrollPositions,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            state.statusMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            }
            state.errorMessage?.let { message ->
                Snackbar(
                    action = { TextButton(onClick = viewModel::dismissError) { Text("Dismiss") } },
                    modifier = Modifier.padding(8.dp),
                ) { Text(message) }
            }
        }
    }

    state.pendingCloseTabId?.takeIf { state.conflictingTabId == null }?.let { tabId ->
        val closingTab = state.tabs.firstOrNull { it.id == tabId }
        AlertDialog(
            onDismissRequest = viewModel::cancelCloseTab,
            title = { Text("Unsaved changes") },
            text = { Text("Save changes to ${closingTab?.displayName ?: "this file"} before closing it?") },
            confirmButton = { TextButton(onClick = viewModel::saveAndCloseTab, enabled = closingTab?.isSaving != true) { Text("Save and close") } },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::discardAndCloseTab) { Text("Discard") }
                    TextButton(onClick = viewModel::cancelCloseTab) { Text("Cancel") }
                }
            },
        )
    }

    if (state.leaveConfirmationVisible && state.conflictingTabId == null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelLeave,
            title = { Text("Unsaved standalone files") },
            text = { Text("Save all changed source files before leaving the standalone editor?") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        if (viewModel.saveDirtyTabs()) {
                            viewModel.cancelLeave()
                            onBack()
                        }
                    }
                }, enabled = state.tabs.none { it.isSaving }) { Text("Save all and leave") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.discardChangesAndLeave(); onBack() }) { Text("Discard and leave") }
                    TextButton(onClick = viewModel::cancelLeave) { Text("Stay") }
                }
            },
        )
    }

    state.conflictingTabId?.let { tabId ->
        val tab = state.tabs.firstOrNull { it.id == tabId }
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text("Source changed") },
            text = { Text("${tab?.displayName ?: "The source file"} changed outside Android IDE. Your unsaved edits have not been written.") },
            confirmButton = { TextButton(onClick = viewModel::reloadConflictingTab) { Text("Reload source and discard edits") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConflict) { Text("Keep editing") } },
        )
    }
}
