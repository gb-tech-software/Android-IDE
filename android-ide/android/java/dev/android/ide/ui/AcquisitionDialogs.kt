package dev.android.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.android.ide.app.AppShellState
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.project.CreateProjectTemplate
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.ui.theme.operationStatusColor

@Composable
internal fun CreateNewProjectDialog(
    state: AppShellState,
    viewModel: AppShellViewModel,
    name: String,
    description: String,
    destination: String?,
    template: CreateProjectTemplate,
    reviewVisible: Boolean,
    confirmationVisible: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onDestination: () -> Unit,
    onTemplateChange: (CreateProjectTemplate) -> Unit,
    onReviewVisibleChange: (Boolean) -> Unit,
    onConfirmationVisibleChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenProject: (String) -> Unit,
    onLocation: (String) -> String,
    validName: (String) -> Boolean,
    onKeepInBackground: () -> Unit = {},
) {
    val acquiredId = state.acquiredProjectId
    val acquiredProject = acquiredId?.let { id -> state.projects.firstOrNull { it.id == id } }
    val acquiredLocation = acquiredProject?.location?.userVisiblePath?.let(onLocation)
        ?: acquiredProject?.location?.displayLabel?.let(onLocation)
        ?: destination?.let { appendProjectName(onLocation(it), name) }
    if (!reviewVisible) {
        AlertDialog(
            onDismissRequest = { if (!state.operationInProgress) onDismiss() },
            title = { Text("Choose a project template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose a starting point for your project.", style = MaterialTheme.typography.bodyMedium)
                    CreateTemplateChoices(template, onTemplateChange, state.operationInProgress)
                }
            },
            confirmButton = { Button(onClick = { onReviewVisibleChange(true) }, enabled = !state.operationInProgress) { Text("Continue") } },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !state.operationInProgress) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = {
                if (!state.operationInProgress) {
                    when {
                        acquiredId != null -> { viewModel.dismissAcquisitionPrompt(); onDismiss() }
                        confirmationVisible -> onConfirmationVisibleChange(false)
                        else -> onDismiss()
                    }
                }
            },
            title = { Text(when {
                acquiredId != null -> "Project ready"
                confirmationVisible -> "Review new project"
                else -> "Project details"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (acquiredId != null) {
                        Text(state.operationReport?.message ?: "Project created successfully.", color = LocalIdeColors.current.success)
                        AcquisitionSuccessDetails(
                            name = acquiredProject?.name ?: name,
                            location = acquiredLocation ?: "Location unavailable",
                            kind = template.title,
                        )
                    } else if (confirmationVisible) {
                        Text("Check the project details before creating it.", style = MaterialTheme.typography.bodyMedium)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AcquisitionReviewLine("Template", template.title)
                                AcquisitionReviewLine("Project name", name)
                                AcquisitionReviewLine("Description", description.ifBlank { "No description" })
                                AcquisitionReviewLine("Project folder", destination?.let { appendProjectName(onLocation(it), name) } ?: "Choose a storage location")
                            }
                        }
                        state.operationReport?.takeIf { it.outcome != OperationOutcome.COMPLETE }?.let {
                            Text(it.message, color = operationStatusColor(it.outcome))
                        }
                        if (state.operationInProgress) AcquisitionStatus("Creating project…", true)
                    } else {
                        Text(template.title, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(name, onNameChange, label = { Text("Project name") }, enabled = !state.operationInProgress, singleLine = true)
                        OutlinedTextField(description, onDescriptionChange, label = { Text("Description") }, enabled = !state.operationInProgress, minLines = 2)
                        AcquisitionPicker(destination, onLocation, "Choose storage location", "Choose another location", onDestination, state.operationInProgress)
                        AcquisitionStatus(
                            message = state.operationReport?.takeIf { it.outcome != OperationOutcome.COMPLETE }?.message ?: when {
                                state.operationInProgress -> "Creating project…"
                                name.isBlank() -> "Enter a project name"
                                !validName(name) -> "Use a valid project name"
                                destination == null -> "Choose a storage location"
                                else -> null
                            },
                            busy = state.operationInProgress,
                            outcome = state.operationReport?.outcome,
                        )
                    }
                }
            },
            confirmButton = {
                if (state.operationInProgress) Button(onClick = onKeepInBackground) {
                    Text("Keep in background")
                } else Button(onClick = {
                    if (acquiredId != null) {
                        viewModel.dismissAcquisitionPrompt()
                        onOpenProject(acquiredId)
                    } else if (confirmationVisible) {
                        destination?.let { viewModel.createBlankProject(it, name, description, template) }
                    } else {
                        onConfirmationVisibleChange(true)
                    }
                }, enabled = !state.operationInProgress && (acquiredId != null || (validName(name) && destination != null))) {
                    Text(when { acquiredId != null -> "Open project"; confirmationVisible -> if (state.operationInProgress) "Creating…" else "Create project"; else -> "Review" })
                }
            },
            dismissButton = { TextButton(onClick = {
                if (acquiredId != null) { viewModel.dismissAcquisitionPrompt(); onDismiss() }
                else if (confirmationVisible) onConfirmationVisibleChange(false)
                else onReviewVisibleChange(false)
            }, enabled = !state.operationInProgress) { Text(if (acquiredId != null) "Close" else if (confirmationVisible) "Back to edit" else "Back") } },
        )
    }
}

