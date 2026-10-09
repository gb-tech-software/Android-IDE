package dev.android.ide.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.android.ide.data.model.GitDetails
import java.net.URI

/** Read-only repository metadata sourced from the project's Git directory. */
@Composable
fun ProjectGitStatusCard(
    git: GitDetails?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Git", style = MaterialTheme.typography.titleMedium)
            Text(
                if (git != null) "Git repository detected" else "No Git repository detected",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (git != null) {
                GitStatusLine("Current branch", git.currentBranch ?: "Detached or unavailable")
                if (git.branches.isNotEmpty()) {
                    GitStatusLine("Local branches (${git.branches.size})", git.branches.take(6).joinToString(", "))
                    if (git.branches.size > 6) {
                        Text("And ${git.branches.size - 6} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                git.latestCommitMessage?.let { GitStatusLine("Last commit message", it) }
                git.latestCommitAuthor?.let { GitStatusLine("Last commit author", it) }
                git.headCommit?.let { GitStatusLine("Last commit hash", it) }
                if (git.remotes.isEmpty()) {
                    GitStatusLine("Remotes", "None configured")
                } else {
                    Text("Remotes", style = MaterialTheme.typography.labelLarge)
                    git.remotes.take(5).forEach { remote ->
                        GitStatusLine(remote.name, safeGitRemoteUrl(remote.url))
                    }
                    if (git.remotes.size > 5) {
                        Text("And ${git.remotes.size - 5} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GitStatusLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Avoid exposing embedded credentials, URL queries, or fragments in status surfaces. */
internal fun safeGitRemoteUrl(rawUrl: String): String {
    val withoutQueryOrFragment = rawUrl.trim().substringBefore('?').substringBefore('#')
    val withoutScpPassword = withoutQueryOrFragment.replace(Regex("^([^/@:]+):[^/@]+@"), "\$1@")
    val uri = runCatching { URI(withoutScpPassword) }.getOrNull()
        ?: return withoutScpPassword.replace(Regex("(?<=://)[^/@]+@"), "")
    if (uri.scheme == null || uri.host == null) {
        return withoutScpPassword.replace(Regex("(?<=://)[^/@]+@"), "")
    }
    val safeUserInfo = uri.userInfo?.takeIf { uri.scheme.equals("ssh", ignoreCase = true) && it == "git" }
    return runCatching {
        URI(uri.scheme, safeUserInfo, uri.host, uri.port, uri.path, null, null).toASCIIString()
    }.getOrDefault(withoutScpPassword.replace(Regex("(?<=://)[^/@]+@"), ""))
}
