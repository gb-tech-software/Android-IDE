// AppShellViewModel coordinates application-scope state while delegating project,
// metadata, and lifecycle ownership to their respective services.
package dev.android.ide.app

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.android.ide.contracts.Surface
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.TerminalStorageSetupPermission
import dev.android.ide.project.ProjectRestoreResult
import dev.android.ide.project.CreateProjectTemplate
import dev.android.ide.project.ProjectDetailsResult
import dev.android.ide.data.model.ProjectDetails
import dev.android.ide.data.model.AppTheme
import dev.android.ide.data.model.TerminalSettings
import com.termux.terminal.TerminalSession
import dev.android.ide.contracts.RuntimeCapabilities
import dev.android.ide.contracts.SessionDescriptor
import dev.android.ide.contracts.SessionAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class AppShellState(
    val applicationTheme: AppTheme = AppTheme.DEVICE,
    val terminalSettings: TerminalSettings = TerminalSettings(),
    val surface: Surface = Surface.HOME,
    val projects: List<dev.android.ide.contracts.ProjectIdentity> = emptyList(),
    val projectSummaries: Map<String, ProjectSummary> = emptyMap(),
    val selectedProjectId: String? = null,
    val selectedProjectIds: Set<String> = emptySet(),
    val navigationStack: List<Surface> = listOf(Surface.HOME),
    val statusMessage: String? = null,
    val operationReport: OperationReport? = null,
    val registryWarning: String? = null,
    val restoring: Boolean = false,
    val refreshingProjects: Boolean = false,
    val operationInProgress: Boolean = false,
    val projectDetails: ProjectDetails? = null,
    val folderInspection: FolderInspection? = null,
    val acquiredProjectId: String? = null,
    val detailsLoading: Boolean = false,
    val exitConfirmationVisible: Boolean = false,
    val runtimeCapabilities: RuntimeCapabilities? = null,
    val terminalSessions: List<SessionDescriptor> = emptyList(),
    val selectedTerminalSessionId: String? = null,
    val terminalOutput: String = "",
    val terminalProgress: String? = null,
    val terminalFeedback: OperationReport? = null,
    val terminalStorageSetupRequest: TerminalStorageSetupRequest? = null,
    val navigationPromptVisible: Boolean = false,
    val pendingNavigation: Surface? = null,
    val pendingBackNavigation: Boolean = false,
    val pendingBrowserNavigations: List<BrowserNavigationRequest> = emptyList(),
    val pendingExternalContent: ExternalContentRoute? = null,
)

data class BrowserNavigationRequest(
    val id: String,
    val url: String,
    val openInNewTab: Boolean = false,
    val reuseKey: String? = null,
    val receiptTracked: Boolean = false,
)

data class FolderInspection(
    val path: String,
    val capabilityState: CapabilityState,
    val readable: Boolean,
    val writable: Boolean,
    val alreadyRegistered: Boolean,
    val containmentVerified: Boolean,
    val overlapsRegisteredProject: Boolean,
    val explanation: String?,
)

data class ProjectSummary(
    val fileCount: Int? = null,
    val totalBytes: Long? = null,
    val hasGit: Boolean? = null,
    val localStorageConfirmed: Boolean = false,
    val localWorkspaceAvailable: Boolean = false,
    val status: String? = null,
)

enum class TerminalStorageSetupStage { AWAITING_PERMISSION, RUNNING }

data class TerminalStorageSetupRequest(
    val id: String,
    val stage: TerminalStorageSetupStage = TerminalStorageSetupStage.AWAITING_PERMISSION,
)

class AppShellViewModel(application: Application) : AndroidViewModel(application) {
    private val services = ApplicationServices.from(application)
    private val saf = services.fileSystem.documents
    private val projectManagement = services.projectManagement
    private val lifecycle = services.lifecycleCoordinator
    private val applicationState = services.applicationStateStore
    private val runtimeState = services.runtimeStateStore
    private val terminalRuntime = services.terminalRuntime
    private val themeRepository = services.themeRepository
    private val terminalSettingsRepository = services.terminalSettingsRepository
    private var restoreJob: kotlinx.coroutines.Job? = null
    private var restoreGeneration = 0L
    private var projectRefreshGeneration = 0L
    private val projectSummaryJobs = mutableMapOf<String, Job>()
    private var folderInspectionGeneration = 0L

    private val _state = MutableStateFlow(
        AppShellState(
            applicationTheme = themeRepository.get(),
            terminalSettings = terminalSettingsRepository.get(),
        ),
    )
    val state: StateFlow<AppShellState> = _state.asStateFlow()

    fun setApplicationTheme(theme: AppTheme) {
        themeRepository.set(theme)
        _state.update { it.copy(applicationTheme = theme) }
    }

