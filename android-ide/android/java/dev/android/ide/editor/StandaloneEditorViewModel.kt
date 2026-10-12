package dev.android.ide.editor

import android.app.Application
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.android.ide.app.OpenContentRequest
import dev.android.ide.viewmodel.model.EditorTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** In-memory standalone document workspace; it never registers or mutates a project workspace. */
data class StandaloneEditorState(
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
    val editorReady: Boolean = false,
    val editorBindRevision: Long = 0L,
    val tabCursorPositions: Map<String, Pair<Int, Int>> = emptyMap(),
    val tabScrollPositions: Map<String, Int> = emptyMap(),
    val hasEditorSelection: Boolean = false,
    val loadingCount: Int = 0,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val pendingCloseTabId: String? = null,
    val leaveConfirmationVisible: Boolean = false,
    val conflictingTabId: String? = null,
) {
    val activeTab: EditorTab? get() = tabs.firstOrNull { it.id == activeTabId }
    val hasUnsavedChanges: Boolean get() = tabs.any { it.isDirty }
}

class StandaloneEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val documentService = StandaloneDocumentEditorService(application)
    private val sourceSnapshots = linkedMapOf<String, StandaloneDocumentSnapshot>()
    private val mutableState = MutableStateFlow(StandaloneEditorState())
    val state: StateFlow<StandaloneEditorState> = mutableState.asStateFlow()

    private val mutableEditorCommands = MutableSharedFlow<EditorOutbound>(extraBufferCapacity = 64)
    val editorCommands: SharedFlow<EditorOutbound> = mutableEditorCommands.asSharedFlow()

    fun open(request: OpenContentRequest) = openDocument(
        uri = Uri.parse(request.uri),
        mimeType = request.mimeType,
        displayName = request.displayName,
        grantFlags = request.grantedFlags,
    )

    fun openDocuments(uris: List<Uri>, grantFlags: Int) {
        viewModelScope.launch {
            for (uri in uris) openDocumentInternal(uri, null, null, grantFlags)
        }
    }

    fun openDocument(
        uri: Uri,
        mimeType: String? = null,
        displayName: String? = null,
        grantFlags: Int = 0,
    ) {
        viewModelScope.launch { openDocumentInternal(uri, mimeType, displayName, grantFlags) }
    }

    private suspend fun openDocumentInternal(
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        grantFlags: Int,
    ) {
        val existing = mutableState.value.tabs.firstOrNull { it.documentUri == uri.toString() }
        if (existing != null) {
            selectTab(existing.id)
            return
        }
        mutableState.update { it.copy(loadingCount = it.loadingCount + 1, errorMessage = null, statusMessage = null) }
        try {
            when (val result = documentService.open(uri.toString(), mimeType, displayName, grantFlags)) {
                is StandaloneDocumentOpenResult.Failed -> mutableState.update { it.copy(errorMessage = result.message) }
                is StandaloneDocumentOpenResult.Opened -> {
                    val source = result.snapshot
                    val tab = EditorTab(
                        id = UUID.randomUUID().toString(),
                        documentUri = source.uri.toString(),
                        displayName = source.displayName,
                        language = source.language,
                        content = source.content,
                        isActive = true,
                        isReadOnly = source.isReadOnly,
                    )
                    sourceSnapshots[source.uri.toString()] = source
                    mutableState.update { current ->
                        current.copy(
                            tabs = current.tabs.map { it.copy(isActive = false) } + tab,
                            activeTabId = tab.id,
                            editorBindRevision = current.editorBindRevision + 1,
                            errorMessage = null,
                            statusMessage = if (source.isReadOnly) "Opened read-only; the source provider does not grant write access" else "Opened source file directly",
                        )
                    }
                }
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.message ?: "The source document could not be opened") }
        } finally {
            mutableState.update { it.copy(loadingCount = (it.loadingCount - 1).coerceAtLeast(0)) }
        }
    }

    fun selectTab(tabId: String) {
        mutableState.update { current ->
            if (current.tabs.none { it.id == tabId }) current
            else current.copy(
                tabs = current.tabs.map { it.copy(isActive = it.id == tabId) },
                activeTabId = tabId,
                hasEditorSelection = false,
            )
        }
    }

    fun requestCloseTab(tabId: String) {
        val tab = mutableState.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.isDirty) mutableState.update { it.copy(pendingCloseTabId = tabId) }
        else removeTab(tabId)
    }

    fun cancelCloseTab() {
        mutableState.update { it.copy(pendingCloseTabId = null) }
    }

    fun discardAndCloseTab() {
        val tabId = mutableState.value.pendingCloseTabId ?: return
        mutableState.update { it.copy(pendingCloseTabId = null) }
        removeTab(tabId)
    }

    fun saveAndCloseTab() {
        val tabId = mutableState.value.pendingCloseTabId ?: return
        viewModelScope.launch {
            if (saveTabInternal(tabId)) {
                mutableState.update { it.copy(pendingCloseTabId = null) }
                removeTab(tabId)
            }
        }
    }

    private fun removeTab(tabId: String) {
        val current = mutableState.value
        val closing = current.tabs.firstOrNull { it.id == tabId } ?: return
        mutableEditorCommands.tryEmit(EditorOutbound.CloseTab(closing.documentUri))
        sourceSnapshots.remove(closing.documentUri)
        val remaining = current.tabs.filterNot { it.id == tabId }
        val nextActive = if (current.activeTabId == tabId) remaining.lastOrNull() else remaining.firstOrNull { it.id == current.activeTabId }
        mutableState.update {
            it.copy(
                tabs = remaining.map { tab -> tab.copy(isActive = tab.id == nextActive?.id) },
                activeTabId = nextActive?.id,
                editorBindRevision = it.editorBindRevision + 1,
                hasEditorSelection = false,
                pendingCloseTabId = null,
                conflictingTabId = if (it.conflictingTabId == tabId) null else it.conflictingTabId,
                tabCursorPositions = it.tabCursorPositions - closing.documentUri,
                tabScrollPositions = it.tabScrollPositions - closing.documentUri,
            )
        }
        if (nextActive == null) mutableEditorCommands.tryEmit(EditorOutbound.ClearActiveModel(UUID.randomUUID().toString()))
    }

    fun saveTab(tabId: String) {
        viewModelScope.launch { saveTabInternal(tabId) }
    }

    private suspend fun saveTabInternal(tabId: String): Boolean {
        val tab = mutableState.value.tabs.firstOrNull { it.id == tabId } ?: return false
        if (tab.isReadOnly || !tab.isDirty || tab.isSaving) return !tab.isDirty
        val source = sourceSnapshots[tab.documentUri]
            ?: run {
                mutableState.update { it.copy(errorMessage = "The source file is no longer available in this editor session") }
                return false
            }
        mutableState.update { current -> current.copy(tabs = current.tabs.map { if (it.id == tabId) it.copy(isSaving = true) else it }) }
        val result = runCatching { documentService.save(source, tab.content.orEmpty()) }
            .getOrElse { StandaloneDocumentSaveResult.Failed(it.message ?: "The source could not be saved") }
        when (result) {
            is StandaloneDocumentSaveResult.Saved -> {
                sourceSnapshots[tab.documentUri] = result.snapshot
                mutableState.update { current ->
                    current.copy(
                        tabs = current.tabs.map {
                            if (it.id == tabId) it.copy(content = result.snapshot.content, isDirty = false, isSaving = false, isReadOnly = result.snapshot.isReadOnly)
                            else it
                        },
                        statusMessage = "Saved to the original source",
                        errorMessage = null,
                        conflictingTabId = if (current.conflictingTabId == tabId) null else current.conflictingTabId,
                    )
                }
                return true
            }
            StandaloneDocumentSaveResult.SourceChanged -> {
                mutableState.update { current -> current.copy(
                    tabs = current.tabs.map { if (it.id == tabId) it.copy(isSaving = false) else it },
                    errorMessage = "The source changed outside Android IDE. Your edits were not written.",
                    conflictingTabId = tabId,
                ) }
            }
            StandaloneDocumentSaveResult.ReadOnly -> {
                mutableState.update { current -> current.copy(
                    tabs = current.tabs.map { if (it.id == tabId) it.copy(isSaving = false, isReadOnly = true) else it },
                    errorMessage = "Write access is no longer available. This tab is now read-only.",
                    statusMessage = null,
                ) }
            }
            StandaloneDocumentSaveResult.TooLarge -> {
                mutableState.update { current -> current.copy(
                    tabs = current.tabs.map { if (it.id == tabId) it.copy(isSaving = false) else it },
                    errorMessage = "The edited file exceeds the standalone editor’s 5 MiB limit and was not saved.",
                ) }
            }
            is StandaloneDocumentSaveResult.Failed -> {
                mutableState.update { current -> current.copy(
                    tabs = current.tabs.map { if (it.id == tabId) it.copy(isSaving = false) else it },
                    errorMessage = result.message,
                ) }
            }
        }
        return false
    }

    suspend fun saveDirtyTabs(): Boolean {
        val dirtyIds = mutableState.value.tabs.filter { it.isDirty }.map { it.id }
        var success = true
        for (tabId in dirtyIds) if (!saveTabInternal(tabId)) success = false
        return success && !mutableState.value.hasUnsavedChanges
    }

    fun requestLeave() {
        if (mutableState.value.hasUnsavedChanges) mutableState.update { it.copy(leaveConfirmationVisible = true) }
    }

    fun cancelLeave() {
        mutableState.update { it.copy(leaveConfirmationVisible = false) }
    }

    fun discardChangesAndLeave() {
        val current = mutableState.value
        current.tabs.filter { it.isDirty }.forEach { tab ->
            sourceSnapshots[tab.documentUri]?.let { snapshot ->
                mutableState.update { state -> state.copy(tabs = state.tabs.map { if (it.id == tab.id) it.copy(content = snapshot.content, isDirty = false, isSaving = false) else it }) }
                mutableEditorCommands.tryEmit(EditorOutbound.ReloadFile(tab.documentUri, tab.content, snapshot.content, tab.language))
            }
        }
        mutableState.update { it.copy(leaveConfirmationVisible = false, conflictingTabId = null) }
    }

    fun dismissError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    fun dismissConflict() {
        mutableState.update { it.copy(conflictingTabId = null) }
    }

    fun reloadConflictingTab() {
        val tabId = mutableState.value.conflictingTabId ?: return
        val tab = mutableState.value.tabs.firstOrNull { it.id == tabId } ?: return
        val oldSnapshot = sourceSnapshots[tab.documentUri] ?: return
        viewModelScope.launch {
            val openResult = runCatching { documentService.open(tab.documentUri, oldSnapshot.mimeType, oldSnapshot.displayName, 0) }
                .getOrElse { StandaloneDocumentOpenResult.Failed(it.message ?: "The source could not be reloaded") }
            when (val result = openResult) {
                is StandaloneDocumentOpenResult.Failed -> mutableState.update { it.copy(errorMessage = result.message) }
                is StandaloneDocumentOpenResult.Opened -> {
                    val newSnapshot = result.snapshot
                    sourceSnapshots[tab.documentUri] = newSnapshot
                    mutableState.update { current -> current.copy(
                        tabs = current.tabs.map { if (it.id == tabId) it.copy(content = newSnapshot.content, isDirty = false, isSaving = false, isReadOnly = newSnapshot.isReadOnly) else it },
                        editorBindRevision = current.editorBindRevision + 1,
                        errorMessage = null,
                        statusMessage = "Reloaded the changed source; unsaved editor changes were discarded",
                        conflictingTabId = null,
                    ) }
                }
            }
        }
    }

    fun onEditorMessage(message: EditorInbound) {
        when (message) {
            EditorInbound.Ready -> mutableState.update { it.copy(editorReady = true) }
            is EditorInbound.ContentChanged -> {
                val tab = mutableState.value.tabs.firstOrNull { it.documentUri == message.path } ?: return
                if (tab.isReadOnly) {
                    mutableEditorCommands.tryEmit(EditorOutbound.ReloadFile(tab.documentUri, message.content, sourceSnapshots[tab.documentUri]?.content ?: tab.content.orEmpty(), tab.language))
                    return
                }
                val baseline = sourceSnapshots[tab.documentUri]?.content
                mutableState.update { current -> current.copy(tabs = current.tabs.map {
                    if (it.id == tab.id) it.copy(content = message.content, isDirty = message.content != baseline) else it
                }) }
            }
            is EditorInbound.CursorMoved -> {
                val path = mutableState.value.activeTab?.documentUri ?: return
                mutableState.update { it.copy(tabCursorPositions = it.tabCursorPositions + (path to (message.line to message.column))) }
            }
            is EditorInbound.SelectionChanged -> mutableState.update { it.copy(hasEditorSelection = message.hasSelection) }
            is EditorInbound.FileSaved -> mutableState.value.tabs.firstOrNull { it.documentUri == message.path }?.let { saveTab(it.id) }
            is EditorInbound.TextCopied -> {
                val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Standalone editor", message.text))
            }
            is EditorInbound.ScrollPositionReport -> {
                val path = mutableState.value.activeTab?.documentUri ?: return
                mutableState.update { it.copy(tabScrollPositions = it.tabScrollPositions + (path to message.scrollTop.coerceAtLeast(0))) }
            }
            is EditorInbound.DiagnosticsChanged -> Unit
            is EditorInbound.LanguageServerMessage -> Unit
            is EditorInbound.FileLoaded -> Unit
            is EditorInbound.FileLoadFailed -> Unit
            is EditorInbound.ActiveModelCleared -> Unit
        }
    }

    fun onEditorRendererGone() {
        mutableState.update { it.copy(editorReady = false, errorMessage = "The editor renderer stopped. Reopen the standalone editor to continue.") }
    }

    fun onSurfaceDisposed() {
        mutableState.update { it.copy(editorReady = false, hasEditorSelection = false) }
    }

    fun sendEditorCommand(command: EditorOutbound) {
        mutableEditorCommands.tryEmit(command)
    }
}
