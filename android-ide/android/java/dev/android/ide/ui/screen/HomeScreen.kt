package dev.android.ide.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.viewmodel.model.AppScreen

/** Home is an orientation and navigation entry point, not a data dashboard. */
@Composable
fun HomeScreen(
    hasProject: Boolean,
    onNavigate: (AppScreen) -> Unit,
) {
    val colors = LocalIdeColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text("Android IDE", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(
            "Choose a workspace area to begin.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Button(onClick = { onNavigate(AppScreen.PROJECTS) }) { Text("Projects") }
        OutlinedButton(
            onClick = { onNavigate(AppScreen.EDITOR) },
            enabled = hasProject,
        ) { Text(if (hasProject) "Editor" else "Open a project for Editor") }
        OutlinedButton(onClick = { onNavigate(AppScreen.TERMINAL) }) { Text("Terminal") }
        OutlinedButton(onClick = { onNavigate(AppScreen.BROWSER) }) { Text("Browser") }
        OutlinedButton(onClick = { onNavigate(AppScreen.SETTINGS) }) { Text("Settings") }
    }
}

@Composable
fun UnavailableDomainScreen(
    title: String,
    description: String,
    onBackToHome: () -> Unit,
) {
    val colors = LocalIdeColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        OutlinedButton(onClick = onBackToHome) { Text("Back to Home") }
    }
}
