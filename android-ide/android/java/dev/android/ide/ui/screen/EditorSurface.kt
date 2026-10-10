package dev.android.ide.ui.screen

import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
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
import dev.android.ide.viewmodel.model.FileNode
import dev.android.ide.viewmodel.model.FileSearchResult
import dev.android.ide.viewmodel.model.FileOpDialog
import dev.android.ide.viewmodel.model.IdeUiState
import dev.android.ide.viewmodel.model.findNode
import dev.android.ide.viewmodel.model.pathTo
import dev.android.ide.editor.EditorOutbound
import kotlinx.coroutines.launch

private const val DIRECTORY_MIME = "vnd.android.document/directory"

@Composable
fun EditorSurface(
    shellState: AppShellState,
    shellViewModel: AppShellViewModel,
    ideViewModel: IdeViewModel,
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
        EditorEmptyState(
            projectName = project?.name,
            onOpenProjects = { shellViewModel.navigate(Surface.PROJECTS) },
            onOpenNavigation = onOpenGlobalNavigation,
            modifier = modifier,
        )
        EditorDialogHost(state, ideViewModel, onChooseExportDestination = { dialog -> exportZip.launch("${dialog.node.displayName}.zip") }, onOpenBrowserContent = onOpenBrowserContent)
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
        modifier = modifier.fillMaxSize().background(LocalIdeColors.current.background),
    )
    if (state.fileMutationLoading) {
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
    EditorDialogHost(state, ideViewModel, onChooseExportDestination = { dialog -> exportZip.launch("${dialog.node.displayName}.zip") }, onOpenBrowserContent = onOpenBrowserContent)
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
        state.statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
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
    modifier: Modifier,
) {
    val colors = LocalIdeColors.current
    val activeTab = state.openTabs.firstOrNull { it.isActive }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val previewScope = rememberCoroutineScope()
    var previewNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeTab?.id) { previewNotice = null }
    Column(modifier.background(colors.background)) {
        EditorTopBar(
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
        )
        previewNotice?.takeIf(String::isNotBlank)?.let { message ->
            Text(
                text = message,
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (!state.localWorkspaceAvailable && state.projectRootUri != null) {
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
        if (state.openTabs.isNotEmpty()) {
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
        if (state.editorSettings.showStatusBar && !keyboardVisible) EditorStatusBar(state, activeTab)
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
) {
    val colors = LocalIdeColors.current
    var moreOpen by remember { mutableStateOf(false) }
    var pathOpen by remember { mutableStateOf(false) }
    val path = activeTab?.let { fileTree.pathTo(it.documentUri) } ?: state.projectName
    val node = activeTab?.let { fileTree.findNode(it.documentUri) }
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
            IconButton(onClick = onSave) {
                Icon(Icons.Default.Save, "Save active document", tint = colors.textSecondary)
            }
        }
        Box {
            IconButton(onClick = { moreOpen = true }) { Icon(Icons.Default.MoreVert, "More editor actions", tint = colors.textSecondary) }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                if (!state.editorSettings.autoSave) DropdownMenuItem(text = { Text("Save") }, enabled = activeTab != null && !activeTab.isImagePreview, onClick = { moreOpen = false; onSave() })
                DropdownMenuItem(text = { Text("Save As") }, enabled = activeTab != null && activeTab.isImagePreview.not(), onClick = { moreOpen = false; onSaveAs() })
                DropdownMenuItem(text = { Text("Refresh project files") }, onClick = { moreOpen = false; onRefreshProject() })
                DropdownMenuItem(text = { Text("Close other tabs") }, enabled = activeTab != null, onClick = { moreOpen = false; activeTab?.let { onCloseOtherTabs(it.id) } })
                DropdownMenuItem(text = { Text("Copy file path") }, enabled = activeTab != null && !activeTab.isBlank, onClick = { moreOpen = false; activeTab?.let { onCopyFilePath(it.documentUri) } })
                DropdownMenuItem(text = { Text("Locate current file") }, enabled = activeTab != null && !activeTab.isBlank, onClick = { moreOpen = false; onLocateFile() })
                DropdownMenuItem(text = { Text("Editor settings") }, onClick = { moreOpen = false; onOpenSettings() })
            }
        }
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
            EditorPathDialog(
                stateKey = "create-file:$parentUri",
                title = "Create new file",
                subtitle = "Add new file in: ${ideViewModel.projectPathPreview(parentUri, "")}",
                initial = "",
                label = "Enter file name or path",
                pathPreview = { value -> "Add new file in: ${ideViewModel.projectPathPreview(parentUri, value)}" },
                error = dialog.errorMessage,
                result = dialog.resultMessage,
                submitting = dialog.isSubmitting,
                successTitle = "File created",
                confirmLabel = "Create file",
                successOpen = createdUri?.let { uri -> ({ ideViewModel.openCreatedFile(uri) }) },
                onDismiss = ideViewModel::dismissFileOpDialog,
                onConfirm = { ideViewModel.createFileInDirectory(dialog.parentNode, it) },
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
        is FileOpDialog.SaveAs -> EditorTextDialog("Save As", "Path from project root", dialog.suggestedName, dialog.errorMessage, dialog.resultMessage, ideViewModel::dismissFileOpDialog, dialog.isSubmitting) { ideViewModel.saveAsAtPath(it) }
        is FileOpDialog.SaveBlank -> SaveBlankFileDialog(
            dialog = dialog,
            projectName = state.projectName,
            onDismiss = ideViewModel::dismissFileOpDialog,
            onSave = { ideViewModel.saveBlankTab(dialog.tabId, it) },
        )
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
private fun SaveBlankFileDialog(
    dialog: FileOpDialog.SaveBlank,
    projectName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(dialog.tabId) { mutableStateOf(dialog.suggestedName) }
    val completed = dialog.resultMessage != null && !dialog.isSubmitting
    AlertDialog(
        onDismissRequest = { if (!dialog.isSubmitting) onDismiss() },
        title = { Text(if (completed) "File saved" else "Save new file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (completed) {
                    Text(dialog.resultMessage!!, color = LocalIdeColors.current.success)
                } else {
                    Text("Create this file in the project root: $projectName/", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("File name") },
                        singleLine = true,
                        enabled = !dialog.isSubmitting,
                    )
                    dialog.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (dialog.isSubmitting) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text("Saving…")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = if (completed) onDismiss else ({ if (name.isNotBlank()) onSave(name.trim()) }),
                enabled = !dialog.isSubmitting && (completed || name.isNotBlank()),
            ) { Text(if (completed) "Done" else if (dialog.isSubmitting) "Saving…" else "Save") }
        },
        dismissButton = { if (!completed) TextButton(onClick = onDismiss, enabled = !dialog.isSubmitting) { Text("Cancel") } },
    )
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
