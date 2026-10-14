// android-ide/android/java/dev/android/ide/viewmodel/IdeViewModel.kt
//
// Single ViewModel for the entire IDE session.
// Bridges SAF, Monaco editor bridge, project registry, session, and theme.

package dev.android.ide.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.android.ide.data.model.EditorSettings
import dev.android.ide.data.model.Project
import dev.android.ide.data.model.ProjectDetails
import dev.android.ide.data.model.VolumeKeyMode
import dev.android.ide.editor.EditorInbound
import dev.android.ide.editor.EditorLanguageRegistry
import dev.android.ide.editor.EditorOutbound
import dev.android.ide.editor.LanguageServerRegistry
import dev.android.ide.contracts.ApplicationIdentity
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.LanguageServerFeature
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.StorageLocation
import dev.android.ide.saf.ChildrenInspectionResult
import dev.android.ide.saf.DocumentPresence
import dev.android.ide.saf.ExactCreateResult
import dev.android.ide.saf.PathResolutionResult
import dev.android.ide.saf.PathResolutionScope
import dev.android.ide.saf.SafeMutationResult
import dev.android.ide.saf.TextDocumentCodec
import dev.android.ide.app.ApplicationServices
import dev.android.ide.runtime.LanguageServerPackageInstaller
import dev.android.ide.viewmodel.model.AppScreen
import dev.android.ide.viewmodel.model.EditorDiagnostic
import dev.android.ide.viewmodel.model.EditorTab
import dev.android.ide.viewmodel.model.FileNode
import dev.android.ide.viewmodel.model.FileOpDialog
import dev.android.ide.viewmodel.model.FileSearchResult
import dev.android.ide.viewmodel.model.IdeUiState
import dev.android.ide.viewmodel.model.ProjectSwitchRequest
import dev.android.ide.project.ResourcePathResolution
import dev.android.ide.project.ResourcePathResolver
import dev.android.ide.project.ProjectDetailsResult
import dev.android.ide.viewmodel.model.ancestorsOf
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.io.File
import dev.android.ide.viewmodel.model.findNode
import dev.android.ide.viewmodel.model.pathTo
import dev.android.ide.viewmodel.model.removeNode
import dev.android.ide.viewmodel.model.replaceNode
import dev.android.ide.viewmodel.model.setChildren
import dev.android.ide.viewmodel.model.sortedForTree
import dev.android.ide.viewmodel.model.toggleExpanded
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.UUID
data class ProjectPreviewTarget(val url: String, val reuseKey: String)
private val BROWSER_PREVIEW_EXTENSIONS = setOf(
    "html", "htm", "md", "markdown", "svg", "pdf", "txt", "csv", "xml", "json",
    "png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico", "tif", "tiff",
    "mp4", "webm", "ogv", "mkv", "mov", "m4v", "avi", "mp3", "wav", "ogg", "flac", "m4a", "aac",
)
private val EDITOR_IMAGE_PREVIEW_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico", "tif", "tiff")
private val BROWSER_ONLY_PREVIEW_EXTENSIONS = setOf(
    "pdf", "mp4", "webm", "ogv", "mkv", "mov", "m4v", "avi", "mp3", "wav", "ogg", "flac", "m4a", "aac",
)
private const val MAX_EDITOR_DOCUMENT_BYTES = 5 * 1024 * 1024

class IdeViewModel(application: Application) : AndroidViewModel(application) {

    private data class LanguageServerContext(
        val projectId: String,
        val serverId: String,
        val supportedFeatures: Set<LanguageServerFeature> = emptySet(),
    )

    private var fileSearchJob: Job? = null
    private var projectSearchJob: Job? = null
    private var projectSearchGeneration = 0L
    private val searchBatchSize = 16

    private val services          = ApplicationServices.from(application)
    private val fileSystem        = services.fileSystem
    private val safRepository     = fileSystem.documents
    private val fileMutations     = fileSystem.operations
    private val resourcePaths     = ResourcePathResolver()
    private val projectMetadata   = services.projectMetadata
    private val projectManagement = services.projectManagement
    private val projectRepository = services.projectRepository
    private val sessionRepository = services.sessionRepository
    private val editorSettingsRepo = services.editorSettingsRepository
    private val languageServerPackageInstaller = services.languageServerPackageInstaller
    private val crashRecovery     = services.crashRecoveryRepository
    private val lifecycleStore    = services.lifecycleStateStore
    private val themeResolver     = services.themeResolver

    private val _uiState = MutableStateFlow(
        IdeUiState(
            recentProjects = projectRepository.getAll(),
            editorSettings = editorSettingsRepo.getEditorSettings(),
            volumeKeyMode  = editorSettingsRepo.getVolumeKeyMode(),
        )
    )
    val uiState: StateFlow<IdeUiState> = _uiState.asStateFlow()

    /**
     * Outbound Monaco commands.
     * EditorPane collects this flow and forwards each command to Monaco via EditorBridge.
     * extraBufferCapacity=16 prevents dropped commands before EditorPane attaches its collector.
     */
    private val _editorCommand = MutableSharedFlow<EditorOutbound>(extraBufferCapacity = 16)
    val editorCommand: SharedFlow<EditorOutbound> = _editorCommand.asSharedFlow()

    private val languageServers = services.languageServerAdapter { projectId, serverId, message ->
        if (_uiState.value.editorSettings.languageServerEnabled) {
            _editorCommand.tryEmit(
                EditorOutbound.LanguageServerMessage(
                    projectId = projectId,
                    serverId = serverId,
                    message = rewriteLanguageServerResponseUris(message.toString(Charsets.UTF_8)),
                ),
            )
        }
    }

    /**
     * Unsaved editor content keyed by tab ID.
     * Kept outside IdeUiState to avoid full Compose recomposition on every keystroke.
     */
    private val pendingContent = mutableMapOf<String, String>()
    private val lastContentRevision = mutableMapOf<String, Long>()
    private var openFileGeneration = 0L
    private val tabSaveMutexes = ConcurrentHashMap<String, Mutex>()
    private val lspDocumentVersions = mutableMapOf<String, Int>()
    private var workspaceSaveJob: Job? = null

    init {
        // Read the previous session marker before marking this process as active.
        // Otherwise every launch looks like an unclean exit.
        val previousSessionDirty = crashRecovery.isPreviousSessionDirty()
        crashRecovery.markSessionStart()
        lifecycleStore.markProcessStarted()
        if (_uiState.value.editorSettings.languageServerEnabled) languageServerPackageInstaller.setEnabled(true)
        viewModelScope.launch {
            languageServerPackageInstaller.state.collect { setup ->
                val enabled = _uiState.value.editorSettings.languageServerEnabled
                _uiState.update { state ->
                    if (state.editorSettings.languageServerEnabled) {
                        state.copy(
                            languageServerPackageSetupMessage = setup.message ?: "Checking language-server packages…",
                            languageServerPackageSetupCanRetry = setup.canRetry,
                        )
                    } else {
                        state.copy(languageServerPackageSetupMessage = null, languageServerPackageSetupCanRetry = false)
                    }
                }
                if (enabled && setup.state == LanguageServerPackageInstaller.State.READY) {
                    val current = _uiState.value
                    val active = current.openTabs.firstOrNull { it.id == current.activeTabId }
                    val content = active?.let { pendingContent[it.id] ?: it.content }
                    if (active != null && content != null && !active.isImagePreview) {
                        initializeLanguageServerDocument(active.id, active.documentUri, active.language, content, current.projectRootUri)
                    }
                }
            }
        }
        restoreSession(previousSessionDirty)
        refreshProjectMetadata()
    }

    override fun onCleared() {
        fileSearchJob?.cancel()
        projectSearchJob?.cancel()
        languageServers.shutdown()
        super.onCleared()
        lifecycleStore.markExplicitExit()
        crashRecovery.markCleanExit()
        saveSession()
    }

    fun onAppForeground() {
        lifecycleStore.markForeground()
        if (_uiState.value.editorSettings.languageServerEnabled) languageServerPackageInstaller.refresh()
    }

    fun onAppBackground() {
        lifecycleStore.markBackground()
        saveSession()
    }

    /** Resolves Monaco's theme from the editor's independent four-way setting. */
    private fun resolveMonacoTheme(): String {
        val settings = _uiState.value.editorSettings
        return when (themeResolver.getEditorTheme(settings.editorTheme)) {
            dev.android.ide.data.ResolvedTheme.DARK -> "dark"
            dev.android.ide.data.ResolvedTheme.LIGHT -> "light"
        }
    }

    // ── Crash recovery ──────────────────────────────────────────────────────

    private fun checkCrashRecovery(projectRootUri: String, previousSessionDirty: Boolean) {
        if (!previousSessionDirty) return
        val entries = crashRecovery.getUnsavedEntries(projectRootUri)
        if (entries.isEmpty()) return
        _uiState.update { it.copy(recoveryEntries = entries) }
    }

    fun restoreFromCrash() {
        val state = _uiState.value
        val projectRootUri = state.projectRootUri ?: return
        val entries = state.recoveryEntries.filter { it.projectRootUri == projectRootUri }
        if (entries.isEmpty()) return

        val restoredTabs = state.openTabs.toMutableList()
        var firstId: String? = null
        entries.forEach { entry ->
            // Replace an already-open Monaco model with the recovered text.
            // Normal rebinds preserve existing models; recovery is explicit.
            sendEditorCommand(EditorOutbound.CloseTab(entry.documentUri))
            val existingIndex = restoredTabs.indexOfFirst { it.documentUri == entry.documentUri }
            val existing = restoredTabs.getOrNull(existingIndex)
            val tabId = existing?.id ?: if (
                restoredTabs.none { it.id == entry.tabId }
            ) {
                entry.tabId
            } else {
                UUID.randomUUID().toString()
            }
            val tab = (existing ?: EditorTab(
                id          = tabId,
                documentUri = entry.documentUri,
                displayName = entry.displayName,
                language    = EditorLanguageRegistry.languageForFileName(entry.displayName),
            )).copy(
                displayName = entry.displayName,
                language    = EditorLanguageRegistry.languageForFileName(entry.displayName),
                content     = entry.content,
                isDirty     = true,
                isTemporary = false,
                isActive    = false,
            )
            if (existingIndex >= 0) {
                restoredTabs[existingIndex] = tab
            } else {
                restoredTabs += tab
            }
            if (firstId == null) firstId = tab.id
            pendingContent[tab.id] = entry.content
            // Keep the draft alive under the tab ID actually used by this
            // session, while the old recovery record remains project-scoped.
            crashRecovery.saveUnsavedContent(
                projectRootUri = projectRootUri,
                tabId          = tab.id,
                documentUri    = tab.documentUri,
                displayName    = tab.displayName,
                content        = entry.content,
            )
            if (entry.tabId != tab.id) {
                crashRecovery.clearUnsavedContent(projectRootUri, entry.tabId)
            }
        }
        _uiState.update {
            it.copy(
                openTabs          = restoredTabs,
                recoveryEntries   = emptyList(),
                currentScreen     = AppScreen.EDITOR,
                editorBindRevision = it.editorBindRevision + 1,
            )
        }
        firstId?.let { selectTab(it) }
    }

    fun dismissCrashRecovery() {
        _uiState.value.projectRootUri?.let { crashRecovery.clearProject(it) }
        _uiState.update { it.copy(recoveryEntries = emptyList()) }
    }

    // ── Session — per-project scoped ────────────────────────────────────────

    private fun restoreSession(previousSessionDirty: Boolean) {
        val projectUri = sessionRepository.getProjectUri() ?: return

        // grant before attempting to open it.  Grants can be revoked after device reboot
        // (if the provider does not survive reboot) or an explicit permission reset.
        val hasPermission = projectUri.startsWith("file://") ||
            getApplication<Application>().contentResolver
                .persistedUriPermissions
                .any { it.uri.toString() == projectUri && it.isReadPermission && it.isWritePermission }
        if (!hasPermission) {
            _uiState.update {
                it.copy(statusMessage = "Previous project no longer accessible — re-open it from Projects")
            }
            return
        }
        // Product behavior deliberately presents Home on every launch, but
        // restoration still validates and loads the last project in the
        // background. This preserves the staged restoration contract without
        // reopening the previous child surface over Home.
        viewModelScope.launch {
            openProjectInternal(projectUri, restoreAtHome = true)
            if (_uiState.value.projectRootUri == projectUri) {
                checkCrashRecovery(projectUri, previousSessionDirty)
            }
        }
    }

    fun noteStatusMessage(msg: String) {
        _uiState.update { it.copy(statusMessage = msg) }
    }

    fun saveSession() {
        val state = _uiState.value
        sessionRepository.save(
            projectUri      = state.projectRootUri,
            openTabUris     = state.openTabs.filter { !it.isBlank && !it.isImagePreview }.map { it.documentUri },
            activeTabUri    = state.openTabs.firstOrNull { it.isActive && !it.isBlank && !it.isImagePreview }?.documentUri,
            screenName      = state.currentScreen.name,
            cursorPositions = state.tabCursorPositions,
            scrollPositions = state.tabScrollPositions,
        )
    }

    /**
     * Save the current project's workspace state before switching to another project.
     * Called internally whenever openProjectInternal runs with a different URI.
     */
    private suspend fun saveCurrentProjectSession() {
        val state = _uiState.value
        if (!state.localWorkspaceAvailable) return
        val projectUri = state.projectRootUri ?: return
        val workspace = JSONObject().apply {
            put("schemaVersion", 1)
            put("activeTabUri", state.openTabs.firstOrNull { it.isActive && !it.isBlank && !it.isImagePreview }?.documentUri ?: JSONObject.NULL)
            put("openTabUris", org.json.JSONArray(state.openTabs.filter { !it.isBlank && !it.isImagePreview }.map { it.documentUri }))
            put("pinnedTabUris", org.json.JSONArray(state.openTabs.filter { !it.isBlank && !it.isImagePreview && it.isPinned }.map { it.documentUri }))
            state.defaultPreviewPath?.let { put("defaultPreviewPath", it) }
            put("cursorPositions", JSONObject().apply {
                state.tabCursorPositions.forEach { (uri, position) ->
                    put(uri, org.json.JSONArray(listOf(position.first, position.second)))
                }
            })
            put("scrollPositions", JSONObject().apply {
                state.tabScrollPositions.forEach { (uri, scrollTop) -> put(uri, scrollTop) }
            })
            put("updatedAt", System.currentTimeMillis())
        }
        if (!projectMetadata.writeWorkspace(projectUri, workspace)) {
            _uiState.update { it.copy(statusMessage = "Workspace state could not be saved") }
        }
    }

