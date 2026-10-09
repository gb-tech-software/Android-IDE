package dev.android.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.ui.theme.operationStatusColor

internal enum class ProjectOperationKind { DUPLICATE, RELOCATE }

/** Mobile-first edit and review steps for project duplication and relocation. */
@Composable
internal fun ProjectOperationDialog(
    kind: ProjectOperationKind,
    projectName: String?,
    sourceLocation: String?,
    name: String,
    description: String,
    destination: String?,
    reviewVisible: Boolean,
    operationReport: OperationReport?,
    operationInProgress: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    isValidName: (String) -> Boolean,
    onChooseDestination: () -> Unit,
    onReviewVisibleChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val completed = operationReport?.outcome == OperationOutcome.COMPLETE && !operationInProgress
    val isDuplicate = kind == ProjectOperationKind.DUPLICATE
    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = {
            Text(when {
                completed -> "Operation complete"
                reviewVisible -> "Review ${if (isDuplicate) "duplicate" else "relocation"}"
                isDuplicate -> "Duplicate project"
                else -> "Relocate project"
            })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    completed -> Text(operationReport!!.message, color = LocalIdeColors.current.success)
                    reviewVisible -> {
                        Text(
                            if (isDuplicate) "A separate copy will be created. The original project will stay where it is."
                            else "The project will be copied to the new location first. The original is removed only after the destination copy is verified.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ReviewLine("Project", name)
                                if (isDuplicate && description.isNotBlank()) ReviewLine("Description", description)
                                ReviewLine("From", sourceLocation ?: "Unavailable")
                                ReviewLine("Project folder", destination?.let { appendLeaf(it, name) } ?: "Choose a destination")
                            }
                        }
                    }
                    else -> {
                        sourceLocation?.takeIf(String::isNotBlank)?.let { ReviewLine("Current location", it) }
                        OutlinedTextField(
                            value = name,
                            onValueChange = onNameChange,
                            label = { Text(if (isDuplicate) "New project name" else "Project display name") },
                            enabled = !operationInProgress,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (isDuplicate) {
                            OutlinedTextField(
                                value = description,
                                onValueChange = onDescriptionChange,
                                label = { Text("Description") },
                                enabled = !operationInProgress,
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        destination?.let { ReviewLine("Destination folder", it) }
                        Button(onClick = onChooseDestination, enabled = !operationInProgress) {
                            Text(if (destination == null) "Choose destination" else "Change destination")
                        }
                    }
                }
                operationReport?.takeIf { it.outcome != OperationOutcome.COMPLETE }?.let { report ->
                    Text(report.message, color = operationStatusColor(report.outcome), style = MaterialTheme.typography.bodySmall)
                    report.recoveryHint?.let { Text(it, color = LocalIdeColors.current.warning, style = MaterialTheme.typography.bodySmall) }
                }
                if (operationInProgress) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(if (isDuplicate) "Creating copy…" else "Relocating project…")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = when {
                    completed -> onDismiss
                    reviewVisible -> onSubmit
                    else -> ({ onReviewVisibleChange(true) })
                },
                enabled = when {
                    operationInProgress -> false
                    completed -> true
                    reviewVisible -> projectName != null && isValidName(name) && destination != null
                    else -> projectName != null && isValidName(name) && destination != null
                },
            ) {
                Text(when {
                    completed -> "Done"
                    operationInProgress -> "Working…"
                    reviewVisible && isDuplicate -> "Confirm duplicate"
                    reviewVisible -> "Confirm relocation"
                    else -> "Review"
                })
            }
        },
        dismissButton = {
            if (!completed) {
                TextButton(
                    onClick = if (reviewVisible) ({ onReviewVisibleChange(false) }) else onDismiss,
                    enabled = !operationInProgress,
                ) {
                    Text(if (reviewVisible) "Back to edit" else "Cancel")
                }
            }
        },
    )
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun appendLeaf(parent: String, name: String): String =
    parent.trimEnd('/').let { base -> if (base.isEmpty()) "/$name" else "$base/$name" }
