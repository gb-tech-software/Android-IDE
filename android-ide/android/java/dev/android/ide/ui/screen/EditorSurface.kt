package dev.android.ide.ui.screen
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.android.ide.app.AppShellState
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.contracts.Surface
import dev.android.ide.ui.components.EditorPane
import dev.android.ide.ui.components.EditorProjectActionsMenu
import dev.android.ide.ui.components.EditorTabBar
import dev.android.ide.ui.components.FileTreePanel
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.viewmodel.IdeViewModel
import dev.android.ide.viewmodel.model.EditorTab
import dev.android.ide.viewmodel.model.EditorDiagnostic
import dev.android.ide.viewmodel.model.FileNode
import dev.android.ide.viewmodel.model.FileSearchResult
import dev.android.ide.viewmodel.model.FileOpDialog
import dev.android.ide.viewmodel.model.IdeUiState
import dev.android.ide.viewmodel.model.findNode
import dev.android.ide.viewmodel.model.pathTo
import dev.android.ide.editor.EditorOutbound
import kotlinx.coroutines.launch

private const val DIRECTORY_MIME = "vnd.android.document/directory"

private fun isLanguageSupportFeedback(message: String): Boolean =
    message.contains("language intelligence", ignoreCase = true) ||
        message.contains("language-server", ignoreCase = true) ||
        message.contains("language server", ignoreCase = true)

@Composable
fun EditorSurface(
    shellState: AppShellState,
    shellViewModel: AppShellViewModel,
    ideViewModel: IdeViewModel,
    visible: Boolean,
    onOpenGlobalNavigation: () -> Unit,
    editorPanelRequest: Long = 0L,
    onOpenSettings: () -> Unit,
    onExportProject: (String) -> Unit,
    onFeedback: (String) -> Unit,
    onOpenBrowserContent: (String) -> Unit,
    onOpenProjectPreview: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ideViewModel.uiState.collectAsState()
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val dialog = state.fileOpDialog as? FileOpDialog.Export
        if (uri != null && dialog != null) ideViewModel.exportDirectory(dialog.node, uri.toString())
    }
    val project = shellState.projects.firstOrNull { it.id == shellState.selectedProjectId }

    LaunchedEffect(project?.id, project?.location?.stableId) {
        project?.location?.stableId?.let(ideViewModel::openProject)
    }

    if (state.projectRootUri == null && !shellState.restoring) {
        if (visible) {
            EditorEmptyState(
                projectName = project?.name,
                onOpenProjects = { shellViewModel.navigate(Surface.PROJECTS) },
                onOpenNavigation = onOpenGlobalNavigation,
                modifier = modifier,
            )
            EditorDialogHost(state, ideViewModel, onChooseExportDestination = { dialog -> exportZip.launch("${dialog.node.displayName}.zip") }, onOpenBrowserContent = onOpenBrowserContent)
        }
        return
    }

    EditorWorkspace(
        state = state,
        ideViewModel = ideViewModel,
        fileTree = state.fileTree,
        onOpenGlobalNavigation = onOpenGlobalNavigation,
        onOpenSettings = onOpenSettings,
        onFeedback = onFeedback,
        onOpenBrowserContent = onOpenBrowserContent,
        onOpenProjectPreview = onOpenProjectPreview,
        showChrome = visible,
        modifier = modifier.fillMaxSize().background(LocalIdeColors.current.background),
    )
    if (visible && state.fileMutationLoading) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)).clickable { },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Surface(
                tonalElevation = 6.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Working…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (visible) {
        EditorDialogHost(state, ideViewModel, onChooseExportDestination = { dialog -> exportZip.launch("${dialog.node.displayName}.zip") }, onOpenBrowserContent = onOpenBrowserContent)
    }
}

@Composable
private fun EditorSidebarActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(38.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = shape,
            )
            .semantics {
                role = Role.Button
                this.selected = selected
            },
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

enum class EditorPanel { FILES, FILENAME_SEARCH, CONTENT_SEARCH }

