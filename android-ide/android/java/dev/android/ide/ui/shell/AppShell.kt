package dev.android.ide.ui.shell

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.android.ide.app.AppShellState
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.browser.BrowserDownloadStatus
import dev.android.ide.browser.BrowserViewModel
import dev.android.ide.CrashReportSummary
import dev.android.ide.contracts.Surface
import dev.android.ide.CrashReporter
import dev.android.ide.ui.screen.HomeSurface
import dev.android.ide.ui.screen.CrashConsoleSurface
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.ui.theme.operationStatusColor
import dev.android.ide.ui.screen.ProjectDetailsSurface
import dev.android.ide.ui.screen.EditorSurface
import dev.android.ide.ui.screen.ProjectsSurface
import dev.android.ide.ui.screen.SettingsScreen
import dev.android.ide.ui.screen.TerminalSurface
import dev.android.ide.ui.screen.BrowserSurface
import dev.android.ide.ui.screen.EditorPanel
import dev.android.ide.ui.screen.EditorSidebar
import dev.android.ide.viewmodel.model.FileNode
import dev.android.ide.viewmodel.IdeViewModel
import kotlinx.coroutines.launch

@Composable
fun AppShell(
    viewModel: AppShellViewModel,
    ideViewModel: IdeViewModel,
    onExit: () -> Unit,
    onCreateProject: (String?) -> Unit,
    onImportFolder: () -> Unit,
    onImportZip: () -> Unit,
    onExportProject: (String) -> Unit,
    onExportProjects: (Set<String>) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onRelocateProject: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val ideState by ideViewModel.uiState.collectAsState()
    val browserViewModel: BrowserViewModel = composeViewModel()
    val browserState by browserViewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val crashReporter = androidx.compose.runtime.remember { CrashReporter(context) }
    var crashReports by remember { mutableStateOf(crashReporter.reports()) }
    var pendingCrashExport by remember { mutableStateOf<String?>(null) }
    var editorNavigationPrompt by rememberSaveable { mutableStateOf(false) }
    var pendingEditorNavigation by rememberSaveable { mutableStateOf<Surface?>(null) }
    var pendingNavigationClosesSidebar by rememberSaveable { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    fun dismissEditorIme() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var phaseFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var editorPanelRequest by rememberSaveable { mutableStateOf(0L) }
    var sidebarSection by rememberSaveable { mutableStateOf(SidebarSection.NAVIGATION) }
    var importTargetUri by rememberSaveable { mutableStateOf<String?>(null) }
    var exportTargetUri by rememberSaveable { mutableStateOf<String?>(null) }
    var projectDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var projectDeleteCode by rememberSaveable { mutableStateOf("") }
    var enteredProjectDeleteCode by rememberSaveable { mutableStateOf("") }
    val crashExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingCrashExport
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("The selected destination could not be opened")
            }.onFailure { /* The document picker owns its own error UI. */ }
        }
        pendingCrashExport = null
    }

    fun openCrashConsole() {
        crashReports = crashReporter.reports()
        viewModel.navigate(Surface.DIAGNOSTICS)
    }

    fun copyCrashReport(report: CrashReportSummary) {
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
            android.content.ClipData.newPlainText("Android IDE crash report", report.rawJson),
        )
    }

    fun shareCrashReport(report: CrashReportSummary) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Android IDE crash report")
            putExtra(Intent.EXTRA_TEXT, report.rawJson)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share crash log")) }
    }

    fun exportCrashReport(report: CrashReportSummary) {
        pendingCrashExport = report.rawJson
        crashExportLauncher.launch("android-ide-crash-${report.timestampMs}.json")
    }
    val importFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = importTargetUri
        if (target != null && uris.isNotEmpty()) ideViewModel.importFiles(target, uris.map { it.toString() })
        importTargetUri = null
    }
    val exportDirectoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val target = exportTargetUri
        if (uri != null && target != null) ideViewModel.exportDirectory(FileNode(target, target.substringAfterLast('/'), "vnd.android.document/directory"), uri.toString())
        exportTargetUri = null
    }
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val surfaceStateHolder = rememberSaveableStateHolder()

    LaunchedEffect(state.selectedProjectId, state.projects, state.surface) {
        val editorProject = ideState.projectRootUri
        val stillRegistered = editorProject != null && state.projects.any {
            it.location.stableId == editorProject || it.location.userVisiblePath == editorProject
        }
        if (editorProject != null &&
            (state.projects.isNotEmpty() || state.operationReport != null) &&
            (state.selectedProjectId == null || !stillRegistered)
        ) {
            ideViewModel.resetWorkspaceAfterProjectRemoval()
        }
    }
    LaunchedEffect(state.surface) {
        sidebarSection = when (state.surface) {
            Surface.EDITOR -> SidebarSection.EDITOR
            Surface.TERMINAL -> SidebarSection.TERMINAL
            Surface.BROWSER -> SidebarSection.BROWSER
            else -> SidebarSection.NAVIGATION
        }
    }

    BackHandler(
        enabled = drawerState.isOpen || phaseFeedback != null || editorNavigationPrompt || state.operationInProgress ||
            state.exitConfirmationVisible || state.navigationStack.size > 1,
    ) {
        when {
            state.exitConfirmationVisible -> viewModel.dismissExitConfirmation()
            editorNavigationPrompt -> {
                editorNavigationPrompt = false
                pendingEditorNavigation = null
                pendingNavigationClosesSidebar = true
            }
            phaseFeedback != null -> phaseFeedback = null
            drawerState.isOpen -> { moreOpen = false; coroutineScope.launch { drawerState.close() } }
            !viewModel.back() -> viewModel.requestExitConfirmation()
        }
    }

    fun navigate(surface: Surface, closeSidebar: Boolean) {
        dismissEditorIme()
        if (ideState.fileMutationLoading) {
            pendingEditorNavigation = surface
            pendingNavigationClosesSidebar = closeSidebar
            editorNavigationPrompt = true
            return
        }
        viewModel.navigate(surface)
        moreOpen = false
        if (closeSidebar) {
            coroutineScope.launch { drawerState.close() }
        }
    }
    val navigateAndCloseSidebar: (Surface) -> Unit = { surface -> navigate(surface, closeSidebar = true) }
    val surface: @Composable (Modifier) -> Unit = { modifier ->
        SurfaceHost(
            modifier = modifier,
            state = state,
            viewModel = viewModel,
            ideViewModel = ideViewModel,
            browserViewModel = browserViewModel,
            onNavigate = navigateAndCloseSidebar,
            onCreateProject = onCreateProject,
            onImportFolder = onImportFolder,
            onImportZip = onImportZip,
            onExportProject = onExportProject,
            onExportProjects = onExportProjects,
            onDuplicateProject = onDuplicateProject,
            onRelocateProject = onRelocateProject,
            onRequestExit = viewModel::requestExitConfirmation,
            onOpenNavigation = {
                dismissEditorIme()
                coroutineScope.launch { drawerState.open() }
            },
            onFeedback = { phaseFeedback = it },
            settingsSection = settingsSection,
            onSettingsSection = { section -> settingsSection = section },
            onSettingsSectionConsumed = { settingsSection = null },
            editorPanelRequest = editorPanelRequest,
            crashReports = crashReports,
            onOpenCrashConsole = ::openCrashConsole,
            onCopyCrashReport = ::copyCrashReport,
            onShareCrashReport = ::shareCrashReport,
            onExportCrashReport = ::exportCrashReport,
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            // Avoid composing the editor tree while the drawer is closed. This is
            // important for very large projects and also removes the accessibility
            // and gesture-visible sliver left by the drawer implementation.
            BoxWithConstraints {
                val drawerWidth = minOf(maxWidth * 0.8f, 380.dp)
                val closedOffset = if (drawerState.isOpen) 0.dp else -(maxWidth * 0.5f)
                ModalDrawerSheet(
                    modifier = Modifier
                        .offset(x = closedOffset)
                        .width(drawerWidth)
                        .fillMaxHeight(),
                ) {
                    if (drawerState.isOpen) surfaceStateHolder.SaveableStateProvider("contextual-navigation") {
                        ContextualNavigation(
                            modifier = Modifier.fillMaxSize(),
                            state = state,
                            section = sidebarSection,
                            appViewModel = viewModel,
                            ideViewModel = ideViewModel,
                            browserViewModel = browserViewModel,
                            browserState = browserState,
                            moreOpen = moreOpen,
                            onMore = { moreOpen = !moreOpen },
                            onNavigate = { surface, closeSidebar -> navigate(surface, closeSidebar) },
                            onSectionChange = { sidebarSection = it },
                            onSectionNavigate = { targetSection, targetSurface ->
                                sidebarSection = targetSection
                                navigate(targetSurface, closeSidebar = false)
                            },
                            onOpenProject = { viewModel.openProject(it); coroutineScope.launch { drawerState.close() } },
                            onCreateProject = { onCreateProject(null); moreOpen = false; coroutineScope.launch { drawerState.close() } },
                            onImportFolder = { onImportFolder(); moreOpen = false; coroutineScope.launch { drawerState.close() } },
                            onImportZip = { onImportZip(); moreOpen = false; coroutineScope.launch { drawerState.close() } },
                            onFeedback = { phaseFeedback = it },
                            onSettingsSection = { section -> settingsSection = section; navigate(Surface.SETTINGS, closeSidebar = true) },
                            onDismissDrawer = { coroutineScope.launch { drawerState.close() } },
                            onOpenEditorPanel = { editorPanelRequest += 1; coroutineScope.launch { drawerState.close() } },
                            onImportFiles = { target -> importTargetUri = target; importFilesLauncher.launch(arrayOf("*/*")) },
                            onExportDirectory = { node -> exportTargetUri = node.documentUri; exportDirectoryLauncher.launch("${node.displayName}.zip") },
                            onExportProject = onExportProject,
                            onDeleteProject = {
                                viewModel.clearOperationFeedback()
                                projectDeleteCode = (100..999).random().toString()
                                enteredProjectDeleteCode = ""
                                projectDeleteConfirmation = true
                            },
                        )
                    }
                }
            }
        },
    ) {
    Scaffold(
        topBar = {},
    ) { padding ->
        surfaceStateHolder.SaveableStateProvider(state.surface.name) {
            surface(Modifier.padding(padding).fillMaxSize())
        }
    }
    }

    if (state.exitConfirmationVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissExitConfirmation,
            title = { Text("Exit Android IDE application?") },
            text = { Text("Are you sure you want to exit the Android IDE application?") },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissExitConfirmation(); viewModel.exit(onExit) },
                ) { Text("Exit") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissExitConfirmation) { Text("Cancel") } },
        )
    }
    if (editorNavigationPrompt) {
        AlertDialog(
            onDismissRequest = {
                editorNavigationPrompt = false
                pendingEditorNavigation = null
                pendingNavigationClosesSidebar = true
            },
            title = { Text("File operation in progress") },
            text = { Text("A file operation is still running. Stay here to keep its progress visible, or leave it running in the background and continue.") },
            confirmButton = {
                Button(onClick = {
                    val destination = pendingEditorNavigation
                    val closeSidebar = pendingNavigationClosesSidebar
                    editorNavigationPrompt = false
                    pendingEditorNavigation = null
                    pendingNavigationClosesSidebar = true
                    if (destination != null) {
                        viewModel.navigate(destination)
                        if (closeSidebar) {
                            coroutineScope.launch { drawerState.close() }
                        }
                    }
                }) { Text("Leave running") }
            },
            dismissButton = { TextButton(onClick = {
                editorNavigationPrompt = false
                pendingEditorNavigation = null
                pendingNavigationClosesSidebar = true
            }) { Text("Keep waiting") } },
        )
    }
    if (state.navigationPromptVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissNavigationPrompt,
            title = { Text("Operation in progress") },
            text = { Text("An operation is still running. Stay here to keep its progress visible, or leave it running in the background and continue.") },
            confirmButton = { Button(onClick = viewModel::continuePendingNavigation) { Text("Leave running") } },
            dismissButton = { TextButton(onClick = viewModel::dismissNavigationPrompt) { Text("Keep waiting") } },
        )
    }
    phaseFeedback?.let { action ->
        AlertDialog(
            onDismissRequest = { phaseFeedback = null },
            title = { Text(action) },
            text = {
                Text("$action not available. Coming soon (phase ${placeholderPhase(action)})")
            },
            confirmButton = { TextButton(onClick = { phaseFeedback = null }) { Text("OK") } },
        )
    }
    if (projectDeleteConfirmation) {
        val projectName = state.projects.firstOrNull { it.id == state.selectedProjectId }?.name ?: "this project"
        val completed = state.operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !state.operationInProgress
        AlertDialog(
            onDismissRequest = { if (!state.operationInProgress) projectDeleteConfirmation = false },
            title = { Text(if (completed) "Deletion complete" else "Permanently delete $projectName?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (completed) Text(state.operationReport!!.message, color = LocalIdeColors.current.success)
                    else {
                        Text("This permanently removes the project data from its selected storage location. This action cannot be undone.")
                        Text("Type $projectDeleteCode to confirm")
                        androidx.compose.material3.OutlinedTextField(
                            value = enteredProjectDeleteCode,
                            onValueChange = { enteredProjectDeleteCode = it },
                            label = { Text("Confirmation code") },
                            singleLine = true,
                            enabled = !state.operationInProgress,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = if (completed) ({ projectDeleteConfirmation = false }) else ({ projectDeleteConfirmation = false; viewModel.permanentlyDeleteSelectedProject() }),
                    enabled = !state.operationInProgress && (completed || enteredProjectDeleteCode == projectDeleteCode),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(if (completed) "Done" else "Delete permanently") }
            },
            dismissButton = {
                if (!completed) TextButton(onClick = { projectDeleteConfirmation = false }, enabled = !state.operationInProgress) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ContextualNavigation(
    modifier: Modifier,
    state: AppShellState,
    section: SidebarSection,
    appViewModel: AppShellViewModel,
    ideViewModel: IdeViewModel,
    browserViewModel: BrowserViewModel,
    browserState: dev.android.ide.browser.BrowserUiState,
    moreOpen: Boolean,
    onMore: () -> Unit,
    onNavigate: (Surface, Boolean) -> Unit,
    onSectionChange: (SidebarSection) -> Unit,
    onSectionNavigate: (SidebarSection, Surface) -> Unit,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String?) -> Unit,
    onImportFolder: () -> Unit,
    onImportZip: () -> Unit,
    onFeedback: (String) -> Unit,
    onSettingsSection: (String) -> Unit,
    onDismissDrawer: () -> Unit,
    onOpenEditorPanel: () -> Unit,
    onImportFiles: (String) -> Unit,
    onExportDirectory: (FileNode) -> Unit,
    onExportProject: (String) -> Unit,
    onDeleteProject: () -> Unit,
) {
    val editorState by ideViewModel.uiState.collectAsState()
    val sectionStateHolder = rememberSaveableStateHolder()
    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavigationTopItem(Icons.Default.Send, "Navigation", section == SidebarSection.NAVIGATION) {
                onSectionChange(SidebarSection.NAVIGATION)
            }
            NavigationTopItem(Icons.Default.Code, "Editor", section == SidebarSection.EDITOR) {
                onSectionNavigate(SidebarSection.EDITOR, Surface.EDITOR)
            }
            NavigationTopItem(Icons.Default.Terminal, "Terminal", section == SidebarSection.TERMINAL) {
                onSectionNavigate(SidebarSection.TERMINAL, Surface.TERMINAL)
            }
            NavigationTopItem(Icons.Default.Language, "Browser", section == SidebarSection.BROWSER) {
                onSectionNavigate(SidebarSection.BROWSER, Surface.BROWSER)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
        ) {
            sectionStateHolder.SaveableStateProvider(section.name) {
            when (section) {
                SidebarSection.NAVIGATION -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Navigation", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NavigationGridItem(Icons.Default.Home, "Home") { onNavigate(Surface.HOME, true) }
                                NavigationGridItem(Icons.Default.FolderOpen, "Projects") { onNavigate(Surface.PROJECTS, true) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NavigationGridItem(Icons.Default.Settings, "Settings") { onNavigate(Surface.SETTINGS, true) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NavigationGridItem(Icons.Default.BugReport, "Console") { onNavigate(Surface.DIAGNOSTICS, true) }
                            }
                        }
                        Text("Recent projects", style = MaterialTheme.typography.titleSmall)
                        val recentProjects = state.projects
                            .sortedWith(
                                compareByDescending<dev.android.ide.contracts.ProjectIdentity> { it.lastOpenedAt ?: java.time.Instant.MIN }
                                    .thenByDescending { it.registeredAt },
                            )
                        if (recentProjects.isEmpty()) {
                            Text(
                                "No projects yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                            ) {
                                items(recentProjects, key = { it.id }) { project ->
                                    RecentProjectRow(
                                        project = project,
                                        onOpen = { onOpenProject(project.id) },
                                        onDetails = { appViewModel.showProjectDetails(project.id); onDismissDrawer() },
                                    )
                                }
                            }
                        }
                    }
                }
                SidebarSection.EDITOR -> {
                    val root = editorState.projectRootUri?.let { FileNode(it, editorState.projectName, "vnd.android.document/directory") }
                    if (root == null) {
                        Text("Open a project to view its files", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        EditorSidebar(
                            state = editorState,
                            contentPanel = when {
                                editorState.isContentSearchVisible -> EditorPanel.CONTENT_SEARCH
                                editorState.isSearchVisible -> EditorPanel.FILENAME_SEARCH
                                else -> EditorPanel.FILES
                            },
                            onPanelSelected = {},
                            onFileSelected = ideViewModel::openFile,
                            onSearchResultSelected = ideViewModel::openFileAtSearchResult,
                            onToggleDirectory = ideViewModel::toggleDirectory,
                            onRefresh = ideViewModel::refreshProject,
                            onLocate = ideViewModel::revealActiveFile,
                            onSearchFiles = ideViewModel::showFileSearch,
                            onSearchContent = ideViewModel::showContentSearch,
                            onHideFileSearch = ideViewModel::hideFileSearch,
                            onHideContentSearch = ideViewModel::hideContentSearch,
                            onImport = { target -> onImportFiles(target); onDismissDrawer() },
                            onExportDirectory = { node -> onExportDirectory(node); onDismissDrawer() },
                            onExportProject = { state.selectedProjectId?.let { onExportProject(it); onDismissDrawer() } },
                            onShowDetails = { state.selectedProjectId?.let { appViewModel.showProjectDetails(it); onDismissDrawer() } },
                            onDeleteProject = onDeleteProject,
                            onRemoveProject = appViewModel::removeSelectedProject,
                            onOpenSettings = { onNavigate(Surface.SETTINGS, true) },
                            onFeedback = onFeedback,
                            onOpenTerminal = { directoryUri ->
                                appViewModel.openTerminalForDirectory(directoryUri)
                                onDismissDrawer()
                            },
                            onOpenBrowserContent = { fileUri ->
                                appViewModel.requestBrowserNavigation(fileUri)
                                onDismissDrawer()
                            },
                            rootNode = root,
                            ideViewModel = ideViewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                SidebarSection.TERMINAL -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 108.dp),
                    ) {
                        item { Text("Terminal sessions", style = MaterialTheme.typography.labelLarge) }
                        item { NavigationItem(Icons.Default.Add, "New terminal session", false, onClick = { appViewModel.createTerminalSession(); onDismissDrawer() }) }
                        state.terminalSessions.forEach { session ->
                            item {
                                TerminalSessionSidebarItem(
                                    session = session,
                                    selected = session.id == state.selectedTerminalSessionId,
                                    feedback = state.terminalFeedback,
                                    onSelect = { appViewModel.selectTerminalSession(session.id); onDismissDrawer() },
                                    onPrepareRename = appViewModel::clearTerminalFeedback,
                                    onRename = { appViewModel.renameTerminalSession(session.id, it) },
                                    onClose = { appViewModel.closeTerminalSession(session.id) },
                                )
                            }
                        }
                        item { NavigationItem(Icons.Default.Close, "Close all sessions", false, onClick = { appViewModel.closeAllTerminalSessions(); onDismissDrawer() }) }
                    }
                }
                SidebarSection.BROWSER -> {
                    val activeDownloads = browserState.downloads.count {
                        it.status == BrowserDownloadStatus.DOWNLOADING || it.status == BrowserDownloadStatus.PAUSED
                    }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { Text("Browser", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                        item {
                            NavigationItem(Icons.Default.Add, "New tab", false, onClick = {
                                browserViewModel.hideDownloadsPage()
                                browserViewModel.createTab()
                                onNavigate(Surface.BROWSER, true)
                            })
                        }
                        item {
                            NavigationItem(if (activeDownloads > 0) Icons.Default.Downloading else Icons.Default.Download, if (activeDownloads > 0) "Downloads · $activeDownloads active" else "Downloads", false, onClick = {
                                browserViewModel.showDownloadsPage()
                                onNavigate(Surface.BROWSER, true)
                            })
                        }
                        item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                        item { Text("Open tabs (${browserState.tabs.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                        items(browserState.tabs, key = { "browser-tab-${it.id}" }) { tab ->
                            NavigationItem(
                                Icons.Default.Language,
                                tab.title.ifBlank { tab.host.ifBlank { "New tab" } },
                                tab.id == browserState.selectedTabId,
                                onClick = {
                                    browserViewModel.hideDownloadsPage()
                                    browserViewModel.selectTab(tab.id)
                                    onNavigate(Surface.BROWSER, true)
                                },
                            )
                        }
                        item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                        item {
                            NavigationItem(Icons.Default.History, "History", false, onClick = {
                                browserViewModel.showHistoryPage()
                                onNavigate(Surface.BROWSER, true)
                            })
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun RecentProjectRow(
    project: dev.android.ide.contracts.ProjectIdentity,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                project.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                relativeOpened(project.lastOpenedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Project actions", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open project") },
                    onClick = { menuOpen = false; onOpen() },
                )
                DropdownMenuItem(
                    text = { Text("Project details") },
                    onClick = { menuOpen = false; onDetails() },
                )
            }
        }
    }
}

@Composable
private fun NavigationItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TerminalSessionSidebarItem(
    session: dev.android.ide.contracts.SessionDescriptor,
    selected: Boolean,
    feedback: dev.android.ide.contracts.OperationReport?,
    onSelect: () -> Unit,
    onPrepareRename: () -> Unit,
    onRename: (String) -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(session.name) }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            NavigationItem(Icons.Default.Terminal, session.name, selected, onSelect, Modifier.weight(1f))
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Session actions") }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                text = { Text("Rename session") },
                onClick = { menuOpen = false; onPrepareRename(); renameValue = session.name; renameOpen = true },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Close, null) },
                text = { Text("Close session") },
                onClick = { menuOpen = false; onClose() },
            )
        }
    }
    if (renameOpen) {
        val completed = feedback?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(if (completed) "Rename complete" else "Rename session") },
            text = {
                if (completed) Text(feedback!!.message, color = LocalIdeColors.current.success)
                else {
                    androidx.compose.material3.OutlinedTextField(
                            value = renameValue,
                            onValueChange = { renameValue = it },
                            label = { Text("Session name") },
                            singleLine = true,
                        )
                    feedback?.let { Text(it.message, color = operationStatusColor(it.outcome)) }
                }
            },
            confirmButton = { Button(onClick = if (completed) ({ renameOpen = false }) else ({ onRename(renameValue) }), enabled = completed || renameValue.isNotBlank()) { Text(if (completed) "Done" else "Rename") } },
            dismissButton = { if (!completed) TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavigationGridItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val tileShape = RoundedCornerShape(12.dp)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(92.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = tileShape,
            )
            .background(MaterialTheme.colorScheme.surface, tileShape)
            .semantics {
                role = Role.Button
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private enum class SidebarSection { NAVIGATION, EDITOR, TERMINAL, BROWSER }

@Composable
private fun NavigationTopItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .height(68.dp)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
            )
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SurfaceHost(
    modifier: Modifier,
    state: AppShellState,
    viewModel: AppShellViewModel,
    ideViewModel: IdeViewModel,
    browserViewModel: BrowserViewModel,
    onNavigate: (Surface) -> Unit,
    onCreateProject: (String?) -> Unit,
    onImportFolder: () -> Unit,
    onImportZip: () -> Unit,
    onExportProject: (String) -> Unit,
    onExportProjects: (Set<String>) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onRelocateProject: (String) -> Unit,
    onRequestExit: () -> Unit,
    onOpenNavigation: () -> Unit,
    onFeedback: (String) -> Unit,
    settingsSection: String?,
    onSettingsSection: (String) -> Unit,
    onSettingsSectionConsumed: () -> Unit,
    editorPanelRequest: Long,
    crashReports: List<CrashReportSummary>,
    onOpenCrashConsole: () -> Unit,
    onCopyCrashReport: (CrashReportSummary) -> Unit,
    onShareCrashReport: (CrashReportSummary) -> Unit,
    onExportCrashReport: (CrashReportSummary) -> Unit,
) {
    val ideState by ideViewModel.uiState.collectAsState()
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        EditorSurface(
            shellState = state,
            shellViewModel = viewModel,
            ideViewModel = ideViewModel,
            browserViewModel = browserViewModel,
            visible = state.surface == Surface.EDITOR,
            onOpenGlobalNavigation = onOpenNavigation,
            editorPanelRequest = editorPanelRequest,
            onOpenSettings = { onSettingsSection("Editor Services"); onNavigate(Surface.SETTINGS) },
            onExportProject = onExportProject,
            onFeedback = onFeedback,
            onOpenBrowserContent = { url -> viewModel.requestBrowserNavigation(url) },
            onOpenProjectPreview = { url, reuseKey ->
                viewModel.hideBrowserOverlay()
                viewModel.requestBrowserNavigation(url, reuseKey = reuseKey)
            },
            onOpenFloatingBrowserPreview = { url, reuseKey ->
                viewModel.requestFloatingBrowserNavigation(url, reuseKey)
            },
            modifier = if (state.surface == Surface.EDITOR) Modifier.fillMaxSize() else Modifier.size(1.dp).alpha(0f),
        )
        when (state.surface) {
        Surface.HOME -> HomeSurface(
            onNavigate = onNavigate,
            onOpenNavigation = onOpenNavigation,
            onExit = onRequestExit,
            onFeedback = onFeedback,
            crashRecoveryCount = ideState.recoveryEntries.size,
            crashReportCount = crashReports.size,
            onOpenCrashConsole = onOpenCrashConsole,
        )
        Surface.DIAGNOSTICS -> CrashConsoleSurface(
            reports = crashReports,
            recoveryCount = ideState.recoveryEntries.size,
            onOpenNavigation = onOpenNavigation,
            onRefresh = onOpenCrashConsole,
            onCopy = onCopyCrashReport,
            onShare = onShareCrashReport,
            onExport = onExportCrashReport,
        )
        Surface.PROJECTS -> ProjectsSurface(state, viewModel, onCreateProject, onImportFolder, onImportZip, onExportProject, onExportProjects, onDuplicateProject, onRelocateProject, viewModel::openTerminalForProject, onFeedback, onOpenNavigation, modifier)
        Surface.PROJECT_DETAILS -> ProjectDetailsSurface(state, viewModel, onExportProject, onDuplicateProject, onRelocateProject, onOpenNavigation, modifier)
        Surface.EDITOR -> Unit
        Surface.TERMINAL -> TerminalSurface(
            state = state,
            viewModel = viewModel,
            terminalSettings = state.terminalSettings,
            onOpenNavigation = onOpenNavigation,
            onOpenSettings = { onSettingsSection("Terminal"); onNavigate(Surface.SETTINGS) },
            onSettingsChanged = viewModel::setTerminalSettings,
            modifier = modifier,
        )
        Surface.BROWSER -> BrowserSurface(
            browser = browserViewModel,
            onOpenNavigation = onOpenNavigation,
            onOpenSettings = { onSettingsSection("Browser"); onNavigate(Surface.SETTINGS) },
            onApplicationBack = { if (!viewModel.back()) viewModel.requestExitConfirmation() },
            initialNavigation = state.pendingBrowserNavigations.firstOrNull(),
            onInitialNavigationConsumed = viewModel::consumeBrowserNavigationRequest,
            modifier = modifier,
        )
        Surface.SETTINGS -> SettingsScreen(
            uiState = ideState,
            ideViewModel = ideViewModel,
            applicationTheme = state.applicationTheme,
            onApplicationThemeChanged = viewModel::setApplicationTheme,
            terminalSettings = state.terminalSettings,
            onTerminalSettingsChanged = viewModel::setTerminalSettings,
            onNavigationIconClick = onOpenNavigation,
            scrollToSection = settingsSection,
            onScrollConsumed = onSettingsSectionConsumed,
        )
        else -> DomainPlaceholderSurface(surfaceTitle(state.surface), modifier, onOpenNavigation, onFeedback)
        }
    }
}

@Composable
private fun DomainPlaceholderSurface(title: String, modifier: Modifier, onOpenNavigation: () -> Unit, onFeedback: (String) -> Unit) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, "Open sidebar") } },
        )
        Text(
            when (title) {
                "Browser" -> "Browser is not currently available. Use Terminal to start and inspect local development servers."
                else -> "$title not available. Coming soon (phase ${placeholderPhase(title)})"
            },
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun placeholderPhase(label: String): Int = when (label) {
    "Editor", "Find", "Replace", "Save", "Save As", "Preview", "Project Filename Search",
    "Project Content Search", "Create file", "Create folder", "Import files", "Locate Current File" -> 4
    "Terminal", "New session", "Close session", "Close all sessions", "Rename session", "Zoom",
    "Open Terminal" -> 3
    "Browser", "New tab", "Back", "Forward", "Reload", "Downloads", "Developer tools", "Viewport",
    "Open Browser or Preview" -> 5
    else -> 8
}

private fun surfaceTitle(surface: Surface): String = when (surface) {
    Surface.HOME -> "Home"
    Surface.DIAGNOSTICS -> "Diagnostics"
    Surface.PROJECTS -> "Projects"
    Surface.PROJECT_DETAILS -> "Project Details"
    Surface.EDITOR -> "Editor"
    Surface.TERMINAL -> "Terminal"
    Surface.BROWSER -> "Browser"
    Surface.SETTINGS -> "Settings"
}

private fun relativeOpened(lastOpenedAt: java.time.Instant?): String {
    if (lastOpenedAt == null) return "Never opened"
    val minutes = java.time.Duration.between(lastOpenedAt, java.time.Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "Opened just now"
        minutes < 60 -> "Opened ${minutes}m ago"
        minutes < 1_440 -> "Opened ${minutes / 60}h ago"
        else -> "Opened ${minutes / 1_440}d ago"
    }
}