@Composable
private fun CreateTemplateChoices(selected: CreateProjectTemplate, onSelect: (CreateProjectTemplate) -> Unit, disabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Starting point", style = MaterialTheme.typography.titleSmall)
        CreateProjectTemplate.values().forEach { template ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !disabled) { onSelect(template) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RadioButton(
                    selected = selected == template,
                    onClick = if (disabled) null else ({ onSelect(template) }),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(template.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        template.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LoadExistingProjectDialog(
    state: AppShellState,
    viewModel: AppShellViewModel,
    folderUri: String?,
    folderName: String,
    confirmationVisible: Boolean,
    onConfirmationVisibleChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onOpenProject: (String) -> Unit,
    onLocation: (String) -> String,
    validName: (String) -> Boolean,
    onKeepInBackground: () -> Unit = {},
) {
    val inspection = state.folderInspection
    val ready = inspection?.let { it.readable && it.writable && !it.alreadyRegistered && it.containmentVerified && !it.overlapsRegisteredProject } == true
    val acquiredId = state.acquiredProjectId
    val acquiredProject = acquiredId?.let { id -> state.projects.firstOrNull { it.id == id } }
    val acquiredLocation = acquiredProject?.location?.userVisiblePath?.let(onLocation)
        ?: acquiredProject?.location?.displayLabel?.let(onLocation)
        ?: folderUri?.let(onLocation)
    AlertDialog(
        onDismissRequest = {
            if (!state.operationInProgress) {
                when {
                    acquiredId != null -> onCancel()
                    confirmationVisible -> onConfirmationVisibleChange(false)
                    else -> onCancel()
                }
            }
        },
        title = { Text(when {
            acquiredId != null -> "Project ready"
            confirmationVisible -> "Review existing project"
            else -> "Load an existing project"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (acquiredId != null) {
                    Text(state.operationReport?.message ?: "Project loaded successfully.", color = LocalIdeColors.current.success)
                    AcquisitionSuccessDetails(
                        name = acquiredProject?.name ?: folderName,
                        location = acquiredLocation ?: "Location unavailable",
                        kind = "Existing folder",
                    )
                } else if (confirmationVisible) {
                    Text("Review the selected folder before registering it as a project.", style = MaterialTheme.typography.bodyMedium)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AcquisitionReviewLine("Project folder", folderUri?.let(onLocation) ?: "Unavailable")
                            AcquisitionReviewLine("Project name", folderName)
                            AcquisitionReviewLine("Action", "Register this existing folder without copying its files")
                        }
                    }
                    state.operationReport?.takeIf { it.outcome != OperationOutcome.COMPLETE }?.let {
                        Text(it.message, color = operationStatusColor(it.outcome))
                    }
                    if (state.operationInProgress) AcquisitionStatus("Loading project…", true)
                } else {
                    folderUri?.let { Text(onLocation(it), style = MaterialTheme.typography.bodySmall) }
                    AcquisitionStatus(
                        message = state.operationReport?.takeIf { it.outcome != OperationOutcome.COMPLETE }?.message ?: when {
                            state.operationInProgress -> "Inspecting project location…"
                            inspection == null -> "Inspecting project location…"
                            inspection.alreadyRegistered -> "This folder is already registered as a project."
                            !inspection.readable -> inspection.explanation ?: "This folder cannot be read."
                            !inspection.writable -> inspection.explanation ?: "This folder cannot be written to."
                            !inspection.containmentVerified -> "The folder could not be safely compared with registered projects. Choose another folder."
                            inspection.overlapsRegisteredProject -> "This folder overlaps another registered project. Choose a non-overlapping folder."
                            inspection.capabilityState != dev.android.ide.contracts.CapabilityState.SUPPORTED -> inspection.explanation ?: "This folder is not supported for project acquisition."
                            !ready -> "This folder is not ready to import."
                            else -> null
                        },
                        busy = state.operationInProgress || inspection == null,
                        outcome = state.operationReport?.outcome,
                    )
                }
            }
        },
        confirmButton = {
            if (state.operationInProgress) Button(onClick = onKeepInBackground) {
                Text("Keep in background")
            } else Button(onClick = {
                if (acquiredId != null) {
                    viewModel.dismissAcquisitionPrompt()
                    onOpenProject(acquiredId)
                } else if (confirmationVisible && folderUri != null) {
                    viewModel.importExistingFolder(folderUri, folderName)
                } else if (ready) {
                    onConfirmationVisibleChange(true)
                }
            }, enabled = !state.operationInProgress && (acquiredId != null || (folderUri != null && validName(folderName) && (ready || confirmationVisible)))) {
                Text(when {
                    acquiredId != null -> "Open project"
                    confirmationVisible -> "Load project"
                    else -> "Review"
                })
            }
        },
        dismissButton = { TextButton(
            onClick = { if (confirmationVisible && acquiredId == null) onConfirmationVisibleChange(false) else onCancel() },
            enabled = !state.operationInProgress,
        ) { Text(if (acquiredId != null) "Close" else if (confirmationVisible) "Back to inspect" else "Cancel") } },
    )
}

@Composable
internal fun ImportZipProjectDialog(
    state: AppShellState,
    viewModel: AppShellViewModel,
    archiveUri: String?,
    name: String,
    description: String,
    destination: String?,
    confirmationVisible: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onDestination: () -> Unit,
    onConfirmationVisibleChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onOpenProject: (String) -> Unit,
    onLocation: (String) -> String,
    validName: (String) -> Boolean,
    onKeepInBackground: () -> Unit = {},
) {
    val ready = archiveUri != null && validName(name) && destination != null
    val acquiredId = state.acquiredProjectId
    val acquiredProject = acquiredId?.let { id -> state.projects.firstOrNull { it.id == id } }
    val acquiredLocation = acquiredProject?.location?.userVisiblePath?.let(onLocation)
        ?: acquiredProject?.location?.displayLabel?.let(onLocation)
        ?: destination?.let { appendProjectName(onLocation(it), name) }
    AlertDialog(
        onDismissRequest = {
            if (!state.operationInProgress) {
                if (acquiredId != null) {
                    viewModel.dismissAcquisitionPrompt()
                    onCancel()
                } else if (confirmationVisible) onConfirmationVisibleChange(false)
                else onCancel()
            }
        },
        title = { Text(when {
            acquiredId != null -> "Project ready"
            confirmationVisible -> "Review ZIP import"
            else -> "Import ZIP project"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (acquiredId != null) {
                        Text(state.operationReport?.message ?: "Project imported successfully.", color = LocalIdeColors.current.success)
                        AcquisitionSuccessDetails(
                            name = acquiredProject?.name ?: name,
                            location = acquiredLocation ?: "Location unavailable",
                            kind = "ZIP archive import",
                        )
                    } else if (confirmationVisible) {
                        Text("Review the archive and where its project will be created.", style = MaterialTheme.typography.bodyMedium)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AcquisitionReviewLine("Archive", archiveUri?.let(onLocation) ?: "Unavailable")
                                AcquisitionReviewLine("Project name", name)
                                AcquisitionReviewLine("Description", description.ifBlank { "No description" })
                                AcquisitionReviewLine("Project folder", destination?.let { appendProjectName(onLocation(it), name) } ?: "Choose a storage location")
                            }
                        }
                        state.operationReport?.takeIf { it.outcome != OperationOutcome.COMPLETE }?.let {
                            Text(it.message, color = operationStatusColor(it.outcome))
                        }
                        if (state.operationInProgress) AcquisitionStatus("Importing project…", true)
                    } else {
                    archiveUri?.let { Text(onLocation(it), style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(name, onNameChange, label = { Text("Project name") }, enabled = !state.operationInProgress, singleLine = true)
                    OutlinedTextField(description, onDescriptionChange, label = { Text("Description") }, enabled = !state.operationInProgress, minLines = 2)
                    AcquisitionPicker(destination, onLocation, "Choose storage location", "Choose another location", onDestination, state.operationInProgress)
                    AcquisitionStatus(
                        state.operationReport?.message ?: when {
                            state.operationInProgress -> "Importing project…"
                            archiveUri == null -> "Choose a ZIP archive"
                            !validName(name) -> "Enter a valid project name"
                            destination == null -> "Choose a storage location"
                            else -> null
                        },
                        state.operationInProgress,
                        outcome = state.operationReport?.outcome,
                    )
                }
            }
        },
        confirmButton = {
            if (state.operationInProgress) Button(onClick = onKeepInBackground) {
                Text("Keep in background")
            } else Button(onClick = {
                if (acquiredId != null) {
                    viewModel.dismissAcquisitionPrompt()
                    onOpenProject(acquiredId)
                } else if (confirmationVisible) {
                    if (archiveUri != null && destination != null) viewModel.importZip(archiveUri, destination, name, description)
                } else if (archiveUri != null && destination != null) {
                    onConfirmationVisibleChange(true)
                }
            }, enabled = !state.operationInProgress && (acquiredId != null || ready)) {
                Text(when { acquiredId != null -> "Open project"; confirmationVisible -> if (state.operationInProgress) "Importing…" else "Import project"; else -> "Review" })
            }
        },
        dismissButton = { TextButton(onClick = {
            if (confirmationVisible && acquiredId == null) onConfirmationVisibleChange(false)
            else onCancel()
        }, enabled = !state.operationInProgress) { Text(if (acquiredId != null) "Close" else if (confirmationVisible) "Back to edit" else "Cancel") } },
    )
}

@Composable
private fun AcquisitionPicker(
    value: String?,
    format: (String) -> String,
    emptyLabel: String,
    selectedLabel: String,
    onPick: () -> Unit,
    disabled: Boolean,
) {
    value?.let { Text(format(it), style = MaterialTheme.typography.bodySmall) }
    Button(onClick = onPick, enabled = !disabled) { Text(if (value == null) emptyLabel else selectedLabel) }
}

@Composable
private fun AcquisitionStatus(message: String?, busy: Boolean, outcome: OperationOutcome? = null) {
    if (message == null) return
    val statusColor = outcome?.let { operationStatusColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodySmall, color = statusColor)
    }
}

@Composable
private fun AcquisitionSuccessDetails(name: String, location: String, kind: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Registered project", style = MaterialTheme.typography.titleSmall)
            AcquisitionReviewLine("Project name", name)
            AcquisitionReviewLine("Location", location)
            AcquisitionReviewLine("Acquisition", kind)
        }
    }
}

@Composable
private fun AcquisitionReviewLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun appendProjectName(parent: String, name: String): String =
    parent.trimEnd('/').let { base -> if (base.isEmpty()) "/$name" else "$base/$name" }
