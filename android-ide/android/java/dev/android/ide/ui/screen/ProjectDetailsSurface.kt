package dev.android.ide.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.android.ide.app.AppShellState
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.Surface
import dev.android.ide.ui.ProjectActionsMenu
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.ui.theme.operationStatusColor
import kotlin.random.Random

@Composable
fun ProjectDetailsSurface(
    state: AppShellState,
    viewModel: AppShellViewModel,
    onExportProject: (String) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onRelocateProject: (String) -> Unit,
    onOpenNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = state.projects.firstOrNull { it.id == state.selectedProjectId }
    val projectSummary = project?.id?.let { state.projectSummaries[it] }
    val details = state.projectDetails?.takeIf { it.project.stableLocationId == project?.id }
    val fullWorkspaceAvailable = details?.storageCapabilities?.localWorkspaceAvailable == true
    val localStorageConfirmed = details?.storageCapabilities?.localStorageConfirmed == true
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renameVisible by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(project?.name.orEmpty()) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var deleteCode by remember { mutableStateOf(Random.nextInt(100, 1000).toString()) }
    var enteredDeleteCode by remember { mutableStateOf("") }
    val context = LocalContext.current
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, contentDescription = "Open sidebar") }
            Text("Project details", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { viewModel.refreshSelectedProjectDetails() }, enabled = !state.operationInProgress) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh project details")
            }
            Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Project actions") }
            ProjectActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                enabled = !state.operationInProgress,
                fullWorkspaceAvailable = fullWorkspaceAvailable,
                localStorageConfirmed = localStorageConfirmed,
                showRefreshItem = false,
                onRefresh = { viewModel.refreshSelectedProjectDetails() },
                onChangeDisplayName = { viewModel.clearOperationFeedback(); renameValue = project?.name.orEmpty(); renameVisible = true },
                onChangeLocation = { viewModel.clearOperationFeedback(); project?.id?.let(onRelocateProject) },
                onDuplicate = { viewModel.clearOperationFeedback(); project?.id?.let(onDuplicateProject) },
                onExport = { viewModel.clearOperationFeedback(); project?.id?.let(onExportProject) },
                onCopyPath = {
                    val path = humanReadableStorageLocation(project?.location?.userVisiblePath ?: project?.location?.displayLabel)
                    if (path != null) {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Project storage path", path))
                            viewModel.reportStatus("Copied project storage path")
                        } else actionFeedback = "The system clipboard is unavailable."
                    } else {
                        actionFeedback = "The project storage path is unavailable. Refresh project details and try again."
                    }
                },
                onOpenEditor = { viewModel.navigate(Surface.EDITOR) },
                onOpenTerminal = { project?.id?.let(viewModel::openTerminalForProject) },
                onOpenBrowser = { viewModel.navigate(Surface.BROWSER) },
                onRemoveFromRegistry = { viewModel.clearOperationFeedback(); confirmRemove = true },
                onDeletePermanently = {
                    viewModel.clearOperationFeedback()
                    deleteCode = Random.nextInt(100, 1000).toString()
                    enteredDeleteCode = ""
                    confirmDelete = true
                },
            )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.operationInProgress) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                    Text("Project operation in progress…")
                }
            }
            if (state.detailsLoading) Text("Refreshing project details…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.operationReport?.let { report ->
                Text(report.message, color = operationStatusColor(report.outcome))
                report.recoveryHint?.let { Text("Recovery: $it", color = LocalIdeColors.current.warning) }
            } ?: state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (confirmRemove) {
            val completed = state.operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !state.operationInProgress
            AlertDialog(
                onDismissRequest = { if (!state.operationInProgress) confirmRemove = false },
                title = { Text(if (completed) "Removal complete" else "Remove project from registry?") },
                text = { if (completed) Text(state.operationReport!!.message, color = LocalIdeColors.current.success) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("${project?.name ?: "This project"} will be removed from Android IDE, but its files, Git data, and location will remain unchanged.\n\nLocation: ${humanReadableStorageLocation(project?.location?.userVisiblePath ?: project?.location?.displayLabel)}"); state.operationReport?.let { Text(it.message, color = operationStatusColor(it.outcome)) } } },
                confirmButton = { OutlinedButton(onClick = if (completed) ({ confirmRemove = false }) else viewModel::removeSelectedProject, enabled = !state.operationInProgress) { Text(if (completed) "Done" else if (state.operationInProgress) "Removing…" else "Remove from Registry") } },
                dismissButton = { if (!completed) TextButton(onClick = { confirmRemove = false }, enabled = !state.operationInProgress) { Text(if (state.operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        if (renameVisible) {
            val completed = state.operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !state.operationInProgress
            AlertDialog(
                onDismissRequest = { if (!state.operationInProgress) renameVisible = false },
                title = { Text(if (completed) "Rename complete" else "Change project display name") },
                text = { if (completed) Text(state.operationReport!!.message, color = LocalIdeColors.current.success) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { androidx.compose.material3.OutlinedTextField(renameValue, { renameValue = it }, label = { Text("Project name") }, enabled = !state.operationInProgress, singleLine = true); state.operationReport?.let { Text(it.message, color = operationStatusColor(it.outcome)) } } },
                confirmButton = { TextButton(onClick = if (completed) ({ renameVisible = false }) else ({ viewModel.renameSelectedProject(renameValue) }), enabled = !state.operationInProgress && (completed || renameValue.isNotBlank())) { Text(if (completed) "Done" else if (state.operationInProgress) "Saving…" else "Save") } },
                dismissButton = { if (!completed) TextButton(onClick = { renameVisible = false }, enabled = !state.operationInProgress) { Text(if (state.operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        actionFeedback?.let { action ->
            AlertDialog(
                onDismissRequest = { actionFeedback = null },
                title = { Text("Project action") },
                text = { Text(action) },
                confirmButton = { TextButton(onClick = { actionFeedback = null }) { Text("OK") } },
            )
        }
        if (confirmDelete) {
            val completed = state.operationReport?.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && !state.operationInProgress
            AlertDialog(
                onDismissRequest = { if (!state.operationInProgress) confirmDelete = false },
                title = { Text(if (completed) "Deletion complete" else "Permanently delete ${project?.name ?: "this project"}?") },
                text = {
                    Column {
                        if (completed) Text(state.operationReport!!.message, color = LocalIdeColors.current.success)
                        else {
                            Text("This permanently removes the project data from its selected storage location. This action cannot be undone.")
                            Text("Project: ${project?.name ?: "Unavailable"}")
                            Text("Location: ${humanReadableStorageLocation(project?.location?.userVisiblePath ?: project?.location?.displayLabel)}")
                            Text("Type $deleteCode to confirm")
                            androidx.compose.material3.OutlinedTextField(enteredDeleteCode, { enteredDeleteCode = it }, label = { Text("Confirmation code") }, enabled = !state.operationInProgress, singleLine = true)
                            state.operationReport?.let { Text(it.message, color = operationStatusColor(it.outcome)) }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = if (completed) ({ confirmDelete = false }) else viewModel::permanentlyDeleteSelectedProject, enabled = !state.operationInProgress && (completed || enteredDeleteCode == deleteCode), colors = ButtonDefaults.buttonColors(containerColor = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError)) { Text(if (completed) "Done" else if (state.operationInProgress) "Deleting…" else "Delete permanently") }
                },
                dismissButton = { if (!completed) TextButton(onClick = { confirmDelete = false }, enabled = !state.operationInProgress) { Text(if (state.operationInProgress) "Please wait" else "Cancel") } },
            )
        }
        val location = humanReadableStorageLocation(
            details?.storagePath ?: project?.location?.userVisiblePath ?: project?.location?.displayLabel,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(project?.name ?: "No project selected", style = MaterialTheme.typography.headlineSmall)
                Text(
                    details?.description?.takeIf { it.isNotBlank() }
                        ?: project?.description?.takeIf { it.isNotBlank() }
                        ?: "No description",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.HorizontalDivider()
                DetailLine("Project location", location)
            }
        }
        if (details != null && !details.storageCapabilities.localWorkspaceAvailable) {
            val storageCapabilities = details.storageCapabilities
            val localStorageConfirmed = storageCapabilities.localStorageConfirmed
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (localStorageConfirmed) "Native tools unavailable" else "Storage access needs attention",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (localStorageConfirmed) {
                            "This project is confirmed as device-local. Android IDE uses the storage provider for editing and file/project operations it supports. Terminal, Git, language servers, and development-server preview need a native path. Move the project to Android IDE storage or Android external storage for those tools."
                        } else {
                            storageCapabilities.explanation
                                ?: storageCapabilities.localWorkspaceExplanation
                                ?: "This location has not been confirmed as device-local. Cloud or remote projects are not supported; reselect a local location with valid access."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailMetric(
                "Size",
                details?.totalBytes?.let(::formatBytes) ?: projectSummary?.totalBytes?.let(::formatBytes)
                    ?: if (state.detailsLoading) "Loading" else "Unavailable",
                Modifier.weight(1f),
            )
            DetailMetric(
                "Files",
                details?.fileCount?.toString() ?: projectSummary?.fileCount?.toString()
                    ?: if (state.detailsLoading) "Loading" else "Unavailable",
                Modifier.weight(1f),
            )
            DetailMetric(
                "Folders",
                details?.folderCount?.toString() ?: if (state.detailsLoading) "Loading" else "—",
                Modifier.weight(1f),
            )
        }
        if (details != null) {
            DetailSection("Storage") {
                DetailLine("Provider", details.storageProvider)
                DetailLine("Availability", availabilityLabel(details.storageCapabilities.state))
            }
            if (details.languageBytes.isNotEmpty()) DetailSection("Languages") {
                details.languageBytes.entries.sortedByDescending { it.value }.forEach { (language, bytes) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(language, style = MaterialTheme.typography.bodyMedium)
                        Text(formatBytes(bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            DetailSection("Activity") {
                details.creationTimeMs?.let { DetailLine("Created", formatTimestamp(it)) }
                details.lastModifiedTimeMs?.let { DetailLine("Last updated", formatTimestamp(it)) }
            }
        } else if (project != null) {
            val detailsStatus = projectSummary?.status?.takeIf { it.isNotBlank() }
                ?: if (state.detailsLoading) "Loading project statistics and metadata…" else "Project statistics and metadata are unavailable."
            Card(Modifier.fillMaxWidth()) {
                Text(detailsStatus, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (project != null) {
            ProjectGitStatusCard(git = details?.git)
        }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private fun formatTimestamp(value: Long): String =
    java.time.Instant.ofEpochMilli(value).toString().replace("T", " ").substringBefore('.')

@Composable
private fun DetailLine(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun availabilityLabel(state: CapabilityState): String = when (state) {
    CapabilityState.SUPPORTED -> "Available"
    CapabilityState.NOT_YET_CHECKED -> "Checking storage access"
    CapabilityState.UNSUPPORTED -> "Not supported at this location"
    CapabilityState.UNAVAILABLE -> "Storage location unavailable"
    CapabilityState.PERMISSION_LOST -> "Permission needed"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return "%.1f %s".format(value, units[index])
}
