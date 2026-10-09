package dev.android.ide.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import dev.android.ide.app.AppShellState
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.ui.theme.operationStatusColor
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.OperationReport
import dev.android.ide.ui.ProjectActionsMenu
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

@Composable
fun ProjectsSurface(
    state: AppShellState,
    viewModel: AppShellViewModel,
    onCreateProject: (String?) -> Unit,
    onImportFolder: () -> Unit,
    onImportZip: () -> Unit,
    onExportProject: (String) -> Unit,
    onExportProjects: (Set<String>) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onRelocateProject: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onFeedback: (String) -> Unit,
    onOpenNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun copyPaths(ids: Set<String>) {
        val paths = state.projects.filter { it.id in ids }.mapNotNull { it.location.userVisiblePath ?: it.location.displayLabel }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("Project storage paths", paths.joinToString("\n")),
        )
        viewModel.reportStatus("Copied ${paths.size} project storage path(s)")
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(ProjectsSurfaceSort.NAME) }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var filterMode by rememberSaveable { mutableStateOf(ProjectFilter.ALL) }
    var addActionsExpanded by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    val fabScale by animateFloatAsState(if (addActionsExpanded) 1.08f else 1f, label = "project-fab-scale")
    val listBusy = state.operationInProgress || state.restoring
    var confirmBatchRemove by remember { mutableStateOf(false) }
    var confirmBatchExport by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var batchDeleteCode by remember { mutableStateOf(Random.nextInt(100, 1000).toString()) }
    var enteredBatchDeleteCode by remember { mutableStateOf("") }
    val visibleProjects = state.projects
        .filter { it.name.contains(searchQuery, ignoreCase = true) }
        .filter { project ->
            val summary = state.projectSummaries[project.id]
            when (filterMode) {
                ProjectFilter.ALL -> true
                ProjectFilter.GIT -> summary?.hasGit == true
                ProjectFilter.AVAILABLE -> project.location.capabilityState == CapabilityState.SUPPORTED && summary?.status == null
                ProjectFilter.ATTENTION -> project.location.capabilityState != CapabilityState.SUPPORTED || summary?.status != null
            }
        }
        .let { projects ->
            when (sortMode) {
                ProjectsSurfaceSort.NAME -> projects.sortedBy { it.name.lowercase() }
                ProjectsSurfaceSort.LAST_OPENED -> projects.sortedByDescending { it.lastOpenedAt ?: Instant.MIN }
                ProjectsSurfaceSort.REGISTERED -> projects.sortedByDescending { it.registeredAt }
                ProjectsSurfaceSort.FILE_COUNT -> projects.sortedByDescending { state.projectSummaries[it.id]?.fileCount ?: -1 }
                ProjectsSurfaceSort.SIZE -> projects.sortedByDescending { state.projectSummaries[it.id]?.totalBytes ?: -1L }
            }
        }

    BackHandler(enabled = addActionsExpanded || state.selectedProjectIds.isNotEmpty()) {
        when {
            addActionsExpanded -> addActionsExpanded = false
            else -> {
                selectionMenuOpen = false
                viewModel.clearProjectSelection()
            }
        }
    }
    Box(modifier.fillMaxSize()) {
    if (addActionsExpanded) {
        Box(Modifier.fillMaxSize().clickable { addActionsExpanded = false })
    }
    if (state.selectedProjectIds.isNotEmpty() && !listBusy) {
        Box(Modifier.fillMaxSize().clickable {
            selectionMenuOpen = false
            viewModel.clearProjectSelection()
        })
    }
    Column(Modifier.fillMaxSize().padding(20.dp).padding(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenNavigation, enabled = !listBusy) {
                Icon(Icons.Default.Menu, contentDescription = "Open sidebar")
            }
            Text("Projects", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (state.refreshingProjects) {
                CircularProgressIndicator(Modifier.padding(horizontal = 8.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = viewModel::refreshProjectList, enabled = !listBusy && !state.refreshingProjects) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh projects")
            }
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, contentDescription = "Sort projects") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    ProjectsSurfaceSort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { sortMode = option; sortOpen = false })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search projects") },
                singleLine = true,
                trailingIcon = {
                    Row {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear project search")
                            }
                        }
                        Box {
                            IconButton(onClick = { filterOpen = true }) { Icon(Icons.Default.FilterList, contentDescription = "Filter projects") }
                            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                                ProjectFilter.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = { filterMode = option; filterOpen = false })
                                }
                            }
                        }
                    }
                },
            )
        }
        state.registryWarning?.let { Text(it, color = LocalIdeColors.current.warning) }
        if (state.statusMessage != null && !state.operationInProgress &&
            state.operationReport == null && state.acquiredProjectId == null
        ) {
            val statusColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text(state.statusMessage!!, color = statusColor, style = MaterialTheme.typography.bodySmall)
        }
        if (state.selectedProjectIds.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${state.selectedProjectIds.size} selected")
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { selectionMenuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Selected project actions")
                    }
                    DropdownMenu(expanded = selectionMenuOpen, onDismissRequest = { selectionMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Select all") }, onClick = { selectionMenuOpen = false; viewModel.selectAllProjects() })
                        DropdownMenuItem(text = { Text("Unselect all") }, onClick = { selectionMenuOpen = false; viewModel.clearProjectSelection() })
                        DropdownMenuItem(text = { Text("Export or share") }, onClick = { selectionMenuOpen = false; viewModel.clearOperationFeedback(); confirmBatchExport = true })
                        DropdownMenuItem(text = { Text("Copy storage path") }, onClick = { selectionMenuOpen = false; copyPaths(state.selectedProjectIds) })
                        DropdownMenuItem(text = { Text("Remove from registry") }, onClick = { selectionMenuOpen = false; viewModel.clearOperationFeedback(); confirmBatchRemove = true })
                        DropdownMenuItem(text = { Text("Permanently delete", color = MaterialTheme.colorScheme.error) }, onClick = { selectionMenuOpen = false; viewModel.clearOperationFeedback(); batchDeleteCode = Random.nextInt(100, 1000).toString(); enteredBatchDeleteCode = ""; confirmBatchDelete = true })
                    }
                }
            }
        }
        if (confirmBatchDelete) {
            val completed = state.operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !state.operationInProgress
            AlertDialog(
                onDismissRequest = { if (!state.operationInProgress) confirmBatchDelete = false },
                title = { Text(if (completed) "Deletion complete" else "Permanently delete selected projects?") },
                text = {
                    Column {
                        if (completed) Text(state.operationReport!!.message, color = LocalIdeColors.current.success)
                        else {
                            Text("This permanently removes ${state.selectedProjectIds.size} selected project location(s). This action cannot be undone.")
                            Text("Selected projects: ${state.projects.filter { it.id in state.selectedProjectIds }.joinToString { it.name }}")
                            Text("Type $batchDeleteCode to confirm")
                            OutlinedTextField(enteredBatchDeleteCode, { enteredBatchDeleteCode = it }, label = { Text("Confirmation code") }, singleLine = true)
                        }
                    }
                },
                confirmButton = { Button(onClick = if (completed) ({ confirmBatchDelete = false }) else ({ viewModel.permanentlyDeleteProjects(state.selectedProjectIds.toList()) }), enabled = !state.operationInProgress && (completed || enteredBatchDeleteCode == batchDeleteCode), colors = ButtonDefaults.buttonColors(containerColor = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError)) { Text(if (completed) "Done" else if (state.operationInProgress) "Deleting…" else "Delete permanently") } },
                dismissButton = { if (!completed) TextButton(onClick = { confirmBatchDelete = false }, enabled = !state.operationInProgress) { Text(if (state.operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        if (confirmBatchRemove) {
            val completed = state.operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !state.operationInProgress
            AlertDialog(
                onDismissRequest = { if (!state.operationInProgress) confirmBatchRemove = false },
                title = { Text(if (completed) "Removal complete" else "Remove selected projects from registry?") },
                text = { if (completed) Text(state.operationReport!!.message, color = LocalIdeColors.current.success) else Text("${state.selectedProjectIds.size} project record(s) will be removed from Android IDE. User files, Git data, and locations remain unchanged.") },
                confirmButton = { Button(onClick = if (completed) ({ confirmBatchRemove = false }) else ({ viewModel.removeProjectsFromRegistry(state.selectedProjectIds.toList()) }), enabled = !state.operationInProgress) { Text(if (completed) "Done" else if (state.operationInProgress) "Removing…" else "Remove from Registry") } },
                dismissButton = { if (!completed) TextButton(onClick = { confirmBatchRemove = false }, enabled = !state.operationInProgress) { Text(if (state.operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        if (confirmBatchExport) {
            val selectedNames = state.projects.filter { it.id in state.selectedProjectIds }.joinToString { it.name }
            val completed = state.operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !state.operationInProgress
            AlertDialog(
                onDismissRequest = { confirmBatchExport = false },
                title = { Text(if (completed) "Export complete" else "Export selected projects as ZIP") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (completed) Text(state.operationReport!!.message, color = LocalIdeColors.current.success)
                        else {
                            Text("Selected projects: $selectedNames\n\nOne ZIP archive per project will be created in a destination you choose. Source projects remain unchanged.")
                            state.operationReport?.let { report -> Text(report.message, color = operationStatusColor(report.outcome)) }
                            if (state.operationInProgress) Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                                Text("Exporting…")
                            }
                        }
                    }
                },
                confirmButton = { Button(onClick = if (completed) ({ confirmBatchExport = false }) else ({ onExportProjects(state.selectedProjectIds) }), enabled = !state.operationInProgress) { Text(if (completed) "Done" else "Choose export location") } },
                dismissButton = { if (!completed) TextButton(onClick = { confirmBatchExport = false }, enabled = !state.operationInProgress) { Text(if (state.operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        if (visibleProjects.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (searchQuery.isBlank()) "No registered projects" else "No matching projects")
                if (searchQuery.isNotBlank()) {
                    TextButton(onClick = { onCreateProject(searchQuery) }) {
                        Text("Create a new project named ${searchQuery.trim()}")
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleProjects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        summary = state.projectSummaries[project.id],
                        summaryLoading = !state.projectSummaries.containsKey(project.id),
                        onLoadSummary = viewModel::loadProjectSummary,
                        onOpen = viewModel::openProject,
                        onDetails = viewModel::showProjectDetails,
                        onSelect = viewModel::selectProject,
                        onFeedback = onFeedback,
                        onRefresh = viewModel::refreshProjectList,
                        onRemove = viewModel::removeSelectedProject,
                        onDelete = viewModel::permanentlyDeleteSelectedProject,
                        onRename = viewModel::renameSelectedProject,
                        selected = project.id in state.selectedProjectIds,
                        selectionMode = state.selectedProjectIds.isNotEmpty(),
                        onToggleSelection = viewModel::toggleProjectSelection,
                        onCopyPath = { copyPaths(setOf(it)) },
                        onExport = onExportProject,
                        onDuplicate = onDuplicateProject,
                        onRelocate = onRelocateProject,
                        onOpenBrowser = { viewModel.navigate(dev.android.ide.contracts.Surface.BROWSER) },
                        onOpenTerminal = onOpenTerminal,
                        operationInProgress = listBusy,
                        operationReport = state.operationReport,
                        onPrepareOperation = viewModel::clearOperationFeedback,
                    )
                }
            }
        }
    }
    if (listBusy) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)).clickable { },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("Working…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    FloatingActionButton(
        onClick = { if (!listBusy) addActionsExpanded = !addActionsExpanded },
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).graphicsLayer { scaleX = fabScale; scaleY = fabScale },
    ) { androidx.compose.material3.Icon(if (addActionsExpanded) Icons.Default.Close else Icons.Default.Add, contentDescription = if (addActionsExpanded) "Close project actions" else "Add project") }
    if (addActionsExpanded) {
        Card(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 84.dp).animateContentSize(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectAcquisitionAction(Icons.Default.CreateNewFolder, "Create new project") { addActionsExpanded = false; onCreateProject(null) }
            ProjectAcquisitionAction(Icons.Default.FolderOpen, "Load an existing project") { addActionsExpanded = false; onImportFolder() }
            ProjectAcquisitionAction(Icons.Default.Archive, "Import from ZIP archive") { addActionsExpanded = false; onImportZip() }
        }
        }
    }
    }
}

@Composable
private fun ProjectAcquisitionAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private enum class ProjectsSurfaceSort(val label: String) {
    NAME("Name"),
    LAST_OPENED("Last opened"),
    REGISTERED("Registration time"),
    FILE_COUNT("File count"),
    SIZE("Project size"),
}

private enum class ProjectFilter(val label: String) {
    ALL("All projects"),
    GIT("Git projects"),
    AVAILABLE("Available"),
    ATTENTION("Needs attention"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectIdentity,
    summary: dev.android.ide.app.ProjectSummary?,
    summaryLoading: Boolean,
    onLoadSummary: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDetails: (String) -> Unit,
    onSelect: (String) -> Unit,
    onFeedback: (String) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    onExport: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onRelocate: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    operationInProgress: Boolean,
    operationReport: OperationReport?,
    onPrepareOperation: () -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(project.id, summaryLoading) {
        if (summaryLoading) onLoadSummary(project.id)
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteCode by remember { mutableStateOf(Random.nextInt(100, 1000).toString()) }
    var enteredDeleteCode by remember { mutableStateOf("") }
    var renameVisible by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(project.name) }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) onToggleSelection(project.id) else onOpen(project.id) },
            onLongClick = { onToggleSelection(project.id) },
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) LocalIdeColors.current.activeHighlight else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 5.dp else 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(if (project.description.isBlank()) "No description" else project.description, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (summaryLoading) {
                            Text("Files loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Size loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            summary?.fileCount?.let { Text("$it files", style = MaterialTheme.typography.bodySmall) }
                                ?: Text("Files unavailable", style = MaterialTheme.typography.bodySmall)
                            summary?.totalBytes?.let { Text(formatBytes(it), style = MaterialTheme.typography.bodySmall) }
                                ?: Text("Size unavailable", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(relativeLastOpened(project.lastOpenedAt), style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!summaryLoading && summary?.hasGit == true) {
                        ProjectBadge("Git", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!summaryLoading) {
                        projectAttentionLabel(project, summary)?.let { (label, container, content) ->
                            ProjectBadge(label, container, content)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { if (!selectionMode) onSelect(project.id); menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Project actions") }
                if (selectionMode) {
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (selected) "Unselect" else "Select") },
                            onClick = { menuOpen = false; onToggleSelection(project.id) },
                        )
                    }
                } else {
                    ProjectActionsMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        fullWorkspaceAvailable = summary?.localWorkspaceAvailable == true,
                        localStorageConfirmed = summary?.localStorageConfirmed == true,
                        onDetails = { onDetails(project.id) },
                        onRefresh = { onSelect(project.id); onRefresh() },
                        onChangeDisplayName = { onPrepareOperation(); renameValue = project.name; renameVisible = true },
                        onChangeLocation = { onPrepareOperation(); onRelocate(project.id) },
                        onDuplicate = { onPrepareOperation(); onDuplicate(project.id) },
                        onExport = { onPrepareOperation(); onExport(project.id) },
                        onCopyPath = { onCopyPath(project.id) },
                        onOpenEditor = { onOpen(project.id) },
                        onOpenTerminal = { onOpenTerminal(project.id) },
                        onOpenBrowser = { onOpenBrowser(project.id) },
                        onRemoveFromRegistry = { onPrepareOperation(); onSelect(project.id); confirmRemove = true },
                        onDeletePermanently = {
                            onPrepareOperation()
                            onSelect(project.id)
                            deleteCode = Random.nextInt(100, 1000).toString()
                            enteredDeleteCode = ""
                            confirmDelete = true
                        },
                    )
                }
            }
        }
        if (confirmRemove) {
            val completed = operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !operationInProgress
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text(if (completed) "Removal complete" else "Remove project from registry?") },
                text = { if (completed) Text(operationReport!!.message, color = LocalIdeColors.current.success) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("${project.name} will be removed from Android IDE, but its files, Git data, and location will remain unchanged.\n\nLocation: ${humanReadableStorageLocation(project.location.userVisiblePath ?: project.location.displayLabel)}") }; if (operationInProgress) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Removing…") } },
                confirmButton = { Button(onClick = if (completed) ({ confirmRemove = false }) else onRemove, enabled = !operationInProgress) { Text(if (completed) "Done" else if (operationInProgress) "Removing…" else "Remove from Registry") } },
                dismissButton = { if (!completed) TextButton(onClick = { confirmRemove = false }, enabled = !operationInProgress) { Text(if (operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        if (confirmDelete) {
            val completed = operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !operationInProgress
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(if (completed) "Deletion complete" else "Permanently delete ${project.name}?") },
                text = {
                    Column {
                        if (completed) Text(operationReport!!.message, color = LocalIdeColors.current.success)
                        else {
                            Text("This permanently removes the project data from its selected storage location. This action cannot be undone.")
                            Text("Project: ${project.name}")
                            Text("Location: ${humanReadableStorageLocation(project.location.userVisiblePath ?: project.location.displayLabel)}")
                            Text("Type $deleteCode to confirm")
                            OutlinedTextField(enteredDeleteCode, { enteredDeleteCode = it }, label = { Text("Confirmation code") }, singleLine = true, enabled = !operationInProgress)
                            if (operationInProgress) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Deleting…") }
                        }
                    }
                },
                confirmButton = { Button(onClick = if (completed) ({ confirmDelete = false }) else onDelete, enabled = !operationInProgress && (completed || enteredDeleteCode == deleteCode), colors = ButtonDefaults.buttonColors(containerColor = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError)) { Text(if (completed) "Done" else if (operationInProgress) "Deleting…" else "Delete permanently") } },
                dismissButton = { if (!completed) TextButton(onClick = { confirmDelete = false }, enabled = !operationInProgress) { Text(if (operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        if (renameVisible) {
            val completed = operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !operationInProgress
            AlertDialog(
                onDismissRequest = { renameVisible = false },
                title = { Text(if (completed) "Rename complete" else "Change project display name") },
                text = { if (completed) Text(operationReport!!.message, color = LocalIdeColors.current.success) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(renameValue, { renameValue = it }, label = { Text("Project name") }, singleLine = true, enabled = !operationInProgress); if (operationInProgress) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Renaming…") } } },
                confirmButton = {
                    Button(onClick = if (completed) ({ renameVisible = false }) else ({ onSelect(project.id); onRename(renameValue) }), enabled = !operationInProgress && (completed || renameValue.isNotBlank())) { Text(if (completed) "Done" else if (operationInProgress) "Saving…" else "Save") }
                },
                dismissButton = { if (!completed) TextButton(onClick = { renameVisible = false }, enabled = !operationInProgress) { Text(if (operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        }
    }
}

@Composable
private fun ProjectBadge(
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun projectAttentionLabel(
    project: ProjectIdentity,
    summary: dev.android.ide.app.ProjectSummary?,
): Triple<String, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>? {
    val status = summary?.status
    return when {
        project.location.capabilityState == CapabilityState.PERMISSION_LOST -> Triple("Permission needed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        project.location.capabilityState == CapabilityState.UNSUPPORTED -> Triple("Provider unsupported", LocalIdeColors.current.warningContainer, LocalIdeColors.current.onWarningContainer)
        project.location.capabilityState == CapabilityState.UNAVAILABLE -> Triple("Location unavailable", LocalIdeColors.current.warningContainer, LocalIdeColors.current.onWarningContainer)
        status != null -> {
            val shortLabel = when {
                status.contains("permission", ignoreCase = true) -> "Permission needed"
                status.contains("unsupported", ignoreCase = true) -> "Provider unsupported"
                status.contains("unavailable", ignoreCase = true) -> "Location unavailable"
                status.contains("inspect", ignoreCase = true) -> "Inspection needed"
                else -> "Needs attention"
            }
            if (shortLabel == "Permission needed") Triple(shortLabel, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            else Triple(shortLabel, LocalIdeColors.current.warningContainer, LocalIdeColors.current.onWarningContainer)
        }
        else -> null
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
    return "%.1f %s".format(value, units[index])
}

private fun relativeLastOpened(lastOpenedAt: Instant?): String {
    if (lastOpenedAt == null) return "Never"
    val seconds = Duration.between(lastOpenedAt, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        seconds < 2_592_000 -> "${seconds / 86_400}d ago"
        seconds < 31_536_000 -> "${seconds / 2_592_000}mo ago"
        else -> "${seconds / 31_536_000}y ago"
    }
}