    private fun scheduleWorkspaceSave() {
        workspaceSaveJob?.cancel()
        workspaceSaveJob = viewModelScope.launch {
            delay(250)
            saveCurrentProjectSession()
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    fun navigateTo(screen: AppScreen) {
        _uiState.update { state ->
            if (state.currentScreen == screen) state
            else state.copy(previousScreen = state.currentScreen, currentScreen = screen)
        }
        saveSession()
    }

    /** Refreshes the editor from the current resolved application theme. */
    fun onApplicationThemeChanged() {
        if (_uiState.value.isEditorReady) {
            sendEditorCommand(EditorOutbound.SetTheme(resolveMonacoTheme()))
        }
    }

    // ── Editor settings ─────────────────────────────────────────────────────

    fun setEditorSettings(settings: EditorSettings) {
        val previousSettings = _uiState.value.editorSettings
        val previousTheme = previousSettings.editorTheme
        editorSettingsRepo.setEditorSettings(settings)
        _uiState.update { it.copy(editorSettings = settings) }
        if (settings.languageServerEnabled != previousSettings.languageServerEnabled) {
            if (settings.languageServerEnabled) {
                languageServerPackageInstaller.setEnabled(true)
            } else {
                languageServerPackageInstaller.setEnabled(false)
                _uiState.update {
                    it.copy(
                        languageIntelligenceAvailable = false,
                        languageServerFeatures = emptySet(),
                        languageServerDiagnostics = emptyMap(),
                        languageServerPackageSetupMessage = null,
                        languageServerPackageSetupCanRetry = false,
                        statusMessage = it.statusMessage.takeUnless { message ->
                            message.contains("language intelligence", ignoreCase = true) ||
                                message.contains("language-server", ignoreCase = true) ||
                                message.contains("language server", ignoreCase = true)
                        }.orEmpty(),
                    )
                }
                viewModelScope.launch { languageServers.stopAll() }
            }
        }
        sendEditorCommand(EditorOutbound.SetEditorOptions(
            tabSize                = settings.tabSize,
            wordWrap               = settings.wordWrap,
            lineNumbers            = settings.lineNumbers,
            fontSize               = settings.fontSize,
            renderWhitespace       = settings.renderWhitespace,
            minimapEnabled         = settings.minimapEnabled,
            scrollBeyondLastLine   = settings.scrollBeyondLastLine,
            cursorStyle            = settings.cursorStyle,
            bracketPairColorization= settings.bracketPairColorization,
            autoClosingBrackets    = settings.autoClosingBrackets,
        ))
        if (_uiState.value.isEditorReady && settings.editorTheme != previousTheme) {
            sendEditorCommand(EditorOutbound.SetTheme(resolveMonacoTheme()))
        }
        if (settings.hideGitFolder != previousSettings.hideGitFolder ||
            settings.hideProjectMetadataFolder != previousSettings.hideProjectMetadataFolder
        ) {
            refreshProject()
        }
    }

    fun retryLanguageServerPackageInstallation() {
        if (_uiState.value.editorSettings.languageServerEnabled) languageServerPackageInstaller.retry()
    }

    fun setVolumeKeyMode(mode: VolumeKeyMode) {
        editorSettingsRepo.setVolumeKeyMode(mode)
        _uiState.update { it.copy(volumeKeyMode = mode) }
    }

    // ── Volume keys ─────────────────────────────────────────────────────────

    fun onVolumeUp() {
        when (_uiState.value.volumeKeyMode) {
            VolumeKeyMode.HORIZONTAL -> sendEditorCommand(EditorOutbound.ExecuteCommand("cursorLeft"))
            VolumeKeyMode.VERTICAL   -> sendEditorCommand(EditorOutbound.ExecuteCommand("cursorUp"))
            VolumeKeyMode.DISABLED   -> return
        }
    }

    fun onVolumeDown() {
        when (_uiState.value.volumeKeyMode) {
            VolumeKeyMode.HORIZONTAL -> sendEditorCommand(EditorOutbound.ExecuteCommand("cursorRight"))
            VolumeKeyMode.VERTICAL   -> sendEditorCommand(EditorOutbound.ExecuteCommand("cursorDown"))
            VolumeKeyMode.DISABLED   -> return
        }
    }

    /** Returns true when the persisted volume-key mode consumes the key for the editor. */
    fun handleVolumeKey(up: Boolean): Boolean {
        if (_uiState.value.volumeKeyMode == VolumeKeyMode.DISABLED) return false
        if (up) onVolumeUp() else onVolumeDown()
        return true
    }

    fun sendEditorCommand(command: EditorOutbound) {
        _editorCommand.tryEmit(command)
    }

    // ── Project management ─────────────────────────────────────────────────

    fun openProject(treeUriString: String) {
        val currentState = _uiState.value
        if (treeUriString == currentState.projectRootUri) {
            navigateTo(AppScreen.EDITOR)
            return
        }
        if (currentState.openTabs.any { it.isDirty }) {
            _uiState.update {
                it.copy(
                    projectSwitchRequest = ProjectSwitchRequest(
                        projectUri  = treeUriString,
                        projectName = extractProjectName(treeUriString),
                    ),
                )
            }
            return
        }
        switchProjectNow(treeUriString)
    }

    fun cancelProjectSwitch() {
        _uiState.update { it.copy(projectSwitchRequest = null) }
    }

    fun discardAndSwitchProject() {
        val target = _uiState.value.projectSwitchRequest ?: return
        val currentProject = _uiState.value.projectRootUri
        if (currentProject != null) {
            crashRecovery.clearProject(currentProject)
        }
        _uiState.value.openTabs.forEach { pendingContent.remove(it.id) }
        switchProjectNow(target.projectUri)
    }

    fun saveAndSwitchProject() {
        val target = _uiState.value.projectSwitchRequest ?: return
        viewModelScope.launch {
            if (!saveDirtyTabsForProject()) return@launch
            switchProjectNow(target.projectUri)
        }
    }

    private fun switchProjectNow(treeUriString: String) {
        viewModelScope.launch {
            workspaceSaveJob?.cancel()
            saveCurrentProjectSession()
            _uiState.update { it.copy(projectSwitchRequest = null) }
            openProjectInternal(treeUriString)
        }
    }

    private suspend fun saveDirtyTabsForProject(): Boolean {
        val state = _uiState.value
        val projectRootUri = state.projectRootUri
        for (tab in state.openTabs.filter { it.isDirty && !it.isBlank }) {
            val saved = tabSaveMutexes.computeIfAbsent(tab.id) { Mutex() }.withLock {
                val currentTab = _uiState.value.openTabs.find { it.id == tab.id }
                    ?: return@withLock true
                if (!currentTab.isDirty || currentTab.isBlank) return@withLock true
                val content = pendingContent[tab.id] ?: run {
                    _uiState.update { it.copy(statusMessage = "Save failed — draft content was not available") }
                    return@withLock false
                }
                val ok = fileMutations.write(currentTab.documentUri, content.toByteArray(Charsets.UTF_8))
                if (!ok) {
                    _uiState.update { it.copy(statusMessage = "Save failed — project was not switched") }
                    return@withLock false
                }
                if (pendingContent[tab.id] != content) {
                    _uiState.update { it.copy(statusMessage = "Newer edits are still being saved — project was not switched") }
                    return@withLock false
                }
                pendingContent.remove(tab.id)
                if (projectRootUri != null) crashRecovery.clearUnsavedContent(projectRootUri, tab.id)
                _uiState.update { current ->
                    current.copy(openTabs = current.openTabs.map {
                        if (it.id == tab.id) it.copy(content = content, isDirty = false, isSaving = false) else it
                    })
                }
                true
            }
            if (!saved) return false
        }
        return true
    }

    private suspend fun openProjectInternal(
        treeUriString: String,
        restoreAtHome: Boolean = false,
    ) {
        val name = extractProjectName(treeUriString)
        val registeredProject = projectRepository.getAll().firstOrNull { it.uri == treeUriString }
        val displayName = registeredProject?.name ?: name
        val openedAt = System.currentTimeMillis()
        val location = ProjectLocation(
            stableId = treeUriString,
            displayLabel = displayName,
            userVisiblePath = treeUriString,
        )
        val capabilities = fileMutations.inspectStorage(StorageLocation(location.stableId, location.displayLabel, location.userVisiblePath))
        if (capabilities.state != CapabilityState.SUPPORTED) {
            projectRepository.upsert(
                Project(
                    name = displayName,
                    uri = treeUriString,
                    lastOpenedMs = registeredProject?.lastOpenedMs ?: openedAt,
                    createdMs = registeredProject?.createdMs ?: openedAt,
                    stableLocationId = location.stableId,
                    locationLabel = location.displayLabel,
                    capabilityState = capabilities.state,
                    capabilityMessage = capabilities.explanation,
                ),
            )
            _uiState.update {
                it.copy(
                    recentProjects = projectRepository.getAll(),
                    statusMessage = capabilities.explanation ?: "Project location is unavailable",
                )
            }
            sendEditorCommand(EditorOutbound.CloseAllModels)
            _uiState.value.openTabs.forEach { pendingContent.remove(it.id) }
            _uiState.update {
                it.copy(
                    projectName = "",
                    projectRootUri = null,
                    localStorageConfirmed = false,
                    localWorkspaceAvailable = false,
                    localWorkspaceExplanation = capabilities.explanation,
                    defaultPreviewPath = null,
                    fileTree = emptyList(),
                    openTabs = emptyList(),
                    activeTabId = null,
                    languageIntelligenceAvailable = false,
                    languageServerFeatures = emptySet(),
                    clipboardItems = emptyList(),
                    clipboardIsCut = false,
                    statusMessage = capabilities.explanation ?: "Project location is unavailable",
                )
            }
            return
        }
        if (capabilities.localWorkspaceAvailable &&
            !ensureProjectMetadata(treeUriString, displayName, registeredProject?.createdMs ?: openedAt)
        ) return
        // tabs — prevents stale models from leaking into the new project (same filename
        // in both projects would reuse the old model and show wrong content).
        sendEditorCommand(EditorOutbound.CloseAllModels)
        // Close tabs from the previous project; restore tabs for the new project.
        _uiState.value.openTabs.forEach { pendingContent.remove(it.id) }
        _uiState.update { it.copy(
            projectName    = displayName,
            projectRootUri = treeUriString,
            localStorageConfirmed = capabilities.localStorageConfirmed,
            localWorkspaceAvailable = capabilities.localWorkspaceAvailable,
            localWorkspaceExplanation = capabilities.localWorkspaceExplanation,
            defaultPreviewPath = null,
            currentScreen  = if (restoreAtHome) AppScreen.HOME else AppScreen.EDITOR,
            openTabs       = emptyList(),
            activeTabId    = null,
            languageIntelligenceAvailable = false,
            languageServerFeatures = emptySet(),
            recoveryEntries = emptyList(),
            isMultiSelectMode = false,
            selectedUris = emptySet(),
        ) }
        projectRepository.upsert(
            Project(
                name = displayName,
                uri = treeUriString,
                lastOpenedMs = openedAt,
                createdMs = registeredProject?.createdMs ?: openedAt,
                stableLocationId = location.stableId,
                locationLabel = location.displayLabel,
                capabilityState = capabilities.state,
                capabilityMessage = capabilities.explanation,
            ),
        )
        _uiState.update { it.copy(recentProjects = projectRepository.getAll()) }
        refreshProjectMetadata()
        val nodes = visibleFileTreeChildren(treeUriString)
        _uiState.update { it.copy(fileTree = nodes.sortedForTree()) }

        // Restore this project's workspace state.
        val workspace = projectMetadata.readWorkspace(treeUriString)
        val defaultPreviewPath = workspace?.optString("defaultPreviewPath")
            ?.takeIf { it.isNotBlank() && it != "null" }
        _uiState.update { it.copy(defaultPreviewPath = defaultPreviewPath) }
        val tabUris = workspace?.optJSONArray("openTabUris")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
        } ?: emptyList()
        val activeUri = workspace?.optString("activeTabUri")?.takeIf { it.isNotBlank() && it != "null" }
        val pinnedUris = workspace?.optJSONArray("pinnedTabUris")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
        }?.toSet().orEmpty()
        val cursorPositions = workspace?.optJSONObject("cursorPositions")?.let { obj ->
            obj.keys().asSequence().mapNotNull { uri ->
                val position = obj.optJSONArray(uri)
                if (position != null && position.length() >= 2) {
                    uri to Pair(position.optInt(0, 1), position.optInt(1, 1))
                } else null
            }.toMap()
        }.orEmpty()
        val scrollPositions = workspace?.optJSONObject("scrollPositions")?.let { obj ->
            obj.keys().asSequence().map { uri -> uri to obj.optInt(uri, 0) }.toMap()
        }.orEmpty()
        _uiState.update { it.copy(tabCursorPositions = cursorPositions, tabScrollPositions = scrollPositions) }
        tabUris.forEach { uri ->
            openFileInternal(uri, markActive = uri == activeUri, pinned = uri in pinnedUris)
        }
        if (_uiState.value.activeTabId == null) {
            _uiState.value.openTabs.firstOrNull()?.id?.let(::selectTab)
        }
    }

    private suspend fun ensureProjectMetadata(
        projectUri: String,
        displayName: String,
        createdAt: Long,
        description: String = "",
    ): Boolean {
        val ok = projectMetadata.ensureEditorFiles(projectUri, displayName, createdAt, description)
        if (!ok) {
            _uiState.update { it.copy(statusMessage = "Project opened, but Android IDE metadata could not be initialized") }
        }
        return ok
    }

    fun refreshProjectMetadata() {
        viewModelScope.launch {
            _uiState.update { it.copy(projectMetadataLoading = true) }
            _uiState.update { it.copy(projectDetailsByUri = emptyMap() ) }
            val details = projectManagement.registeredProjects().mapNotNull { identity ->
                when (val result = projectManagement.loadDetails(identity.id)) {
                    is ProjectDetailsResult.Loaded -> result.details.project.uri to result.details
                    is ProjectDetailsResult.Unavailable -> null
                }
            }.toMap()
            _uiState.update {
                it.copy(
                    recentProjects = projectRepository.getAll(),
                    projectDetailsByUri = details,
                    projectMetadataLoading = false,
                )
            }
        }
    }

    fun closeCurrentProject() {
        viewModelScope.launch {
            saveCurrentProjectSession()
            _uiState.value.openTabs.forEach { pendingContent.remove(it.id) }
            _uiState.update { state ->
                state.copy(
                    projectName    = "",
                    projectRootUri = null,
                    localStorageConfirmed = false,
                    localWorkspaceAvailable = false,
                    localWorkspaceExplanation = null,
                    defaultPreviewPath = null,
                    fileTree       = emptyList(),
                    openTabs       = emptyList(),
                    activeTabId    = null,
                    languageIntelligenceAvailable = false,
                    languageServerFeatures = emptySet(),
                    isEditorReady  = false,
                    currentScreen  = AppScreen.PROJECTS,
                )
            }
            saveSession()
        }
    }

    fun refreshProject() {
        viewModelScope.launch { refreshProjectNow() }
    }

    private suspend fun visibleFileTreeChildren(parentUri: String): List<FileNode> =
        fileMutations.listChildren(parentUri).filterNot { node ->
            (node.displayName == ".git" && _uiState.value.editorSettings.hideGitFolder) ||
                (node.displayName in setOf(
                    ApplicationIdentity.TARGET_METADATA_DIRECTORY,
                    ApplicationIdentity.LEGACY_METADATA_DIRECTORY,
                ) && _uiState.value.editorSettings.hideProjectMetadataFolder)
        }

    private suspend fun refreshProjectNow() {
        val rootUri = _uiState.value.projectRootUri ?: return
        _uiState.update { it.copy(fileTreeLoading = true) }
        try {
            when (val inspection = fileMutations.inspectChildren(rootUri)) {
                is ChildrenInspectionResult.Success -> {
                    val settings = _uiState.value.editorSettings
                    val refreshed = inspection.children.filterNot { node ->
                        (node.displayName == ".git" && settings.hideGitFolder) ||
                            (node.displayName in setOf(
                                ApplicationIdentity.TARGET_METADATA_DIRECTORY,
                                ApplicationIdentity.LEGACY_METADATA_DIRECTORY,
                            ) && settings.hideProjectMetadataFolder)
                    }.sortedForTree()
                    val merged = mergeRefreshedTree(_uiState.value.fileTree, refreshed)
                    _uiState.update { it.copy(fileTree = merged) }
                }
                is ChildrenInspectionResult.Failed ->
                    _uiState.update { it.copy(statusMessage = "Could not refresh the project tree") }
            }
        } finally {
            _uiState.update { it.copy(fileTreeLoading = false) }
        }
    }

    /** Replace inspected metadata while retaining expansion and already-loaded children. */
    private suspend fun mergeRefreshedTree(
        previous: List<FileNode>,
        refreshed: List<FileNode>,
    ): List<FileNode> = refreshed.map { current ->
            val old = previous.firstOrNull { it.documentUri == current.documentUri }
            if (old != null && current.isDirectory) {
                val children = if (old.isExpanded) {
                    when (val inspection = fileMutations.inspectChildren(current.documentUri)) {
                        is ChildrenInspectionResult.Success ->
                            mergeRefreshedTree(old.children, inspection.children.sortedForTree())
                        is ChildrenInspectionResult.Failed -> old.children
                    }
                } else {
                    old.children
                }
                current.copy(
                    children = children,
                    isExpanded = old.isExpanded,
                )
            } else current
        }

    // ── File tree ──────────────────────────────────────────────────────────

    fun toggleDirectory(documentUri: String) {
        _uiState.update { state -> state.copy(fileTree = state.fileTree.toggleExpanded(documentUri)) }
        val node = _uiState.value.fileTree.findNode(documentUri)
        if (node != null && node.isExpanded && node.isDirectory && node.children.isEmpty()) {
            viewModelScope.launch {
                val children = visibleFileTreeChildren(documentUri)
                _uiState.update { state ->
                    state.copy(fileTree = state.fileTree.setChildren(documentUri, children.sortedForTree()))
                }
            }
        }
    }

    // ── File tree clipboard — supports multi-item ───────────────────────────

    private fun requireConfirmedLocalStorageForMutation(): Boolean {
        val state = _uiState.value
        if (state.localStorageConfirmed) return true
        val message = state.localWorkspaceExplanation
            ?: "Cloud or remote projects are not supported. Import or move this project to local device storage."
        _uiState.update { it.copy(statusMessage = message) }
        return false
    }

    /**
     * Copy [node] (or all selected nodes if in multi-select mode) to the clipboard.
     * Exits multi-select mode immediately so the user can navigate to a destination.
     */
    fun copyFileNode(node: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val items = selectedNodesForOperation(node)
        _uiState.update { it.copy(
            clipboardItems  = items,
            clipboardIsCut  = false,
            isMultiSelectMode = false,
            selectedUris    = emptySet(),
        ) }
    }

