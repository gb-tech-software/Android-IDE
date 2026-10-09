// android-ide/android/java/dev/android/ide/ui/components/FileTreePanel.kt
//
// Sidebar file tree — shows the SAF-backed project directory tree.
//
// Features:
//   • Root project node with its own action menu
//   • File menu: Rename, Copy path, Copy, Cut, Delete, Select
//   • Folder menu: New file, New folder, Import, Rename, Copy path, Export, Copy, Cut, Paste, Delete
//   • Active file highlighting
//   • Multi-selection mode with exit button
//   • .git folder filtering (controlled by hideGitFolder)
//   • Project-content search panel (controlled by isSearchVisible)
//
// Clipboard:
//   clipboardItems: List<FileNode> replaces the old clipboard: FileNode? to support
//   multi-item cut/copy from multi-select mode. The paste menu item is shown
//   whenever the clipboard is non-empty.

package dev.android.ide.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.android.ide.editor.EditorLanguageRegistry
import dev.android.ide.editor.FileIconKind
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.viewmodel.model.FileNode
import dev.android.ide.viewmodel.model.FileSearchResult
import dev.android.ide.viewmodel.model.ancestorsOf
import dev.android.ide.viewmodel.model.pathTo
import compose.icons.SimpleIcons
import compose.icons.simpleicons.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTreePanel(
    // ── Data ──────────────────────────────────────────────────────────────
    nodes: List<FileNode>,
    /** Files/folders pending paste. Empty = clipboard is clear. */
    clipboardItems: List<FileNode>,
    clipboardIsCut: Boolean,
    projectName: String,
    activeTabDocumentUri: String?,
    locateTargetUri: String?,
    locateRequestToken: Long,
    onLocateConsumed: () -> Unit,
    hideGitFolder: Boolean,
    fullWorkspaceAvailable: Boolean = false,
    localStorageConfirmed: Boolean = false,
    isMultiSelectMode: Boolean,
    selectedUris: Set<String>,
    isSearchVisible: Boolean,
    isContentSearchVisible: Boolean,
    fileSearchQuery: String,
    fileSearchResults: List<FileSearchResult>,
    fileSearchIncludeFolders: Boolean = false,
    fileSearchRunning: Boolean = false,
    contentSearchQuery: String,
    contentSearchResults: List<FileSearchResult>,
    contentSearchMatchCase: Boolean,
    contentSearchWholeWord: Boolean,
    contentSearchRegex: Boolean,
    contentSearchShowContext: Boolean,
    // ── File-level callbacks ───────────────────────────────────────────────
    onFileClick: (String) -> Unit,
    onFileDoubleClick: (String) -> Unit,
    onDirToggle: (String) -> Unit,
    onShowRenameDialog: (FileNode) -> Unit,
    onShowDeleteDialog: (FileNode) -> Unit,
    onShowCreateFileDialog: (FileNode) -> Unit,
    onShowCreateFolderDialog: (FileNode) -> Unit,
    onShowDuplicateDialog: (FileNode) -> Unit,
    onCopyNode: (FileNode) -> Unit,
    onCutNode: (FileNode) -> Unit,
    onPasteInto: (FileNode) -> Unit,
    onImportFilesAt: (FileNode) -> Unit,
    onExportDirectory: (FileNode) -> Unit,
    // ── Root-level callbacks ───────────────────────────────────────────────
    onNewFileAtRoot: () -> Unit,
    onNewFolderAtRoot: () -> Unit,
    onImportFilesAtRoot: () -> Unit,
    onExportProject: () -> Unit,
    onRefresh: () -> Unit,
    onShowProjectDetails: () -> Unit,
    onDeleteProject: () -> Unit,
    onRemoveProject: () -> Unit,
    onPasteAtRoot: () -> Unit,
    // ── Search / selection / path callbacks ───────────────────────────────
    onCopyPath: (String) -> Unit,
    onToggleNodeSelection: (String) -> Unit,
    onExitSelectionMode: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onContentSearchQueryChange: (String) -> Unit,
    onContentSearchMatchCaseChange: (Boolean) -> Unit,
    onContentSearchWholeWordChange: (Boolean) -> Unit,
    onContentSearchRegexChange: (Boolean) -> Unit,
    onContentSearchShowContextChange: (Boolean) -> Unit,
    onReplaceProjectContents: (String, String, List<String>) -> Unit,
    onReplaceFileContents: (String, String, String, String) -> Unit,
    onClearContentSearchResults: () -> Unit,
    onFileSearchIncludeFoldersChange: (Boolean) -> Unit = {},
    onSearchFileSelect: (FileSearchResult) -> Unit,
    onExecuteContentSearch: () -> Unit = {},
    contentSearchCompletedQuery: String? = null,
    contentSearchRunning: Boolean = false,
    contentSearchWarning: String? = null,
    onOpenTerminalAtRoot: () -> Unit = {},
    onOpenTerminalAt: (FileNode) -> Unit = {},
    onOpenBrowserPreview: (FileNode) -> Unit = {},
    defaultPreviewPath: String? = null,
    onSetDefaultPreview: (FileNode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalIdeColors.current
    val treeListState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeTrailingPadding = with(density) { (imeBottomPx * 1.2f).toDp() }
    val sidebarBottomPadding = maxOf(imeTrailingPadding, (LocalConfiguration.current.screenHeightDp.dp * 0.28f))

    when {
        isContentSearchVisible -> ProjectContentSearchPanel(
            query = contentSearchQuery,
            results = contentSearchResults,
            matchCase = contentSearchMatchCase,
            wholeWord = contentSearchWholeWord,
            regex = contentSearchRegex,
            showContext = contentSearchShowContext,
            completedQuery = contentSearchCompletedQuery,
            running = contentSearchRunning,
            warning = contentSearchWarning,
            onQueryChange = onContentSearchQueryChange,
            onMatchCaseChange = onContentSearchMatchCaseChange,
            onWholeWordChange = onContentSearchWholeWordChange,
            onRegexChange = onContentSearchRegexChange,
            onShowContextChange = onContentSearchShowContextChange,
            onReplaceProjectContents = onReplaceProjectContents,
            onReplaceFileContents = onReplaceFileContents,
            onClearResults = onClearContentSearchResults,
            onSearchFileSelect = onSearchFileSelect,
            onExecuteSearch = onExecuteContentSearch,
            modifier = modifier,
        )

        // ── Filename search panel ──────────────────────────────────────────
        isSearchVisible -> FilenameSearchPanel(
            query = fileSearchQuery,
            results = fileSearchResults,
            includeFolders = fileSearchIncludeFolders,
            running = fileSearchRunning,
            onIncludeFoldersChange = onFileSearchIncludeFoldersChange,
            onQueryChange = onSearchQueryChange,
            onSelect = onSearchFileSelect,
            modifier = modifier,
        )

        // ── Empty state ────────────────────────────────────────────────────
        nodes.isEmpty() -> {
            Box(
                modifier         = modifier.padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text  = "No project open.\nOpen or create a project to see files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDisabled,
                )
            }
        }

        // ── File tree ──────────────────────────────────────────────────────
        else -> {
            val filteredNodes = if (hideGitFolder) nodes.filterNot { it.displayName == ".git" } else nodes
            val flatNodes = flattenTree(filteredNodes)
            val activeAncestorUris = activeTabDocumentUri
                ?.let { nodes.ancestorsOf(it).orEmpty().map(FileNode::documentUri).toSet() }
                .orEmpty()
            LaunchedEffect(locateRequestToken, locateTargetUri, flatNodes) {
                val targetIndex = locateTargetUri?.let { uri ->
                    flatNodes.indexOfFirst { it.first.documentUri == uri }
                } ?: -1
                val headerItems = (if (isMultiSelectMode) 1 else 0) +
                    (if (projectName.isNotEmpty()) 1 else 0)
                if (targetIndex >= 0) {
                    treeListState.animateScrollToItem(targetIndex + headerItems)
                    onLocateConsumed()
                }
            }
            LazyColumn(
                state = treeListState,
                modifier = modifier,
                contentPadding = PaddingValues(bottom = sidebarBottomPadding),
            ) {
                // Exit selection mode banner
                if (isMultiSelectMode) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.activeHighlight)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text     = "${selectedUris.size} selected",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = colors.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onExitSelectionMode, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector        = Icons.Default.Close,
                                    contentDescription = "Exit selection mode",
                                    tint               = colors.primary,
                                    modifier           = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                // Root project node
                if (projectName.isNotEmpty()) {
                    item {
                        RootProjectNode(
                            projectName    = projectName,
                            fullWorkspaceAvailable = fullWorkspaceAvailable,
                            localStorageConfirmed = localStorageConfirmed,
                            isActivePath   = activeTabDocumentUri != null,
                            clipboardItems = clipboardItems,
                            onNewFile      = onNewFileAtRoot,
                            onNewFolder    = onNewFolderAtRoot,
                            onImportFiles  = onImportFilesAtRoot,
                            onExport       = onExportProject,
                            onRefresh      = onRefresh,
                            onShowDetails  = onShowProjectDetails,
                            onDelete       = onDeleteProject,
                            onRemove       = onRemoveProject,
                            onPasteAtRoot  = onPasteAtRoot,
                            onOpenTerminal = onOpenTerminalAtRoot,
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
                    }
                }

                // File tree items
                    items(
                    items = flatNodes,
                    key   = { (node, _) -> node.documentUri },
                ) { (node, depth) ->
                    FileTreeRow(
                        node                     = node,
                        depth                    = depth,
                        fullWorkspaceAvailable   = fullWorkspaceAvailable,
                        localStorageConfirmed    = localStorageConfirmed,
                        clipboardItems           = clipboardItems,
                        clipboardIsCut            = clipboardIsCut,
                        isActive                 = node.documentUri == activeTabDocumentUri,
                        isActiveAncestor         = node.documentUri in activeAncestorUris,
                        isSelected               = node.documentUri in selectedUris,
                        isMultiSelectMode        = isMultiSelectMode,
                        onFileClick              = onFileClick,
                        onFileDoubleClick        = onFileDoubleClick,
                        onDirToggle              = onDirToggle,
                        onShowRenameDialog       = onShowRenameDialog,
                        onShowDeleteDialog       = onShowDeleteDialog,
                        onShowCreateFileDialog   = onShowCreateFileDialog,
                        onShowCreateFolderDialog = onShowCreateFolderDialog,
                        onCopyNode               = onCopyNode,
                        onCutNode                = onCutNode,
                        onPasteInto              = onPasteInto,
                        onImportFilesAt          = onImportFilesAt,
                        onExportDirectory        = onExportDirectory,
                        onCopyPath               = onCopyPath,
                        onSelect                 = onToggleNodeSelection,
                        onShowDuplicateDialog    = onShowDuplicateDialog,
                        onOpenTerminalAt         = onOpenTerminalAt,
                        onOpenBrowserPreview     = onOpenBrowserPreview,
                        isDefaultPreview         = defaultPreviewPath != null && nodes.pathTo(node.documentUri)?.removePrefix("/") == defaultPreviewPath,
                        onSetDefaultPreview      = onSetDefaultPreview,
                    )
                }
            }
        }
    }
}