@Composable
fun EditorSidebar(
    state: IdeUiState,
    contentPanel: EditorPanel,
    onPanelSelected: (EditorPanel) -> Unit,
    onFileSelected: (String) -> Unit,
    onSearchResultSelected: (FileSearchResult) -> Unit,
    onToggleDirectory: (String) -> Unit,
    onRefresh: () -> Unit,
    onLocate: () -> Unit,
    onSearchFiles: () -> Unit,
    onSearchContent: () -> Unit,
    onHideFileSearch: () -> Unit,
    onHideContentSearch: () -> Unit,
    onImport: (String) -> Unit,
    onExportDirectory: (FileNode) -> Unit,
    onExportProject: () -> Unit,
    onShowDetails: () -> Unit,
    onDeleteProject: () -> Unit,
    onRemoveProject: () -> Unit,
    onOpenSettings: () -> Unit,
    onFeedback: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenBrowserContent: (String) -> Unit,
    rootNode: FileNode?,
    ideViewModel: IdeViewModel,
    modifier: Modifier,
) {
    val colors = LocalIdeColors.current
    val panelStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val root = rootNode ?: return
    Column(modifier.background(colors.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorSidebarActionTile(
                icon = Icons.Default.FolderOpen,
                label = "Files",
                selected = contentPanel == EditorPanel.FILES,
                onClick = { onPanelSelected(EditorPanel.FILES); onHideFileSearch(); onHideContentSearch() },
            )
            EditorSidebarActionTile(
                icon = Icons.Default.Search,
                label = "Search",
                selected = state.isSearchVisible,
                onClick = {
                    if (state.isSearchVisible) onHideFileSearch() else onSearchFiles()
                    onPanelSelected(if (state.isSearchVisible) EditorPanel.FILES else EditorPanel.FILENAME_SEARCH)
                },
            )
            EditorSidebarActionTile(
                icon = Icons.Default.Code,
                label = "Contents",
                selected = state.isContentSearchVisible,
                onClick = {
                    if (state.isContentSearchVisible) onHideContentSearch() else onSearchContent()
                    onPanelSelected(if (state.isContentSearchVisible) EditorPanel.FILES else EditorPanel.CONTENT_SEARCH)
                },
            )
            EditorSidebarActionTile(Icons.Default.MyLocation, "Locate", onClick = onLocate)
            if (state.localStorageConfirmed) {
                EditorSidebarActionTile(Icons.Default.Description, "New file", onClick = { ideViewModel.showCreateFileDialog(root) })
                EditorSidebarActionTile(Icons.Default.CreateNewFolder, "New folder", onClick = { ideViewModel.showCreateFolderDialog(root) })
            }
            var moreOpen by remember { mutableStateOf(false) }
            Box {
                EditorSidebarActionTile(Icons.Default.MoreVert, "More", onClick = { moreOpen = true })
                EditorProjectActionsMenu(
                    expanded = moreOpen,
                    onDismiss = { moreOpen = false },
                    onNewFile = { ideViewModel.showCreateFileDialog(root) },
                    onNewFolder = { ideViewModel.showCreateFolderDialog(root) },
                    onImportFiles = { onImport(root.documentUri) },
                    onExportProject = onExportProject,
                    onRefresh = onRefresh,
                    onShowDetails = onShowDetails,
                    onDeleteProject = onDeleteProject,
                    onRemoveProject = onRemoveProject,
                    onOpenTerminal = { onOpenTerminal(root.documentUri) },
                    includeCreationActions = false,
                    fullWorkspaceAvailable = state.localWorkspaceAvailable,
                    localStorageConfirmed = state.localStorageConfirmed,
                )
            }
        }
        HorizontalDivider(color = colors.separator)
        panelStateHolder.SaveableStateProvider(contentPanel.name) {
        when (contentPanel) {
            EditorPanel.FILES -> FileTreePanel(
                nodes = state.fileTree,
                clipboardItems = state.clipboardItems,
                clipboardIsCut = state.clipboardIsCut,
                projectName = state.projectName,
                activeTabDocumentUri = state.openTabs.firstOrNull { it.isActive }?.documentUri,
                locateTargetUri = state.locateTargetUri,
                locateRequestToken = state.locateRequestToken,
                onLocateConsumed = ideViewModel::clearLocateRequest,
                hideGitFolder = state.editorSettings.hideGitFolder,
                fullWorkspaceAvailable = state.localWorkspaceAvailable,
                localStorageConfirmed = state.localStorageConfirmed,
                isMultiSelectMode = state.isMultiSelectMode,
                selectedUris = state.selectedUris,
                isSearchVisible = false,
                isContentSearchVisible = false,
                fileSearchQuery = state.fileSearchQuery,
                fileSearchResults = state.fileSearchResults,
                contentSearchQuery = state.contentSearchQuery,
                contentSearchResults = state.contentSearchResults,
                contentSearchMatchCase = state.contentSearchMatchCase,
                contentSearchWholeWord = state.contentSearchWholeWord,
                contentSearchRegex = state.contentSearchRegex,
                contentSearchShowContext = state.contentSearchShowContext,
                onFileClick = onFileSelected,
                onFileDoubleClick = ideViewModel::openFilePermanent,
                onDirToggle = onToggleDirectory,
                onShowRenameDialog = ideViewModel::showRenameDialog,
                onShowDeleteDialog = ideViewModel::showDeleteDialog,
                onShowCreateFileDialog = ideViewModel::showCreateFileDialog,
                onShowCreateFolderDialog = ideViewModel::showCreateFolderDialog,
                onShowDuplicateDialog = ideViewModel::showDuplicateDialog,
                onCopyNode = ideViewModel::copyFileNode,
                onCutNode = ideViewModel::cutFileNode,
                onPasteInto = ideViewModel::pasteFileNode,
                onImportFilesAt = { onImport(it.documentUri) },
                onExportDirectory = onExportDirectory,
                onNewFileAtRoot = { ideViewModel.showCreateFileDialog(root) },
                onNewFolderAtRoot = { ideViewModel.showCreateFolderDialog(root) },
                onImportFilesAtRoot = { onImport(root.documentUri) },
                onExportProject = onExportProject,
                onRefresh = onRefresh,
                onShowProjectDetails = onShowDetails,
                onDeleteProject = onDeleteProject,
                onRemoveProject = onRemoveProject,
                onPasteAtRoot = { ideViewModel.pasteFileNode(root) },
                onCopyPath = ideViewModel::copyPathToClipboard,
                onToggleNodeSelection = ideViewModel::toggleNodeSelection,
                onExitSelectionMode = ideViewModel::exitSelectionMode,
                onSearchQueryChange = ideViewModel::searchFiles,
                onContentSearchQueryChange = ideViewModel::setContentSearchQuery,
                onContentSearchMatchCaseChange = ideViewModel::setContentSearchMatchCase,
                onContentSearchWholeWordChange = ideViewModel::setContentSearchWholeWord,
                onContentSearchRegexChange = ideViewModel::setContentSearchRegex,
                onContentSearchShowContextChange = ideViewModel::setContentSearchShowContext,
                onReplaceProjectContents = ideViewModel::replaceProjectContents,
                onReplaceFileContents = ideViewModel::replaceFileContents,
                onClearContentSearchResults = ideViewModel::clearContentSearchResults,
                onSearchFileSelect = { result -> onFileSelected(result.documentUri) },
                onExecuteContentSearch = { ideViewModel.searchProjectContents(state.contentSearchQuery) },
                onOpenTerminalAtRoot = { onOpenTerminal(root.documentUri) },
                onOpenTerminalAt = { onOpenTerminal(it.documentUri) },
                onOpenBrowserContent = { node ->
                    ideViewModel.browserContentUri(node.documentUri)?.let(onOpenBrowserContent)
                },
                defaultPreviewPath = state.defaultPreviewPath,
                onSetDefaultPreview = { node -> ideViewModel.setDefaultPreviewFile(node.documentUri) },
                modifier = Modifier.weight(1f),
            )
            EditorPanel.FILENAME_SEARCH -> FileTreePanel(
                nodes = state.fileTree, clipboardItems = state.clipboardItems, clipboardIsCut = state.clipboardIsCut,
                projectName = state.projectName, activeTabDocumentUri = state.openTabs.firstOrNull { it.isActive }?.documentUri,
                locateTargetUri = state.locateTargetUri, locateRequestToken = state.locateRequestToken, onLocateConsumed = ideViewModel::clearLocateRequest,
                hideGitFolder = state.editorSettings.hideGitFolder, fullWorkspaceAvailable = state.localWorkspaceAvailable,
                localStorageConfirmed = state.localStorageConfirmed, isMultiSelectMode = false, selectedUris = emptySet(),
                isSearchVisible = true, isContentSearchVisible = false, fileSearchQuery = state.fileSearchQuery, fileSearchResults = state.fileSearchResults,
                fileSearchIncludeFolders = state.fileSearchIncludeFolders, fileSearchRunning = state.fileSearchRunning,
                contentSearchQuery = state.contentSearchQuery, contentSearchResults = state.contentSearchResults,
                contentSearchMatchCase = state.contentSearchMatchCase, contentSearchWholeWord = state.contentSearchWholeWord,
                contentSearchRegex = state.contentSearchRegex, contentSearchShowContext = state.contentSearchShowContext,
                onFileClick = onFileSelected, onFileDoubleClick = ideViewModel::openFilePermanent,
                onDirToggle = onToggleDirectory, onShowRenameDialog = ideViewModel::showRenameDialog, onShowDeleteDialog = ideViewModel::showDeleteDialog,
                onShowCreateFileDialog = ideViewModel::showCreateFileDialog, onShowCreateFolderDialog = ideViewModel::showCreateFolderDialog, onShowDuplicateDialog = ideViewModel::showDuplicateDialog,
                onCopyNode = ideViewModel::copyFileNode, onCutNode = ideViewModel::cutFileNode, onPasteInto = ideViewModel::pasteFileNode, onImportFilesAt = { onImport(it.documentUri) }, onExportDirectory = onExportDirectory,
                onNewFileAtRoot = { ideViewModel.showCreateFileDialog(root) }, onNewFolderAtRoot = { ideViewModel.showCreateFolderDialog(root) }, onImportFilesAtRoot = { onImport(root.documentUri) }, onExportProject = onExportProject,
                onRefresh = onRefresh, onShowProjectDetails = onShowDetails, onDeleteProject = onDeleteProject, onRemoveProject = onRemoveProject, onPasteAtRoot = { ideViewModel.pasteFileNode(root) }, onCopyPath = ideViewModel::copyPathToClipboard,
                onToggleNodeSelection = ideViewModel::toggleNodeSelection, onExitSelectionMode = ideViewModel::exitSelectionMode, onSearchQueryChange = ideViewModel::searchFiles, onContentSearchQueryChange = ideViewModel::setContentSearchQuery,
                onContentSearchMatchCaseChange = ideViewModel::setContentSearchMatchCase, onContentSearchWholeWordChange = ideViewModel::setContentSearchWholeWord,
                onContentSearchRegexChange = ideViewModel::setContentSearchRegex, onContentSearchShowContextChange = ideViewModel::setContentSearchShowContext,
                onReplaceProjectContents = ideViewModel::replaceProjectContents,
                onReplaceFileContents = ideViewModel::replaceFileContents,
                onClearContentSearchResults = ideViewModel::clearContentSearchResults,
                onFileSearchIncludeFoldersChange = ideViewModel::setFileSearchIncludeFolders, onSearchFileSelect = { result -> onFileSelected(result.documentUri) }, onExecuteContentSearch = { ideViewModel.searchProjectContents(state.contentSearchQuery) }, onOpenTerminalAtRoot = { onOpenTerminal(root.documentUri) }, onOpenTerminalAt = { onOpenTerminal(it.documentUri) }, onOpenBrowserContent = { node -> ideViewModel.browserContentUri(node.documentUri)?.let(onOpenBrowserContent) }, defaultPreviewPath = state.defaultPreviewPath, onSetDefaultPreview = { node -> ideViewModel.setDefaultPreviewFile(node.documentUri) }, modifier = Modifier.weight(1f),
            )
            EditorPanel.CONTENT_SEARCH -> FileTreePanel(
                nodes = state.fileTree, clipboardItems = state.clipboardItems, clipboardIsCut = state.clipboardIsCut,
                projectName = state.projectName, activeTabDocumentUri = state.openTabs.firstOrNull { it.isActive }?.documentUri,
                locateTargetUri = state.locateTargetUri, locateRequestToken = state.locateRequestToken, onLocateConsumed = ideViewModel::clearLocateRequest,
                hideGitFolder = state.editorSettings.hideGitFolder, fullWorkspaceAvailable = state.localWorkspaceAvailable,
                localStorageConfirmed = state.localStorageConfirmed, isMultiSelectMode = false, selectedUris = emptySet(),
                isSearchVisible = false, isContentSearchVisible = true, fileSearchQuery = state.fileSearchQuery, fileSearchResults = state.fileSearchResults,
                contentSearchQuery = state.contentSearchQuery, contentSearchResults = state.contentSearchResults,
                contentSearchCompletedQuery = state.contentSearchCompletedQuery,
                contentSearchRunning = state.contentSearchRunning,
                contentSearchWarning = state.contentSearchWarning,
                contentSearchMatchCase = state.contentSearchMatchCase, contentSearchWholeWord = state.contentSearchWholeWord,
                contentSearchRegex = state.contentSearchRegex, contentSearchShowContext = state.contentSearchShowContext,
                onFileClick = onFileSelected, onFileDoubleClick = ideViewModel::openFilePermanent,
                onDirToggle = onToggleDirectory, onShowRenameDialog = ideViewModel::showRenameDialog, onShowDeleteDialog = ideViewModel::showDeleteDialog,
                onShowCreateFileDialog = ideViewModel::showCreateFileDialog, onShowCreateFolderDialog = ideViewModel::showCreateFolderDialog, onShowDuplicateDialog = ideViewModel::showDuplicateDialog,
                onCopyNode = ideViewModel::copyFileNode, onCutNode = ideViewModel::cutFileNode, onPasteInto = ideViewModel::pasteFileNode, onImportFilesAt = { onImport(it.documentUri) }, onExportDirectory = onExportDirectory,
                onNewFileAtRoot = { ideViewModel.showCreateFileDialog(root) }, onNewFolderAtRoot = { ideViewModel.showCreateFolderDialog(root) }, onImportFilesAtRoot = { onImport(root.documentUri) }, onExportProject = onExportProject,
                onRefresh = onRefresh, onShowProjectDetails = onShowDetails, onDeleteProject = onDeleteProject, onRemoveProject = onRemoveProject, onPasteAtRoot = { ideViewModel.pasteFileNode(root) }, onCopyPath = ideViewModel::copyPathToClipboard,
                onToggleNodeSelection = ideViewModel::toggleNodeSelection, onExitSelectionMode = ideViewModel::exitSelectionMode, onSearchQueryChange = ideViewModel::searchFiles, onContentSearchQueryChange = ideViewModel::setContentSearchQuery,
                onContentSearchMatchCaseChange = ideViewModel::setContentSearchMatchCase, onContentSearchWholeWordChange = ideViewModel::setContentSearchWholeWord,
                onContentSearchRegexChange = ideViewModel::setContentSearchRegex, onContentSearchShowContextChange = ideViewModel::setContentSearchShowContext,
                onReplaceProjectContents = ideViewModel::replaceProjectContents,
                onReplaceFileContents = ideViewModel::replaceFileContents,
                onClearContentSearchResults = ideViewModel::clearContentSearchResults,
                onSearchFileSelect = onSearchResultSelected, onExecuteContentSearch = { ideViewModel.searchProjectContents(state.contentSearchQuery) }, onOpenTerminalAtRoot = { onOpenTerminal(root.documentUri) }, onOpenTerminalAt = { onOpenTerminal(it.documentUri) }, onOpenBrowserContent = { node -> ideViewModel.browserContentUri(node.documentUri)?.let(onOpenBrowserContent) }, defaultPreviewPath = state.defaultPreviewPath, onSetDefaultPreview = { node -> ideViewModel.setDefaultPreviewFile(node.documentUri) }, modifier = Modifier.weight(1f),
            )
        }
        }
    }
}