    /**
     * Cut [node] (or all selected nodes if in multi-select mode) to the clipboard.
     * Exits multi-select mode immediately so the user can navigate to a destination.
     */
    fun cutFileNode(node: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val items = selectedNodesForOperation(node)
        _uiState.update { it.copy(
            clipboardItems  = items,
            clipboardIsCut  = true,
            isMultiSelectMode = false,
            selectedUris    = emptySet(),
        ) }
    }

    fun clearClipboard() = _uiState.update { it.copy(clipboardItems = emptyList(), clipboardIsCut = false) }

    fun clearLocateRequest() {
        _uiState.update { it.copy(locateTargetUri = null) }
    }

    /**
     * Paste all items in [clipboardItems] into [targetDir].
     * Supports cut (move) and copy. Works within a project, across projects,
     * into the project root, and into nested folders.
     */
    fun pasteFileNode(targetDir: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val items  = _uiState.value.clipboardItems
        val isCut  = _uiState.value.clipboardIsCut
        if (items.isEmpty()) return
        val activeRootUri = _uiState.value.projectRootUri
        viewModelScope.launch {
            _uiState.update { it.copy(fileMutationLoading = true, statusMessage = "Processing file operation…") }
            var successCount = 0
            var previewPathChanged = false
            items.forEach { source ->
                if (source.isDirectory) {
                    when (fileMutations.isSameOrDescendant(source.documentUri, targetDir.documentUri)) {
                        true -> {
                            _uiState.update { it.copy(statusMessage = "Cannot paste a folder into itself or one of its subfolders") }
                            return@forEach
                        }
                        null -> {
                            _uiState.update { it.copy(statusMessage = "Could not verify the paste destination") }
                            return@forEach
                        }
                        false -> Unit
                    }
                }
                if (isCut) {
                    val sourceRelativePath = projectPathForDocument(source.documentUri).removePrefix("/")
                    val targetRelativePath = projectPathForDocument(targetDir.documentUri).removePrefix("/")
                    val trackPreviewMove = activeRootUri != null &&
                        fileMutations.isSameOrDescendant(activeRootUri, source.documentUri) == true &&
                        fileMutations.isSameOrDescendant(activeRootUri, targetDir.documentUri) == true
                    val sourceParent = source.parentDocumentUri ?: run {
                        _uiState.update { it.copy(statusMessage = "Move failed: unknown parent for ${source.displayName}") }
                        return@forEach
                    }
                    val moved = fileMutations.move(source.documentUri, sourceParent, targetDir.documentUri)
                    val newUri = (moved as? SafeMutationResult.Created)?.documentUri ?: run {
                        val reason = when (moved) {
                            SafeMutationResult.Duplicate -> "already exists in the destination"
                            SafeMutationResult.InspectionFailed -> "destination could not be inspected"
                            is SafeMutationResult.Partial -> "partial move; ${moved.recoveryHint}"
                            else -> "provider rejected the operation"
                        }
                        _uiState.update { it.copy(statusMessage = "Move failed for ${source.displayName}: $reason") }
                        return@forEach
                    }
                    reconcileOpenTabReference(source.documentUri, newUri)
                    if (trackPreviewMove && sourceRelativePath.isNotBlank()) {
                        val configured = _uiState.value.defaultPreviewPath
                        val belongsToMovedItem = configured == sourceRelativePath ||
                            (source.isDirectory && configured?.startsWith("$sourceRelativePath/") == true)
                        if (belongsToMovedItem && configured != null) {
                            val suffix = configured.removePrefix(sourceRelativePath)
                            val movedPath = (if (targetRelativePath.isBlank()) source.displayName else "$targetRelativePath/${source.displayName}") + suffix
                            val nextPreviewPath = movedPath.takeIf { it.substringAfterLast('.', "").lowercase() in setOf("html", "htm") }
                            _uiState.update { state ->
                                if (state.defaultPreviewPath == configured) state.copy(defaultPreviewPath = nextPreviewPath) else state
                            }
                            previewPathChanged = true
                        }
                    }
                    successCount++
                } else {
                    val copied = fileMutations.copy(source.documentUri, targetDir.documentUri)
                    if (copied !is SafeMutationResult.Created) {
                        val reason = when (copied) {
                            SafeMutationResult.Duplicate -> "already exists in the destination"
                            SafeMutationResult.InspectionFailed -> "destination could not be inspected"
                            is SafeMutationResult.Partial -> "partial copy; ${copied.recoveryHint}"
                            else -> "provider rejected the operation"
                        }
                        _uiState.update { it.copy(statusMessage = "Copy failed for ${source.displayName}: $reason") }
                        return@forEach
                    }
                    successCount++
                }
            }
            refreshProjectNow()
            val verb = if (isCut) "Moved" else "Copied"
            _uiState.update { it.copy(
                fileMutationLoading = false,
                clipboardItems = if (successCount == items.size) emptyList() else it.clipboardItems,
                clipboardIsCut = if (successCount == items.size) false else it.clipboardIsCut,
                statusMessage  = if (successCount == items.size) "$verb $successCount item(s)" else
                    "$verb $successCount of ${items.size} item(s); some operations failed",
            ) }
            if (previewPathChanged) scheduleWorkspaceSave()
        }
    }

    // ── Import / Export ────────────────────────────────────────────────────

