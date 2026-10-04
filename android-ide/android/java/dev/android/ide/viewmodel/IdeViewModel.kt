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
import dev.android.ide.data.CrashRecoveryRepository
import dev.android.ide.data.EditorSettingsRepository
import dev.android.ide.data.ProjectRepository
import dev.android.ide.data.SessionRepository
import dev.android.ide.data.ThemeResolver
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
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.lifecycle.LifecycleStateStore
import dev.android.ide.saf.ChildrenInspectionResult
import dev.android.ide.saf.DocumentPresence
import dev.android.ide.saf.ExactCreateResult
import dev.android.ide.saf.PathResolutionResult
import dev.android.ide.saf.PathResolutionScope
import dev.android.ide.saf.SafeMutationResult
import dev.android.ide.saf.SafRepository
import dev.android.ide.saf.FileManagementService
import dev.android.ide.project.ProjectMetadataStore
import dev.android.ide.saf.TextDocumentCodec
import dev.android.ide.saf.AndroidIdeDocumentsProvider
import dev.android.ide.project.ProjectStorageAdapterImpl
import dev.android.ide.runtime.LanguageServerRuntimeAdapterImpl
import dev.android.ide.viewmodel.model.AppScreen
import dev.android.ide.viewmodel.model.EditorTab
import dev.android.ide.viewmodel.model.FileNode
import dev.android.ide.viewmodel.model.FileOpDialog
import dev.android.ide.viewmodel.model.FileSearchResult
import dev.android.ide.viewmodel.model.IdeUiState
import dev.android.ide.viewmodel.model.ProjectSwitchRequest
import dev.android.ide.viewmodel.model.NormalizedPathResult
import dev.android.ide.viewmodel.model.ancestorsOf
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import dev.android.ide.viewmodel.model.findNode
import dev.android.ide.viewmodel.model.normalizeProjectPath
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class IdeViewModel(application: Application) : AndroidViewModel(application) {

    private data class LanguageServerContext(val projectId: String, val serverId: String)

    private var fileSearchJob: Job? = null
    private var projectSearchJob: Job? = null
    private var projectSearchGeneration = 0L
    private val searchBatchSize = 16

    private val safRepository      = SafRepository(application)
    private val storageAdapter     = ProjectStorageAdapterImpl(safRepository)
    private val fileMutations      = FileManagementService(safRepository)
    private val projectMetadata   = ProjectMetadataStore(fileMutations)
    private val projectRepository  = ProjectRepository(application)
    private val sessionRepository  = SessionRepository(application)
    private val editorSettingsRepo = EditorSettingsRepository(application)
    private val crashRecovery      = CrashRecoveryRepository(application)
    private val lifecycleStore     = LifecycleStateStore(application)
    private val themeResolver      = ThemeResolver(application)

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

    private val languageServers = LanguageServerRuntimeAdapterImpl(application) { projectId, serverId, message ->
        _editorCommand.tryEmit(
            EditorOutbound.LanguageServerMessage(
                projectId = projectId,
                serverId = serverId,
                message = rewriteLanguageServerResponseUris(message.toString(Charsets.UTF_8)),
            ),
        )
    }

    /**
     * Unsaved editor content keyed by tab ID.
     * Kept outside IdeUiState to avoid full Compose recomposition on every keystroke.
     */
    private val pendingContent = mutableMapOf<String, String>()
    private val lspDocumentVersions = mutableMapOf<String, Int>()
    private var workspaceSaveJob: Job? = null

    init {
        // Read the previous session marker before marking this process as active.
        // Otherwise every launch looks like an unclean exit.
        val previousSessionDirty = crashRecovery.isPreviousSessionDirty()
        crashRecovery.markSessionStart()
        lifecycleStore.markProcessStarted()
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
            openTabUris     = state.openTabs.filter { !it.isBlank }.map { it.documentUri },
            activeTabUri    = state.openTabs.firstOrNull { it.isActive && !it.isBlank }?.documentUri,
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
        val projectUri = state.projectRootUri ?: return
        val workspace = JSONObject().apply {
            put("schemaVersion", 1)
            put("activeTabUri", state.openTabs.firstOrNull { it.isActive && !it.isBlank }?.documentUri ?: JSONObject.NULL)
            put("openTabUris", org.json.JSONArray(state.openTabs.filter { !it.isBlank }.map { it.documentUri }))
            put("pinnedTabUris", org.json.JSONArray(state.openTabs.filter { !it.isBlank && it.isPinned }.map { it.documentUri }))
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
            val content = pendingContent[tab.id] ?: run {
                _uiState.update {
                    it.copy(statusMessage = "Save failed — draft content was not available")
                }
                return false
            }
            val ok = fileMutations.write(
                tab.documentUri,
                content.toByteArray(Charsets.UTF_8),
            )
            if (!ok) {
                _uiState.update { it.copy(statusMessage = "Save failed — project was not switched") }
                return false
            }
            pendingContent.remove(tab.id)
            if (projectRootUri != null) {
                crashRecovery.clearUnsavedContent(projectRootUri, tab.id)
            }
        }
        _uiState.update { current ->
            current.copy(
                openTabs = current.openTabs.map {
                    if (it.isDirty && !it.isBlank) it.copy(isDirty = false, isSaving = false) else it
                },
            )
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
        val capabilities = fileMutations.inspectStorage(location)
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
            return
        }
        if (!ensureProjectMetadata(treeUriString, displayName, registeredProject?.createdMs ?: openedAt)) return
        // tabs — prevents stale models from leaking into the new project (same filename
        // in both projects would reuse the old model and show wrong content).
        sendEditorCommand(EditorOutbound.CloseAllModels)
        // Close tabs from the previous project; restore tabs for the new project.
        _uiState.value.openTabs.forEach { pendingContent.remove(it.id) }
        _uiState.update { it.copy(
            projectName    = displayName,
            projectRootUri = treeUriString,
            currentScreen  = if (restoreAtHome) AppScreen.HOME else AppScreen.EDITOR,
            openTabs       = emptyList(),
            activeTabId    = null,
            languageIntelligenceAvailable = false,
            recoveryEntries = emptyList(),
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
            val projects = projectRepository.getAll()
            _uiState.update { it.copy(projectDetailsByUri = emptyMap() ) }
            val capabilitiesByUri = mutableMapOf<String, dev.android.ide.contracts.ProjectStorageCapabilities>()
            val verifiedProjects = projects.map { project ->
                val capabilities = fileMutations.inspectStorage(
                    ProjectLocation(
                        stableId = project.stableLocationId,
                        displayLabel = project.locationLabel,
                        userVisiblePath = project.locationLabel,
                    ),
                )
                capabilitiesByUri[project.uri] = capabilities
                project.copy(
                    capabilityState = capabilities.state,
                    capabilityMessage = capabilities.explanation,
                ).also { verified ->
                    if (verified.capabilityState != project.capabilityState ||
                        verified.capabilityMessage != project.capabilityMessage
                    ) projectRepository.upsert(verified)
                }
            }
            val details = verifiedProjects.mapNotNull { project ->
                val metadata = fileMutations.inspectTree(project.uri) ?: return@mapNotNull null
                project.uri to ProjectDetails(
                    project = project,
                    description = metadata.description,
                    creationTimeMs = metadata.creationTimeMs ?: project.createdMs,
                    lastModifiedTimeMs = metadata.lastModifiedTimeMs,
                    storageProvider = metadata.storageProvider,
                    storagePath = metadata.storagePath,
                    fileCount = metadata.fileCount,
                    folderCount = metadata.folderCount,
                    totalBytes = metadata.totalBytes,
                    storageCapabilities = capabilitiesByUri[project.uri]
                        ?: fileMutations.inspectStorage(
                            ProjectLocation(
                                stableId = project.stableLocationId,
                                displayLabel = project.locationLabel,
                                userVisiblePath = project.locationLabel,
                                capabilityState = project.capabilityState,
                            ),
                        ),
                    languageBytes = metadata.languageBytes,
                    git = metadata.git,
                )
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
                    fileTree       = emptyList(),
                    openTabs       = emptyList(),
                    activeTabId    = null,
                    languageIntelligenceAvailable = false,
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

    /**
     * Copy [node] (or all selected nodes if in multi-select mode) to the clipboard.
     * Exits multi-select mode immediately so the user can navigate to a destination.
     */
    fun copyFileNode(node: FileNode) {
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
        val items  = _uiState.value.clipboardItems
        val isCut  = _uiState.value.clipboardIsCut
        if (items.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(fileMutationLoading = true, statusMessage = "Processing file operation…") }
            var successCount = 0
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
        }
    }

    // ── Import / Export ────────────────────────────────────────────────────

    fun importFiles(targetDirUri: String, sourceUris: List<String>) {
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
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.Export(node)) }
    }

    fun exportProject() {
        _uiState.update { it.copy(statusMessage = "Choose a destination file to export the project") }
    }

    fun exportProject(projectUri: String, destinationUri: String) {
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
        if (markActive) _uiState.update { it.copy(languageIntelligenceAvailable = false) }
        val existing = _uiState.value.openTabs.find { it.documentUri == documentUri }
        if (existing != null) {
            if (!temporary && existing.isTemporary) pinTab(existing.id)
            if (markActive) selectTab(existing.id)
            _uiState.update { it.copy(editorFileLoading = false) }
            return
        }

        if (temporary) {
            val oldTemp = _uiState.value.openTabs.firstOrNull { it.isTemporary }
            if (oldTemp != null) {
                val replacedActiveTab = _uiState.value.activeTabId == oldTemp.id
                pendingContent.remove(oldTemp.id)
                _uiState.update { state ->
                    val remaining  = state.openTabs.filter { it.id != oldTemp.id }
                    val newActive  = if (state.activeTabId == oldTemp.id) remaining.lastOrNull()?.id else state.activeTabId
                    state.copy(
                        openTabs    = remaining.map { it.copy(isActive = it.id == newActive) },
                        activeTabId = newActive,
                        languageIntelligenceAvailable = if (replacedActiveTab) false else state.languageIntelligenceAvailable,
                    )
                }
                if (replacedActiveTab && !markActive) _uiState.value.activeTabId?.let(::selectTab)
            }
        }

        val bytes = fileMutations.read(documentUri) ?: run {
            _uiState.update { it.copy(editorFileLoading = false, statusMessage = "Could not open file") }
            return
        }

        // Monaco and the Kotlin string allocation that precedes it.
        if (bytes.size > 5 * 1024 * 1024) {
            _uiState.update { it.copy(statusMessage = "Cannot open: file is ${bytes.size / 1_048_576} MB (5 MB limit)") }
            _uiState.update { it.copy(editorFileLoading = false) }
            return
        }
        val displayName = _uiState.value.fileTree.findNode(documentUri)?.displayName
            ?: fileMutations.displayName(documentUri)
            ?: displayNameFromUri(documentUri)
        // Binary content (.apk, .class, compiled assets) corrupts Monaco's text model.
        val decodedFile = TextDocumentCodec.decode(bytes)
        if (decodedFile == null) {
            _uiState.update { it.copy(fileOpDialog = FileOpDialog.BinaryOpenError(displayName)) }
            _uiState.update { it.copy(editorFileLoading = false) }
            return
        }

        val content = decodedFile.text
        val language = EditorLanguageRegistry.languageForFileName(displayName)
        val languageServer = startLanguageServerForActiveProject(language)

        val newTab = EditorTab(
            documentUri = documentUri,
            displayName = displayName,
            language    = language,
            content     = content,
            isActive    = markActive,
            isTemporary = temporary && !pinned,
            isPinned = pinned,
        )
        _uiState.update { state ->
            val tabs = if (markActive) {
                state.openTabs.map { it.copy(isActive = false) } + newTab
            } else {
                state.openTabs + newTab
            }
            state.copy(
                openTabs      = tabs,
                activeTabId   = if (markActive) newTab.id else state.activeTabId,
                currentScreen = AppScreen.EDITOR,
                hasEditorSelection = false,
                languageIntelligenceAvailable = if (markActive) languageServer != null else state.languageIntelligenceAvailable,
                editorFileLoading = false,
            )
        }
        languageServer?.let { context ->
            lspDocumentVersions[documentUri] = 1
            sendLanguageServerNotification(
                context = context,
                method = "textDocument/didOpen",
                params = JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("uri", languageServerDocumentUri(documentUri))
                        put("languageId", language)
                        put("version", 1)
                        put("text", content)
                    })
                },
            )
        }
        scheduleWorkspaceSave()
    }

    private suspend fun startLanguageServerForActiveProject(
        languageId: String,
        rootUri: String? = _uiState.value.projectRootUri,
    ): LanguageServerContext? {
        rootUri ?: return null
        val stored = projectRepository.getAll().firstOrNull {
            it.stableLocationId == rootUri || it.uri == rootUri
        } ?: return null
        val project = dev.android.ide.contracts.ProjectIdentity(
            id = stored.stableLocationId,
            name = stored.name,
            description = stored.description,
            location = ProjectLocation(
                stableId = stored.stableLocationId,
                displayLabel = stored.locationLabel,
                userVisiblePath = stored.uri,
                capabilityState = stored.capabilityState,
                capabilityExplanation = stored.capabilityMessage,
            ),
            registeredAt = java.time.Instant.ofEpochMilli(stored.createdMs),
            lastOpenedAt = java.time.Instant.ofEpochMilli(stored.lastOpenedMs),
        )
        val status = languageServers.start(project, languageId)
        if (!status.available && status.explanation != null) {
            _uiState.update { it.copy(statusMessage = "Language intelligence unavailable: ${status.explanation}") }
        }
        return if (status.available && status.initialized) {
            LanguageServerContext(stored.stableLocationId, status.serverId)
        } else {
            null
        }
    }

    private fun languageServerDocumentUri(documentUri: String): String =
        AndroidIdeDocumentsProvider.localFileForUri(getApplication(), documentUri)
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
        viewModelScope.launch {
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
            )
        }
        viewModelScope.launch {
            val available = startLanguageServerForActiveProject(selectedTab.language, projectRootUri) != null
            _uiState.update { state ->
                if (state.activeTabId == tabId && state.projectRootUri == projectRootUri) {
                    state.copy(languageIntelligenceAvailable = available)
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
        _uiState.value.projectRootUri?.let { projectRootUri ->
            crashRecovery.clearUnsavedContent(projectRootUri, tabId)
        }
        _uiState.update { state ->
            val remaining   = state.openTabs.filter { it.id != tabId }
            val newActiveId = if (state.activeTabId == tabId) remaining.lastOrNull()?.id else state.activeTabId
            state.copy(
                openTabs    = remaining.map { it.copy(isActive = it.id == newActiveId) },
                activeTabId = newActiveId,
            )
        }
        if (wasActive) {
            _uiState.value.activeTabId?.let(::selectTab)
                ?: _uiState.update { it.copy(languageIntelligenceAvailable = false) }
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
                projectName = "",
                fileTree = emptyList(),
                openTabs = emptyList(),
                activeTabId = null,
                languageIntelligenceAvailable = false,
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
    fun editorContentForTab(tabId: String, fallback: String?): String =
        pendingContent[tabId] ?: fallback.orEmpty()

    fun onEditorMessage(message: EditorInbound) {
        when (message) {
            is EditorInbound.Ready -> onEditorReady()

            is EditorInbound.ContentChanged -> {
                val tab = _uiState.value.openTabs.find { it.documentUri == message.path } ?: return
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
        val rootUri = _uiState.value.projectRootUri ?: return
        val stored = projectRepository.getAll().firstOrNull {
            it.stableLocationId == rootUri || it.uri == rootUri
        } ?: return
        val definition = LanguageServerRegistry.forLanguage(tab.language) ?: return
        sendLanguageServerNotification(
            LanguageServerContext(stored.stableLocationId, definition.id),
            method,
            params,
        )
    }

    private fun onLanguageServerMessage(rawMessage: String) {
        val tab = _uiState.value.openTabs.firstOrNull { it.isActive } ?: return
        val rootUri = _uiState.value.projectRootUri ?: return
        val stored = projectRepository.getAll().firstOrNull {
            it.stableLocationId == rootUri || it.uri == rootUri
        } ?: return
        val definition = LanguageServerRegistry.forLanguage(tab.language) ?: return
        val message = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        message.optJSONObject("params")
            ?.optJSONObject("textDocument")
            ?.put("uri", languageServerDocumentUri(tab.documentUri))
        viewModelScope.launch {
            val report = languageServers.send(
                stored.stableLocationId,
                definition.id,
                message.toString().toByteArray(Charsets.UTF_8),
            )
            if (report.outcome != OperationOutcome.COMPLETE) {
                _uiState.update { state ->
                    state.copy(
                        statusMessage = report.message,
                        languageIntelligenceAvailable = if (
                            state.activeTabId == tab.id && state.projectRootUri == rootUri
                        ) false else state.languageIntelligenceAvailable,
                    )
                }
            }
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────

    fun saveActiveFile() {
        val active = _uiState.value.openTabs.firstOrNull { it.isActive } ?: return
        if (active.isBlank) return
        saveFile(active.documentUri)
    }

    fun saveTabById(tabId: String) {
        val tab = _uiState.value.openTabs.find { it.id == tabId } ?: return
        if (tab.isBlank) return
        saveFile(tab.documentUri)
    }

    private fun saveFile(documentUri: String) {
        val tab     = _uiState.value.openTabs.find { it.documentUri == documentUri } ?: return
        if (tab.isBlank) return
        val content = pendingContent[tab.id] ?: return

        _uiState.update { state ->
            state.copy(openTabs = state.openTabs.map {
                if (it.id == tab.id) it.copy(isSaving = true) else it
            })
        }
        viewModelScope.launch {
            val ok = fileMutations.write(documentUri, content.toByteArray(Charsets.UTF_8))
            if (ok) {
                pendingContent.remove(tab.id)
                _uiState.value.projectRootUri?.let { projectRootUri ->
                    crashRecovery.clearUnsavedContent(projectRootUri, tab.id)
                }
                _uiState.update { state ->
                    state.copy(
                        openTabs      = state.openTabs.map {
                            if (it.id == tab.id) it.copy(isDirty = false, isSaving = false) else it
                        },
                        statusMessage = "Saved",
                    )
                }
                sendLanguageServerNotificationForTab(
                    tab,
                    "textDocument/didSave",
                    JSONObject().apply {
                        put("textDocument", JSONObject().put("uri", languageServerDocumentUri(tab.documentUri)))
                        put("text", content)
                    },
                )
            } else {
                _uiState.update { state ->
                    state.copy(
                        openTabs      = state.openTabs.map {
                            if (it.id == tab.id) it.copy(isSaving = false) else it
                        },
                        statusMessage = "Save failed",
                    )
                }
            }
        }
    }

    fun saveActiveFileAs(newUri: String) {
        val active  = _uiState.value.openTabs.firstOrNull { it.isActive } ?: return
        val content = pendingContent[active.id] ?: active.content ?: return
        viewModelScope.launch {
            val ok = fileMutations.write(newUri, content.toByteArray(Charsets.UTF_8))
            if (!ok) { _uiState.update { it.copy(statusMessage = "Save As failed") }; return@launch }
            val newName = fileMutations.displayName(newUri) ?: displayNameFromUri(newUri)
            val newLang = EditorLanguageRegistry.languageForFileName(newName)
            pendingContent.remove(active.id)
            _uiState.value.projectRootUri?.let { projectRootUri ->
                crashRecovery.clearUnsavedContent(projectRootUri, active.id)
            }
            _uiState.update { state ->
                state.copy(
                    openTabs      = state.openTabs.map {
                        if (it.id == active.id) it.copy(
                            documentUri = newUri, displayName = newName,
                            language = newLang, isDirty = false, isBlank = false,
                        ) else it
                    },
                    statusMessage = "Saved as $newName",
                )
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

    fun showRenameDialog(node: FileNode)         = _uiState.update { it.copy(fileOpDialog = FileOpDialog.Rename(node)) }
    fun showDeleteDialog(node: FileNode)         = _uiState.update {
        it.copy(fileOpDialog = FileOpDialog.Delete(node, selectedNodesForOperation(node)))
    }
    fun showCreateFileDialog(parent: FileNode)   = _uiState.update { it.copy(fileOpDialog = FileOpDialog.CreateFile(parent)) }
    fun showCreateFolderDialog(parent: FileNode) = _uiState.update { it.copy(fileOpDialog = FileOpDialog.CreateFolder(parent)) }
    fun showDuplicateDialog(node: FileNode)      = _uiState.update { it.copy(fileOpDialog = FileOpDialog.Duplicate(node)) }
    fun dismissFileOpDialog()                    = _uiState.update { it.copy(fileOpDialog = null) }

    fun renameNode(node: FileNode, newName: String) {
        val rootUri = _uiState.value.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project open") }
            return
        }
        val sourceParentUri = node.parentDocumentUri ?: rootUri
        val input = newName.trim()
        val baseSegments = projectRelativeSegments(sourceParentUri, rootUri) ?: run {
                _uiState.update { it.copy(statusMessage = "Could not determine the current folder") }
                return
            }
        when (val normalized = normalizeUserProjectPath(input, rootUri, baseSegments)) {
            NormalizedPathResult.AboveProjectRoot ->
                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "The path cannot go above the project root"), statusMessage = "The path cannot go above the project root") }
            NormalizedPathResult.MissingFinalName,
            NormalizedPathResult.InvalidComponent ->
                _uiState.update { state -> state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(errorMessage = "Enter a valid rename path"), statusMessage = "Enter a valid rename path") }
            is NormalizedPathResult.Success -> viewModelScope.launch {
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
                                _uiState.update { state ->
                                    state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.Rename)?.copy(isSubmitting = false, errorMessage = null, resultMessage = "Renamed to ${resolved.leafName}"), statusMessage = "Renamed to ${resolved.leafName}")
                                }
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
        val nodes = if (selectedNodes.isEmpty()) listOf(node) else selectedNodes
        val allAffectedUris = nodes.flatMap { affectedUris(it) }.toSet()
        viewModelScope.launch {
            _uiState.update { state -> state.copy(fileMutationLoading = true, statusMessage = "Deleting ${nodes.size} item(s)…", fileOpDialog = (state.fileOpDialog as? FileOpDialog.Delete)?.copy(errorMessage = null, isSubmitting = true)) }
            var deletedCount = 0
            val deletedUris = mutableSetOf<String>()
            nodes.forEach { selectedNode ->
                if (fileMutations.delete(selectedNode.documentUri) &&
                    !fileMutations.exists(selectedNode.documentUri)
                ) {
                    deletedCount++
                    deletedUris += allAffectedUris.intersect(affectedUris(selectedNode))
                }
            }
            _uiState.value.openTabs
                .filter { it.documentUri in deletedUris }
                .forEach { closeTab(it.id) }
            refreshProjectNow()
            val complete = deletedCount == nodes.size
            val message = if (complete) {
                "Deleted $deletedCount item(s)"
            } else {
                "Deleted $deletedCount of ${nodes.size} item(s)"
            }
            _uiState.update { it.copy(
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
        when (val normalized = normalizeUserProjectPath(rawName, rootUri, baseSegments)) {
            is NormalizedPathResult.Success -> {
                markCreateSubmitting(isDirectory)
                viewModelScope.launch {
                    when (val resolved = fileMutations.resolvePath(PathResolutionScope(startUri = rootUri, boundaryUri = rootUri), normalized.segments)) {
                        is PathResolutionResult.Resolved -> createEntry(
                            parentUri = resolved.parentUri,
                            name = resolved.leafName,
                            isDirectory = isDirectory,
                            openFileAfterCreate = !isDirectory,
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
            NormalizedPathResult.AboveProjectRoot ->
                setCreateError(isDirectory, "The path cannot go above the project root")
            NormalizedPathResult.MissingFinalName ->
                setCreateError(isDirectory, "Enter a file or folder name")
            NormalizedPathResult.InvalidComponent ->
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

    private fun normalizeUserProjectPath(
        rawPath: String,
        rootUri: String,
        relativeBase: List<String>,
    ): NormalizedPathResult {
        val input = rawPath.trim()
        if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(input)) return NormalizedPathResult.AboveProjectRoot
        if (!input.startsWith('/')) return normalizeProjectPath(input, relativeBase)

        val rootPath = fileMutations.localFilesystemPath(rootUri)
            ?: return NormalizedPathResult.InvalidComponent
        return try {
            val root = File(rootPath).canonicalFile
            val candidate = File(input).canonicalFile
            if (candidate == root) return NormalizedPathResult.MissingFinalName
            if (!candidate.toPath().startsWith(root.toPath())) return NormalizedPathResult.AboveProjectRoot
            val relative = root.toPath().relativize(candidate.toPath()).toString()
                .replace(File.separatorChar, '/')
            normalizeProjectPath(relative, emptyList())
        } catch (_: Exception) {
            NormalizedPathResult.InvalidComponent
        }
    }

    private fun createEntry(
        parentUri: String,
        name: String,
        isDirectory: Boolean,
        openFileAfterCreate: Boolean,
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
                    _uiState.update { state ->
                        state.copy(fileOpDialog = (state.fileOpDialog as? FileOpDialog.CreateFolder)?.copy(isSubmitting = false, errorMessage = null, resultMessage = "Created $normalizedName")
                            ?: (state.fileOpDialog as? FileOpDialog.CreateFile)?.copy(isSubmitting = false, errorMessage = null, resultMessage = "Created $normalizedName"), statusMessage = "Created $normalizedName")
                    }
                    if (openFileAfterCreate) openFile(result.documentUri)
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
        val name = _uiState.value.openTabs.firstOrNull { it.isActive }?.displayName ?: "untitled"
        _uiState.update { it.copy(fileOpDialog = FileOpDialog.SaveAs(name)) }
    }

    /**
     * Write active file content to [relativePath] within the project root.
     * Supports "newname.kt" (creates in root) or "src/utils/Foo.kt" (creates
     * intermediate dirs if needed).
     */
    fun saveAsAtPath(relativePath: String) {
        val rootUri = _uiState.value.projectRootUri ?: run {
            _uiState.update { it.copy(statusMessage = "No project open") }
            return
        }
        val active  = _uiState.value.openTabs.firstOrNull { it.isActive } ?: return
        val content = pendingContent[active.id] ?: active.content ?: return
        val normalized = normalizeUserProjectPath(relativePath, rootUri, emptyList())
        val segments = when (normalized) {
            is NormalizedPathResult.Success -> normalized.segments
            NormalizedPathResult.AboveProjectRoot -> {
                _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.SaveAs)?.copy(errorMessage = "The path cannot go above the project root"), statusMessage = "The path cannot go above the project root") }
                return
            }
            NormalizedPathResult.MissingFinalName,
            NormalizedPathResult.InvalidComponent -> {
                _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.SaveAs)?.copy(errorMessage = "Enter a valid project-relative file path"), statusMessage = "Invalid path") }
                return
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(fileOpDialog = (it.fileOpDialog as? FileOpDialog.SaveAs)?.copy(errorMessage = null, isSubmitting = true), fileMutationLoading = true) }
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
                        fileOpDialog = (it.fileOpDialog as? FileOpDialog.SaveAs)?.copy(
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
                            fileOpDialog = (it.fileOpDialog as? FileOpDialog.SaveAs)?.copy(
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
                            fileOpDialog = (it.fileOpDialog as? FileOpDialog.SaveAs)?.copy(
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
                        fileOpDialog = (it.fileOpDialog as? FileOpDialog.SaveAs)?.copy(
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
            refreshProjectNow()
            _uiState.update { state ->
                state.copy(
                    openTabs     = state.openTabs.map {
                        if (it.id == active.id) it.copy(
                            documentUri = newUri, displayName = leafName,
                            language = newLang, isDirty = false, isBlank = false,
                        ) else it
                    },
                    fileOpDialog  = (state.fileOpDialog as? FileOpDialog.SaveAs)?.copy(isSubmitting = false, errorMessage = null, resultMessage = "Saved as $leafName"),
                    fileMutationLoading = false,
                    statusMessage = "Saved as $leafName",
                )
            }
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