@Composable
private fun SidebarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalIdeColors.current
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (selected) colors.primary else colors.textSecondary)
    }
}

@Composable
private fun EditorWorkspace(
    state: IdeUiState,
    ideViewModel: IdeViewModel,
    fileTree: List<FileNode>,
    onOpenGlobalNavigation: () -> Unit,
    onOpenSettings: () -> Unit,
    onFeedback: (String) -> Unit,
    onOpenBrowserContent: (String) -> Unit,
    onOpenProjectPreview: (String, String) -> Unit,
    showChrome: Boolean,
    modifier: Modifier,
) {
    val colors = LocalIdeColors.current
    val context = LocalContext.current
    val activeTab = state.openTabs.firstOrNull { it.isActive }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val previewScope = rememberCoroutineScope()
    var previewNotice by remember { mutableStateOf<String?>(null) }
    if (showChrome) {
        LaunchedEffect(state.statusMessage) {
            val message = state.statusMessage.trim()
            if (message.isNotEmpty() && state.fileOpDialog == null &&
                !isLanguageSupportFeedback(message) && !message.contains("preview", ignoreCase = true)
            ) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (showChrome) LaunchedEffect(activeTab?.id) { previewNotice = null }
    Box(modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            if (showChrome) EditorTopBar(
                state = state, activeTab = activeTab, fileTree = fileTree,
                projectRootUri = state.projectRootUri,
                onOpenGlobalNavigation = onOpenGlobalNavigation,
                onFind = {
                    if (state.isEditorSearchVisible) {
                        ideViewModel.sendEditorCommand(EditorOutbound.CloseSearch)
                        ideViewModel.dismissEditorSearch()
                    } else {
                        ideViewModel.showEditorFind()
                        ideViewModel.sendEditorCommand(EditorOutbound.ShowFind)
                    }
                },
                onSave = {
                    activeTab?.let { tab ->
                        if (tab.isBlank) ideViewModel.showBlankTabSaveDialog(tab.id) else ideViewModel.saveActiveFile()
                    }
                },
                onSaveAs = ideViewModel::showSaveAsDialog,
                onPreview = {
                    previewScope.launch {
                        val previewTarget = ideViewModel.resolveActivePreviewTarget()
                        if (previewTarget == null) previewNotice = ideViewModel.uiState.value.statusMessage
                        else {
                            previewNotice = null
                            onOpenProjectPreview(previewTarget.url, previewTarget.reuseKey)
                        }
                    }
                },
                onRefreshProject = ideViewModel::refreshProject,
                onCloseOtherTabs = ideViewModel::closeOtherTabs,
                onCopyFilePath = ideViewModel::copyPathToClipboard,
                onLocateFile = ideViewModel::revealActiveFile,
                onOpenFile = ideViewModel::openFile,
                loadNavChildren = ideViewModel::loadNavChildren,
                onFeedback = onFeedback,
                onOpenSettings = onOpenSettings,
                onRetryLanguageServerPackages = ideViewModel::retryLanguageServerPackageInstallation,
                onRevealDiagnostic = ideViewModel::revealDiagnostic,
            )
            if (showChrome && !state.localWorkspaceAvailable && state.projectRootUri != null) {
                Text(
                    text = state.localWorkspaceExplanation
                        ?: "Edit existing files only. Move this project to Android IDE storage or Android external storage for full access.",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (showChrome && state.openTabs.isNotEmpty()) {
                EditorTabBar(
                    tabs = state.openTabs, onTabSelected = ideViewModel::selectTab, onTabCloseSafe = ideViewModel::closeTabSafe,
                    onTabSave = { tabId ->
                        val target = state.openTabs.firstOrNull { it.id == tabId }
                        if (target?.isBlank == true) ideViewModel.showBlankTabSaveDialog(tabId) else ideViewModel.saveTabById(tabId)
                    }, onTabPin = ideViewModel::pinTab,
                    onTabLocate = { tabId -> ideViewModel.selectTab(tabId); ideViewModel.revealActiveFile() },
                    onCloseOthers = ideViewModel::closeOtherTabs,
                    onCloseAll = ideViewModel::closeAllTabs, onNewBlankTab = ideViewModel::newBlankTab,
                    allowManualSave = !state.editorSettings.autoSave,
                )
                HorizontalDivider(color = colors.separator)
            }
            EditorPane(
                activeTab = activeTab, activeTabContent = activeTab?.let { ideViewModel.editorContentForTab(it.id, it.content) },
                isEditorReady = state.isEditorReady, editorBindRevision = state.editorBindRevision, editorCommands = ideViewModel.editorCommand,
                onEditorRendererGone = ideViewModel::onEditorRendererGone, onEditorMessage = ideViewModel::onEditorMessage,
                onInsertText = { ideViewModel.sendEditorCommand(EditorOutbound.InsertText(it)) }, onExecuteCommand = { ideViewModel.sendEditorCommand(EditorOutbound.ExecuteCommand(it)) },
                onInsertPair = { opening, closing -> ideViewModel.sendEditorCommand(EditorOutbound.InsertPair(opening, closing)) },
                onInsertSnippet = { trigger, snippet -> ideViewModel.sendEditorCommand(EditorOutbound.InsertSnippet(trigger, snippet)) },
                onPasteFromClipboard = ideViewModel::pasteFromKotlinClipboard, hasEditorSelection = state.hasEditorSelection,
                languageServerFeatures = state.languageServerFeatures,
                showKeyboardToolbar = state.editorSettings.showKeyboardToolbar, showSymbolBar = state.editorSettings.showSymbolBar,
                keyboardToolbarOrder = state.editorSettings.keyboardToolbarOrder,
                tabCursorPositions = state.tabCursorPositions, tabScrollPositions = state.tabScrollPositions, modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            if (showChrome && state.editorSettings.showStatusBar && !keyboardVisible) EditorStatusBar(state, activeTab)
        }
    }
    if (showChrome) previewNotice?.takeIf(String::isNotBlank)?.let { message ->
        AlertDialog(
            onDismissRequest = { previewNotice = null },
            title = { Text("Preview unavailable") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    previewNotice = null
                    previewScope.launch {
                        val target = ideViewModel.resolveActivePreviewTarget()
                        if (target == null) previewNotice = ideViewModel.uiState.value.statusMessage
                        else onOpenProjectPreview(target.url, target.reuseKey)
                    }
                }) { Text("Retry") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { previewNotice = null; onOpenSettings() }) { Text("Settings") }
                    TextButton(onClick = { previewNotice = null }) { Text("Dismiss") }
                }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    state: IdeUiState,
    activeTab: EditorTab?,
    fileTree: List<FileNode>,
    projectRootUri: String?,
    onOpenGlobalNavigation: () -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onPreview: () -> Unit,
    onRefreshProject: () -> Unit,
    onCloseOtherTabs: (String) -> Unit,
    onCopyFilePath: (String) -> Unit,
    onLocateFile: () -> Unit,
    onOpenFile: (String) -> Unit,
    loadNavChildren: suspend (String) -> List<FileNode>,
    onFeedback: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRetryLanguageServerPackages: () -> Unit,
    onRevealDiagnostic: (EditorDiagnostic) -> Unit,
) {
    val colors = LocalIdeColors.current
    var moreOpen by remember { mutableStateOf(false) }
    var pathOpen by remember { mutableStateOf(false) }
    var languageSupportOpen by rememberSaveable { mutableStateOf(false) }
    val packageSetupPending = state.editorSettings.languageServerEnabled &&
        state.languageServerPackageSetupMessage?.contains("Checking", ignoreCase = true) == true
    val packageSetupFailed = state.editorSettings.languageServerEnabled && state.languageServerPackageSetupCanRetry
    val languageSupportNeedsAttention = packageSetupFailed || state.languageServerFailure != null
    val languageSupportMessage = when {
        !state.editorSettings.languageServerEnabled -> "LSP settings are disabled. Enable them in Editor Services settings to use language support."
        packageSetupFailed -> "LSP service packages are not installed. Install or update them in Editor Services settings."
        packageSetupPending -> "LSP support is checking its service packages for the active project."
        activeTab == null -> "Open a source file in a project to check language support."
        state.languageServerFailure != null -> "LSP service is unavailable for the active document. Retry or inspect Editor Services settings."
        state.languageIntelligenceAvailable -> "Language support is available for the active document."
        else -> "LSP support is unavailable or unsupported for the current active document. Editing remains available."
    }
    val activeDiagnostics = activeTab?.documentUri?.let { state.languageServerDiagnostics[it] }.orEmpty()
    val activeTabCanSave = activeTab != null && !activeTab.isImagePreview &&
        (activeTab.content != null || activeTab.isBlank)
    var problemsOpen by rememberSaveable { mutableStateOf(false) }
    val path = remember(fileTree, activeTab?.documentUri, state.projectName) {
        activeTab?.let { fileTree.pathTo(it.documentUri) } ?: state.projectName
    }
    val node = remember(fileTree, activeTab?.documentUri) {
        activeTab?.let { fileTree.findNode(it.documentUri) }
    }
    val activeParentUri = node?.parentDocumentUri ?: projectRootUri
    var navCurrentUri by remember { mutableStateOf<String?>(null) }
    var navItems by remember { mutableStateOf(emptyList<FileNode>()) }
    var navLoading by remember { mutableStateOf(false) }
    LaunchedEffect(pathOpen, activeParentUri, navCurrentUri) {
        if (!pathOpen) {
            navCurrentUri = null
            navItems = emptyList()
            return@LaunchedEffect
        }
        if (navCurrentUri == null) {
            navCurrentUri = activeParentUri
            return@LaunchedEffect
        }
        val targetUri = navCurrentUri ?: return@LaunchedEffect
        navLoading = true
        navItems = loadNavChildren(targetUri)
        navLoading = false
    }
    Row(Modifier.fillMaxWidth().height(56.dp).background(colors.surface).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenGlobalNavigation) { Icon(Icons.Default.Menu, "Open application navigation", tint = colors.textPrimary) }
        Box(Modifier.weight(1f)) {
            Text(
                text = path.ifBlank { "No file selected" }, color = colors.textPrimary, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { if (activeTab != null) pathOpen = true }.padding(horizontal = 6.dp, vertical = 10.dp),
            )
            DropdownMenu(expanded = pathOpen, onDismissRequest = { pathOpen = false }) {
                val currentUri = navCurrentUri ?: activeParentUri
                val parentUri = currentUri?.let { uri -> fileTree.findNode(uri)?.parentDocumentUri }
                DropdownMenuItem(
                    text = { Text("..", color = colors.primary) },
                    enabled = parentUri != null,
                    onClick = {
                        parentUri?.let { navCurrentUri = it }
                    },
                )
                if (navLoading) {
                    DropdownMenuItem(text = { Text("Loading…", color = colors.textDisabled) }, onClick = {}, enabled = false)
                } else {
                    navItems.forEach { sibling ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sibling.isDirectory) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (sibling.isDirectory) "${sibling.displayName}/" else sibling.displayName, color = if (sibling.documentUri == activeTab?.documentUri) colors.primary else colors.textPrimary)
                                }
                            },
                            onClick = {
                                if (sibling.isDirectory) navCurrentUri = sibling.documentUri
                                else { pathOpen = false; onOpenFile(sibling.documentUri) }
                            },
                        )
                    }
                }
            }
        }
        IconButton(onClick = onFind, enabled = activeTab != null) { Icon(Icons.Default.Search, "Find in document", tint = colors.textSecondary) }
        IconButton(onClick = onPreview, enabled = activeTab != null && state.localStorageConfirmed && state.localWorkspaceAvailable) { Icon(Icons.Default.PlayArrow, "Preview project", tint = colors.textSecondary) }
        if (!state.editorSettings.autoSave && activeTab != null && !activeTab.isImagePreview) {
            IconButton(onClick = onSave, enabled = activeTabCanSave) {
                Icon(Icons.Default.Save, "Save active document", tint = colors.textSecondary)
            }
        }
        Box {
            IconButton(onClick = { moreOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    when {
                        languageSupportNeedsAttention && activeDiagnostics.isNotEmpty() -> "More editor actions; language support needs attention and ${activeDiagnostics.size} problems"
                        languageSupportNeedsAttention -> "More editor actions; language support needs attention"
                        activeDiagnostics.isNotEmpty() -> "More editor actions; ${activeDiagnostics.size} problems"
                        else -> "More editor actions"
                    },
                    tint = colors.textSecondary,
                )
            }
            if (languageSupportNeedsAttention || activeDiagnostics.any { it.severity <= 2 }) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(8.dp)
                        .background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape),
                )
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                if (!state.editorSettings.autoSave) DropdownMenuItem(text = { Text("Save") }, enabled = activeTabCanSave, onClick = { moreOpen = false; onSave() })
                DropdownMenuItem(text = { Text("Save As") }, enabled = activeTabCanSave, onClick = { moreOpen = false; onSaveAs() })
                DropdownMenuItem(text = { Text("Refresh project files") }, onClick = { moreOpen = false; onRefreshProject() })
                DropdownMenuItem(text = { Text("Close other tabs") }, enabled = activeTab != null, onClick = { moreOpen = false; activeTab?.let { onCloseOtherTabs(it.id) } })
                DropdownMenuItem(text = { Text("Copy file path") }, enabled = activeTab != null && !activeTab.isBlank, onClick = { moreOpen = false; activeTab?.let { onCopyFilePath(it.documentUri) } })
                DropdownMenuItem(text = { Text("Locate current file") }, enabled = activeTab != null && !activeTab.isBlank, onClick = { moreOpen = false; onLocateFile() })
                DropdownMenuItem(
                    text = { Text("Problems (${activeDiagnostics.size})") },
                    leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    enabled = activeDiagnostics.isNotEmpty(),
                    onClick = { moreOpen = false; problemsOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(if (languageSupportNeedsAttention) "Language support · Attention" else "Language support") },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = { moreOpen = false; languageSupportOpen = true },
                )
                DropdownMenuItem(text = { Text("Editor settings") }, onClick = { moreOpen = false; onOpenSettings() })
            }
        }
    }
    if (languageSupportOpen) {
        AlertDialog(
            onDismissRequest = { languageSupportOpen = false },
            title = { Text("Language support") },
            text = {
                Text(languageSupportMessage)
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (packageSetupFailed || state.languageServerFailure != null) {
                        TextButton(onClick = { languageSupportOpen = false; onRetryLanguageServerPackages() }) { Text("Retry") }
                    }
                    if (!state.editorSettings.languageServerEnabled || packageSetupFailed || state.languageServerFailure != null) {
                        TextButton(onClick = { languageSupportOpen = false; onOpenSettings() }) { Text("Editor services") }
                    }
                    TextButton(onClick = { languageSupportOpen = false }) { Text("Done") }
                }
            },
        )
    }
    if (problemsOpen) {
        AlertDialog(
            onDismissRequest = { problemsOpen = false },
            title = { Text("Problems (${activeDiagnostics.size})") },
            text = {
                if (activeDiagnostics.isEmpty()) {
                    Text("No current diagnostics for this file.")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        itemsIndexed(activeDiagnostics, key = { index, _ -> "problem-$index" }) { _, diagnostic ->
                            TextButton(
                                onClick = { problemsOpen = false; onRevealDiagnostic(diagnostic) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    val severity = when (diagnostic.severity) {
                                        1 -> "Error"
                                        2 -> "Warning"
                                        3 -> "Information"
                                        else -> "Hint"
                                    }
                                    Text("$severity · ${diagnostic.startLine}:${diagnostic.startColumn}", style = MaterialTheme.typography.labelMedium)
                                    Text(diagnostic.message, style = MaterialTheme.typography.bodySmall)
                                    diagnostic.source?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { problemsOpen = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun EditorStatusBar(state: IdeUiState, activeTab: EditorTab?) {
    val colors = LocalIdeColors.current
    Row(Modifier.fillMaxWidth().height(30.dp).background(colors.surface).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Ln ${state.cursorLine}, Col ${state.cursorColumn}", color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text("Sp: ${state.editorSettings.tabSize}", color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(14.dp))
        Text(activeTab?.language ?: "Plain Text", color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(14.dp))
        Text("UTF-8", color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EditorEmptyState(projectName: String?, onOpenProjects: () -> Unit, onOpenNavigation: () -> Unit, modifier: Modifier) {
    val colors = LocalIdeColors.current
    Column(modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(colors.surface), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, contentDescription = "Open sidebar", tint = colors.textPrimary) }
            Text("Editor", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.FolderOpen, null, tint = colors.secondary, modifier = Modifier.size(44.dp))
                Text(if (projectName == null) "Open a project to start editing" else "Restoring $projectName…", style = MaterialTheme.typography.titleMedium)
                if (projectName == null) {
                    Text("The editor opens files from the project's selected location.", color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onOpenProjects) { Text("Open Projects") }
                }
            }
        }
    }
}

@Composable
private fun EditorDialogHost(
    state: IdeUiState,
    ideViewModel: IdeViewModel,
    onChooseExportDestination: (FileOpDialog.Export) -> Unit,
    onOpenBrowserContent: (String) -> Unit,
) {
    when (val dialog = state.fileOpDialog) {
        is FileOpDialog.BinaryOpenError -> {
            val extension = dialog.fileName.substringAfterLast('.', "").lowercase()
            val browserRenderable = extension in setOf(
                "html", "htm", "md", "markdown", "svg", "pdf", "txt", "csv", "xml", "json",
                "png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico", "tif", "tiff",
                "mp4", "webm", "ogv", "mkv", "mov", "m4v", "avi", "mp3", "wav", "ogg", "flac", "m4a", "aac",
            )
            AlertDialog(
                onDismissRequest = ideViewModel::dismissFileOpDialog,
                title = { Text("File cannot be edited") },
                text = { Text("${dialog.fileName} is not a supported text document.") },
                confirmButton = {
                    if (state.localStorageConfirmed && state.localWorkspaceAvailable && browserRenderable) {
                        Button(onClick = {
                            ideViewModel.browserContentUri(dialog.documentUri)?.let(onOpenBrowserContent)
                            ideViewModel.dismissFileOpDialog()
                        }) { Text("Open in browser") }
                    } else TextButton(onClick = ideViewModel::dismissFileOpDialog) { Text("OK") }
                },
                dismissButton = { if (state.localStorageConfirmed && state.localWorkspaceAvailable && browserRenderable) TextButton(onClick = ideViewModel::dismissFileOpDialog) { Text("Cancel") } },
            )
        }
        is FileOpDialog.Delete -> {
            val selectedNodes = dialog.selectedNodes.ifEmpty { listOf(dialog.node) }
            val paths = selectedNodes.map { node ->
                state.fileTree.pathTo(node.documentUri) ?: "/${node.displayName}"
            }
            val completed = dialog.resultMessage != null && !dialog.isSubmitting
            AlertDialog(
                onDismissRequest = { if (!dialog.isSubmitting) ideViewModel.dismissFileOpDialog() },
                title = { Text(if (completed) "Delete complete" else "Delete ${dialog.node.displayName}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (completed) {
                            Text(dialog.resultMessage!!, color = LocalIdeColors.current.success)
                        } else {
                            Text(if (paths.size == 1) "Project path: ${paths.single()}" else "Project paths:\n${paths.joinToString("\n")}")
                            Text("This permanently removes the selected item and its contents. This action cannot be undone.")
                            dialog.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if (dialog.isSubmitting) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Deleting…") }
                        }
                    }
                },
                confirmButton = { Button(onClick = if (completed) ideViewModel::dismissFileOpDialog else ({ ideViewModel.deleteNode(dialog.node, dialog.selectedNodes) }), enabled = !dialog.isSubmitting, colors = ButtonDefaults.buttonColors(containerColor = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError)) { Text(if (completed) "Done" else if (dialog.isSubmitting) "Deleting…" else if (dialog.errorMessage != null) "Retry delete" else "Delete") } },
                dismissButton = { if (!completed) TextButton(onClick = ideViewModel::dismissFileOpDialog, enabled = !dialog.isSubmitting) { Text(if (dialog.isSubmitting) "Please wait" else "Cancel") } },
            )
        }
        is FileOpDialog.UnsavedClose -> AlertDialog(onDismissRequest = ideViewModel::dismissFileOpDialog, title = { Text("Unsaved changes") }, text = { Text("${dialog.displayName} has unsaved changes. Choose how to close it.") }, confirmButton = { TextButton(onClick = { ideViewModel.saveAndCloseTab(dialog.tabId) }) { Text("Save and close") } }, dismissButton = { Row { TextButton(onClick = { ideViewModel.confirmCloseTab(dialog.tabId) }) { Text("Discard", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = ideViewModel::dismissFileOpDialog) { Text("Cancel") } } })
        is FileOpDialog.ExternalFileConflict -> AlertDialog(
            onDismissRequest = ideViewModel::dismissFileOpDialog,
            title = { Text("File changed outside the editor") },
            text = { Text("${dialog.displayName} was changed on disk while this tab has unsaved edits. Choose which version to keep.") },
            confirmButton = { TextButton(onClick = { ideViewModel.useDiskContentAfterExternalChange(dialog.tabId) }) { Text("Use disk version") } },
            dismissButton = { Row {
                TextButton(onClick = { ideViewModel.keepEditorContentAfterExternalChange(dialog.tabId) }) { Text("Keep editor version") }
                TextButton(onClick = ideViewModel::dismissFileOpDialog) { Text("Cancel") }
            } },
        )
        is FileOpDialog.Rename -> {
            val parentUri = dialog.node.parentDocumentUri ?: state.projectRootUri.orEmpty()
            val itemType = if (dialog.node.isDirectory) "folder" else "file"
            EditorPathDialog(
                stateKey = "rename:${dialog.node.documentUri}",
                title = "Rename $itemType",
                subtitle = "Rename $itemType in ${ideViewModel.projectPathForDocument(parentUri)}",
                initial = dialog.node.displayName,
                label = "Enter new $itemType path/name",
                error = dialog.errorMessage,
                result = dialog.resultMessage,
                submitting = dialog.isSubmitting,
                successTitle = "Rename successful",
                confirmLabel = "Rename",
                onDismiss = ideViewModel::dismissFileOpDialog,
                onConfirm = { ideViewModel.renameNode(dialog.node, it) },
            )
        }
        is FileOpDialog.Duplicate -> EditorTextDialog("Duplicate ${dialog.node.displayName}", "New name or path", "Copy of ${dialog.node.displayName}", dialog.errorMessage, dialog.resultMessage, ideViewModel::dismissFileOpDialog, dialog.isSubmitting) { ideViewModel.duplicateFile(dialog.node, it) }
        is FileOpDialog.Export -> {
            val completed = dialog.resultMessage != null && !dialog.failed && !dialog.isSubmitting
            AlertDialog(
                onDismissRequest = { if (!dialog.isSubmitting) ideViewModel.dismissFileOpDialog() },
                title = { Text(if (completed) "Export complete" else "Export ${dialog.node.displayName}") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (completed) Text(dialog.resultMessage!!, color = LocalIdeColors.current.success)
                    else {
                        Text("The selected ${if (dialog.node.isDirectory) "folder" else "file"} will be exported as a ZIP without changing the source.")
                        dialog.resultMessage?.let { Text(it, color = if (dialog.failed) MaterialTheme.colorScheme.error else LocalIdeColors.current.success) }
                        if (dialog.isSubmitting) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Exporting…") }
                    }
                } },
                confirmButton = { Button(onClick = if (completed) ideViewModel::dismissFileOpDialog else ({ onChooseExportDestination(dialog) }), enabled = !dialog.isSubmitting) { Text(if (completed) "Done" else if (dialog.failed) "Retry export" else "Choose export location") } },
                dismissButton = { if (!completed) TextButton(onClick = ideViewModel::dismissFileOpDialog, enabled = !dialog.isSubmitting) { Text(if (dialog.isSubmitting) "Please wait" else "Cancel") } },
            )
        }
        is FileOpDialog.CreateFile -> {
            val parentUri = dialog.parentNode.documentUri
            val createdUri = dialog.createdDocumentUri
            val sourceTabId = dialog.sourceTabId
            val sourceDocumentUri = sourceTabId?.let { id -> state.openTabs.firstOrNull { it.id == id }?.documentUri }
            val savingTab = sourceTabId != null
            EditorPathDialog(
                stateKey = if (savingTab) "save-file:$sourceTabId:$sourceDocumentUri" else "create-file:$parentUri",
                title = if (savingTab) "Save file to project" else "Create new file",
                subtitle = if (savingTab) "Save in: ${ideViewModel.projectPathPreview(parentUri, "")}" else "Add new file in: ${ideViewModel.projectPathPreview(parentUri, "")}",
                initial = dialog.suggestedName,
                label = if (savingTab) "File name or project-relative path" else "Enter file name or path",
                pathPreview = { value ->
                    if (savingTab) "Save as: ${ideViewModel.projectPathPreview(parentUri, value)}"
                    else "Add new file in: ${ideViewModel.projectPathPreview(parentUri, value)}"
                },
                error = dialog.errorMessage,
                result = dialog.resultMessage,
                submitting = dialog.isSubmitting,
                successTitle = if (savingTab) "File saved" else "File created",
                confirmLabel = if (savingTab) "Save" else "Create file",
                successOpen = if (savingTab) null else createdUri?.let { uri -> ({ ideViewModel.openCreatedFile(uri) }) },
                onDismiss = ideViewModel::dismissFileOpDialog,
                onConfirm = { value ->
                    if (sourceTabId == null) ideViewModel.createFileInDirectory(dialog.parentNode, value)
                    else ideViewModel.saveAsAtPath(value, sourceTabId)
                },
            )
        }
        is FileOpDialog.CreateFolder -> {
            val parentUri = dialog.parentNode.documentUri
            EditorPathDialog(
                stateKey = "create-folder:$parentUri",
                title = "Create new folder",
                subtitle = "Add new folder in: ${ideViewModel.projectPathPreview(parentUri, "")}",
                initial = "",
                label = "Enter folder name or path",
                pathPreview = { value -> "Add new folder in: ${ideViewModel.projectPathPreview(parentUri, value)}" },
                error = dialog.errorMessage,
                result = dialog.resultMessage,
                submitting = dialog.isSubmitting,
                successTitle = "Folder created",
                confirmLabel = "Create folder",
                onDismiss = ideViewModel::dismissFileOpDialog,
                onConfirm = { ideViewModel.createFolderInDirectory(dialog.parentNode, it) },
            )
        }
        is FileOpDialog.ReplaceAll -> AlertDialog(
            onDismissRequest = { if (!dialog.isSubmitting) ideViewModel.dismissFileOpDialog() },
            title = { Text("Replace project content?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialog.resultMessage?.let { Text(it, color = if (it.contains("could not", true) || it.contains("failed", true)) MaterialTheme.colorScheme.error else LocalIdeColors.current.success) }
                        ?: run {
                            Text("Replace all occurrences of \"${dialog.find}\" with \"${dialog.replacement}\"?")
                            Text("${dialog.matches} match(es) across ${dialog.files} file(s) will be changed.")
                        }
                    if (dialog.isSubmitting) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text("Replacing…")
                    }
                }
            },
            confirmButton = {
                Button(onClick = if (dialog.resultMessage != null) ideViewModel::dismissFileOpDialog else ideViewModel::confirmReplaceProjectContents, enabled = !dialog.isSubmitting) {
                    Text(if (dialog.resultMessage != null) "Done" else if (dialog.isSubmitting) "Replacing…" else "Replace all")
                }
            },
            dismissButton = {
                TextButton(onClick = ideViewModel::dismissFileOpDialog, enabled = !dialog.isSubmitting) {
                    Text(if (dialog.isSubmitting) "Please wait" else "Cancel")
                }
            },
        )
        is FileOpDialog.ReplaceFile -> AlertDialog(
            onDismissRequest = { if (!dialog.isSubmitting) ideViewModel.dismissFileOpDialog() },
            title = { Text("Replace matches in ${dialog.fileName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialog.resultMessage?.let { Text(it, color = if (it.contains("could not", true) || it.contains("failed", true)) MaterialTheme.colorScheme.error else LocalIdeColors.current.success) }
                        ?: Text("Replace ${dialog.matches} occurrence(s) of \"${dialog.find}\" with \"${dialog.replacement}\" in this file?")
                    if (dialog.isSubmitting) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text("Replacing…")
                    }
                }
            },
            confirmButton = {
                Button(onClick = if (dialog.resultMessage != null) ideViewModel::dismissFileOpDialog else ideViewModel::confirmReplaceFileContents, enabled = !dialog.isSubmitting) {
                    Text(if (dialog.resultMessage != null) "Done" else if (dialog.isSubmitting) "Replacing…" else "Replace")
                }
            },
            dismissButton = {
                TextButton(onClick = ideViewModel::dismissFileOpDialog, enabled = !dialog.isSubmitting) {
                    Text(if (dialog.isSubmitting) "Please wait" else "Cancel")
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun EditorTextDialog(title: String, label: String, initial: String, error: String?, result: String?, onDismiss: () -> Unit, submitting: Boolean = false, onConfirm: (String) -> Unit) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    val completed = result != null && !submitting
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (completed) "Operation complete" else title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { if (completed) Text(result!!, color = LocalIdeColors.current.success) else { OutlinedTextField(value, { value = it }, label = { Text(label) }, supportingText = { Text("Relative to the selected project folder, or an absolute path inside it.") }, enabled = !submitting, singleLine = true); if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); if (submitting) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Working…") } } } },
        confirmButton = { Button(onClick = if (completed) onDismiss else ({ if (value.isNotBlank()) onConfirm(value.trim()) }), enabled = !submitting && (completed || value.isNotBlank())) { Text(if (completed) "Done" else if (submitting) "Working…" else "Confirm") } },
        dismissButton = { if (!completed) TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@Composable
private fun EditorPathDialog(
    stateKey: String,
    title: String,
    subtitle: String,
    initial: String,
    label: String,
    error: String?,
    result: String?,
    submitting: Boolean,
    successTitle: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    pathPreview: ((String) -> String)? = null,
    successOpen: (() -> Unit)? = null,
) {
    var value by rememberSaveable(stateKey) { mutableStateOf(initial) }
    val completed = result != null && !submitting
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (completed) successTitle else title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (completed) {
                    Text(result!!, color = LocalIdeColors.current.success)
                } else {
                    Text(pathPreview?.invoke(value) ?: subtitle, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(label) },
                        singleLine = true,
                        enabled = !submitting,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            if (completed && successOpen != null) {
                Button(onClick = successOpen) { Text("Open file") }
            } else if (completed) {
                TextButton(onClick = onDismiss) { Text("OK") }
            } else {
                Button(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }, enabled = !submitting && value.isNotBlank()) {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            if (!completed || successOpen != null) {
                TextButton(onClick = onDismiss, enabled = !submitting) { Text(if (completed) "OK" else "Cancel") }
            }
        },
    )
}