private val browserPreviewExtensions = setOf(
    "html", "htm", "md", "markdown", "svg", "pdf", "txt", "csv", "xml", "json",
    "png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico", "tif", "tiff",
    "mp4", "webm", "ogv", "mkv", "mov", "m4v", "avi", "mp3", "wav", "ogg", "flac", "m4a", "aac",
)

private fun isBrowserPreviewCandidate(node: FileNode): Boolean =
    !node.isDirectory && node.displayName.substringAfterLast('.', "").lowercase() in browserPreviewExtensions

private fun isHtmlPreviewCandidate(node: FileNode): Boolean =
    !node.isDirectory && node.displayName.substringAfterLast('.', "").lowercase() in setOf("html", "htm")

// ── Search result row ──────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    result: FileSearchResult,
    onSelect: (FileSearchResult) -> Unit,
    compact: Boolean = false,
) {
    val colors = LocalIdeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onSelect(result) })
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (compact) {
            Text(
                text = result.matchLine?.toString() ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.width(32.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            FileTypeBadge(result.displayName, muted = false, accent = false)
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (!compact) {
            Text(
                text     = result.displayName,
                style    = MaterialTheme.typography.bodyMedium,
                color    = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text     = result.relativePath,
                style    = MaterialTheme.typography.labelSmall,
                color    = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            }
            result.matchLine?.let { line ->
                if (!compact) Text("Line $line", style = MaterialTheme.typography.labelSmall, color = colors.primary)
            }
            if (result.matchPreview.isNotBlank()) {
                val highlightedPreview = remember(
                    result.matchPreview,
                    result.previewMatchStart,
                    result.previewMatchLength,
                ) {
                    buildAnnotatedString {
                        val start = result.previewMatchStart
                        val end = (start + result.previewMatchLength).coerceAtMost(result.matchPreview.length)
                        if (start >= 0 && start < end) {
                            append(result.matchPreview.substring(0, start))
                            withStyle(SpanStyle(background = colors.primary.copy(alpha = 0.32f), color = colors.textPrimary)) {
                                append(result.matchPreview.substring(start, end))
                            }
                            append(result.matchPreview.substring(end))
                        } else {
                            append(result.matchPreview)
                        }
                    }
                }
                Text(
                    text = highlightedPreview,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Root project node ──────────────────────────────────────────────────────────

@Composable
private fun RootProjectNode(
    projectName: String,
    fullWorkspaceAvailable: Boolean,
    localStorageConfirmed: Boolean,
    isActivePath: Boolean,
    clipboardItems: List<FileNode>,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onImportFiles: () -> Unit,
    onExport: () -> Unit,
    onRefresh: () -> Unit,
    onShowDetails: () -> Unit,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
    onPasteAtRoot: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val colors     = LocalIdeColors.current
    var menuOpen   by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActivePath) colors.activeHighlight.copy(alpha = 0.22f) else Color.Transparent)
            .padding(start = 8.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Icon(
            imageVector        = Icons.Default.FolderOpen,
            contentDescription = null,
            tint               = if (isActivePath) colors.primary else colors.secondary,
            modifier           = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text     = projectName,
            style    = MaterialTheme.typography.labelMedium,
            color    = if (isActivePath) colors.primary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector        = Icons.Default.MoreVert,
                    contentDescription = "Project actions",
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(14.dp),
                )
            }
            EditorProjectActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onNewFile = onNewFile,
                onNewFolder = onNewFolder,
                onImportFiles = onImportFiles,
                onExportProject = onExport,
                onRefresh = onRefresh,
                onShowDetails = onShowDetails,
                onDeleteProject = onDelete,
                onRemoveProject = onRemove,
                onPasteAtRoot = onPasteAtRoot.takeIf { clipboardItems.isNotEmpty() },
                onOpenTerminal = onOpenTerminal,
                fullWorkspaceAvailable = fullWorkspaceAvailable,
                localStorageConfirmed = localStorageConfirmed,
            )
        }
    }
}

// ── Tree row ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileNode,
    depth: Int,
    fullWorkspaceAvailable: Boolean,
    localStorageConfirmed: Boolean,
    clipboardItems: List<FileNode>,
    clipboardIsCut: Boolean,
    isActive: Boolean,
    isActiveAncestor: Boolean,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onFileClick: (String) -> Unit,
    onFileDoubleClick: (String) -> Unit,
    onDirToggle: (String) -> Unit,
    onShowRenameDialog: (FileNode) -> Unit,
    onShowDeleteDialog: (FileNode) -> Unit,
    onShowCreateFileDialog: (FileNode) -> Unit,
    onShowCreateFolderDialog: (FileNode) -> Unit,
    onCopyNode: (FileNode) -> Unit,
    onCutNode: (FileNode) -> Unit,
    onPasteInto: (FileNode) -> Unit,
    onImportFilesAt: (FileNode) -> Unit,
    onExportDirectory: (FileNode) -> Unit,
    onCopyPath: (String) -> Unit,
    onSelect: (String) -> Unit,
    onShowDuplicateDialog: (FileNode) -> Unit,
    onOpenTerminalAt: (FileNode) -> Unit,
    onOpenBrowserPreview: (FileNode) -> Unit,
    isDefaultPreview: Boolean,
    onSetDefaultPreview: (FileNode) -> Unit,
) {
    val colors   = LocalIdeColors.current
    var menuOpen by remember { mutableStateOf(false) }

    // Node is "pending paste" if it appears in the clipboard.
    val isInClipboard = clipboardItems.any { it.documentUri == node.documentUri }

    val rowBackground = when {
        isSelected     -> colors.activeHighlight
        isActive       -> colors.activeHighlight.copy(alpha = 0.6f)
        isActiveAncestor -> colors.activeHighlight.copy(alpha = 0.22f)
        else           -> Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(
                onClick = {
                    if (isMultiSelectMode) onSelect(node.documentUri)
                    else if (node.isDirectory) onDirToggle(node.documentUri)
                    else onFileClick(node.documentUri)
                },
                onDoubleClick = {
                    if (!isMultiSelectMode && !node.isDirectory) onFileDoubleClick(node.documentUri)
                },
                onLongClick = { menuOpen = true },
            )
            .padding(end = 0.dp, top = 3.dp, bottom = 3.dp),
    ) {
        repeat(depth) { level ->
            Canvas(Modifier.width(16.dp).height(36.dp)) {
                drawLine(
                    color = if (isActive || isActiveAncestor) colors.primary.copy(alpha = 0.7f) else colors.separator,
                    start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
                    strokeWidth = if ((isActive || isActiveAncestor) && level == depth - 1) 2.dp.toPx() else 1.dp.toPx(),
                )
            }
        }

        // Multi-select checkbox
        if (isMultiSelectMode) {
            Icon(
                imageVector        = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (isSelected) "Deselect" else "Select",
                tint               = if (isSelected) colors.primary else colors.textSecondary,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }

        // Use one leading slot for both the folder chevron and file icon.
        // This keeps filenames aligned at the same depth without reserving
        // an additional, invisible folder-icon slot.
        if (node.isDirectory) {
            Icon(
                imageVector = if (node.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (node.isExpanded) "Collapse" else "Expand",
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        } else {
            FileTypeBadge(
                displayName = node.displayName,
                muted = isInClipboard && clipboardIsCut,
                accent = isInClipboard,
            )
        }

        Spacer(Modifier.width(4.dp))

        Text(
            text  = node.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isInClipboard && clipboardIsCut -> colors.textDisabled
                isActive                        -> colors.primary
                isActiveAncestor                -> colors.primary
                else                            -> colors.textPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // ••• context menu
        Box {
            IconButton(
                onClick  = { menuOpen = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.MoreVert,
                    contentDescription = "File options",
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(14.dp),
                )
            }

            DropdownMenu(
                expanded         = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                val hasClipboard = clipboardItems.isNotEmpty()

                if (node.isDirectory) {
                    // ── Folder menu ──────────────────────────────────────
                    if (localStorageConfirmed) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Article, null) },
                            text    = { Text("New file") },
                            onClick = { menuOpen = false; onShowCreateFileDialog(node) },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            text    = { Text("New folder") },
                            onClick = { menuOpen = false; onShowCreateFolderDialog(node) },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            text    = { Text("Import files") },
                            onClick = { menuOpen = false; onImportFilesAt(node) },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            text    = { Text("Duplicate") },
                            onClick = { menuOpen = false; onShowDuplicateDialog(node) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            text    = { Text("Rename") },
                            onClick = { menuOpen = false; onShowRenameDialog(node) },
                        )
                    }
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        text    = { Text("Copy path") },
                        onClick = { menuOpen = false; onCopyPath(node.documentUri) },
                    )
                    if (localStorageConfirmed) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Terminal, null) },
                            text = { Text("Open terminal") },
                            onClick = { menuOpen = false; onOpenTerminalAt(node) },
                        )
                    }
                    if (localStorageConfirmed) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            text    = { Text("Export\u2026") },
                            onClick = { menuOpen = false; onExportDirectory(node) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            text    = { Text("Copy") },
                            onClick = { menuOpen = false; onCopyNode(node) },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                            text    = { Text("Cut") },
                            onClick = { menuOpen = false; onCutNode(node) },
                        )
                    }
                    if (localStorageConfirmed && hasClipboard) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                            text = { Text("Paste") },
                            onClick = { menuOpen = false; onPasteInto(node) },
                        )
                    }
                    if (localStorageConfirmed) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = LocalIdeColors.current.error) },
                            text    = { Text("Delete", color = LocalIdeColors.current.error) },
                            onClick = { menuOpen = false; onShowDeleteDialog(node) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                            text    = { Text("Select") },
                            onClick = { menuOpen = false; onSelect(node.documentUri) },
                        )
                    }
                } else {
                    // ── File menu ────────────────────────────────────────
                    if (localStorageConfirmed) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            text    = { Text("Rename") },
                            onClick = { menuOpen = false; onShowRenameDialog(node) },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            text    = { Text("Duplicate") },
                            onClick = { menuOpen = false; onShowDuplicateDialog(node) },
                        )
                        if (fullWorkspaceAvailable && isBrowserPreviewCandidate(node)) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Visibility, null) },
                                text = { Text("Preview in browser") },
                                onClick = { menuOpen = false; onOpenBrowserPreview(node) },
                            )
                        }
                        if (fullWorkspaceAvailable && isHtmlPreviewCandidate(node)) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                text = { Text(if (isDefaultPreview) "Default HTML preview" else "Set as default HTML preview") },
                                enabled = !isDefaultPreview,
                                onClick = { menuOpen = false; onSetDefaultPreview(node) },
                            )
                        }
                    }
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        text    = { Text("Copy path") },
                        onClick = { menuOpen = false; onCopyPath(node.documentUri) },
                    )
                    if (localStorageConfirmed) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            text    = { Text("Copy") },
                            onClick = { menuOpen = false; onCopyNode(node) },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                            text    = { Text("Cut") },
                            onClick = { menuOpen = false; onCutNode(node) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = LocalIdeColors.current.error) },
                            text    = { Text("Delete", color = LocalIdeColors.current.error) },
                            onClick = { menuOpen = false; onShowDeleteDialog(node) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                            text    = { Text("Select") },
                            onClick = { menuOpen = false; onSelect(node.documentUri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilenameSearchPanel(
    query: String,
    results: List<FileSearchResult>,
    includeFolders: Boolean,
    running: Boolean,
    onIncludeFoldersChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (FileSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIdeColors.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomPadding = with(density) { imeBottomPx.toDp() }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Find filenames…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear filename search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = includeFolders,
                onClick = { onIncludeFoldersChange(!includeFolders) },
                label = { Text("Include folders") },
                leadingIcon = if (includeFolders) {
                    { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                } else null,
            )
            if (running) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Searching…", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
        if (!running && query.isNotEmpty() && results.isEmpty()) {
            Text(
                "No filenames matching “$query”",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textDisabled,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = maxOf(imeBottomPadding + 12.dp, LocalConfiguration.current.screenHeightDp.dp * 0.28f)),
            ) {
                itemsIndexed(results, key = { index, result -> "${result.documentUri}:$index" }) { _, result ->
                    SearchResultRow(result, onSelect)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ProjectContentSearchPanel(
    query: String,
    results: List<FileSearchResult>,
    matchCase: Boolean,
    wholeWord: Boolean,
    regex: Boolean,
    showContext: Boolean,
    completedQuery: String?,
    running: Boolean,
    warning: String?,
    onQueryChange: (String) -> Unit,
    onMatchCaseChange: (Boolean) -> Unit,
    onWholeWordChange: (Boolean) -> Unit,
    onRegexChange: (Boolean) -> Unit,
    onShowContextChange: (Boolean) -> Unit,
    onReplaceProjectContents: (String, String, List<String>) -> Unit,
    onReplaceFileContents: (String, String, String, String) -> Unit,
    onClearResults: () -> Unit,
    onSearchFileSelect: (FileSearchResult) -> Unit,
    onExecuteSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIdeColors.current
    var replaceText by rememberSaveable { mutableStateOf("") }
    var replaceOpen by rememberSaveable { mutableStateOf(false) }
    var excludedUris by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var expandedUris by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val replaceFocus = remember { FocusRequester() }
    val hasCurrentResults = query.isNotBlank() && completedQuery == query && !running
    val visibleResults = results.filterNot { it.documentUri in excludedUris }
    val groupedResults = visibleResults.groupBy { it.documentUri }
    val allExpanded = groupedResults.isNotEmpty() && groupedResults.keys.all { it in expandedUris }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomPadding = with(density) { imeBottomPx.toDp() }

    LaunchedEffect(completedQuery, query, running) {
        if (running || completedQuery == null) {
            expandedUris = emptySet()
            excludedUris = emptySet()
        } else if (hasCurrentResults) {
            expandedUris = groupedResults.keys
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SEARCH", style = MaterialTheme.typography.titleSmall, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onRegexChange(!regex) }, modifier = Modifier.size(38.dp)) {
                Text(".*", color = if (regex) MaterialTheme.colorScheme.primary else colors.textSecondary, style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = { onMatchCaseChange(!matchCase) }, modifier = Modifier.size(38.dp)) {
                Text("Aa", color = if (matchCase) MaterialTheme.colorScheme.primary else colors.textSecondary, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { onWholeWordChange(!wholeWord) }, modifier = Modifier.size(38.dp)) {
                Text("Ab|", color = if (wholeWord) MaterialTheme.colorScheme.primary else colors.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { onShowContextChange(!showContext) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.FilterList, contentDescription = "Toggle context", tint = if (showContext) MaterialTheme.colorScheme.primary else colors.textSecondary)
            }
            IconButton(onClick = { replaceOpen = !replaceOpen }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.SwapHoriz, contentDescription = if (replaceOpen) "Hide replace" else "Show replace", tint = if (replaceOpen) MaterialTheme.colorScheme.primary else colors.textSecondary)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange(""); onClearResults(); excludedUris = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            placeholder = { Text("Find in project") },
            keyboardOptions = KeyboardOptions(imeAction = if (replaceOpen) ImeAction.Next else ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { if (query.isNotBlank()) onExecuteSearch() },
                onNext = { replaceFocus.requestFocus() },
            ),
        )

        if (replaceOpen) {
            OutlinedTextField(
                value = replaceText,
                onValueChange = { replaceText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).focusRequester(replaceFocus),
                singleLine = true,
                placeholder = { Text("Replace with") },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (query.isNotBlank()) {
                            if (hasCurrentResults) onReplaceProjectContents(query, replaceText, visibleResults.map { it.documentUri }.distinct())
                            else onExecuteSearch()
                        }
                    },
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RESULTS", style = MaterialTheme.typography.titleSmall, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { expandedUris = if (allExpanded) emptySet() else groupedResults.keys },
                enabled = groupedResults.isNotEmpty(),
                modifier = Modifier.size(38.dp),
            ) {
                Icon(if (allExpanded) Icons.Default.IndeterminateCheckBox else Icons.Default.AddBox, contentDescription = if (allExpanded) "Collapse all results" else "Expand all results")
            }
            IconButton(onClick = onExecuteSearch, enabled = query.isNotBlank() && !running, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Search again")
            }
            IconButton(
                onClick = { onClearResults(); expandedUris = emptySet(); excludedUris = emptySet() },
                enabled = completedQuery != null || results.isNotEmpty() || running,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.Default.Block, contentDescription = "Clear results")
            }
        }

        if (running) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Searching project…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        if (query.isNotBlank()) {
            warning?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.contains("Invalid", ignoreCase = true) || it.contains("failed", ignoreCase = true)) MaterialTheme.colorScheme.error else colors.warning,
                )
            }
            if (visibleResults.isEmpty()) {
                if (!running && hasCurrentResults) Text(
                    if (results.isNotEmpty()) "All results are hidden." else "No matches found.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = maxOf(imeBottomPadding + 12.dp, LocalConfiguration.current.screenHeightDp.dp * 0.28f)),
                ) {
                    item(key = "content-search-summary") {
                        Text(
                            "${visibleResults.size} result(s) in ${groupedResults.size} file(s)",
                            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    groupedResults.forEach { (documentUri, matches) ->
                        val first = matches.first()
                        val expanded = documentUri in expandedUris
                        item(key = "content-search-file:$documentUri") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            expandedUris = if (expanded) expandedUris - documentUri else expandedUris + documentUri
                                        },
                                    )
                                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                    contentDescription = if (expanded) "Collapse ${first.displayName}" else "Expand ${first.displayName}",
                                    modifier = Modifier.size(36.dp).padding(8.dp),
                                )
                                FileTypeBadge(first.displayName, muted = false, accent = false)
                                Spacer(Modifier.width(8.dp))
                                Text(first.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                if (replaceOpen) {
                                    IconButton(
                                        onClick = { onReplaceFileContents(documentUri, first.displayName, query, replaceText) },
                                        enabled = hasCurrentResults,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.SwapHoriz, contentDescription = "Replace matches in ${first.displayName}", tint = colors.textSecondary)
                                    }
                                }
                                IconButton(
                                    onClick = { excludedUris = excludedUris + documentUri },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Hide ${first.displayName}", tint = colors.textSecondary)
                                }
                            }
                        }
                        if (expanded) {
                            itemsIndexed(matches, key = { index, result ->
                                "content-search-match:$documentUri:${result.matchLine}:${result.matchColumn}:$index"
                            }) { _, result ->
                                SearchResultRow(result, onSearchFileSelect, compact = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── File type icon ─────────────────────────────────────────────────────────────

/** Shared typed badge used by the file tree, filename search, and content search. */
@Composable
private fun FileTypeBadge(displayName: String, muted: Boolean, accent: Boolean) {
    val kind = EditorLanguageRegistry.iconKindForFileName(displayName)
    val fileName = displayName.substringAfterLast('/').lowercase()
    val extension = fileName.substringAfterLast('.', "")
    val icon = when {
        fileName == "androidmanifest.xml" -> SimpleIcons.Android
        fileName == "pubspec.yaml" || fileName == "analysis_options.yaml" -> SimpleIcons.Flutter
        fileName == "package.json" || fileName == "package-lock.json" || fileName == "npm-shrinkwrap.json" -> SimpleIcons.Npm
        fileName == "yarn.lock" -> SimpleIcons.Yarn
        fileName == "tsconfig.json" || fileName == "tsconfig.base.json" -> SimpleIcons.Typescript
        fileName == "jsconfig.json" -> SimpleIcons.Javascript
        fileName == "angular.json" -> SimpleIcons.Angular
        fileName == "firebase.json" -> SimpleIcons.Firebase
        fileName == "supabase.json" -> SimpleIcons.Supabase
        fileName == "dockerfile" || fileName == "containerfile" || fileName.startsWith("docker-compose") -> SimpleIcons.Docker
        fileName == "webpack.config.js" || fileName == "webpack.config.ts" || fileName == "webpack.config.cjs" -> SimpleIcons.Webpack
        fileName == "rollup.config.js" || fileName == "rollup.config.ts" -> SimpleIcons.RollupDotJs
        fileName == "babel.config.js" || fileName == "babel.config.cjs" || fileName == ".babelrc" -> SimpleIcons.Babel
        fileName == "tailwind.config.js" || fileName == "tailwind.config.ts" || fileName == "tailwind.config.cjs" -> SimpleIcons.Tailwindcss
        fileName == "build.gradle" || fileName == "build.gradle.kts" || fileName == "settings.gradle" || fileName == "settings.gradle.kts" || fileName == "gradle.properties" || fileName == "gradlew" || fileName == "gradlew.bat" -> SimpleIcons.Gradle
        fileName == "pom.xml" -> SimpleIcons.Apachemaven
        fileName == "podfile" || fileName == "podfile.lock" -> SimpleIcons.Cocoapods
        fileName == "package.swift" || extension == "xcodeproj" || fileName == "info.plist" -> SimpleIcons.Xcode
        fileName == "gemfile" || fileName == "rakefile" -> SimpleIcons.Ruby
        fileName == "requirements.txt" || fileName == "pyproject.toml" || fileName == "setup.py" -> SimpleIcons.Python
        fileName == "cargo.toml" || fileName == "cargo.lock" -> SimpleIcons.Rust
        fileName == "mix.exs" || fileName == "mix.lock" -> SimpleIcons.Elixir
        fileName == "composer.json" -> SimpleIcons.Php
        fileName == "terraform.tfvars" || extension == "tf" || extension == "tfvars" -> SimpleIcons.Terraform
        fileName == "chart.yaml" || fileName == "helmfile.yaml" -> SimpleIcons.Helm
        fileName == "kustomization.yaml" || fileName == "kustomization.yml" -> SimpleIcons.Kubernetes
        fileName == "jenkinsfile" -> SimpleIcons.Jenkins
        fileName == ".gitlab-ci.yml" -> SimpleIcons.Gitlab
        fileName == "circle.yml" || fileName == ".circleci.yml" -> SimpleIcons.Circleci
        fileName == ".travis.yml" -> SimpleIcons.Travisci
        fileName == "appveyor.yml" -> SimpleIcons.Appveyor
        extension == "jsx" || extension == "tsx" -> SimpleIcons.React
        extension == "svelte" -> SimpleIcons.Svelte
        extension == "astro" -> SimpleIcons.Html5
        kind == FileIconKind.LOCKFILE && displayName.lowercase().contains("yarn") -> SimpleIcons.Yarn
        kind == FileIconKind.LOCKFILE -> SimpleIcons.Npm
        kind == FileIconKind.SHELL && displayName.substringAfterLast('.').lowercase() in setOf("ps1", "psm1", "psd1") -> SimpleIcons.Powershell
        kind == FileIconKind.CSS && displayName.substringAfterLast('.').lowercase() in setOf("scss", "sass") -> SimpleIcons.Sass
        kind == FileIconKind.CSS && displayName.substringAfterLast('.').lowercase() == "less" -> SimpleIcons.Less
        else -> when (kind) {
        FileIconKind.KOTLIN -> SimpleIcons.Kotlin
        FileIconKind.JAVA -> SimpleIcons.Java
        FileIconKind.JSON -> SimpleIcons.Json
        FileIconKind.MARKDOWN -> SimpleIcons.Markdown
        FileIconKind.PYTHON -> SimpleIcons.Python
        FileIconKind.GRADLE -> SimpleIcons.Gradle
        FileIconKind.GRAPHQL -> SimpleIcons.Graphql
        FileIconKind.MAKE -> SimpleIcons.Gnu
        FileIconKind.CMAKE -> SimpleIcons.Cmake
        FileIconKind.DART -> SimpleIcons.Dart
        FileIconKind.LUA -> SimpleIcons.Lua
        FileIconKind.R -> SimpleIcons.R
        FileIconKind.SCALA -> SimpleIcons.Scala
        FileIconKind.PERL -> SimpleIcons.Perl
        FileIconKind.ELIXIR -> SimpleIcons.Elixir
        FileIconKind.JAVASCRIPT -> SimpleIcons.Javascript
        FileIconKind.TYPESCRIPT -> SimpleIcons.Typescript
        FileIconKind.C_CPP -> SimpleIcons.Cplusplus
        FileIconKind.RUST -> SimpleIcons.Rust
        FileIconKind.GO -> SimpleIcons.Go
        FileIconKind.SWIFT -> SimpleIcons.Swift
        FileIconKind.RUBY -> SimpleIcons.Ruby
        FileIconKind.PHP -> SimpleIcons.Php
        FileIconKind.HTML -> SimpleIcons.Html5
        FileIconKind.CSS -> SimpleIcons.Css3
        FileIconKind.SQL -> SimpleIcons.Sqlite
        FileIconKind.SHELL -> SimpleIcons.Gnubash
        FileIconKind.DOCKER -> SimpleIcons.Docker
        FileIconKind.GIT -> SimpleIcons.Git
        FileIconKind.DATABASE -> SimpleIcons.Sqlite
        FileIconKind.IMAGE -> Icons.Default.Image
        FileIconKind.TEXT -> Icons.Default.Article
        FileIconKind.GENERIC -> Icons.Default.InsertDriveFile
        else -> Icons.Default.Code
        }
    }
    val label = when (kind) {
        FileIconKind.KOTLIN -> "KT"
        FileIconKind.JAVA -> "JAVA"
        FileIconKind.XML -> "XML"
        FileIconKind.JSON -> "JSON"
        FileIconKind.YAML -> "YML"
        FileIconKind.MARKDOWN -> "MD"
        FileIconKind.PYTHON -> "PY"
        FileIconKind.TOML -> "TOML"
        FileIconKind.ENV -> "ENV"
        FileIconKind.GRADLE -> "GRADLE"
        FileIconKind.PROTO -> "PROTO"
        FileIconKind.GRAPHQL -> "GQL"
        FileIconKind.MAKE -> "MAKE"
        FileIconKind.CMAKE -> "CMAKE"
        FileIconKind.LOCKFILE -> "LOCK"
        FileIconKind.DART -> "DART"
        FileIconKind.LUA -> "LUA"
        FileIconKind.R -> "R"
        FileIconKind.SCALA -> "SCALA"
        FileIconKind.PERL -> "PERL"
        FileIconKind.ELIXIR -> "EX"
        FileIconKind.IMAGE -> "IMG"
        FileIconKind.TEXT -> "TXT"
        FileIconKind.HTML -> "HTML"
        FileIconKind.CSS -> "CSS"
        FileIconKind.JAVASCRIPT -> "JS"
        FileIconKind.TYPESCRIPT -> "TS"
        FileIconKind.C_CPP -> "C++"
        FileIconKind.RUST -> "RS"
        FileIconKind.GO -> "GO"
        FileIconKind.SWIFT -> "SWIFT"
        FileIconKind.RUBY -> "RB"
        FileIconKind.PHP -> "PHP"
        FileIconKind.SQL -> "SQL"
        FileIconKind.SHELL -> "SH"
        FileIconKind.DOCKER -> "DOC"
        FileIconKind.GIT -> "GIT"
        FileIconKind.CONFIG -> "CFG"
        FileIconKind.DATABASE -> "DB"
        FileIconKind.ARCHIVE -> "ZIP"
        FileIconKind.FONT -> "FONT"
        FileIconKind.AUDIO -> "AUD"
        FileIconKind.VIDEO -> "VID"
        FileIconKind.PDF -> "PDF"
        FileIconKind.CODE -> "CODE"
        FileIconKind.GENERIC -> "FILE"
    }
    val color = when (kind) {
        FileIconKind.KOTLIN -> Color(0xFF7F52FF)
        FileIconKind.JAVA -> Color(0xFFB07219)
        FileIconKind.XML -> Color(0xFFE44D26)
        FileIconKind.JSON -> Color(0xFFB8860B)
        FileIconKind.YAML -> Color(0xFFCB171E)
        FileIconKind.MARKDOWN -> Color(0xFF6B7280)
        FileIconKind.PYTHON -> Color(0xFF3776AB)
        FileIconKind.TOML -> Color(0xFF9C4221)
        FileIconKind.ENV -> Color(0xFF4A5568)
        FileIconKind.GRADLE -> Color(0xFF02303A)
        FileIconKind.PROTO -> Color(0xFF4285F4)
        FileIconKind.GRAPHQL -> Color(0xFFE10098)
        FileIconKind.MAKE -> Color(0xFF6B7280)
        FileIconKind.CMAKE -> Color(0xFF064F8C)
        FileIconKind.LOCKFILE -> Color(0xFF718096)
        FileIconKind.DART -> Color(0xFF0175C2)
        FileIconKind.LUA -> Color(0xFF000080)
        FileIconKind.R -> Color(0xFF276DC3)
        FileIconKind.SCALA -> Color(0xFFDC322F)
        FileIconKind.PERL -> Color(0xFF39457E)
        FileIconKind.ELIXIR -> Color(0xFF6E4A7E)
        FileIconKind.HTML -> Color(0xFFE44D26)
        FileIconKind.CSS -> Color(0xFF2965F1)
        FileIconKind.JAVASCRIPT -> Color(0xFFF0DB4F)
        FileIconKind.TYPESCRIPT -> Color(0xFF3178C6)
        FileIconKind.C_CPP -> Color(0xFF00599C)
        FileIconKind.RUST -> Color(0xFFDEA584)
        FileIconKind.GO -> Color(0xFF00ADD8)
        FileIconKind.SWIFT -> Color(0xFFF05138)
        FileIconKind.RUBY -> Color(0xFFCC342D)
        FileIconKind.PHP -> Color(0xFF777BB4)
        FileIconKind.SQL -> Color(0xFF336791)
        FileIconKind.SHELL -> Color(0xFF4EAA25)
        FileIconKind.DOCKER -> Color(0xFF2496ED)
        FileIconKind.GIT -> Color(0xFFF05032)
        FileIconKind.CONFIG -> Color(0xFF6B7280)
        FileIconKind.DATABASE -> Color(0xFF4F8CC9)
        FileIconKind.ARCHIVE -> Color(0xFFB7791F)
        FileIconKind.FONT -> Color(0xFF805AD5)
        FileIconKind.AUDIO -> Color(0xFFD53F8C)
        FileIconKind.VIDEO -> Color(0xFFDD6B20)
        FileIconKind.PDF -> Color(0xFFE53E3E)
        FileIconKind.IMAGE -> Color(0xFF8E6AC8)
        FileIconKind.TEXT -> Color(0xFF6B7280)
        FileIconKind.CODE -> Color(0xFF4F8CC9)
        FileIconKind.GENERIC -> Color(0xFF6B7280)
    }
    val iconTint = color.copy(alpha = if (muted) 0.45f else if (accent) 0.78f else 1f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
        // Do not put code-file icons on a custom badge: the background masks
        // their language-specific visual identity in tree and search results.
        Icon(icon, contentDescription = "$label file", tint = iconTint, modifier = Modifier.size(18.dp))
    }
}

// ── Tree flattening ────────────────────────────────────────────────────────────

private fun flattenTree(nodes: List<FileNode>, depth: Int = 0): List<Pair<FileNode, Int>> {
    val result = mutableListOf<Pair<FileNode, Int>>()
    for (node in nodes) {
        result += node to depth
        if (node.isDirectory && node.isExpanded) {
            result += flattenTree(node.children, depth + 1)
        }
    }
    return result
}
