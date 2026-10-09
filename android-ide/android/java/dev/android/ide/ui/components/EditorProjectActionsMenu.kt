package dev.android.ide.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.android.ide.ui.theme.LocalIdeColors

/** Shared project-root actions used by the editor overflow and file-tree menus. */
@Composable
fun EditorProjectActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onImportFiles: () -> Unit,
    onExportProject: () -> Unit,
    onRefresh: () -> Unit,
    onShowDetails: () -> Unit,
    onDeleteProject: () -> Unit,
    onRemoveProject: () -> Unit,
    onPasteAtRoot: (() -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
    includeCreationActions: Boolean = true,
    fullWorkspaceAvailable: Boolean = false,
    localStorageConfirmed: Boolean = false,
) {
    val colors = LocalIdeColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (localStorageConfirmed && includeCreationActions) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Article, null) },
                text = { Text("New file") },
                onClick = { onDismiss(); onNewFile() },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                text = { Text("New folder") },
                onClick = { onDismiss(); onNewFolder() },
            )
        }
        if (localStorageConfirmed) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                text = { Text("Import files") },
                onClick = { onDismiss(); onImportFiles() },
            )
            onPasteAtRoot?.let { paste ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                    text = { Text("Paste") },
                    onClick = { onDismiss(); paste() },
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.Refresh, null) },
            text = { Text("Refresh files") },
            onClick = { onDismiss(); onRefresh() },
        )
        if (localStorageConfirmed) {
            onOpenTerminal?.let { openTerminal ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Terminal, null) },
                    text = { Text("Open terminal") },
                    onClick = { onDismiss(); openTerminal() },
                )
            }
        }
        if (localStorageConfirmed) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Share, null) },
                text = { Text("Export project") },
                onClick = { onDismiss(); onExportProject() },
            )
        }
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.Info, null) },
            text = { Text("Project details") },
            onClick = { onDismiss(); onShowDetails() },
        )
        if (localStorageConfirmed) {
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = colors.error) },
                text = { Text("Delete permanently", color = colors.error) },
                onClick = { onDismiss(); onDeleteProject() },
            )
        }
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null, tint = colors.textSecondary) },
            text = { Text("Remove from registry", color = colors.textSecondary) },
            onClick = { onDismiss(); onRemoveProject() },
        )
    }
}