    fun setTerminalSettings(settings: TerminalSettings) {
        val normalized = settings.copy(textSize = settings.textSize.coerceIn(8, 32))
        terminalSettingsRepository.set(normalized)
        _state.update { it.copy(terminalSettings = normalized) }
    }

    init {
        terminalRuntime.setTerminalFeedbackListener { report ->
            _state.update { it.copy(terminalFeedback = report) }
        }
        viewModelScope.launch {
            terminalRuntime.initializationProgress.collect { progress ->
                _state.update { it.copy(terminalProgress = progress) }
            }
        }
        viewModelScope.launch {
            lifecycle.restore()
            refreshProjects()
        }
        viewModelScope.launch {
            runtimeState.ensureReady()
            val initialization = terminalRuntime.initialize()
            _state.update {
                it.copy(
                    runtimeCapabilities = terminalRuntime.capabilities(),
                    terminalProgress = null,
                    terminalFeedback = initialization.takeUnless { report -> report.outcome == OperationOutcome.COMPLETE },
                )
            }
            refreshTerminalSessions()
        }
    }

    fun foreground() {
        viewModelScope.launch { lifecycle.onForeground() }
    }

    fun background() {
        viewModelScope.launch { lifecycle.onBackground() }
    }

    fun refreshTerminalSessions() {
        viewModelScope.launch {
            val sessions = terminalRuntime.listSessions()
            val selectedId = _state.value.selectedTerminalSessionId
                ?.takeIf { id -> sessions.any { session -> session.id == id && session.availability == SessionAvailability.AVAILABLE } }
                ?: sessions.firstOrNull { it.availability == SessionAvailability.AVAILABLE }?.id
            _state.update {
                it.copy(
                    terminalSessions = sessions,
                    selectedTerminalSessionId = selectedId,
                    terminalOutput = selectedId?.let(terminalRuntime::outputSnapshot).orEmpty(),
                    runtimeCapabilities = terminalRuntime.capabilities(),
                )
            }
        }
    }

    fun createTerminalSession(workingDirectory: String? = null, name: String = "") {
        viewModelScope.launch {
            val session = terminalRuntime.createSession(
                workingDirectory,
                name.trim().ifBlank { "Untitled session" },
            )
            _state.update { state ->
                val launchFailure = session.terminationReason?.let { reason ->
                    OperationReport(OperationOutcome.BLOCKED, reason, ErrorCategory.UNAVAILABLE_RUNTIME)
                }
                val packageWarning = state.terminalFeedback?.takeIf {
                    it.outcome == OperationOutcome.PARTIAL && it.errorCategory == ErrorCategory.PACKAGE_FAILURE
                }
                state.copy(
                    selectedTerminalSessionId = session.id.takeIf { session.availability == SessionAvailability.AVAILABLE },
                    terminalFeedback = launchFailure ?: packageWarning,
                )
            }
            refreshTerminalSessions()
        }
    }

    /** Open a terminal for a project using its accessible filesystem directory when available. */
    fun openTerminalForProject(projectId: String, directoryUri: String? = null) {
        _state.update { it.copy(selectedProjectId = projectId, terminalFeedback = null, statusMessage = null) }
        viewModelScope.launch {
            val project = _state.value.projects.firstOrNull { it.id == projectId }
            if (project == null) {
                reportTerminalLaunchFailure(OperationReport(OperationOutcome.BLOCKED, "The selected project is no longer available", ErrorCategory.PERMISSION_LOST))
                return@launch
            }
            val terminalProject = directoryUri?.let { uri ->
                project.copy(location = project.location.copy(stableId = uri, userVisiblePath = uri, displayLabel = uri))
            } ?: project
            val access = terminalRuntime.inspectProjectAccess(terminalProject)
            if (!access.available) {
                val message = access.explanation
                    ?: "This project is on a storage path that Terminal cannot access. Move it to Android IDE storage or Android external storage for full access."
                if (!openTerminalWithAccessNotice(project.name, message)) {
                    reportTerminalLaunchFailure(OperationReport(
                        OperationOutcome.BLOCKED,
                        message,
                        ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY,
                    ))
                }
                return@launch
            }
            val directory = terminalRuntime.workingDirectory(terminalProject)
            if (directory == null) {
                reportTerminalLaunchFailure(OperationReport(OperationOutcome.BLOCKED, "The selected project or folder has no accessible terminal working directory", ErrorCategory.PERMISSION_LOST))
                return@launch
            }
            if (!terminalRuntime.isUsableWorkingDirectory(directory)) {
                reportTerminalLaunchFailure(OperationReport(OperationOutcome.BLOCKED, "Terminal cannot read, write, or enter the selected project folder: $directory", ErrorCategory.PERMISSION_LOST))
                return@launch
            }
            val session = terminalRuntime.createSession(directory, project.name)
            if (session.availability != SessionAvailability.AVAILABLE) {
                reportTerminalLaunchFailure(OperationReport(
                    OperationOutcome.BLOCKED,
                    session.terminationReason ?: "Terminal could not start in the selected project folder",
                    ErrorCategory.UNAVAILABLE_RUNTIME,
                ))
                return@launch
            }
            _state.update { it.copy(selectedTerminalSessionId = session.id, terminalFeedback = null, operationReport = null, statusMessage = null) }
            refreshTerminalSessions()
            navigate(Surface.TERMINAL)
        }
    }

