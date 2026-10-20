package dev.android.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.ui.theme.operationStatusColor

/**
 * Single project-export review surface.
 *
 * Project export is deliberately initiated through this component regardless of
 * whether the request came from Projects, Project Details, or the editor tree.
 * The composition root owns launchers; this component owns the user-facing copy
 * and progress/feedback presentation.
 */
@Composable
fun ProjectExportDialog(
    visible: Boolean,
    projectName: String?,
    storageLocation: String?,
    operationReport: OperationReport?,
    operationInProgress: Boolean,
    onChooseDestination: (suggestedFileName: String) -> Unit,
    onDismiss: () -> Unit,
    onKeepInBackground: () -> Unit = {},
) {
    if (!visible) return

    val name = projectName?.takeIf { it.isNotBlank() } ?: "project"
    val completed = operationReport?.outcome == OperationOutcome.COMPLETE && !operationInProgress
    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text(if (completed) "Export complete" else "Export project as ZIP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (completed) {
                    Text(operationReport!!.message, color = operationStatusColor(operationReport!!.outcome))
                } else {
                    storageLocation?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    operationReport?.let { report ->
                        Text(
                            report.message,
                            color = operationStatusColor(report.outcome),
                        )
                    }
                    if (operationInProgress) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                            Text("Exporting…")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (operationInProgress) Button(onClick = onKeepInBackground) {
                Text("Keep in background")
            } else Button(
                onClick = if (completed) onDismiss else ({ onChooseDestination("$name.zip") }),
                enabled = !operationInProgress && (completed || projectName != null),
            ) {
                Text(if (completed) "Done" else "Choose export location")
            }
        },
        dismissButton = {
            if (!completed) TextButton(onClick = onDismiss, enabled = !operationInProgress) { Text(if (operationInProgress) "Please wait" else "Cancel") }
        },
    )
}
