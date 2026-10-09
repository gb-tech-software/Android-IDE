package dev.android.ide.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import dev.android.ide.contracts.Surface
import dev.android.ide.ui.theme.LocalIdeColors

/** Home is an orientation surface only: it owns no project, process, or tab summary. */
@Composable
fun HomeSurface(
    onNavigate: (Surface) -> Unit,
    onOpenNavigation: () -> Unit,
    onExit: () -> Unit,
    onFeedback: (String) -> Unit,
    crashRecoveryCount: Int = 0,
    crashReportCount: Int = 0,
    onOpenCrashConsole: () -> Unit = {},
) {
    val colors = LocalIdeColors.current
    val destinations = buildList {
        add(HomeDestinationData(Icons.Default.FolderOpen, "Projects", "Manage your projects and workspace", true) { onNavigate(Surface.PROJECTS) })
        add(HomeDestinationData(Icons.Default.Code, "Editor", "Edit code and manage project files", true) { onNavigate(Surface.EDITOR) })
        add(HomeDestinationData(Icons.Default.Terminal, "Terminal", "Access your project and workspace from a command line interface", true) { onNavigate(Surface.TERMINAL) })
        add(HomeDestinationData(Icons.Default.Language, "Browser", "Browse the web and preview your code.", true) { onNavigate(Surface.BROWSER) })
        add(HomeDestinationData(Icons.Default.Settings, "Settings", "Customize your workspace and app preferences", true) { onNavigate(Surface.SETTINGS) })
        val recoveryText = when (crashRecoveryCount) {
            1 -> "1 unsaved file is available to restore"
            in 2..Int.MAX_VALUE -> "$crashRecoveryCount unsaved files are available to restore"
            else -> null
        }
        val reportText = when (crashReportCount) {
            1 -> "1 crash report is available to review"
            in 2..Int.MAX_VALUE -> "$crashReportCount crash reports are available to review"
            else -> null
        }
        val diagnosticContext = listOfNotNull(recoveryText, reportText).joinToString("; ")
        add(HomeDestinationData(
            if (crashRecoveryCount > 0 || crashReportCount > 0) Icons.Default.WarningAmber else Icons.Default.BugReport,
            "Diagnostics",
            if (diagnosticContext.isBlank()) "Inspect application logs and crash reports."
            else "Your last session needs attention — $diagnosticContext",
            true,
            isWarning = crashRecoveryCount > 0 || crashReportCount > 0,
        ) { onOpenCrashConsole() })
    }

    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopAppBar(
            title = { Text("Android IDE") },
            navigationIcon = {
                IconButton(onClick = onOpenNavigation) {
                    Icon(Icons.Default.Menu, contentDescription = "Open sidebar")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val columns = if (maxWidth < 560.dp) {
                    2
                } else {
                    ceil(((maxWidth.value + 12f) / (250f + 12f)).toDouble()).toInt().coerceAtLeast(2)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(destinations, key = { it.title }) { destination ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            HomeDestination(destination, Modifier.widthIn(max = 250.dp))
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Exit Android IDE") }
        }
    }
}

@Composable
private fun HomeDestination(destination: HomeDestinationData, modifier: Modifier = Modifier) {
    val colors = LocalIdeColors.current
    val accent = if (destination.isWarning) colors.warning else colors.primary
    Card(
        onClick = destination.onClick,
        enabled = destination.enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 176.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (destination.isWarning) colors.warningContainer else colors.surface,
            contentColor = if (destination.isWarning) colors.onWarningContainer else colors.textPrimary,
            disabledContainerColor = colors.surface.copy(alpha = 0.55f),
            disabledContentColor = colors.textDisabled,
        ),
    ) {
        BoxWithConstraints {
            Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = destination.title,
                    tint = if (destination.enabled) accent else colors.textSecondary,
                    modifier = Modifier.size((this@BoxWithConstraints.maxWidth * 0.30f).coerceIn(48.dp, 72.dp)),
                )
            }
            Text(
                destination.title,
                style = MaterialTheme.typography.titleLarge,
                color = if (destination.isWarning) colors.onWarningContainer else colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                destination.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (destination.isWarning) colors.onWarningContainer else colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        }
    }
}

private data class HomeDestinationData(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val isWarning: Boolean = false,
    val onClick: () -> Unit,
)