    /** Open a terminal for the currently open editor project at a root or folder node. */
    fun openTerminalForDirectory(directoryUri: String) {
        val projectId = _state.value.selectedProjectId
        if (projectId == null) {
            reportTerminalLaunchFailure(
                OperationReport(
                    OperationOutcome.BLOCKED,
                    "Open a project before opening its folder in Terminal",
                    ErrorCategory.DESTINATION_CONFLICT,
                ),
            )
            return
        }
        openTerminalForProject(projectId, directoryUri)
    }

    private fun reportTerminalLaunchFailure(report: OperationReport) {
        _state.update { it.copy(terminalFeedback = report, operationReport = report, statusMessage = report.message) }
        if (_state.value.surface != Surface.TERMINAL) navigate(Surface.TERMINAL)
    }

    private suspend fun openTerminalWithAccessNotice(projectName: String, message: String): Boolean {
        val session = terminalRuntime.createSession(null, "$projectName access information")
        if (session.availability != SessionAvailability.AVAILABLE) return false
        _state.update {
            it.copy(
                selectedTerminalSessionId = session.id,
                terminalFeedback = null,
                operationReport = null,
                statusMessage = null,
            )
        }
        navigate(Surface.TERMINAL)
        val safeMessage = message.replace("'", "’").replace("\n", " ")
        val command = "printf '\\n%s\\n\\n' '$safeMessage'\n"
        val report = terminalRuntime.sendInput(session.id, command.toByteArray(Charsets.UTF_8))
        if (report.outcome != OperationOutcome.COMPLETE) {
            _state.update { it.copy(terminalFeedback = report) }
        }
        refreshTerminalSessions()
        refreshTerminalOutput()
        return true
    }

    fun resizeTerminal(columns: Int, rows: Int) {
        val sessionId = _state.value.selectedTerminalSessionId ?: return
        viewModelScope.launch {
            _state.update { it.copy(terminalFeedback = terminalRuntime.resize(sessionId, columns, rows)) }
        }
    }

    fun terminalSession(sessionId: String): TerminalSession? = terminalRuntime.terminalSession(sessionId)
    fun bindTerminalView(sessionId: String, invalidate: () -> Unit) = terminalRuntime.bindTerminalView(sessionId, invalidate)
    fun unbindTerminalView(sessionId: String) = terminalRuntime.unbindTerminalView(sessionId)

    fun selectTerminalSession(sessionId: String) {
        val session = _state.value.terminalSessions.firstOrNull { it.id == sessionId } ?: return
        if (session.availability != SessionAvailability.AVAILABLE) return
        _state.update { it.copy(selectedTerminalSessionId = sessionId, terminalOutput = terminalRuntime.outputSnapshot(sessionId)) }
    }

    fun sendTerminalInput(input: String) {
        sendTerminalInput(input.toByteArray(Charsets.UTF_8))
    }

    fun sendTerminalInput(input: ByteArray) {
        val sessionId = _state.value.selectedTerminalSessionId ?: return
        viewModelScope.launch {
            val report = terminalRuntime.sendInput(sessionId, input)
            if (report.outcome != OperationOutcome.COMPLETE) {
                _state.update { it.copy(terminalFeedback = report) }
            }
            refreshTerminalOutput()
        }
    }

    fun refreshTerminalOutput() {
        val sessionId = _state.value.selectedTerminalSessionId ?: return
        viewModelScope.launch {
            terminalRuntime.readOutput(sessionId)
            val output = terminalRuntime.outputSnapshot(sessionId)
            _state.update {
                if (it.selectedTerminalSessionId == sessionId) it.copy(terminalOutput = output) else it
            }
        }
    }

    fun interruptTerminalSession() {
        val sessionId = _state.value.selectedTerminalSessionId ?: return
        viewModelScope.launch {
            _state.update { it.copy(terminalFeedback = terminalRuntime.interrupt(sessionId)) }
            refreshTerminalSessions()
        }
    }

    fun closeTerminalSession(sessionId: String) {
        viewModelScope.launch {
            _state.update { it.copy(terminalFeedback = terminalRuntime.closeSession(sessionId)) }
            refreshTerminalSessions()
        }
    }