    fun importFiles(targetDirUri: String, sourceUris: List<String>) {
        if (!requireConfirmedLocalStorageForMutation()) return
        if (sourceUris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(fileMutationLoading = true, statusMessage = "Importing selected files…") }
            try {
                var count = 0
                sourceUris.forEach { uri ->
                    val name  = fileMutations.displayName(uri)
                        ?: uri.substringAfterLast('/', "imported_file")
                    when (fileMutations.copy(uri, targetDirUri, name)) {
                        is SafeMutationResult.Created -> count++
                        else -> Unit
                    }
                }
                refreshProjectNow()
                val outcome = if (count == sourceUris.size) "Imported $count file(s)" else "Imported $count of ${sourceUris.size} file(s); some files could not be imported"
                _uiState.update { it.copy(statusMessage = outcome) }
            } finally {
                _uiState.update { it.copy(fileMutationLoading = false) }
            }
        }
    }

    fun exportDirectory(node: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.Export(node)) }
    }

    fun exportProject() {
        if (!requireConfirmedLocalStorageForMutation()) return
        _uiState.update { it.copy(statusMessage = "Choose a destination file to export the project") }
    }

    fun exportProject(projectUri: String, destinationUri: String) {
        if (!requireConfirmedLocalStorageForMutation()) return
        viewModelScope.launch {
            val result = fileMutations.writeDirectoryArchive(projectUri, destinationUri)
            _uiState.update {
                it.copy(
                    statusMessage = if (result == null) {
                        "Export failed"
                    } else {
                        "Exported ${result.fileCount} file(s) (${formatBytes(result.totalBytes)})"
                    },
                )
            }
        }
    }

    fun exportDirectory(node: FileNode, destinationUri: String) {
        if (!requireConfirmedLocalStorageForMutation()) return
        viewModelScope.launch {
            _uiState.update { state -> state.copy(fileMutationLoading = true, fileOpDialog = (state.fileOpDialog as? FileOpDialog.Export)?.copy(isSubmitting = true, resultMessage = null, failed = false)) }
            try {
                val result = fileMutations.writeDirectoryArchive(node.documentUri, destinationUri)
                _uiState.update {
                    it.copy(
                        fileOpDialog = (it.fileOpDialog as? FileOpDialog.Export)?.copy(
                            isSubmitting = false,
                            resultMessage = if (result == null) "Export failed for ${node.displayName}" else "Exported ${node.displayName} (${result.fileCount} file(s))",
                            failed = result == null,
                        ),
                        statusMessage = if (result == null) "Export failed for ${node.displayName}" else "Exported ${node.displayName} (${result.fileCount} file(s))",
                    )
                }
            } finally {
                _uiState.update { it.copy(fileMutationLoading = false) }
            }
        }
    }

    // ── Editor tabs ────────────────────────────────────────────────────────

    fun newBlankTab() {
        val tabId = UUID.randomUUID().toString()
        val tab = EditorTab(
            id          = tabId,
            documentUri = "blank://new/$tabId",
            displayName = "untitled",
            language    = "plaintext",
            content     = "",
            isActive    = true,
            isBlank     = true,
        )
        _uiState.update { state ->
            state.copy(
                openTabs      = state.openTabs.map { it.copy(isActive = false) } + tab,
                activeTabId   = tabId,
                currentScreen = AppScreen.EDITOR,
            )
        }
        scheduleWorkspaceSave()
    }

    fun openFile(documentUri: String) {
        _uiState.update { it.copy(editorFileLoading = true) }
        viewModelScope.launch { openFileInternal(documentUri, markActive = true, temporary = true) }
    }

    fun openFilePermanent(documentUri: String) {
        _uiState.update { it.copy(editorFileLoading = true) }
        viewModelScope.launch { openFileInternal(documentUri, markActive = true, temporary = false) }
    }

    /** Open a search result and, for content matches, select/reveal the matching range. */
    fun openFileAtSearchResult(result: FileSearchResult) {
        _uiState.update { it.copy(editorFileLoading = true) }
        viewModelScope.launch {
            openFileInternal(result.documentUri, markActive = true, temporary = true)
            val opened = _uiState.value.openTabs.any { it.isActive && it.documentUri == result.documentUri }
            if (opened && (result.matchLine != null || result.matchColumn != null || result.matchLength != null)) {
                delay(300)
                sendEditorCommand(
                    EditorOutbound.SelectMatch(
                        line = result.matchLine ?: 1,
                        column = result.matchColumn ?: 1,
                        length = result.matchLength ?: 0,
                    )
                )
            }
        }
    }

    private suspend fun openFileInternal(
        documentUri: String,
        markActive: Boolean,
        temporary: Boolean = false,
        pinned: Boolean = false,
    ) {
        val requestGeneration = if (markActive) ++openFileGeneration else openFileGeneration
        if (markActive) _uiState.update { it.copy(languageIntelligenceAvailable = false, languageServerFeatures = emptySet()) }
        val existing = _uiState.value.openTabs.find { it.documentUri == documentUri }
        if (existing != null) {
            if (!temporary && existing.isTemporary) pinTab(existing.id)
            if (markActive) reopenExistingTab(existing) else _uiState.update { it.copy(editorFileLoading = false) }
            return
        }

        val displayName = _uiState.value.fileTree.findNode(documentUri)?.displayName
            ?: fileMutations.displayName(documentUri)
            ?: displayNameFromUri(documentUri)
        if (displayName.substringAfterLast('.', "").lowercase() in BROWSER_ONLY_PREVIEW_EXTENSIONS) {
            if (markActive) {
                _uiState.update {
                    it.copy(
                        editorFileLoading = false,
                        fileOpDialog = FileOpDialog.BinaryOpenError(displayName, documentUri),
                        statusMessage = "",
                    )
                }
            }
            return
        }
        val language = EditorLanguageRegistry.languageForFileName(displayName)
        val previousActiveTabId = _uiState.value.activeTabId
        val oldTemporaryTab = if (temporary) _uiState.value.openTabs.firstOrNull { it.isTemporary } else null
        if (_uiState.value.localWorkspaceAvailable &&
            displayName.substringAfterLast('.', "").lowercase() in EDITOR_IMAGE_PREVIEW_EXTENSIONS
        ) {
            val imageTab = EditorTab(
                documentUri = documentUri,
                displayName = displayName,
                language = language,
                content = null,
                isActive = markActive,
                isTemporary = temporary && !pinned,
                isPinned = pinned,
                isImagePreview = true,
            )
            if (oldTemporaryTab != null) pendingContent.remove(oldTemporaryTab.id)
            _uiState.update { state ->
                val remaining = state.openTabs.filterNot { oldTemporaryTab != null && it.id == oldTemporaryTab.id }
                val tabs = if (markActive) remaining.map { it.copy(isActive = false) } + imageTab else remaining + imageTab
                state.copy(
                    openTabs = tabs,
                    activeTabId = if (markActive) imageTab.id else state.activeTabId,
                    currentScreen = AppScreen.EDITOR,
                    hasEditorSelection = false,
                    languageIntelligenceAvailable = if (markActive) false else state.languageIntelligenceAvailable,
                    languageServerFeatures = if (markActive) emptySet() else state.languageServerFeatures,
                    editorFileLoading = false,
                )
            }
            if (markActive) sendEditorCommand(EditorOutbound.ClearActiveModel(UUID.randomUUID().toString()))
            oldTemporaryTab?.let { sendEditorCommand(EditorOutbound.CloseTab(it.documentUri)) }
            scheduleWorkspaceSave()
            return
        }
        val loadingTab = EditorTab(
            documentUri = documentUri,
            displayName = displayName,
            language = language,
            content = null,
            isActive    = markActive,
            isTemporary = temporary && !pinned,
            isPinned = pinned,
        )
        _uiState.update { state ->
            val tabs = if (markActive) state.openTabs.map { it.copy(isActive = false) } + loadingTab
                else state.openTabs + loadingTab
            state.copy(
                openTabs = tabs,
                activeTabId = if (markActive) loadingTab.id else state.activeTabId,
                currentScreen = AppScreen.EDITOR,
                hasEditorSelection = false,
                languageIntelligenceAvailable = if (markActive) false else state.languageIntelligenceAvailable,
                languageServerFeatures = if (markActive) emptySet() else state.languageServerFeatures,
                editorFileLoading = true,
            )
        }
        fun failLoading(message: String, binaryFile: Boolean = false) {
            val restoreId = previousActiveTabId?.takeIf { id -> _uiState.value.openTabs.any { it.id == id } }
            _uiState.update { state ->
                val tabs = state.openTabs.filterNot { it.id == loadingTab.id }
                val activeId = if (state.activeTabId == loadingTab.id) restoreId else state.activeTabId
                state.copy(
                    openTabs = tabs.map { it.copy(isActive = it.id == activeId) },
                    activeTabId = activeId,
                    editorFileLoading = false,
                    statusMessage = message,
                    fileOpDialog = if (binaryFile) FileOpDialog.BinaryOpenError(displayName, documentUri) else state.fileOpDialog,
                    languageIntelligenceAvailable = if (state.activeTabId == loadingTab.id) false else state.languageIntelligenceAvailable,
                    languageServerFeatures = if (state.activeTabId == loadingTab.id) emptySet() else state.languageServerFeatures,
                )
            }
            if (_uiState.value.activeTabId == restoreId && restoreId != null) selectTab(restoreId)
        }

        val bytes = withContext(Dispatchers.IO) {
            fileMutations.read(documentUri, maxBytes = MAX_EDITOR_DOCUMENT_BYTES)
        }
        if (markActive && requestGeneration != openFileGeneration) {
            _uiState.update { state -> state.copy(openTabs = state.openTabs.filterNot { it.id == loadingTab.id }) }
            return
        }
        if (bytes == null) {
            failLoading("Could not open file")
            return
        }
        if (bytes.size > MAX_EDITOR_DOCUMENT_BYTES) {
            failLoading("Cannot open: file is ${bytes.size / 1_048_576} MB (5 MB limit)")
            return
        }
        val decodedFile = withContext(Dispatchers.Default) { TextDocumentCodec.decode(bytes) }
        if (decodedFile == null) {
            failLoading("This file is not a supported text document", binaryFile = true)
            return
        }
        val content = decodedFile.text
        val oldTemporaryUri = oldTemporaryTab?.documentUri
        if (oldTemporaryTab != null) pendingContent.remove(oldTemporaryTab.id)
        if (_uiState.value.openTabs.none { it.id == loadingTab.id }) return
        _uiState.update { state ->
            if (state.openTabs.none { it.id == loadingTab.id }) return@update state
            val remaining = state.openTabs.filterNot { oldTemporaryTab != null && it.id == oldTemporaryTab.id }
            state.copy(
                openTabs = remaining.map { tab ->
                    when {
                        tab.id == loadingTab.id -> tab.copy(content = content, isActive = state.activeTabId == loadingTab.id)
                        else -> tab
                    }
                },
                activeTabId = state.activeTabId,
                editorFileLoading = false,
            )
        }
        oldTemporaryUri?.let { sendEditorCommand(EditorOutbound.CloseTab(it)) }

        initializeLanguageServerDocument(loadingTab.id, documentUri, language, content, _uiState.value.projectRootUri)
        scheduleWorkspaceSave()
    }

    /** Validate only when an already-open file is explicitly reopened. */
    private suspend fun reopenExistingTab(tab: EditorTab) {
        selectTab(tab.id)
        if (tab.isBlank || tab.isImagePreview || tab.content == null) {
            _uiState.update { it.copy(editorFileLoading = false) }
            return
        }
        val diskContent = withContext(Dispatchers.IO) {
            fileMutations.read(tab.documentUri, maxBytes = MAX_EDITOR_DOCUMENT_BYTES)
                ?.let(TextDocumentCodec::decode)?.text
        }
        if (diskContent == null) {
            _uiState.update { it.copy(editorFileLoading = false, statusMessage = "Could not validate ${tab.displayName}; keeping the open editor content") }
            return
        }
        if (diskContent == tab.content) {
            _uiState.update { it.copy(editorFileLoading = false) }
            return
        }
        if (tab.isDirty || pendingContent.containsKey(tab.id)) {
            _uiState.update { it.copy(editorFileLoading = false, fileOpDialog = FileOpDialog.ExternalFileConflict(tab.id, tab.displayName, diskContent)) }
            return
        }
        applyReopenedDiskContent(tab, diskContent, tab.content)
    }

    private fun applyReopenedDiskContent(tab: EditorTab, diskContent: String, expectedEditorContent: String?) {
        pendingContent.remove(tab.id)
        lastContentRevision.remove(tab.id)
        _uiState.update { state ->
            state.copy(
                openTabs = state.openTabs.map { if (it.id == tab.id) it.copy(content = diskContent, isDirty = false, isSaving = false) else it },
                editorFileLoading = false,
                fileOpDialog = null,
            )
        }
        sendEditorCommand(EditorOutbound.ReloadFile(tab.documentUri, expectedEditorContent, diskContent, tab.language))
        initializeLanguageServerDocument(tab.id, tab.documentUri, tab.language, diskContent, _uiState.value.projectRootUri)
        scheduleWorkspaceSave()
    }

    fun keepEditorContentAfterExternalChange(tabId: String) {
        if (_uiState.value.openTabs.none { it.id == tabId }) return
        _uiState.update { it.copy(fileOpDialog = null, editorFileLoading = false) }
    }

    fun useDiskContentAfterExternalChange(tabId: String) {
        val dialog = _uiState.value.fileOpDialog as? FileOpDialog.ExternalFileConflict ?: return
        if (dialog.tabId != tabId) return
        val tab = _uiState.value.openTabs.firstOrNull { it.id == tabId } ?: return
        applyReopenedDiskContent(tab, dialog.diskContent, tab.content)
    }

    private fun initializeLanguageServerDocument(
        tabId: String,
        documentUri: String,
        language: String,
        initialContent: String,
        projectRootAtOpen: String?,
    ) {
        if (!_uiState.value.editorSettings.languageServerEnabled) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!_uiState.value.editorSettings.languageServerEnabled) return@launch
            val languageServer = startLanguageServerForActiveProject(language, projectRootAtOpen) ?: return@launch
            val currentTab = _uiState.value.openTabs.firstOrNull { it.id == tabId } ?: return@launch
            if (!_uiState.value.editorSettings.languageServerEnabled ||
                _uiState.value.projectRootUri != projectRootAtOpen || currentTab.documentUri != documentUri
            ) return@launch
            val latestContent = pendingContent[currentTab.id] ?: currentTab.content ?: initialContent
            lspDocumentVersions[documentUri] = 1
            sendLanguageServerNotification(
                context = languageServer,
                method = "textDocument/didOpen",
                params = JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("uri", languageServerDocumentUri(documentUri))
                        put("languageId", language)
                        put("version", 1)
                        put("text", latestContent)
                    })
                },
            )
            _uiState.update { state ->
                if (state.activeTabId == tabId && state.editorSettings.languageServerEnabled) {
                    state.copy(
                        languageIntelligenceAvailable = true,
                        languageServerFeatures = languageServer.supportedFeatures,
                    )
                } else state
            }
        }
    }

    private suspend fun startLanguageServerForActiveProject(
        languageId: String,
        rootUri: String? = _uiState.value.projectRootUri,
    ): LanguageServerContext? {
        if (!_uiState.value.editorSettings.languageServerEnabled) return null
        if (!languageServerPackageInstaller.arePackagesInstalled()) {
            languageServerPackageInstaller.refresh()
            return null
        }
        if (!_uiState.value.editorSettings.languageServerEnabled) return null
        rootUri ?: return null
        val project = projectManagement.registeredProjectAt(rootUri) ?: return null
        val status = languageServers.start(project, languageId)
        if (!_uiState.value.editorSettings.languageServerEnabled) {
            if (status.available) languageServers.stop(project.id, status.serverId)
            return null
        }
        if (!status.available && status.explanation != null &&
            languageServerPackageInstaller.state.value.state == LanguageServerPackageInstaller.State.READY
        ) {
            _uiState.update { it.copy(statusMessage = "Language intelligence unavailable: ${status.explanation}") }
        }
        return if (status.available && status.initialized) {
            LanguageServerContext(project.id, status.serverId, status.supportedFeatures)
        } else {
            null
        }
    }

    private fun languageServerDocumentUri(documentUri: String): String =
        safRepository.localFilesystemPath(documentUri)
            ?.let(::File)
            ?.toURI()
            ?.toString()
            ?: documentUri

    /**
     * Servers operate on native file URIs while Monaco models retain their
     * provider-backed document identities. Translate every URI-bearing value in
     * server responses before they reach Monaco so diagnostics, definitions,
     * references, rename edits, and workspace edits remain actionable.
     */
    private fun rewriteLanguageServerResponseUris(raw: String): String {
        val tabs = _uiState.value.openTabs.associate { tab ->
            languageServerDocumentUri(tab.documentUri) to tab.documentUri
        }
        if (tabs.isEmpty()) return raw
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        fun rewrite(value: Any?): Any? = when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                keys.forEach { key -> value.put(key, rewrite(value.opt(key))) }
                value
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) value.put(index, rewrite(value.opt(index)))
                value
            }
            is String -> tabs[value] ?: value
            else -> value
        }
        return (rewrite(json) as JSONObject).toString()
    }

    private fun nextLspDocumentVersion(documentUri: String): Int {
        val next = (lspDocumentVersions[documentUri] ?: 0) + 1
        lspDocumentVersions[documentUri] = next
        return next
    }

    private fun sendLanguageServerNotification(
        context: LanguageServerContext,
        method: String,
        params: JSONObject,
    ) {
        if (!_uiState.value.editorSettings.languageServerEnabled) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!_uiState.value.editorSettings.languageServerEnabled) return@launch
            languageServers.send(
                context.projectId,
                context.serverId,
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", params)
                }.toString().toByteArray(Charsets.UTF_8),
            )
        }
    }

    fun selectTab(tabId: String) {
        val selectionState = _uiState.value
        val selectedTab = selectionState.openTabs.firstOrNull { it.id == tabId } ?: return
        val projectRootUri = selectionState.projectRootUri
        _uiState.update { state ->
            state.copy(
                openTabs    = state.openTabs.map { it.copy(isActive = it.id == tabId) },
                activeTabId = tabId,
                hasEditorSelection = false,
                languageIntelligenceAvailable = false,
                languageServerFeatures = emptySet(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val languageServer = startLanguageServerForActiveProject(selectedTab.language, projectRootUri)
            _uiState.update { state ->
                if (state.activeTabId == tabId && state.projectRootUri == projectRootUri) {
                    state.copy(
                        languageIntelligenceAvailable = languageServer != null,
                        languageServerFeatures = languageServer?.supportedFeatures.orEmpty(),
                    )
                } else state
            }
        }
        scheduleWorkspaceSave()
    }

    fun pinTab(tabId: String) {
        _uiState.update { state ->
            state.copy(openTabs = state.openTabs.map {
                if (it.id == tabId) it.copy(isTemporary = false, isPinned = true) else it
            })
        }
        scheduleWorkspaceSave()
    }

    /** Close a tab immediately, discarding unsaved changes without confirmation. */
    fun closeTab(tabId: String) {
        val wasActive = _uiState.value.activeTabId == tabId
        val tab = _uiState.value.openTabs.find { it.id == tabId }
        tab?.let { closedTab ->
            sendLanguageServerNotificationForTab(
                closedTab,
                "textDocument/didClose",
                JSONObject().apply {
                    put("textDocument", JSONObject().put("uri", languageServerDocumentUri(closedTab.documentUri)))
                },
            )
        }
        pendingContent.remove(tabId)
        lastContentRevision.remove(tabId)
        _uiState.value.projectRootUri?.let { projectRootUri ->
            crashRecovery.clearUnsavedContent(projectRootUri, tabId)
        }
        _uiState.update { state ->
            val remaining   = state.openTabs.filter { it.id != tabId }
            val newActiveId = if (state.activeTabId == tabId) remaining.lastOrNull()?.id else state.activeTabId
            state.copy(
                openTabs    = remaining.map { it.copy(isActive = it.id == newActiveId) },
                activeTabId = newActiveId,
                languageServerDiagnostics = if (tab != null) {
                    state.languageServerDiagnostics - tab.documentUri
                } else state.languageServerDiagnostics,
            )
        }
        if (wasActive) {
            _uiState.value.activeTabId?.let(::selectTab)
                ?: _uiState.update { it.copy(languageIntelligenceAvailable = false, languageServerFeatures = emptySet()) }
        }
        tab?.let { sendEditorCommand(EditorOutbound.CloseTab(it.documentUri)) }
        scheduleWorkspaceSave()
    }

    fun closeTabSafe(tabId: String) {
        val tab = _uiState.value.openTabs.find { it.id == tabId } ?: return
        if (tab.isDirty) {
            _uiState.update { it.copy(fileOpDialog = FileOpDialog.UnsavedClose(tabId, tab.displayName)) }
        } else {
            closeTab(tabId)
        }
    }

    fun saveAndCloseTab(tabId: String) {
        val tab     = _uiState.value.openTabs.find { it.id == tabId } ?: run {
            _uiState.update { it.copy(fileOpDialog = null) }
            return
        }
        val content = pendingContent[tabId]
        _uiState.update { it.copy(fileOpDialog = null) }
        if (content == null || tab.isBlank) { closeTab(tabId); return }
        viewModelScope.launch {
            val ok = fileMutations.write(tab.documentUri, content.toByteArray(Charsets.UTF_8))
            if (ok) {
                pendingContent.remove(tabId)
                _uiState.value.projectRootUri?.let { projectRootUri ->
                    crashRecovery.clearUnsavedContent(projectRootUri, tabId)
                }
            }
            closeTab(tabId)
        }
    }

    fun confirmCloseTab(tabId: String) {
        _uiState.update { it.copy(fileOpDialog = null) }
        closeTab(tabId)
    }

    fun closeOtherTabs(tabId: String) {
        _uiState.value.openTabs.filter { it.id != tabId && !it.isPinned }.forEach { closeTabSafe(it.id) }
    }

    fun closeAllTabs() {
        _uiState.value.openTabs.filterNot { it.isPinned }.forEach { closeTabSafe(it.id) }
    }

    /**
     * Clears the project-scoped editor boundary after the registry/storage owner
     * has removed the project. This deliberately disposes Monaco models before
     * clearing tabs so a same-named file from a later project cannot reuse stale
     * content or dirty state.
     */
    fun resetWorkspaceAfterProjectRemoval() {
        workspaceSaveJob?.cancel()
        sendEditorCommand(EditorOutbound.CloseAllModels)
        _uiState.value.openTabs.forEach { pendingContent.remove(it.id) }
        _uiState.update {
            it.copy(
                projectRootUri = null,
                    localStorageConfirmed = false,
                    localWorkspaceAvailable = false,
                    localWorkspaceExplanation = null,
                    defaultPreviewPath = null,
                projectName = "",
                fileTree = emptyList(),
                openTabs = emptyList(),
                activeTabId = null,
                languageIntelligenceAvailable = false,
                languageServerFeatures = emptySet(),
                tabCursorPositions = emptyMap(),
                tabScrollPositions = emptyMap(),
                recoveryEntries = emptyList(),
                projectSwitchRequest = null,
                currentScreen = AppScreen.HOME,
                statusMessage = "The project is no longer available in the editor",
                editorBindRevision = it.editorBindRevision + 1,
            )
        }
    }

    fun saveAllFiles() {
        _uiState.value.openTabs.filter { it.isDirty && !it.isBlank }.forEach { saveFile(it.documentUri) }
    }

    // ── Exit confirmation ───────────────────────────────────────────────────

    fun requestExit(): Boolean {
        val hasDirty = _uiState.value.openTabs.any { it.isDirty }
        if (hasDirty) {
            _uiState.update { it.copy(showExitConfirmation = true) }
            return true
        }
        return false
    }

    fun dismissExitConfirmation() = _uiState.update { it.copy(showExitConfirmation = false) }

    fun saveAllAndExit(onReady: () -> Unit) {
        viewModelScope.launch {
            if (!saveDirtyTabsForProject()) return@launch
            _uiState.update { it.copy(showExitConfirmation = false) }
            crashRecovery.markCleanExit()
            onReady()
        }
    }

    // ── Editor bridge ──────────────────────────────────────────────────────

    fun onEditorReady() {
        _uiState.update {
            it.copy(
                isEditorReady    = true,
                editorBindRevision = it.editorBindRevision + 1,
            )
        }
        val settings = _uiState.value.editorSettings
        sendEditorCommand(EditorOutbound.SetEditorOptions(
            tabSize                = settings.tabSize,
            wordWrap               = settings.wordWrap,
            lineNumbers            = settings.lineNumbers,
            fontSize               = settings.fontSize,
            renderWhitespace       = settings.renderWhitespace,
            minimapEnabled         = settings.minimapEnabled,
            scrollBeyondLastLine   = settings.scrollBeyondLastLine,
            cursorStyle            = settings.cursorStyle,
            bracketPairColorization= settings.bracketPairColorization,
            autoClosingBrackets    = settings.autoClosingBrackets,
        ))
        sendEditorCommand(EditorOutbound.SetTheme(resolveMonacoTheme()))
        sendEditorCommand(EditorOutbound.ForceLayout)
    }

    /** Marks the native editor as unavailable until the WebView sends Ready again. */
    fun onEditorRendererGone() {
        _uiState.update {
            it.copy(
                isEditorReady    = false,
                editorBindRevision = it.editorBindRevision + 1,
            )
        }
    }

    /** Returns the latest draft when Monaco needs to be rebound after a layout change. */
    fun editorContentForTab(tabId: String, fallback: String?): String? =
        pendingContent[tabId] ?: fallback

    fun revealDiagnostic(diagnostic: EditorDiagnostic) {
        val state = _uiState.value
        val tab = state.openTabs.firstOrNull { it.documentUri == diagnostic.documentUri } ?: return
        val line = diagnostic.startLine.coerceAtLeast(1)
        val column = diagnostic.startColumn.coerceAtLeast(1)
        _uiState.update { current ->
            current.copy(
                tabCursorPositions = current.tabCursorPositions + (diagnostic.documentUri to (line to column)),
                cursorLine = if (current.activeTabId == tab.id) line else current.cursorLine,
                cursorColumn = if (current.activeTabId == tab.id) column else current.cursorColumn,
            )
        }
        if (state.activeTabId == tab.id) {
            sendEditorCommand(EditorOutbound.SetCursorPosition(line, column))
        } else {
            selectTab(tab.id)
        }
    }

    fun onEditorMessage(message: EditorInbound) {
        when (message) {
            is EditorInbound.Ready -> onEditorReady()
            is EditorInbound.FileLoaded,
            is EditorInbound.FileLoadFailed,
            is EditorInbound.ActiveModelCleared,
            -> Unit

            is EditorInbound.ContentChanged -> {
                val tab = _uiState.value.openTabs.find { it.documentUri == message.path } ?: return
                if (tab.content == null && !tab.isBlank) return
                val previousRevision = lastContentRevision[tab.id]
                if (previousRevision != null && message.revision <= previousRevision) return
                lastContentRevision[tab.id] = message.revision
                pendingContent[tab.id] = message.content
                _uiState.update { state ->
                    state.copy(openTabs = state.openTabs.map {
                        if (it.id == tab.id) it.copy(isDirty = true, isTemporary = false) else it
                    })
                }
                val projectRootUri = _uiState.value.projectRootUri ?: return
                crashRecovery.saveUnsavedContent(
                    projectRootUri = projectRootUri,
                    tabId          = tab.id,
                    documentUri    = tab.documentUri,
                    displayName    = tab.displayName,
                    content        = message.content,
                )
                sendLanguageServerNotificationForTab(
                    tab,
                    "textDocument/didChange",
                    JSONObject().apply {
                        put("textDocument", JSONObject().apply {
                            put("uri", languageServerDocumentUri(tab.documentUri))
                            put("version", nextLspDocumentVersion(tab.documentUri))
                        })
                        put("contentChanges", org.json.JSONArray().put(JSONObject().put("text", message.content)))
                    },
                )
                if (_uiState.value.editorSettings.autoSave) {
                    saveFile(message.path)
                }
            }

            is EditorInbound.CursorMoved -> {
                _uiState.update { state ->
                    val activeUri = state.openTabs.firstOrNull { it.isActive }?.documentUri
                    val updatedCursors = if (activeUri != null)
                        state.tabCursorPositions + (activeUri to Pair(message.line, message.column))
                    else state.tabCursorPositions
                    state.copy(
                        cursorLine         = message.line,
                        cursorColumn       = message.column,
                        tabCursorPositions = updatedCursors,
                    )
                }
                scheduleWorkspaceSave()
            }

            is EditorInbound.SelectionChanged ->
                _uiState.update { it.copy(hasEditorSelection = message.hasSelection) }

            is EditorInbound.FileSaved -> saveFile(message.path)

            is EditorInbound.LanguageServerMessage -> onLanguageServerMessage(message.message)

            is EditorInbound.DiagnosticsChanged -> {
                _uiState.update { state ->
                    if (!state.editorSettings.languageServerEnabled ||
                        state.openTabs.none { it.documentUri == message.documentUri }
                    ) return@update state
                    val diagnostics = state.languageServerDiagnostics.toMutableMap()
                    if (message.diagnostics.isEmpty()) diagnostics.remove(message.documentUri)
                    else diagnostics[message.documentUri] = message.diagnostics
                    state.copy(languageServerDiagnostics = diagnostics)
                }
            }

            // isCut=true means Monaco has already deleted the selection — nothing else
            // to do on the Kotlin side after placing the text in the clipboard.
            is EditorInbound.TextCopied -> {
                val clipboard = getApplication<Application>()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", message.text))
            }

            is EditorInbound.ScrollPositionReport -> {
                _uiState.update { state ->
                    val activeUri = state.openTabs.firstOrNull { it.isActive }?.documentUri
                    val updatedScrolls = if (activeUri != null)
                        state.tabScrollPositions + (activeUri to message.scrollTop)
                    else state.tabScrollPositions
                    state.copy(tabScrollPositions = updatedScrolls)
                }
                scheduleWorkspaceSave()
            }
        }
    }

    private fun sendLanguageServerNotificationForTab(tab: EditorTab, method: String, params: JSONObject) {
        if (!_uiState.value.editorSettings.languageServerEnabled) return
        val rootUri = _uiState.value.projectRootUri ?: return
        val project = projectManagement.registeredProjectAt(rootUri) ?: return
        val definition = LanguageServerRegistry.forLanguage(tab.language) ?: return
        sendLanguageServerNotification(
            LanguageServerContext(project.id, definition.id),
            method,
            params,
        )
    }

    private fun onLanguageServerMessage(rawMessage: String) {
        if (!_uiState.value.editorSettings.languageServerEnabled) return
        val tab = _uiState.value.openTabs.firstOrNull { it.isActive } ?: return
        val rootUri = _uiState.value.projectRootUri ?: return
        val project = projectManagement.registeredProjectAt(rootUri) ?: return
        val definition = LanguageServerRegistry.forLanguage(tab.language) ?: return
        val message = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        message.optJSONObject("params")
            ?.optJSONObject("textDocument")
            ?.put("uri", languageServerDocumentUri(tab.documentUri))
        viewModelScope.launch(Dispatchers.IO) {
            if (!_uiState.value.editorSettings.languageServerEnabled) return@launch
            val report = languageServers.send(
                project.id,
                definition.id,
                message.toString().toByteArray(Charsets.UTF_8),
            )
            if (report.outcome != OperationOutcome.COMPLETE) {
                _uiState.update { state ->
                    if (!state.editorSettings.languageServerEnabled) return@update state
                    state.copy(
                        statusMessage = report.message,
                        languageIntelligenceAvailable = if (
                            state.activeTabId == tab.id && state.projectRootUri == rootUri
                        ) false else state.languageIntelligenceAvailable,
                        languageServerFeatures = if (
                            state.activeTabId == tab.id && state.projectRootUri == rootUri
                        ) emptySet() else state.languageServerFeatures,
                    )
                }
            }
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────

    fun saveActiveFile() {
        val active = _uiState.value.openTabs.firstOrNull { it.isActive } ?: return
        if (active.isBlank || active.content == null) return
        saveFile(active.documentUri)
    }

    fun saveTabById(tabId: String) {
        val tab = _uiState.value.openTabs.find { it.id == tabId } ?: return
        if (tab.isBlank || tab.content == null) return
        saveFile(tab.documentUri)
    }

    private fun saveFile(documentUri: String) {
        val tab     = _uiState.value.openTabs.find { it.documentUri == documentUri } ?: return
        if (tab.isBlank || tab.content == null) return
        viewModelScope.launch {
            val mutex = tabSaveMutexes.computeIfAbsent(tab.id) { Mutex() }
            mutex.withLock {
                val currentTab = _uiState.value.openTabs.find { it.id == tab.id } ?: return@withLock
                if (currentTab.isBlank || currentTab.content == null) return@withLock
                val content = pendingContent[tab.id] ?: return@withLock
                _uiState.update { state ->
                    state.copy(
                        openTabs = state.openTabs.map {
                            if (it.id == tab.id) it.copy(isSaving = true) else it
                        },
                    )
                }
                val ok = fileMutations.write(currentTab.documentUri, content.toByteArray(Charsets.UTF_8))
                if (ok) {
                    val draftIsCurrent = pendingContent[tab.id] == content
                    if (draftIsCurrent) {
                        pendingContent.remove(tab.id)
                        _uiState.value.projectRootUri?.let { projectRootUri ->
                            crashRecovery.clearUnsavedContent(projectRootUri, tab.id)
                        }
                    }
                    _uiState.update { state ->
                        state.copy(
                            openTabs = state.openTabs.map {
                                if (it.id == tab.id) it.copy(
                                    content = content,
                                    isDirty = !draftIsCurrent,
                                    isSaving = false,
                                ) else it
                            },
                            statusMessage = if (draftIsCurrent) "Saved" else "Saved; newer edits remain unsaved",
                        )
                    }
                    sendLanguageServerNotificationForTab(
                        currentTab,
                        "textDocument/didSave",
                        JSONObject().apply {
                            put("textDocument", JSONObject().put("uri", languageServerDocumentUri(currentTab.documentUri)))
                            put("text", content)
                        },
                    )
                } else {
                    _uiState.update { state ->
                        state.copy(
                            openTabs = state.openTabs.map {
                                if (it.id == tab.id) it.copy(isSaving = false) else it
                            },
                            statusMessage = "Save failed",
                        )
                    }
                }
            }
        }
    }

    // ── File search ────────────────────────────────────────────────────────

    fun showFileSearch() {
        fileSearchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchVisible = true,
                isContentSearchVisible = false,
                fileSearchQuery = "",
                fileSearchResults = emptyList(),
                fileSearchRunning = false,
            )
        }
    }

    fun hideFileSearch() {
        fileSearchJob?.cancel()
        fileSearchJob = null
        _uiState.update {
            it.copy(
                isSearchVisible = false,
                fileSearchQuery = "",
                fileSearchResults = emptyList(),
                fileSearchRunning = false,
            )
        }
    }

    fun setFileSearchIncludeFolders(enabled: Boolean) {
        if (_uiState.value.fileSearchIncludeFolders == enabled) return
        _uiState.update { it.copy(fileSearchIncludeFolders = enabled) }
        val query = _uiState.value.fileSearchQuery
        if (query.isNotBlank()) searchFiles(query)
    }

    fun searchFiles(query: String) {
        fileSearchJob?.cancel()
        fileSearchJob = null
        _uiState.update {
            it.copy(
                fileSearchQuery = query,
                fileSearchResults = emptyList(),
                fileSearchRunning = query.isNotBlank(),
            )
        }
        if (query.isBlank()) {
            _uiState.update { it.copy(fileSearchRunning = false) }
            return
        }
        val results = mutableListOf<FileSearchResult>()
        fileSearchJob = viewModelScope.launch {
            val includeFolders = _uiState.value.fileSearchIncludeFolders
            val rootUri = _uiState.value.projectRootUri
            var published = 0
            suspend fun publishFilenameResults(force: Boolean = false) {
                if (!force && results.size - published < searchBatchSize) return
                if (_uiState.value.fileSearchQuery != query) return
                published = results.size
                _uiState.update { it.copy(fileSearchResults = results.toList()) }
                yield()
            }
            suspend fun scan(uri: String, path: String) {
                fileMutations.listChildren(uri).forEach { node ->
                    val nodePath = if (path.isEmpty()) node.displayName else "$path/${node.displayName}"
                    if (node.displayName == ".git" || node.displayName in setOf(ApplicationIdentity.TARGET_METADATA_DIRECTORY, ApplicationIdentity.LEGACY_METADATA_DIRECTORY)) return@forEach
                    if ((!node.isDirectory || includeFolders) && node.displayName.contains(query, ignoreCase = true)) {
                        results += FileSearchResult(node.documentUri, node.displayName, "/$nodePath", isDirectory = node.isDirectory)
                        publishFilenameResults()
                    }
                    if (node.isDirectory) scan(node.documentUri, nodePath)
                }
            }
            if (rootUri != null) {
                scan(rootUri, "")
            } else {
                suspend fun searchNodes(nodes: List<FileNode>, path: String) {
                    nodes.forEach { node ->
                        val nodePath = if (path.isEmpty()) node.displayName else "$path/${node.displayName}"
                        if (node.displayName != ".git" && node.displayName !in setOf(ApplicationIdentity.TARGET_METADATA_DIRECTORY, ApplicationIdentity.LEGACY_METADATA_DIRECTORY)) {
                            if ((!node.isDirectory || includeFolders) && node.displayName.contains(query, ignoreCase = true)) {
                                results += FileSearchResult(node.documentUri, node.displayName, "/$nodePath", isDirectory = node.isDirectory)
                                publishFilenameResults()
                            }
                            if (node.isDirectory) searchNodes(node.children, nodePath)
                        }
                    }
                }
                searchNodes(_uiState.value.fileTree, "")
            }
            publishFilenameResults(force = true)
            if (_uiState.value.fileSearchQuery == query) {
                _uiState.update {
                    it.copy(
                        fileSearchResults = results.distinctBy { result -> result.documentUri }.sortedBy { it.relativePath.lowercase() },
                        fileSearchRunning = false,
                    )
                }
            }
        }
    }

    private fun cancelProjectContentSearch() {
        projectSearchGeneration++
        projectSearchJob?.cancel()
        projectSearchJob = null
    }

    private fun invalidateContentSearchRun() {
        cancelProjectContentSearch()
        _uiState.update {
            it.copy(
                contentSearchResults = emptyList(),
                contentSearchCompletedQuery = null,
                contentSearchRunning = false,
                contentSearchWarning = null,
            )
        }
    }

    fun showContentSearch() {
        invalidateContentSearchRun()
        _uiState.update {
            it.copy(isContentSearchVisible = true, isSearchVisible = false, contentSearchQuery = "")
        }
    }

    fun hideContentSearch() {
        invalidateContentSearchRun()
        _uiState.update {
            it.copy(isContentSearchVisible = false, contentSearchQuery = "")
        }
    }

    fun clearContentSearchResults() {
        invalidateContentSearchRun()
    }

    fun setContentSearchQuery(query: String) {
        if (_uiState.value.contentSearchQuery == query) return
        invalidateContentSearchRun()
        _uiState.update { it.copy(contentSearchQuery = query) }
    }

    fun setContentSearchMatchCase(enabled: Boolean) {
        if (_uiState.value.contentSearchMatchCase == enabled) return
        invalidateContentSearchRun()
        _uiState.update { it.copy(contentSearchMatchCase = enabled) }
    }

    fun setContentSearchWholeWord(enabled: Boolean) {
        if (_uiState.value.contentSearchWholeWord == enabled) return
        invalidateContentSearchRun()
        _uiState.update { it.copy(contentSearchWholeWord = enabled) }
    }

    fun setContentSearchRegex(enabled: Boolean) {
        if (_uiState.value.contentSearchRegex == enabled) return
        invalidateContentSearchRun()
        _uiState.update { it.copy(contentSearchRegex = enabled) }
    }

    fun setContentSearchShowContext(enabled: Boolean) {
        if (_uiState.value.contentSearchShowContext == enabled) return
        invalidateContentSearchRun()
        _uiState.update { it.copy(contentSearchShowContext = enabled) }
    }

    fun searchProjectContents(query: String) {
        cancelProjectContentSearch()
        val requestId = projectSearchGeneration
        val options = _uiState.value
        val rootUri = options.projectRootUri
        _uiState.update {
            it.copy(
                contentSearchQuery = query,
                contentSearchResults = emptyList(),
                contentSearchCompletedQuery = null,
                contentSearchRunning = query.isNotBlank(),
                contentSearchWarning = null,
            )
        }
        if (query.isBlank()) return
        if (rootUri == null) {
            _uiState.update {
                it.copy(contentSearchCompletedQuery = query, contentSearchRunning = false, contentSearchWarning = "Open a project before searching.")
            }
            return
        }

        val regex = if (options.contentSearchRegex) {
            runCatching {
                Regex(query, if (options.contentSearchMatchCase) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE))
            }.getOrNull()
        } else null
        if (options.contentSearchRegex && regex == null) {
            _uiState.update {
                it.copy(contentSearchCompletedQuery = query, contentSearchRunning = false, contentSearchWarning = "Invalid search expression.")
            }
            return
        }

        projectSearchJob = viewModelScope.launch {
            val results = mutableListOf<FileSearchResult>()
            var published = 0
            val visitedDirectories = mutableSetOf(rootUri)
            var unreadableDirectories = 0
            var skippedFiles = 0

            suspend fun searchDirectory(uri: String, path: String) {
                val children = when (val inspection = fileMutations.inspectChildren(uri)) {
                    is ChildrenInspectionResult.Success -> inspection.children
                    is ChildrenInspectionResult.Failed -> {
                        unreadableDirectories++
                        return
                    }
                }
                children.forEach { node ->
                    val nodePath = if (path.isEmpty()) node.displayName else "$path/${node.displayName}"
                    if (node.isDirectory) {
                        if (visitedDirectories.add(node.documentUri)) searchDirectory(node.documentUri, nodePath)
                    } else {
                        var lineIndex = 0
                        var previousLine = ""
                        var pendingContextIndices = emptyList<Int>()
                        val fileResults = mutableListOf<FileSearchResult>()
                        val streamed = fileMutations.forEachTextLine(node.documentUri) { line ->
                            if (options.contentSearchShowContext && pendingContextIndices.isNotEmpty()) {
                                val nextContext = line.trim().take(70)
                                if (nextContext.isNotEmpty()) {
                                    pendingContextIndices.forEach { index ->
                                        fileResults[index] = fileResults[index].copy(
                                            matchPreview = fileResults[index].matchPreview + "\n" + nextContext,
                                        )
                                    }
                                }
                            }
                            val currentLineResultStart = fileResults.size
                            val matches = if (options.contentSearchRegex) {
                                regex!!.findAll(line).map { MatchRange(it.range.first, it.range.last + 1, it.value) }.toList()
                            } else {
                                literalContentMatches(line, query, options.contentSearchMatchCase, options.contentSearchWholeWord)
                            }
                            matches.filter { match ->
                                !options.contentSearchWholeWord || isWholeWordBoundary(line, match.range.first, match.range.last + 1)
                            }.forEach { match ->
                                val start = match.range.first
                                val length = match.value.length.coerceAtLeast(1)
                                val windowStart = maxOf(0, start - 80)
                                val windowEnd = minOf(line.length, start + length + 160)
                                val leadingMarker = if (windowStart > 0) "…" else ""
                                val trailingMarker = if (windowEnd < line.length) "…" else ""
                                val beforeContext = if (options.contentSearchShowContext && lineIndex > 0) {
                                    previousLine.trim().takeLast(70) + "\n"
                                } else ""
                                val linePreview = line.substring(windowStart, windowEnd)
                                val preview = beforeContext + leadingMarker + linePreview + trailingMarker
                                val previewMatchStart = beforeContext.length + leadingMarker.length + (start - windowStart)
                                fileResults += FileSearchResult(
                                    node.documentUri,
                                    node.displayName,
                                    "/$nodePath",
                                    preview,
                                    lineIndex + 1,
                                    start + 1,
                                    length,
                                    false,
                                    previewMatchStart,
                                    length,
                                )
                            }
                            pendingContextIndices = if (fileResults.size > currentLineResultStart) {
                                (currentLineResultStart until fileResults.size).toList()
                            } else {
                                emptyList()
                            }
                            previousLine = line.takeLast(70)
                            lineIndex++
                        }
                        if (streamed) {
                            results.addAll(fileResults)
                            if (results.size - published >= searchBatchSize) {
                                if (requestId == projectSearchGeneration) {
                                    published = results.size
                                    _uiState.update { state ->
                                        if (state.contentSearchQuery != query) state
                                        else state.copy(contentSearchResults = results.toList())
                                    }
                                    yield()
                                }
                            }
                        } else skippedFiles++
                    }
                }
            }

            try {
                searchDirectory(rootUri, "")
                if (requestId != projectSearchGeneration) return@launch
                if (published != results.size) {
                    published = results.size
                    _uiState.update { state ->
                        if (state.contentSearchQuery != query) state
                        else state.copy(contentSearchResults = results.toList())
                    }
                }
                val warnings = buildList {
                    if (unreadableDirectories > 0) add("$unreadableDirectories folder(s) could not be inspected.")
                    if (skippedFiles > 0) add("$skippedFiles binary or unreadable file(s) were skipped; text extensions are not filtered.")
                }.joinToString(" ").ifBlank { null }
                _uiState.update { state ->
                    if (state.contentSearchQuery != query) state
                    else state.copy(
                        contentSearchResults = results.sortedWith(
                            compareBy<FileSearchResult> { it.relativePath.lowercase() }
                                .thenBy { it.matchLine ?: 0 }
                                .thenBy { it.matchColumn ?: 0 },
                        ),
                        contentSearchCompletedQuery = query,
                        contentSearchRunning = false,
                        contentSearchWarning = warnings,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestId == projectSearchGeneration) {
                    _uiState.update {
                        it.copy(
                            contentSearchResults = emptyList(),
                            contentSearchCompletedQuery = query,
                            contentSearchRunning = false,
                            contentSearchWarning = error.message ?: "Project search failed.",
                        )
                    }
                }
            }
        }
    }

    /** Show a preview before replacing; mutation starts only after confirmation. */
    fun replaceProjectContents(find: String, replacement: String, targetDocumentUris: List<String>) {
        if (find.isBlank()) return
        val state = _uiState.value
        if (state.contentSearchRunning || state.contentSearchCompletedQuery != find) {
            _uiState.update { it.copy(statusMessage = "Run the current search before replacing project content") }
            return
        }
        val selectedUris = targetDocumentUris.toSet()
        val results = state.contentSearchResults.filter { it.documentUri in selectedUris }
        if (results.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "No matching project content to replace") }
            return
        }
        val matchCount = results.size
        val documentUris = results.map { it.documentUri }.distinct()
        _uiState.update {
            it.copy(fileOpDialog = FileOpDialog.ReplaceAll(find, replacement, documentUris.size, matchCount, documentUris))
        }
    }

    /** Show a confirmation before replacing only the matches belonging to one file. */
    fun replaceFileContents(documentUri: String, fileName: String, find: String, replacement: String) {
        if (find.isBlank()) return
        val state = _uiState.value
        if (state.contentSearchRunning || state.contentSearchCompletedQuery != find) return
        val matches = state.contentSearchResults.count { it.documentUri == documentUri }
        if (matches == 0) return
        _uiState.update {
            it.copy(fileOpDialog = FileOpDialog.ReplaceFile(documentUri, fileName, find, replacement, matches))
        }
    }

    fun confirmReplaceProjectContents() {
        val dialog = _uiState.value.fileOpDialog as? FileOpDialog.ReplaceAll ?: return
        val options = _uiState.value
        _uiState.update { it.copy(fileOpDialog = dialog.copy(isSubmitting = true)) }
        viewModelScope.launch {
            val includedUris = dialog.documentUris.toSet()
            val results = _uiState.value.contentSearchResults
                .filter { it.documentUri in includedUris }
                .distinctBy { it.documentUri }
            var changedFiles = 0
            var failedFiles = 0
            results.forEach { result ->
                val bytes = fileMutations.read(result.documentUri)
                val decoded = bytes?.let(TextDocumentCodec::decode)
                if (decoded == null) {
                    failedFiles++
                    return@forEach
                }
                val changed = replaceContentMatches(
                    decoded.text,
                    dialog.find,
                    dialog.replacement,
                    options.contentSearchMatchCase,
                    options.contentSearchWholeWord,
                    options.contentSearchRegex,
                )
                if (changed == decoded.text) return@forEach
                if (fileMutations.write(result.documentUri, decoded.encoding.encode(changed))) changedFiles++ else failedFiles++
            }
            val message = if (failedFiles == 0) {
                "Replaced matches in $changedFiles file(s)"
            } else {
                "Replaced matches in $changedFiles file(s); $failedFiles file(s) could not be updated"
            }
            _uiState.update { it.copy(fileOpDialog = dialog.copy(isSubmitting = false, resultMessage = message), statusMessage = message) }
            searchProjectContents(_uiState.value.contentSearchQuery)
        }
    }

    fun confirmReplaceFileContents() {
        val dialog = _uiState.value.fileOpDialog as? FileOpDialog.ReplaceFile ?: return
        val options = _uiState.value
        _uiState.update { it.copy(fileOpDialog = dialog.copy(isSubmitting = true)) }
        viewModelScope.launch {
            val decoded = fileMutations.read(dialog.documentUri)?.let(TextDocumentCodec::decode)
            val changed = decoded?.let { source ->
                replaceContentMatches(
                    source.text,
                    dialog.find,
                    dialog.replacement,
                    options.contentSearchMatchCase,
                    options.contentSearchWholeWord,
                    options.contentSearchRegex,
                ).takeUnless { it == source.text }
            }
            val written = changed != null && decoded != null &&
                fileMutations.write(dialog.documentUri, decoded.encoding.encode(changed))
            _uiState.update {
                val message = if (written) "Replaced matches in ${dialog.fileName}" else "Could not update ${dialog.fileName}"
                it.copy(
                    fileOpDialog = dialog.copy(isSubmitting = false, resultMessage = message),
                    statusMessage = message,
                )
            }
            searchProjectContents(_uiState.value.contentSearchQuery)
        }
    }

    private fun literalContentMatches(
        line: String,
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): List<MatchRange> {
        if (query.isEmpty()) return emptyList()
        val matches = mutableListOf<MatchRange>()
        var from = 0
        while (from <= line.length) {
            val start = line.indexOf(query, from, ignoreCase = !matchCase)
            if (start < 0) break
            val end = start + query.length
            if (!wholeWord || isWholeWordBoundary(line, start, end)) {
                matches += MatchRange(start, end, line.substring(start, end))
            }
            from = start + maxOf(query.length, 1)
        }
        return matches
    }

    private fun isWholeWordBoundary(text: String, start: Int, end: Int): Boolean {
        fun isWordCharacter(value: Char?) = value != null && (value.isLetterOrDigit() || value == '_')
        return !isWordCharacter(text.getOrNull(start - 1)) && !isWordCharacter(text.getOrNull(end))
    }

    private fun replaceContentMatches(
        source: String,
        find: String,
        replacement: String,
        matchCase: Boolean,
        wholeWord: Boolean,
        regex: Boolean,
    ): String {
        if (regex) {
            return runCatching {
                val expression = if (wholeWord) "(?<![\\p{L}\\p{N}_])(?:$find)(?![\\p{L}\\p{N}_])" else find
                Regex(expression, if (matchCase) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)).replace(source, replacement)
            }.getOrDefault(source)
        }
        if (find.isEmpty()) return source
        val result = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            val start = source.indexOf(find, cursor, ignoreCase = !matchCase)
            if (start < 0) {
                result.append(source, cursor, source.length)
                break
            }
            val end = start + find.length
            if (wholeWord && !isWholeWordBoundary(source, start, end)) {
                result.append(source, cursor, end)
                cursor = end
            } else {
                result.append(source, cursor, start).append(replacement)
                cursor = end
            }
        }
        return result.toString()
    }

    private data class MatchRange(val start: Int, val end: Int, val value: String) {
        val range: IntRange get() = start until end
    }

    fun showEditorFind() {
        _uiState.update { it.copy(isEditorSearchVisible = true) }
        sendEditorCommand(EditorOutbound.ShowFind)
    }

    fun showEditorReplace() {
        _uiState.update { it.copy(isEditorSearchVisible = true) }
        sendEditorCommand(EditorOutbound.ShowReplace)
    }

    fun dismissEditorSearch(): Boolean {
        if (!_uiState.value.isEditorSearchVisible) return false
        _uiState.update { it.copy(isEditorSearchVisible = false) }
        sendEditorCommand(EditorOutbound.CloseSearch)
        sendEditorCommand(EditorOutbound.ExecuteCommand("focusEditor"))
        return true
    }

    // ── Reveal active file ─────────────────────────────────────────────────

    fun revealActiveFile() {
        val activeTab = _uiState.value.openTabs.firstOrNull { it.isActive } ?: run {
            _uiState.update { it.copy(statusMessage = "No active file") }
            return
        }
        val activeUri = activeTab.documentUri
        val activeDisplayName = activeTab.displayName
        val rootUri = _uiState.value.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project is open") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Locating active file…") }
            val cachedPath = findCachedAncestorPath(_uiState.value.fileTree, activeUri)
            if (cachedPath != null) {
                _uiState.update { state ->
                    var tree = state.fileTree
                    cachedPath.forEach { directoryUri ->
                        val node = tree.findNode(directoryUri)
                        if (node != null && node.isDirectory && !node.isExpanded) {
                            tree = tree.toggleExpanded(directoryUri)
                        }
                    }
                    state.copy(
                        fileTree = tree,
                        locateTargetUri = activeUri,
                        locateRequestToken = state.locateRequestToken + 1,
                        statusMessage = "Located $activeDisplayName",
                    )
                }
                return@launch
            }
            when (val result = discoverPathFromRoot(rootUri, activeUri)) {
                is LocateResult.Found -> {
                    var tree = _uiState.value.fileTree
                    result.discoveredChildren[rootUri]?.let {
                        tree = mergeTreeChildren(tree, it.sortedForTree())
                    }
                    result.discoveredChildren
                        .filterKeys { it != rootUri }
                        .forEach { (parentUri, children) ->
                            tree = tree.setChildren(parentUri, children.sortedForTree())
                        }
                    _uiState.update { state ->
                        state.copy(
                            fileTree = tree,
                            locateTargetUri = activeUri,
                            locateRequestToken = state.locateRequestToken + 1,
                            statusMessage = "Located ${result.displayName}",
                        )
                    }
                }
                LocateResult.NotFound ->
                    _uiState.update { it.copy(statusMessage = "Active file was not found in the project") }
                is LocateResult.Failed ->
                    _uiState.update { it.copy(statusMessage = "Could not inspect the project while locating the file") }
            }
        }
    }

    private fun findCachedAncestorPath(
        nodes: List<FileNode>,
        targetUri: String,
        ancestors: List<String> = emptyList(),
    ): List<String>? {
        for (node in nodes) {
            if (node.documentUri == targetUri) return ancestors
            if (node.isDirectory && node.children.isNotEmpty()) {
                val found = findCachedAncestorPath(
                    nodes = node.children,
                    targetUri = targetUri,
                    ancestors = ancestors + node.documentUri,
                )
                if (found != null) return found
            }
        }
        return null
    }

    private fun mergeTreeChildren(
        previous: List<FileNode>,
        refreshed: List<FileNode>,
    ): List<FileNode> = refreshed.map { current ->
        val old = previous.firstOrNull { it.documentUri == current.documentUri }
        if (old != null && current.isDirectory) {
            current.copy(
                children = old.children,
                isExpanded = old.isExpanded,
            )
        } else {
            current
        }
    }.sortedForTree()

    private sealed class LocateResult {
        data class Found(
            val displayName: String,
            val discoveredChildren: LinkedHashMap<String, List<FileNode>>,
        ) : LocateResult()
        data object NotFound : LocateResult()
        data object Failed : LocateResult()
    }

    private suspend fun discoverPathFromRoot(rootUri: String, targetUri: String): LocateResult {
        val visited = mutableSetOf<String>()
        var inspectionFailed = false

        suspend fun visit(directoryUri: String): LocateResult {
            if (!visited.add(directoryUri)) return LocateResult.NotFound
            val children = when (val inspection = fileMutations.inspectChildren(directoryUri)) {
                is ChildrenInspectionResult.Success -> inspection.children
                is ChildrenInspectionResult.Failed -> {
                    inspectionFailed = true
                    return LocateResult.NotFound
                }
            }
            children.firstOrNull { it.documentUri == targetUri }?.let { target ->
                return LocateResult.Found(
                    displayName = target.displayName,
                    discoveredChildren = linkedMapOf(directoryUri to children),
                )
            }
            for (child in children) {
                if (!child.isDirectory) continue
                when (val nested = visit(child.documentUri)) {
                    is LocateResult.Found -> {
                        val pathChildren = linkedMapOf<String, List<FileNode>>()
                        pathChildren[directoryUri] = children
                        pathChildren.putAll(nested.discoveredChildren)
                        return LocateResult.Found(nested.displayName, pathChildren)
                    }
                    is LocateResult.Failed -> Unit
                    LocateResult.NotFound -> Unit
                }
            }
            return if (inspectionFailed) LocateResult.Failed else LocateResult.NotFound
        }

        return visit(rootUri)
    }

    // ── Multi-selection ────────────────────────────────────────────────────

    fun enterSelectionMode() {
        _uiState.update { it.copy(isMultiSelectMode = true, selectedUris = emptySet()) }
    }

    fun exitSelectionMode() {
        _uiState.update { it.copy(isMultiSelectMode = false, selectedUris = emptySet()) }
    }

    fun toggleNodeSelection(uri: String) {
        _uiState.update { state ->
            val updated = if (uri in state.selectedUris) state.selectedUris - uri else state.selectedUris + uri
            // Enter selection mode automatically on first selection; exit when all deselected.
            state.copy(isMultiSelectMode = updated.isNotEmpty(), selectedUris = updated)
        }
    }

    /**
     * Resolve the current multi-selection for a destructive or clipboard operation.
     * If a selected folder contains another selected node, only the folder is kept;
     * this prevents duplicate copy/cut/delete requests for the same subtree.
     */
    private fun selectedNodesForOperation(fallback: FileNode): List<FileNode> {
        val state = _uiState.value
        val candidates = if (state.isMultiSelectMode && state.selectedUris.isNotEmpty()) {
            state.selectedUris.mapNotNull { state.fileTree.findNode(it) }
        } else {
            listOf(fallback)
        }
        val selectedUris = candidates.map { it.documentUri }.toSet()
        return candidates.filter { node ->
            var parentUri = node.parentDocumentUri
            var hasSelectedAncestor = false
            while (parentUri != null) {
                if (parentUri in selectedUris) {
                    hasSelectedAncestor = true
                    break
                }
                parentUri = state.fileTree.findNode(parentUri)?.parentDocumentUri
            }
            !hasSelectedAncestor
        }
    }

    private fun affectedUris(node: FileNode): Set<String> = buildSet {
        add(node.documentUri)
        node.children.forEach { addAll(affectedUris(it)) }
    }

    private fun reconcileOpenTabReference(oldUri: String, newUri: String, newName: String? = null) {
        val rootUri = _uiState.value.projectRootUri
        val tabs = _uiState.value.openTabs.filter { it.documentUri == oldUri }
        _uiState.update { state ->
            state.copy(openTabs = state.openTabs.map { tab ->
                if (tab.documentUri == oldUri) {
                    tab.copy(documentUri = newUri, displayName = newName ?: tab.displayName)
                } else tab
            })
        }
        if (rootUri != null) {
            tabs.forEach { tab ->
                pendingContent[tab.id]?.let { content ->
                    crashRecovery.saveUnsavedContent(
                        rootUri,
                        tab.id,
                        newUri,
                        newName ?: tab.displayName,
                        content,
                    )
                }
            }
        }
    }

    // ── Clipboard path copy ────────────────────────────────────────────────

    /**
     * Copy the absolute display path of [documentUri] to the system clipboard.
     * Paths always start with "/" (e.g. "/src/pages/home.html").
     */
    fun copyPathToClipboard(documentUri: String) {
        val path = _uiState.value.fileTree.pathTo(documentUri)
            ?: run {
                // pathTo returns null for root-level nodes; decode URI as fallback.
                val raw = Uri.decode(documentUri).substringAfterLast('/')
                "/$raw"
            }
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("File Path", path))
        _uiState.update { it.copy(statusMessage = "Path copied: $path") }
    }

    /**
     * Read the Android clipboard text and insert it into Monaco as a text operation.
     * This avoids the WebView clipboard API (which requires permission and is slow).
     */
    fun pasteFromKotlinClipboard() {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(getApplication())?.toString()
        if (!text.isNullOrEmpty()) {
            sendEditorCommand(EditorOutbound.InsertText(text))
        }
    }

    // ── File operations ────────────────────────────────────────────────────

    fun showRenameDialog(node: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.Rename(node)) }
    }
    fun showDeleteDialog(node: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.Delete(node, selectedNodesForOperation(node))) }
    }
    fun showCreateFileDialog(parent: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.CreateFile(parent)) }
    }
    fun showCreateFolderDialog(parent: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.CreateFolder(parent)) }
    }
    fun showDuplicateDialog(node: FileNode) {
        if (!requireConfirmedLocalStorageForMutation()) return
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.Duplicate(node)) }
    }
    fun dismissFileOpDialog()                    = _uiState.update { it.copy(fileOpDialog = null) }
    fun openCreatedFile(documentUri: String) {
        _uiState.update { it.copy(fileOpDialog = null) }
        openFile(documentUri)
    }

    fun renameNode(node: FileNode, newName: String) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val rootUri = _uiState.value.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project open") }
            return
        }
        val sourceParentUri = node.parentDocumentUri ?: rootUri
        val input = newName.trim()
        val oldPath = projectPathForDocument(node.documentUri)
        val oldRelativePath = oldPath.removePrefix("/")
        val itemType = if (node.isDirectory) "folder" else "file"
        val baseSegments = projectRelativeSegments(sourceParentUri, rootUri) ?: run {
                _uiState.update { it.copy(statusMessage = "Could not determine the current folder") }
                return
            }
        when (val normalized = resourcePaths.resolve(input, baseSegments)) {
            ResourcePathResolution.OutsideBoundary ->
                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "The path cannot go above the project root"), statusMessage = "The path cannot go above the project root") }
            ResourcePathResolution.MissingFinalName,
            ResourcePathResolution.InvalidComponent ->
                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "Enter a valid rename path"), statusMessage = "Enter a valid rename path") }
            is ResourcePathResolution.Success -> viewModelScope.launch {
                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = null, isSubmitting = true)) }
                when (val resolved = fileMutations.resolvePath(PathResolutionScope(startUri = rootUri, boundaryUri = rootUri), normalized.segments)) {
                    is PathResolutionResult.Resolved -> {
                        val result = fileMutations.moveAndRename(
                            sourceUri = node.documentUri,
                            sourceParentUri = sourceParentUri,
                            targetParentUri = resolved.parentUri,
                            newName = resolved.leafName,
                        )
                        when (result) {
                            is SafeMutationResult.Created -> {
                                refreshProjectNow()
                                reconcileOpenTabReference(node.documentUri, result.documentUri, resolved.leafName)
                                val newPath = resourcePaths.format(normalized.segments)
                                val newRelativePath = newPath.removePrefix("/")
                                val configuredPreview = _uiState.value.defaultPreviewPath
                                val previewWasRenamed = configuredPreview == oldRelativePath ||
                                    (node.isDirectory && configuredPreview?.startsWith("$oldRelativePath/") == true)
                                val updatedPreview = if (previewWasRenamed && configuredPreview != null) {
                                    val suffix = configuredPreview.removePrefix(oldRelativePath)
                                    (newRelativePath + suffix).takeIf { it.substringAfterLast('.', "").lowercase() in setOf("html", "htm") }
                                } else configuredPreview
                                val successMessage = "Successfully renamed $itemType from '$oldPath' to '$newPath'"
                                _uiState.update { state ->
                                    state.copy(
                                        defaultPreviewPath = updatedPreview,
                                        fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(isSubmitting = false, errorMessage = null, resultMessage = successMessage),
                                        statusMessage = successMessage,
                                    )
                                }
                                if (previewWasRenamed) scheduleWorkspaceSave()
                            }
                            is SafeMutationResult.Partial -> {
                                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "Rename partially completed; inspect both source and destination", isSubmitting = false), statusMessage = "Rename partially completed") }
                            }
                            SafeMutationResult.Duplicate -> {
                                fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                                _uiState.update { state ->
                                    state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "\u201c${resolved.leafName}\u201d already exists"))
                                }
                            }
                            SafeMutationResult.InspectionFailed -> {
                                fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "Could not inspect the rename destination"), statusMessage = "Could not inspect the rename destination") }
                            }
                            SafeMutationResult.Failed -> {
                                fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "Rename failed"), statusMessage = "Rename failed") }
                            }
                        }
                    }
                    is PathResolutionResult.BlockedByFile -> {
                        fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                        _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "A file blocks part of the rename path"), statusMessage = "A file blocks part of the rename path") }
                    }
                    is PathResolutionResult.IntermediateCreationFailed -> {
                        fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                        _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "Could not create the rename path"), statusMessage = "Could not create the rename path") }
                    }
                    is PathResolutionResult.IntermediateNameMismatch -> {
                        fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                        _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "A rename folder could not be created exactly"), statusMessage = "A rename folder could not be created exactly") }
                    }
                    PathResolutionResult.EmptyPath -> _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "Could not resolve the rename path"), statusMessage = "Could not resolve the rename path") }
                }
                _uiState.update { state ->
                    val dialog = state.fileOpDialog as? FileOpDialog.Rename
                    if (dialog != null) state.copy(fileOpDialog = dialog.copy(isSubmitting = false)) else state
                }
            }
        }
    }

    fun deleteNode(node: FileNode, selectedNodes: List<FileNode> = emptyList()) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val nodes = if (selectedNodes.isEmpty()) listOf(node) else selectedNodes
        val targetPaths = nodes.map { projectPathForDocument(it.documentUri) }
        val allAffectedUris = nodes.flatMap { affectedUris(it) }.toSet()
        viewModelScope.launch {
            _uiState.update { state -> state.copy(fileMutationLoading = true, statusMessage = "Deleting ${nodes.size} item(s)…", fileOpDialog = (state.fileOpDialog as? FileOpDialog.Delete)?.copy(errorMessage = null, isSubmitting = true)) }
            var deletedCount = 0
            val deletedUris = mutableSetOf<String>()
            val deletedProjectPaths = mutableSetOf<String>()
            nodes.forEachIndexed { index, selectedNode ->
                if (fileMutations.delete(selectedNode.documentUri) &&
                    !fileMutations.exists(selectedNode.documentUri)
                ) {
                    deletedCount++
                    deletedUris += allAffectedUris.intersect(affectedUris(selectedNode))
                    deletedProjectPaths += targetPaths[index].removePrefix("/")
                }
            }
            _uiState.value.openTabs
                .filter { it.documentUri in deletedUris }
                .forEach { closeTab(it.id) }
            refreshProjectNow()
            val complete = deletedCount == nodes.size
            val message = if (complete && nodes.size == 1) {
                "Deleted ${targetPaths.single()}"
            } else if (complete) {
                "Deleted ${targetPaths.joinToString(", ")}"
            } else {
                "Deleted $deletedCount of ${nodes.size} item(s)"
            }
            val configuredPreview = _uiState.value.defaultPreviewPath
            val previewWasDeleted = configuredPreview != null && deletedProjectPaths.any { path ->
                configuredPreview == path || (path.isNotEmpty() && configuredPreview.startsWith("$path/"))
            }
            _uiState.update { it.copy(
                defaultPreviewPath = if (previewWasDeleted) null else it.defaultPreviewPath,
                fileMutationLoading = false,
                fileOpDialog = (it.fileOpDialog as? FileOpDialog.Delete)?.copy(
                    isSubmitting = false,
                    errorMessage = if (complete) null else "$message. Some items could not be removed; inspect the project and retry.",
                    resultMessage = if (complete) message else null,
                ),
                isMultiSelectMode = false,
                selectedUris = emptySet(),
                statusMessage = message,
            ) }
            if (previewWasDeleted) scheduleWorkspaceSave()
        }
    }

    fun createFileInDirectory(parentNode: FileNode, name: String) {
        createEntryFromInput(parentNode, name, isDirectory = false)
    }

    fun createFolderInDirectory(parentNode: FileNode, name: String) {
        createEntryFromInput(parentNode, name, isDirectory = true)
    }

    fun createFileAtRoot(name: String) {
        val rootUri = _uiState.value.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project open") }
            return
        }
        createEntryFromInput(
            FileNode(rootUri, "", "vnd.android.document/directory"),
            name,
            isDirectory = false,
        )
    }

    fun createFolderAtRoot(name: String) {
        val rootUri = _uiState.value.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project open") }
            return
        }
        createEntryFromInput(
            FileNode(rootUri, "", "vnd.android.document/directory"),
            name,
            isDirectory = true,
        )
    }

    private fun createEntryFromInput(parentNode: FileNode, rawName: String, isDirectory: Boolean) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val rootUri = _uiState.value.projectRootUri
        if (rootUri == null) {
            setCreateError(isDirectory, "No project is open")
            return
        }
        val baseSegments = projectRelativeSegments(parentNode.documentUri, rootUri)
            ?: run {
                setCreateError(isDirectory, "Could not determine the selected folder")
                return
            }
        when (val normalized = resourcePaths.resolve(rawName, baseSegments)) {
            is ResourcePathResolution.Success -> {
                markCreateSubmitting(isDirectory)
                viewModelScope.launch {
                    when (val resolved = fileMutations.resolvePath(PathResolutionScope(startUri = rootUri, boundaryUri = rootUri), normalized.segments)) {
                        is PathResolutionResult.Resolved -> createEntry(
                            parentUri = resolved.parentUri,
                            name = resolved.leafName,
                            isDirectory = isDirectory,
                            projectPathSegments = normalized.segments,
                            createdIntermediateUris = resolved.createdIntermediateUris,
                            submittingAlreadyMarked = true,
                        )
                        PathResolutionResult.EmptyPath -> setCreateError(isDirectory, "Enter a file or folder name")
                        is PathResolutionResult.BlockedByFile -> {
                            fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                            setCreateError(isDirectory, "A file blocks part of this path")
                        }
                        is PathResolutionResult.IntermediateCreationFailed -> {
                            fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                            setCreateError(isDirectory, "Could not create the required folders")
                        }
                        is PathResolutionResult.IntermediateNameMismatch -> {
                            fileMutations.rollbackCreatedDirectories(resolved.createdIntermediateUris)
                            setCreateError(isDirectory, "A required folder could not be created with its exact name")
                        }
                    }
                }
            }
            ResourcePathResolution.OutsideBoundary ->
                setCreateError(isDirectory, "The path cannot go above the project root")
            ResourcePathResolution.MissingFinalName ->
                setCreateError(isDirectory, "Enter a file or folder name")
            ResourcePathResolution.InvalidComponent ->
                setCreateError(isDirectory, "Enter a valid file or folder path")
        }
    }

    private fun projectRelativeSegments(documentUri: String, rootUri: String): List<String>? {
        if (documentUri == rootUri) return emptyList()
        fun find(nodes: List<FileNode>, prefix: List<String>): List<String>? {
            for (node in nodes) {
                val next = prefix + node.displayName
                if (node.documentUri == documentUri) return next
                if (node.isDirectory) {
                    val found = find(node.children, next)
                    if (found != null) return found
                }
            }
            return null
        }
        return find(_uiState.value.fileTree, emptyList())
    }

    /** Resolve an editor document to a browser-loadable path only inside a native local workspace. */
    fun browserContentUri(documentUri: String): String? {
        val state = _uiState.value
        if (!state.localStorageConfirmed) {
            _uiState.update { it.copy(statusMessage = "Cloud or remote project locations cannot be previewed") }
            return null
        }
        if (!state.localWorkspaceAvailable) {
            _uiState.update {
                it.copy(statusMessage = state.localWorkspaceExplanation ?: "Browser preview requires a native local project path")
            }
            return null
        }
        val rootUri = state.projectRootUri ?: return null
        val rootPath = safRepository.localFilesystemPath(rootUri)
        val documentPath = safRepository.localFilesystemPath(documentUri)
        if (rootPath == null || documentPath == null) {
            _uiState.update { it.copy(statusMessage = "The local file path could not be resolved for browser preview") }
            return null
        }
        val root = runCatching { File(rootPath).canonicalFile.toPath() }.getOrNull()
        val document = runCatching { File(documentPath).canonicalFile }.getOrNull()
        if (root == null || document == null || !document.toPath().startsWith(root) || !document.isFile || !document.canRead()) {
            _uiState.update { it.copy(statusMessage = "This file is unavailable for safe browser preview") }
            return null
        }
        _uiState.update { it.copy(statusMessage = "") }
        return document.toURI().toString()
    }

    fun setDefaultPreviewFile(documentUri: String) {
        val state = _uiState.value
        if (!state.localStorageConfirmed || !state.localWorkspaceAvailable) {
            _uiState.update { it.copy(statusMessage = state.localWorkspaceExplanation ?: "Default browser preview requires a native local project path") }
            return
        }
        val rootUri = state.projectRootUri ?: return
        viewModelScope.launch {
            val node = _uiState.value.fileTree.findNode(documentUri)
            val name = node?.displayName ?: fileMutations.displayName(documentUri)
            if (node == null || node.isDirectory || name == null || !name.substringAfterLast('.', "").equals("html", true) &&
                !name.substringAfterLast('.', "").equals("htm", true)
            ) {
                _uiState.update { it.copy(statusMessage = "Choose an HTML file to set as the default preview") }
                return@launch
            }
            if (fileMutations.isSameOrDescendant(rootUri, documentUri) != true) {
                _uiState.update { it.copy(statusMessage = "The selected HTML file is outside this project") }
                return@launch
            }
            val relativePath = _uiState.value.fileTree.pathTo(documentUri)?.removePrefix("/")
            if (relativePath.isNullOrBlank()) {
                _uiState.update { it.copy(statusMessage = "The selected file is no longer in the project tree") }
                return@launch
            }
            _uiState.update { it.copy(defaultPreviewPath = relativePath, statusMessage = "Default preview set to $relativePath") }
            scheduleWorkspaceSave()
        }
    }

    /** Resolve the editor Preview action to a project-root HTTP URL and reusable browser destination. */
    suspend fun resolveActivePreviewTarget(): ProjectPreviewTarget? {
        val initialState = _uiState.value
        if (!initialState.localStorageConfirmed || !initialState.localWorkspaceAvailable) {
            _uiState.update { it.copy(statusMessage = initialState.localWorkspaceExplanation ?: "Browser preview requires a native local project path") }
            return null
        }
        val state = _uiState.value
        if (!state.localStorageConfirmed || !state.localWorkspaceAvailable) {
            _uiState.update { it.copy(statusMessage = state.localWorkspaceExplanation ?: "Browser preview requires a native local project path") }
            return null
        }
        val active = state.openTabs.firstOrNull { it.isActive }
        val extension = active?.displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val directlyPreviewable = extension in BROWSER_PREVIEW_EXTENSIONS && extension !in setOf("css", "js", "mjs", "cjs", "ts", "tsx", "jsx")
        val targetUri = if (directlyPreviewable && active != null && !active.isBlank) {
            active.documentUri
        } else {
            val relativePath = state.defaultPreviewPath
            if (relativePath.isNullOrBlank()) {
                _uiState.update { it.copy(statusMessage = "No default HTML preview is set. Choose an HTML file in the file tree and set it as the default preview.") }
                return null
            }
            val rootUri = state.projectRootUri ?: return null
            val normalized = resourcePaths.resolve(relativePath, emptyList()) as? ResourcePathResolution.Success
            if (normalized == null || relativePath.startsWith('/') || relativePath.startsWith('\\')) {
                _uiState.update { it.copy(statusMessage = "The configured HTML preview path is invalid. Set the default HTML file again.") }
                return null
            }
            resolveProjectHtmlFile(rootUri, normalized.segments) ?: run {
                _uiState.update { it.copy(statusMessage = "The configured HTML preview file could not be found. Set the default HTML file again.") }
                return null
            }
        }
        val rootUri = state.projectRootUri ?: return null
        val rootPath = safRepository.localFilesystemPath(rootUri)
        val targetPath = safRepository.localFilesystemPath(targetUri)
        if (rootPath == null || targetPath == null) {
            _uiState.update { it.copy(statusMessage = "The local project path could not be resolved for preview") }
            return null
        }
        val root = runCatching { File(rootPath).canonicalFile }.getOrNull()
        val target = runCatching { File(targetPath).canonicalFile }.getOrNull()
        if (root == null || target == null || !root.isDirectory || !target.isFile || !target.canRead() || !target.toPath().startsWith(root.toPath())) {
            _uiState.update { it.copy(statusMessage = "The preview file is unavailable or outside the project root") }
            return null
        }
        val url = try {
            services.projectStaticPreviewServer.urlFor(root.path, target.path)
        } catch (error: Exception) {
            _uiState.update { it.copy(statusMessage = "Could not start the local preview server: ${error.message ?: "project path unavailable"}") }
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(root.path.toByteArray(Charsets.UTF_8))
        val reuseKey = "project-root-" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        _uiState.update { it.copy(statusMessage = "") }
        return ProjectPreviewTarget(url, reuseKey)
    }

    private suspend fun resolveProjectHtmlFile(rootUri: String, segments: List<String>): String? {
        if (segments.isEmpty()) return null
        var currentUri = rootUri
        var target: FileNode? = null
        for ((index, segment) in segments.withIndex()) {
            val children = (fileMutations.inspectChildren(currentUri) as? ChildrenInspectionResult.Success)?.children ?: return null
            val child = children.firstOrNull { it.displayName == segment } ?: return null
            if (fileMutations.isSameOrDescendant(rootUri, child.documentUri) != true) return null
            val last = index == segments.lastIndex
            if (last) {
                if (child.isDirectory || child.displayName.substringAfterLast('.', "").lowercase() !in setOf("html", "htm")) return null
                target = child
            } else {
                if (!child.isDirectory) return null
                currentUri = child.documentUri
            }
        }
        return target?.documentUri
    }

    fun projectPathForDocument(documentUri: String): String {
        val rootUri = _uiState.value.projectRootUri
            ?: return "/${Uri.decode(documentUri).substringAfterLast('/')}"
        val segments = projectRelativeSegments(documentUri, rootUri)
            ?: return "/${Uri.decode(documentUri).substringAfterLast('/')}"
        return resourcePaths.format(segments)
    }

    fun projectPathPreview(parentUri: String, rawPath: String): String {
        val rootUri = _uiState.value.projectRootUri ?: return "Path unavailable"
        val base = projectRelativeSegments(parentUri, rootUri) ?: return "Path unavailable"
        if (rawPath.isBlank()) return resourcePaths.format(base)
        return when (val normalized = resourcePaths.resolve(rawPath, base)) {
            is ResourcePathResolution.Success -> resourcePaths.format(normalized.segments)
            ResourcePathResolution.OutsideBoundary -> "Path would be outside the project"
            ResourcePathResolution.MissingFinalName -> resourcePaths.format(base)
            ResourcePathResolution.InvalidComponent -> "Invalid project path"
        }
    }

    private fun createEntry(
        parentUri: String,
        name: String,
        isDirectory: Boolean,
        projectPathSegments: List<String>,
        createdIntermediateUris: List<String> = emptyList(),
        submittingAlreadyMarked: Boolean = false,
    ) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || normalizedName.contains('/') || normalizedName.contains('\\')) {
            setCreateError(isDirectory, "Enter a valid name without path separators")
            return
        }
        if (!submittingAlreadyMarked) markCreateSubmitting(isDirectory)
        viewModelScope.launch {
            val result = fileMutations.createFile(
                parentUri = parentUri,
                name = normalizedName,
                mimeType = if (isDirectory) {
                    "vnd.android.document/directory"
                } else {
                    EditorLanguageRegistry.mimeTypeForFileName(normalizedName)
                },
            )
            when (result) {
                ExactCreateResult.Duplicate -> {
                    fileMutations.rollbackCreatedDirectories(createdIntermediateUris)
                    setCreateError(
                        isDirectory,
                        "A ${if (isDirectory) "folder" else "file"} already exist with this name. Use a different name",
                    )
                }
                ExactCreateResult.InspectionFailed -> {
                    fileMutations.rollbackCreatedDirectories(createdIntermediateUris)
                    setCreateError(isDirectory, "Could not inspect the target folder")
                }
                ExactCreateResult.Failed -> {
                    fileMutations.rollbackCreatedDirectories(createdIntermediateUris)
                    setCreateError(isDirectory, "Could not create ${if (isDirectory) "folder" else "file"}")
                }
                is ExactCreateResult.Partial -> {
                    fileMutations.delete(result.documentUri)
                    val itemAbsent = !fileMutations.exists(result.documentUri)
                    val parentsRemoved = fileMutations.rollbackCreatedDirectories(createdIntermediateUris)
                    val recovery = if (!itemAbsent || !parentsRemoved) " ${result.recoveryHint}" else ""
                    setCreateError(
                        isDirectory,
                        "The provider created an unexpected item; inspect the destination.$recovery",
                    )
                }
                is ExactCreateResult.Created -> {
                    EditorLanguageRegistry.templateForFileName(normalizedName)?.let { template ->
                        if (!fileMutations.write(result.documentUri, template.toByteArray(Charsets.UTF_8))) {
                            fileMutations.delete(result.documentUri)
                            fileMutations.rollbackCreatedDirectories(createdIntermediateUris)
                            setCreateError(isDirectory, "Could not initialize the HTML file")
                            return@launch
                        }
                    }
                    refreshProjectNow()
                    val parentPath = resourcePaths.format(projectPathSegments.dropLast(1))
                    val successMessage = "Successfully created \"$normalizedName\" in $parentPath"
                    _uiState.update { state ->
                        state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.CreateFolder)?.copy(isSubmitting = false, errorMessage = null, resultMessage = successMessage)
                            ?: (state.fileOpDialog as? FileOpDialog.CreateFile)?.copy(isSubmitting = false, errorMessage = null, resultMessage = successMessage, createdDocumentUri = result.documentUri), statusMessage = successMessage)
                    }
                }
            }
        }
    }

    private fun markCreateSubmitting(isDirectory: Boolean) {
        _uiState.update { state ->
            val dialog = if (isDirectory) {
                (state.fileOpDialog as? FileOpDialog.CreateFolder)?.copy(errorMessage = null, isSubmitting = true)
            } else {
                (state.fileOpDialog as? FileOpDialog.CreateFile)?.copy(errorMessage = null, isSubmitting = true)
            }
            state.copy(fileOpDialog = dialog ?: state.fileOpDialog)
        }
    }

    private fun setCreateError(isDirectory: Boolean, message: String) {
        _uiState.update { state ->
            val dialog = if (isDirectory) {
                (state.fileOpDialog as? FileOpDialog.CreateFolder)?.copy(errorMessage = message, isSubmitting = false)
            } else {
                (state.fileOpDialog as? FileOpDialog.CreateFile)?.copy(errorMessage = message, isSubmitting = false)
            }
            state.copy(fileOpDialog = dialog ?: state.fileOpDialog, statusMessage = message)
        }
    }

    fun duplicateFile(node: FileNode, newName: String) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val parentUri = node.parentDocumentUri ?: run {
            _uiState.update { it.copy(fileOpDialog = null, statusMessage = "Cannot duplicate: no parent") }
            return
        }
        val parentSiblings = _uiState.value.fileTree.findNode(parentUri)?.children
            ?: _uiState.value.fileTree
        if (parentSiblings.any { it.displayName.equals(newName, ignoreCase = true) }) {
            _uiState.update { state ->
                state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Duplicate)?.copy(errorMessage = "\u201c$newName\u201d already exists in this folder"))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Duplicate)?.copy(errorMessage = null, isSubmitting = true)) }
            val copied = fileMutations.copy(node.documentUri, parentUri, newName)
            val newUri = when (copied) {
                is SafeMutationResult.Created -> copied.documentUri
                is SafeMutationResult.Partial -> {
                    _uiState.update {
                        it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.Duplicate)?.copy(isSubmitting = false, errorMessage = "Duplicate partially completed: ${copied.recoveryHint}"), statusMessage = "Duplicate partially completed")
                    }
                    return@launch
                }
                else -> {
                    _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.Duplicate)?.copy(isSubmitting = false, errorMessage = "The item could not be duplicated"), statusMessage = "The item could not be duplicated") }
                    return@launch
                }
            }
            refreshProjectNow()
            openFilePermanent(newUri)
            _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.Duplicate)?.copy(isSubmitting = false, errorMessage = null, resultMessage = "Duplicated as $newName"), statusMessage = "Duplicated as $newName") }
        }
    }


    /**
     * Load the immediate children of [parentUri] directly from SAF.
     * Called by IdeTopBar's path-navigator dropdown, so it always returns
     * live data regardless of which tree nodes are expanded.
     */
    suspend fun loadNavChildren(parentUri: String): List<FileNode> =
        visibleFileTreeChildren(parentUri)


    /** Open the inline Save-As dialog pre-filled with the active tab name. */
    fun showSaveAsDialog() {
        showSaveFileCreateDialog()
    }

    fun showBlankTabSaveDialog(tabId: String? = null) {
        val active = if (tabId == null) _uiState.value.openTabs.firstOrNull { it.isActive }
            else _uiState.value.openTabs.firstOrNull { it.id == tabId }
        if (active?.isBlank != true) return
        showSaveFileCreateDialog(active.id)
    }

    private fun showSaveFileCreateDialog(tabId: String? = null) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val state = _uiState.value
        if (state.editorFileLoading) return
        val active = if (tabId == null) state.openTabs.firstOrNull { it.isActive }
            else state.openTabs.firstOrNull { it.id == tabId }
        if (active == null || active.isImagePreview ||
            (active.content == null && pendingContent[active.id] == null && !active.isBlank)
        ) return
        val rootUri = state.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project open") }
            return
        }
        val rootNode = FileNode(rootUri, state.projectName.ifBlank { "Project" }, "vnd.android.document/directory")
        _uiState.update {
            it.copy(
                fileOpDialog = FileOpDialog.CreateFile(
                    parentNode = rootNode,
                    suggestedName = active.displayName,
                    sourceTabId = active.id,
                ),
            )
        }
    }

    /**
     * Write active file content to [relativePath] within the project root.
     * Supports "newname.kt" (creates in root) or "src/utils/Foo.kt" (creates
     * intermediate dirs if needed).
     */
    fun saveAsAtPath(relativePath: String, tabId: String? = null) {
        if (!requireConfirmedLocalStorageForMutation()) return
        val initialState = _uiState.value
        val rootUri = initialState.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project open") }
            return
        }
        val active = if (tabId == null) initialState.openTabs.firstOrNull { it.isActive }
            else initialState.openTabs.firstOrNull { it.id == tabId }
        active ?: return
        if (active.isImagePreview || (active.content == null && pendingContent[active.id] == null && !active.isBlank)) return
        val content = pendingContent[active.id] ?: active.content ?: if (active.isBlank) "" else return
        val normalized = resourcePaths.resolve(relativePath, emptyList())
        val segments = when (normalized) {
            is ResourcePathResolution.Success -> normalized.segments
            ResourcePathResolution.OutsideBoundary -> {
                _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.CreateFile)?.copy(errorMessage = "The path cannot go above the project root"), statusMessage = "The path cannot go above the project root") }
                return
            }
            ResourcePathResolution.MissingFinalName,
            ResourcePathResolution.InvalidComponent -> {
                _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.CreateFile)?.copy(errorMessage = "Enter a valid project-relative file path"), statusMessage = "Invalid path") }
                return
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.CreateFile)?.copy(errorMessage = null, isSubmitting = true), fileMutationLoading = true) }
            val resolved = fileMutations.resolvePath(PathResolutionScope(startUri = rootUri, boundaryUri = rootUri), segments)
            val path = resolved as? PathResolutionResult.Resolved ?: run {
                val created = when (resolved) {
                    is PathResolutionResult.BlockedByFile -> resolved.createdIntermediateUris
                    is PathResolutionResult.IntermediateCreationFailed -> resolved.createdIntermediateUris
                    is PathResolutionResult.IntermediateNameMismatch -> resolved.createdIntermediateUris
                    else -> emptyList()
                }
                val parentsRemoved = fileMutations.rollbackCreatedDirectories(created)
                _uiState.update {
                    it.copy(
                        fileOpDialog = (it.fileOpDialog as? FileOpDialog.CreateFile)?.copy(
                            errorMessage = if (parentsRemoved) "Could not resolve the destination path" else "Save As partially completed; inspect newly created parent folders",
                            isSubmitting = false,
                        ),
                        fileMutationLoading = false,
                        statusMessage = if (parentsRemoved) "Save As: could not resolve path"
                        else "Save As partially completed; inspect newly created parent folders",
                    )
                }
                return@launch
            }
            val (targetParentUri, leafName) = path.parentUri to path.leafName
            val created = fileMutations.createFile(
                targetParentUri,
                leafName,
                EditorLanguageRegistry.mimeTypeForFileName(leafName),
            )
            val newUri = when (created) {
                is ExactCreateResult.Created -> created.documentUri
                is ExactCreateResult.Partial -> {
                    fileMutations.delete(created.documentUri)
                    val absent = !fileMutations.exists(created.documentUri)
                    val parentsRemoved = fileMutations.rollbackCreatedDirectories(path.createdIntermediateUris)
                    _uiState.update {
                        it.copy(
                            fileOpDialog = (it.fileOpDialog as? FileOpDialog.CreateFile)?.copy(
                                errorMessage = if (absent && parentsRemoved) "Save As failed; the created file was removed" else "Save As may have left a partially created file",
                                isSubmitting = false,
                            ),
                            fileMutationLoading = false,
                            statusMessage = if (absent && parentsRemoved) "Save As failed; unexpected provider item was removed"
                            else "Save As may have left ${created.documentUri}: ${created.recoveryHint}",
                        )
                    }
                    return@launch
                }
                else -> {
                    val parentsRemoved = fileMutations.rollbackCreatedDirectories(path.createdIntermediateUris)
                val message = if (created is ExactCreateResult.Duplicate) {
                    "\u201c$leafName\u201d already exists — choose a different name"
                } else {
                    "Save As: could not inspect or create the target"
                }
                    _uiState.update {
                        it.copy(
                            fileOpDialog = (it.fileOpDialog as? FileOpDialog.CreateFile)?.copy(
                                errorMessage = message,
                                isSubmitting = false,
                            ),
                            fileMutationLoading = false,
                            statusMessage = if (parentsRemoved) message else "$message; parent-folder cleanup was incomplete",
                        )
                    }
                    return@launch
                }
            }
            val ok = fileMutations.write(newUri, content.toByteArray(Charsets.UTF_8))
            if (!ok) {
                fileMutations.delete(newUri)
                val itemAbsent = !fileMutations.exists(newUri)
                val parentsRemoved = fileMutations.rollbackCreatedDirectories(path.createdIntermediateUris)
                _uiState.update {
                    it.copy(
                        fileOpDialog = (it.fileOpDialog as? FileOpDialog.CreateFile)?.copy(
                            errorMessage = if (itemAbsent && parentsRemoved) "Save As failed; the created file was removed" else "Save As partially completed; inspect the created file and parent folders",
                            isSubmitting = false,
                        ),
                        fileMutationLoading = false,
                        statusMessage = if (itemAbsent && parentsRemoved) "Save As: write failed; created file was removed"
                        else "Save As partially completed; inspect ${newUri} and any newly created parent folders",
                    )
                }
                return@launch
            }
            val newLang = EditorLanguageRegistry.languageForFileName(leafName)
            pendingContent.remove(active.id)
            crashRecovery.clearUnsavedContent(rootUri, active.id)
            sendLanguageServerNotificationForTab(
                active,
                "textDocument/didClose",
                JSONObject().put("textDocument", JSONObject().put("uri", languageServerDocumentUri(active.documentUri))),
            )
            refreshProjectNow()
            _uiState.update { state ->
                state.copy(
                    openTabs     = state.openTabs.map {
                        if (it.id == active.id) it.copy(
                            documentUri = newUri, displayName = leafName,
                            language = newLang, content = content, isDirty = false, isBlank = false,
                        ) else it
                    },
                    fileOpDialog  = (state.fileOpDialog as? FileOpDialog.CreateFile)?.copy(
                        isSubmitting = false,
                        errorMessage = null,
                        resultMessage = "Saved as $leafName",
                        createdDocumentUri = newUri,
                    ),
                    languageServerDiagnostics = state.languageServerDiagnostics - active.documentUri,
                    fileMutationLoading = false,
                    statusMessage = "Saved as $leafName",
                )
            }
            initializeLanguageServerDocument(active.id, newUri, newLang, content, rootUri)
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private suspend fun nextAvailableProjectName(
        targetParentUri: String,
        baseName: String,
    ): String? {
        val children = when (val inspection = fileMutations.inspectChildren(targetParentUri)) {
            is ChildrenInspectionResult.Success -> inspection.children
            is ChildrenInspectionResult.Failed -> return null
        }
        fun occupied(candidate: String): Boolean =
            children.any { it.isDirectory && it.displayName.equals(candidate, ignoreCase = true) }

        if (!occupied(baseName)) return baseName
        for (index in 2..100) {
            val candidate = "$baseName $index"
            if (!occupied(candidate)) return candidate
        }
        return null
    }

    private fun projectMutationReason(result: SafeMutationResult): String =
        when (result) {
            SafeMutationResult.Duplicate -> "a project with that name already exists"
            SafeMutationResult.InspectionFailed -> "the destination could not be inspected"
            SafeMutationResult.Failed -> "the operation could not be completed"
            is SafeMutationResult.Partial -> "source and destination may both remain; inspect both locations"
            is SafeMutationResult.Created -> "unexpected result"
        }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        else -> "${bytes / (1024L * 1024L * 1024L)} GB"
    }

    private fun extractProjectName(treeUriString: String): String = try {
        Uri.decode(treeUriString).substringAfterLast('/').ifEmpty { "Project" }
    } catch (_: Exception) { "Project" }

    private fun displayNameFromUri(documentUri: String): String = try {
        Uri.decode(documentUri).substringAfterLast('/').ifEmpty { "file" }
    } catch (_: Exception) { "file" }

}