    fun renameTerminalSession(sessionId: String, name: String) {
        viewModelScope.launch {
            val report = terminalRuntime.renameSession(sessionId, name)
            _state.update { it.copy(terminalFeedback = report) }
            if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) refreshTerminalSessions()
        }
    }

    fun clearTerminalFeedback() {
        _state.update { it.copy(terminalFeedback = null) }
    }

    /** Starts the one pending storage setup operation requested by termux-setup-storage. */
    fun requestTerminalStorageSetup(requestId: String) {
        if (!requestId.matches(Regex("[0-9]+"))) return
        val activeRequest = _state.value.terminalStorageSetupRequest
        if (activeRequest?.id == requestId) return
        if (activeRequest != null) {
            viewModelScope.launch {
                try {
                    terminalRuntime.completeStorageSetupRequest(
                        requestId,
                        TerminalStorageSetupPermission.Denied(
                            OperationReport(
                                OperationOutcome.BLOCKED,
                                "Another termux-setup-storage command is already waiting for Android permission. Run this command again after it finishes.",
                                ErrorCategory.DESTINATION_CONFLICT,
                            ),
                        ),
                    )
                } catch (error: Exception) {
                    Log.e("AppShellViewModel", "Could not reject duplicate storage setup request $requestId", error)
                }
            }
            return
        }
        _state.update { state ->
            if (state.terminalStorageSetupRequest != null) state
            else state.copy(
                terminalStorageSetupRequest = TerminalStorageSetupRequest(requestId),
            )
        }
    }

    /** Completes setup and returns its report through the shell command's result channel. */
    fun completeTerminalStorageSetup(
        requestId: String,
        permission: TerminalStorageSetupPermission,
    ) {
        val currentRequest = _state.value.terminalStorageSetupRequest
        if (currentRequest?.id != requestId || currentRequest.stage != TerminalStorageSetupStage.AWAITING_PERMISSION) return
        _state.update { state ->
            if (state.terminalStorageSetupRequest?.id == requestId) {
                state.copy(terminalStorageSetupRequest = currentRequest.copy(stage = TerminalStorageSetupStage.RUNNING))
            } else state
        }
        viewModelScope.launch {
            try {
                terminalRuntime.completeStorageSetupRequest(requestId, permission)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("AppShellViewModel", "Could not publish storage setup result for terminal request $requestId", error)
            } finally {
                _state.update { state ->
                    if (state.terminalStorageSetupRequest?.id == requestId) {
                        state.copy(terminalStorageSetupRequest = null)
                    } else state
                }
            }
        }
    }

    fun closeAllTerminalSessions() {
        viewModelScope.launch {
            _state.update { it.copy(terminalFeedback = terminalRuntime.closeAllSessions()) }
            refreshTerminalSessions()
        }
    }

    override fun onCleared() {
        terminalRuntime.setTerminalFeedbackListener(null)
        super.onCleared()
    }

    fun reportStatus(message: String) {
        _state.update { it.copy(statusMessage = message, operationReport = null) }
    }

    fun clearOperationFeedback() {
        _state.update { it.copy(statusMessage = null, operationReport = null, acquiredProjectId = null) }
    }

    fun selectProject(projectId: String) {
        _state.update { it.copy(selectedProjectId = projectId) }
    }

    fun toggleProjectSelection(projectId: String) {
        _state.update { current ->
            val next = current.selectedProjectIds.toMutableSet().apply {
                if (!add(projectId)) remove(projectId)
            }
            current.copy(selectedProjectIds = next, selectedProjectId = projectId)
        }
    }

    fun selectAllProjects() {
        _state.update { current ->
            current.copy(selectedProjectIds = current.projects.map { it.id }.toSet())
        }
    }

    fun clearProjectSelection() {
        _state.update { it.copy(selectedProjectIds = emptySet()) }
    }

    fun inspectExistingFolder(projectRootUri: String) {
        val generation = ++folderInspectionGeneration
        _state.update { it.copy(folderInspection = null) }
        viewModelScope.launch {
            val location = ProjectLocation(projectRootUri, projectRootUri, projectRootUri)
            val capabilities = projectManagement.inspectStorage(location)
            val registered = projectManagement.registeredProjects()
            val alreadyRegistered = registered.any { it.location.stableId == projectRootUri }
            var containmentVerified = true
            var overlapsRegisteredProject = false
            registered.filterNot { it.location.stableId == projectRootUri }.forEach { project ->
                val existingContainsSelected = projectManagement.isSameOrDescendant(
                    project.location.stableId,
                    projectRootUri,
                )
                val selectedContainsExisting = projectManagement.isSameOrDescendant(
                    projectRootUri,
                    project.location.stableId,
                )
                if (existingContainsSelected == null || selectedContainsExisting == null) {
                    containmentVerified = false
                }
                if (existingContainsSelected == true || selectedContainsExisting == true) {
                    overlapsRegisteredProject = true
                }
            }
            if (generation != folderInspectionGeneration) return@launch
            _state.update {
                it.copy(folderInspection = FolderInspection(
                    path = projectRootUri,
                    capabilityState = capabilities.state,
                    readable = capabilities.readable,
                    writable = capabilities.writable,
                    alreadyRegistered = alreadyRegistered,
                    containmentVerified = containmentVerified,
                    overlapsRegisteredProject = overlapsRegisteredProject,
                    explanation = capabilities.explanation,
                ))
            }
        }
    }

    fun refreshProjectList() {
        viewModelScope.launch {
            _state.update { it.copy(refreshingProjects = true, statusMessage = null, operationReport = null) }
            try {
                refreshProjects()
            } finally {
                _state.update { it.copy(refreshingProjects = false) }
            }
        }
    }

    fun navigate(surface: Surface) {
        if (_state.value.operationInProgress) {
            _state.update { it.copy(navigationPromptVisible = true, pendingNavigation = surface, pendingBackNavigation = false) }
            return
        }
        restoreGeneration++
        restoreJob?.cancel()
        _state.update { current ->
            if (current.surface == surface) current.copy(restoring = false, detailsLoading = false)
            else if (surface == Surface.HOME) current.copy(
                surface = Surface.HOME,
                navigationStack = listOf(Surface.HOME),
                selectedProjectIds = emptySet(),
                statusMessage = null,
                restoring = false,
                detailsLoading = false,
            ) else current.copy(
                surface = surface,
                navigationStack = current.navigationStack + surface,
                selectedProjectIds = if (surface == Surface.PROJECTS) current.selectedProjectIds else emptySet(),
                statusMessage = null,
                restoring = false,
                detailsLoading = false,
            )
        }
        if (surface != Surface.STANDALONE_EDITOR) applicationState.recordSurface(surface)
    }

    /** Queue an ordinary browser navigation request; the originating workflow is not encoded. */
    fun requestBrowserNavigation(
        url: String,
        openInNewTab: Boolean = false,
        reuseKey: String? = null,
        requestId: String? = null,
    ) {
        val value = url.trim()
        if (value.isBlank()) return
        queueBrowserNavigation(value, openInNewTab, reuseKey, requestId)
    }

    fun consumeBrowserNavigationRequest(requestId: String) {
        var receiptTracked = false
        _state.update { current ->
            val request = current.pendingBrowserNavigations.firstOrNull()
            if (request?.id == requestId) {
                receiptTracked = request.receiptTracked
                current.copy(pendingBrowserNavigations = current.pendingBrowserNavigations.drop(1))
            } else current
        }
        if (receiptTracked) services.browserNavigationReceipts.record(requestId, "dispatched")
    }

    /** Deliver external content to a capability chooser or to its selected owner. */
    fun requestExternalContent(route: ExternalContentRoute) {
        if (route !is ExternalContentRoute.Unsupported) {
            _state.update { it.copy(pendingExternalContent = route) }
        }
    }

    fun selectExternalContentAction(kind: ExternalContentActionKind) {
        val choice = _state.value.pendingExternalContent as? ExternalContentRoute.Choose ?: return
        val action = choice.actions.firstOrNull { it.kind == kind } ?: return
        val request = choice.request
        val selected = when (action.kind) {
            ExternalContentActionKind.BROWSER -> ExternalContentRoute.Browser(OpenUrlRequest(request.uri, requestId = request.requestId))
            ExternalContentActionKind.EDITOR -> ExternalContentRoute.Editor(request)
            ExternalContentActionKind.IMPORT_ARCHIVE -> ExternalContentRoute.ImportArchive(request)
            ExternalContentActionKind.EXTERNAL -> ExternalContentRoute.External(request)
        }
        _state.update { current ->
            if (current.pendingExternalContent == choice) current.copy(pendingExternalContent = selected) else current
        }
    }

    fun consumeExternalContent(requestId: String) {
        _state.update { current ->
            val pending = current.pendingExternalContent
            if (pending?.requestId() == requestId) current.copy(pendingExternalContent = null) else current
        }
    }

    private fun queueBrowserNavigation(url: String, openInNewTab: Boolean, reuseKey: String?, requestId: String?) {
        val id = requestId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        _state.update { current ->
            if (current.pendingBrowserNavigations.any { it.id == id }) current
            else current.copy(pendingBrowserNavigations = current.pendingBrowserNavigations + BrowserNavigationRequest(id, url, openInNewTab, reuseKey, requestId != null))
        }
        if (requestId != null) services.browserNavigationReceipts.record(id, "accepted")
        navigate(Surface.BROWSER)
    }

    fun back(): Boolean {
        val current = _state.value
        if (current.operationInProgress) {
            val cancelBrowserQueue = current.pendingBrowserNavigations.isNotEmpty()
            val cancelledReceipts = if (cancelBrowserQueue) current.pendingBrowserNavigations.filter { it.receiptTracked }.map { it.id } else emptyList()
            _state.update {
                it.copy(
                    navigationPromptVisible = true,
                    pendingNavigation = null,
                    pendingBackNavigation = true,
                    pendingBrowserNavigations = if (cancelBrowserQueue) emptyList() else it.pendingBrowserNavigations,
                )
            }
            cancelledReceipts.forEach { services.browserNavigationReceipts.record(it, "rejected") }
            return true
        }
        if (current.navigationStack.size <= 1) return false
        restoreGeneration++
        restoreJob?.cancel()
        val stack = current.navigationStack.dropLast(1)
        _state.update {
            it.copy(
                surface = stack.last(),
                navigationStack = stack,
                statusMessage = null,
                restoring = false,
                detailsLoading = false,
            )
        }
        applicationState.recordSurface(stack.last())
        return true
    }

    fun dismissNavigationPrompt() {
        val cancelBrowserQueue = _state.value.pendingBrowserNavigations.isNotEmpty()
        val cancelledReceipts = if (cancelBrowserQueue) _state.value.pendingBrowserNavigations.filter { it.receiptTracked }.map { it.id } else emptyList()
        _state.update {
            it.copy(
                navigationPromptVisible = false,
                pendingNavigation = null,
                pendingBackNavigation = false,
                pendingBrowserNavigations = if (cancelBrowserQueue) emptyList() else it.pendingBrowserNavigations,
            )
        }
        cancelledReceipts.forEach { services.browserNavigationReceipts.record(it, "rejected") }
    }

    fun continuePendingNavigation() {
        val current = _state.value
        if (current.pendingBackNavigation) {
            val cancelledReceipts = current.pendingBrowserNavigations.filter { it.receiptTracked }.map { it.id }
            _state.update {
                it.copy(
                    navigationPromptVisible = false,
                    pendingNavigation = null,
                    pendingBackNavigation = false,
                    pendingBrowserNavigations = emptyList(),
                )
            }
            cancelledReceipts.forEach { services.browserNavigationReceipts.record(it, "rejected") }
            if (current.navigationStack.size > 1) {
                val stack = current.navigationStack.dropLast(1)
                _state.update { it.copy(surface = stack.last(), navigationStack = stack, restoring = false, detailsLoading = false) }
                applicationState.recordSurface(stack.last())
            }
            return
        }
        val destination = current.pendingNavigation ?: return dismissNavigationPrompt()
        if (_state.value.operationInProgress) return
        val cancelBrowserQueue = destination != Surface.BROWSER && current.pendingBrowserNavigations.isNotEmpty()
        val cancelledReceipts = if (cancelBrowserQueue) current.pendingBrowserNavigations.filter { it.receiptTracked }.map { it.id } else emptyList()
        _state.update {
            it.copy(
                navigationPromptVisible = false,
                pendingNavigation = null,
                pendingBackNavigation = false,
                pendingBrowserNavigations = if (cancelBrowserQueue) emptyList() else it.pendingBrowserNavigations,
            )
        }
        cancelledReceipts.forEach { services.browserNavigationReceipts.record(it, "rejected") }
        navigate(destination)
    }

    fun exit(onComplete: () -> Unit) {
        viewModelScope.launch {
            terminalRuntime.shutdown()
            lifecycle.onExplicitExit()
            onComplete()
        }
    }

    fun requestExitConfirmation() {
        _state.update { it.copy(exitConfirmationVisible = true) }
    }

    fun dismissExitConfirmation() {
        _state.update { it.copy(exitConfirmationVisible = false) }
    }

    fun openProject(projectId: String) {
        restoreProject(projectId, Surface.EDITOR, loadDetails = false)
    }

    fun showProjectDetails(projectId: String) {
        restoreProject(projectId, Surface.PROJECT_DETAILS, loadDetails = true)
    }

    private fun restoreProject(projectId: String, destination: Surface, loadDetails: Boolean) {
        val generation = ++restoreGeneration
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    restoring = true,
                    detailsLoading = true,
                    projectDetails = null,
                    selectedProjectId = projectId,
                )
            }
            val result = projectManagement.restore(projectId)
            if (generation != restoreGeneration) return@launch
            when (result) {
                is ProjectRestoreResult.Restored -> _state.update {
                    it.copy(
                        restoring = false,
                        selectedProjectId = result.identity.id,
                        surface = destination,
                        navigationStack = it.navigationStack + destination,
                        statusMessage = null,
                    )
                }
                is ProjectRestoreResult.Unavailable -> _state.update {
                    it.copy(
                        restoring = false,
                        detailsLoading = false,
                        projectDetails = null,
                        statusMessage = result.reason,
                    )
                }
            }
            if (result is ProjectRestoreResult.Restored) {
                applicationState.recordProject(result.identity.id)
            }
            if (result is ProjectRestoreResult.Restored && loadDetails) {
                val details = projectManagement.loadDetails(result.identity.id)
                if (generation != restoreGeneration) return@launch
                when (details) {
                    is ProjectDetailsResult.Loaded -> _state.update {
                        it.copy(projectDetails = details.details, detailsLoading = false)
                    }
                    is ProjectDetailsResult.Unavailable -> _state.update {
                        it.copy(
                            detailsLoading = false,
                            projectDetails = null,
                            statusMessage = details.reason,
                        )
                    }
                }
            } else {
                _state.update { it.copy(detailsLoading = false, projectDetails = null) }
            }
            if (generation != restoreGeneration) return@launch
            refreshProjects()
        }
    }

    fun refreshSelectedProjectDetails() {
        val projectId = _state.value.selectedProjectId ?: return
        val generation = restoreGeneration
        viewModelScope.launch {
            _state.update { it.copy(detailsLoading = true, projectDetails = null, statusMessage = null, operationReport = null) }
            val details = projectManagement.loadDetails(projectId)
            if (generation != restoreGeneration || _state.value.selectedProjectId != projectId) return@launch
            when (details) {
                is ProjectDetailsResult.Loaded -> _state.update {
                    it.copy(projectDetails = details.details, detailsLoading = false)
                }
                is ProjectDetailsResult.Unavailable -> _state.update {
                    it.copy(
                        detailsLoading = false,
                        projectDetails = null,
                        statusMessage = details.reason,
                    )
                }
            }
        }
    }

    fun createBlankProject(
        destinationParentUri: String,
        name: String,
        description: String,
        template: CreateProjectTemplate = CreateProjectTemplate.FROM_SCRATCH,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = runAcquisition { projectManagement.createBlankProject(destinationParentUri, name, description, template) }
            _state.update {
                it.copy(
                    operationInProgress = false,
                    statusMessage = null,
                    operationReport = report,
                    acquiredProjectId = report.affectedIds.firstOrNull().takeIf { report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE },
                )
            }
            refreshProjects()
        }
    }

    fun importExistingFolder(projectRootUri: String, name: String? = null, description: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = runAcquisition { projectManagement.importExistingFolder(projectRootUri, name, description) }
            _state.update {
                it.copy(
                    operationInProgress = false,
                    statusMessage = null,
                    operationReport = report,
                    acquiredProjectId = report.affectedIds.firstOrNull().takeIf { report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE },
                )
            }
            refreshProjects()
        }
    }

    fun dismissAcquisitionPrompt() {
        _state.update { it.copy(acquiredProjectId = null) }
    }

    fun importZip(
        archiveUri: String,
        destinationParentUri: String,
        name: String,
        description: String,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = runAcquisition { projectManagement.importZip(archiveUri, destinationParentUri, name, description) }
            _state.update {
                it.copy(
                    operationInProgress = false,
                    statusMessage = null,
                    operationReport = report,
                    acquiredProjectId = report.affectedIds.firstOrNull().takeIf { report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE },
                )
            }
            refreshProjects()
        }
    }

    private suspend fun runAcquisition(operation: suspend () -> OperationReport): OperationReport = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        OperationReport(
            OperationOutcome.FAILED,
            error.message?.takeIf { it.isNotBlank() } ?: "Project acquisition failed unexpectedly",
            ErrorCategory.MALFORMED_METADATA,
        )
    }

    fun removeSelectedProject() {
        val projectId = _state.value.selectedProjectId ?: return
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = projectManagement.removeFromRegistry(projectId)
            _state.update {
                it.copy(
                    operationInProgress = false,
                    statusMessage = report.message,
                    operationReport = report,
                    projectDetails = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) null else it.projectDetails,
                    selectedProjectId = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) null else it.selectedProjectId,
                    surface = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) Surface.PROJECTS else it.surface,
                )
            }
            refreshProjects()
        }
    }

    fun permanentlyDeleteSelectedProject() {
        val projectId = _state.value.selectedProjectId ?: return
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = projectManagement.permanentlyDelete(projectId)
            _state.update {
                it.copy(
                    operationInProgress = false,
                    statusMessage = report.message,
                    operationReport = report,
                    projectDetails = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) null else it.projectDetails,
                    selectedProjectId = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) null else it.selectedProjectId,
                    surface = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) {
                        Surface.PROJECTS
                    } else it.surface,
                )
            }
            refreshProjects()
        }
    }

    fun exportSelectedProject(
        destinationFileUri: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val projectId = _state.value.selectedProjectId ?: return
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = projectManagement.exportProject(projectId, destinationFileUri)
            _state.update { it.copy(operationInProgress = false, statusMessage = report.message, operationReport = report) }
            refreshProjects()
            onComplete(report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE)
        }
    }

    fun duplicateProject(projectId: String, destinationParentUri: String, name: String, description: String? = null) {
        runProjectOperation(projectId) {
            projectManagement.duplicateProject(projectId, destinationParentUri, name, description)
        }
    }

    fun relocateProject(projectId: String, destinationParentUri: String, name: String) {
        runProjectOperation(projectId, replaceSelection = true) {
            projectManagement.relocateProject(projectId, destinationParentUri, name)
        }
    }

    fun duplicateSelectedProject(destinationParentUri: String, name: String, description: String? = null) {
        _state.value.selectedProjectId?.let { duplicateProject(it, destinationParentUri, name, description) }
    }

    fun relocateSelectedProject(destinationParentUri: String, name: String) {
        _state.value.selectedProjectId?.let { relocateProject(it, destinationParentUri, name) }
    }

    fun renameSelectedProject(name: String) {
        val projectId = _state.value.selectedProjectId ?: return
        runProjectOperation(projectId, replaceSelection = true) {
            projectManagement.renameProject(projectId, name)
        }
    }

    fun removeProjectsFromRegistry(projectIds: List<String>) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = projectManagement.batchRemoveFromRegistry(projectIds)
            _state.update { it.copy(operationInProgress = false, statusMessage = report.message, operationReport = report, selectedProjectIds = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) emptySet() else it.selectedProjectIds) }
            refreshProjects()
        }
    }

    fun exportProjects(projectIds: List<String>, destinationParentUri: String) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = projectManagement.exportProjects(projectIds, destinationParentUri)
            _state.update { it.copy(operationInProgress = false, statusMessage = report.message, operationReport = report) }
            refreshProjects()
        }
    }

    fun permanentlyDeleteProjects(projectIds: List<String>) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, statusMessage = null, operationReport = null) }
            val report = projectManagement.batchPermanentlyDelete(projectIds)
            _state.update { it.copy(operationInProgress = false, statusMessage = report.message, operationReport = report, selectedProjectIds = if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) emptySet() else it.selectedProjectIds) }
            refreshProjects()
        }
    }

    private fun runProjectOperation(
        projectId: String,
        replaceSelection: Boolean = false,
        operation: suspend () -> dev.android.ide.contracts.OperationReport,
    ) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    selectedProjectId = projectId,
                    operationInProgress = true,
                    statusMessage = null,
                    operationReport = null,
                )
            }
            val report = operation()
            _state.update {
                it.copy(
                    operationInProgress = false,
                    statusMessage = report.message,
                    operationReport = report,
                    selectedProjectId = if (
                        replaceSelection &&
                        report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE
                    ) report.affectedIds.firstOrNull() ?: it.selectedProjectId else it.selectedProjectId,
                )
            }
            refreshProjects()
            if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) {
                refreshSelectedProjectDetails()
            }
        }
    }

    fun loadProjectSummary(projectId: String) {
        if (_state.value.projectSummaries.containsKey(projectId) || projectSummaryJobs.containsKey(projectId)) return
        if (_state.value.projects.none { it.id == projectId }) return
        val generation = projectRefreshGeneration
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val summary = when (val result = projectManagement.loadDetails(projectId)) {
                    is ProjectDetailsResult.Loaded -> ProjectSummary(
                        fileCount = result.details.fileCount,
                        totalBytes = result.details.totalBytes,
                        hasGit = result.details.git != null,
                        localStorageConfirmed = result.details.storageCapabilities.localStorageConfirmed,
                        localWorkspaceAvailable = result.details.storageCapabilities.localWorkspaceAvailable,
                    )
                    is ProjectDetailsResult.Unavailable -> ProjectSummary(status = result.reason)
                }
                if (generation == projectRefreshGeneration) {
                    _state.update { current ->
                        if (current.projects.any { it.id == projectId }) {
                            current.copy(projectSummaries = current.projectSummaries + (projectId to summary))
                        } else current
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == projectRefreshGeneration) {
                    _state.update { current ->
                        current.copy(projectSummaries = current.projectSummaries + (projectId to ProjectSummary(status = error.message ?: "Project metrics are unavailable")))
                    }
                }
            } finally {
                projectSummaryJobs.remove(projectId)
            }
        }
        projectSummaryJobs[projectId] = job
        job.start()
    }

    private suspend fun refreshProjects() {
        val generation = ++projectRefreshGeneration
        projectSummaryJobs.values.forEach(Job::cancel)
        projectSummaryJobs.clear()
        val registered = projectManagement.registeredProjects()
        if (generation != projectRefreshGeneration) return
        val warning = projectManagement.registryWarning()
        _state.update { it.copy(projects = registered, projectSummaries = emptyMap(), registryWarning = warning) }
    }

}
