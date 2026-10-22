package dev.android.ide.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.android.ide.postgres.PostgresServerState
import dev.android.ide.postgres.PostgresState
import dev.android.ide.postgres.PostgresViewModel
import dev.android.ide.postgres.POSTGRES_DEVICE_RESERVE_BYTES
import dev.android.ide.postgres.POSTGRES_MAX_DATA_BYTES
import dev.android.ide.postgres.POSTGRES_MIN_CONTAINER_BYTES
import java.util.Locale

@Composable
fun PostgresSurface(
    viewModel: PostgresViewModel,
    onOpenNavigation: () -> Unit,
    projectId: String? = null,
    projectName: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var packageMenuOpen by remember { mutableStateOf(false) }
    var databaseDialog by remember { mutableStateOf(false) }
    var databaseName by remember { mutableStateOf("app_db") }
    var databaseToDelete by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var logDialogOpen by remember { mutableStateOf(false) }
    var restoreDialog by remember { mutableStateOf(false) }
    var restoreDatabaseName by remember { mutableStateOf("restored_db") }
    var pendingExportName by remember { mutableStateOf<String?>(null) }
    var selectedDatabase by rememberSaveable(projectId) {
        mutableStateOf(viewModel.selectedDatabaseForProject(projectId) ?: "postgres")
    }
    var containerMegabytes by remember(state.status.containerLimitBytes) {
        mutableStateOf((state.status.containerLimitBytes / 1_000_000L).toString())
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = pendingExportName
        if (uri != null && name != null) viewModel.exportDatabase(name, uri)
        pendingExportName = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importDatabase(uri, restoreDatabaseName)
    }

    LaunchedEffect(projectId, state.status.databases) {
        val available = state.status.databases
        if (available.isNotEmpty() && selectedDatabase !in available) {
            selectedDatabase = viewModel.selectedDatabaseForProject(projectId)
                ?.takeIf { it in available }
                ?: "postgres".takeIf { it in available }
                ?: available.first()
        }
        viewModel.selectDatabaseForProject(projectId, selectedDatabase)
    }

    fun selectDatabase(name: String) {
        selectedDatabase = name
        viewModel.selectDatabaseForProject(projectId, name)
        viewModel.loadConnectionUri(name)
    }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbar.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Local PostgreSQL") },
                navigationIcon = { IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, "Open navigation") } },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.busy) { Icon(Icons.Default.Refresh, "Refresh status") }
                    IconButton(onClick = { packageMenuOpen = true }, enabled = !state.busy) { Icon(Icons.Default.MoreVert, "PostgreSQL options") }
                    DropdownMenu(expanded = packageMenuOpen, onDismissRequest = { packageMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("View recent logs") },
                            leadingIcon = { Icon(Icons.Default.Terminal, null) },
                            enabled = state.status.packageInstalled,
                            onClick = { packageMenuOpen = false; logDialogOpen = true; viewModel.loadServiceLog() },
                        )
                        DropdownMenuItem(
                            text = { Text("Update PostgreSQL tools") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            enabled = state.status.packageInstalled && state.status.serverState != PostgresServerState.RUNNING && !state.busy,
                            onClick = { packageMenuOpen = false; viewModel.updatePackage() },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove binaries · keep data") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            enabled = state.status.packageInstalled && state.status.serverState != PostgresServerState.RUNNING && !state.busy,
                            onClick = { packageMenuOpen = false; confirmUninstall = true },
                        )
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PostgresStatusCard(
                    state.status.serverState,
                    state.status.packageInstalled,
                    state.status.clusterInitialized,
                    state.busy,
                    onInstall = { viewModel.installPackage() },
                    onInitialize = { viewModel.initializeCluster() },
                    onStart = { viewModel.startServer() },
                    onStop = { viewModel.stopServer() },
                )
            }
            if (!state.loading && state.status.storageWarning) {
                item {
                    NoticeCard(
                        icon = Icons.Default.WarningAmber,
                        title = "The configured PostgreSQL budget exceeds current headroom",
                        body = "${formatBytes(state.status.availableBytes)} is free, which supports at most ${formatBytes(state.status.maximumContainerBytes)} for PostgreSQL while retaining 0.5 GB for Android. Lower the configured budget or free device space before continuing.",
                    )
                }
            }
            if (state.status.nearLimit) {
                item {
                    NoticeCard(
                        icon = Icons.Default.WarningAmber,
                        title = "PostgreSQL is near its data safety limit",
                        body = "${formatBytes(state.status.dataBytes)} is used against the selected ${formatBytes(state.status.containerLimitBytes)} budget. The foreground service requests a clean stop at 95%. This is monitored; Android does not provide a per-directory quota here, so a fast write may briefly exceed the threshold.",
                    )
                }
            }
            state.status.lastStopReason?.let { reason ->
                item {
                    NoticeCard(
                        icon = Icons.Default.ErrorOutline,
                        title = "PostgreSQL was paused",
                        body = reason,
                        trailing = { TextButton(onClick = { viewModel.clearStopReason() }) { Text("Dismiss") } },
                    )
                }
            }
            if (state.busy || state.loading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.progress ?: if (state.loading) "Checking PostgreSQL…" else "Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!state.loading && !state.status.clusterInitialized) item {
                ContainerBudgetCard(
                    configuredBytes = state.status.containerLimitBytes,
                    maximumBytes = state.status.maximumContainerBytes,
                    usedBytes = state.status.dataBytes,
                    valueMb = containerMegabytes,
                    onValueChange = { containerMegabytes = it.filter(Char::isDigit).take(8) },
                    onSave = { viewModel.saveContainerLimit(containerMegabytes) },
                    enabled = !state.busy && !state.loading,
                )
            }
            if (state.status.clusterInitialized && state.status.packageInstalled) {
                item {
                    StorageCard(
                        usedBytes = state.status.dataBytes,
                        limitBytes = state.status.containerLimitBytes,
                        availableBytes = state.status.availableBytes,
                        serverRunning = state.status.serverState == PostgresServerState.RUNNING,
                    )
                }
                item {
                    ConnectionCard(
                        state = state,
                        selectedDatabase = selectedDatabase,
                        onReveal = { viewModel.loadConnectionUri(selectedDatabase) },
                        onHide = viewModel::hideConnectionUri,
                        onCopy = { uri -> copyToClipboard(context, "PostgreSQL connection URI", uri); viewModel.reportFeedback("Connection URI copied") },
                        onCopyPsqlCommand = { uri -> copyToClipboard(context, "PostgreSQL psql command", "psql '${uri}'"); viewModel.reportFeedback("psql connection command copied") },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Databases", style = MaterialTheme.typography.titleMedium)
                            Text(
                                projectName?.let { "Selected for $it" } ?: "Select a database to connect",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { restoreDialog = true }, enabled = !state.busy && state.status.serverState == PostgresServerState.RUNNING) {
                                Icon(Icons.Default.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Restore")
                            }
                            Button(onClick = { databaseName = "app_db"; databaseDialog = true }, enabled = !state.busy && state.status.serverState == PostgresServerState.RUNNING) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("New")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Use one named database per project when you need separation. These logical databases share one PostgreSQL cluster and the aggregate ${formatBytes(POSTGRES_MAX_DATA_BYTES)} data cap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.status.serverState != PostgresServerState.RUNNING) {
                    item { Text("Start PostgreSQL to manage databases and connect using a client you choose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (state.status.databases.isEmpty()) {
                    item { Text("No databases were returned. Refresh status or inspect the service log.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(state.status.databases, key = { "pg-db-$it" }) { name ->
                        DatabaseRow(
                            name = name,
                            enabled = !state.busy,
                            builtIn = name == "postgres",
                            selected = selectedDatabase == name,
                            onSelect = { selectDatabase(name) },
                            onConnect = { selectDatabase(name) },
                            onExport = {
                                pendingExportName = name
                                exportLauncher.launch("$name-${System.currentTimeMillis()}.dump")
                            },
                            onDelete = { databaseToDelete = name },
                        )
                    }
                }
                item {
                    Text(
                        "Data directory: app-private storage · ${state.status.connection.host}:${state.status.connection.port} · the selected limit is monitored, not a filesystem-enforced quota. Package binaries and exported backups are outside it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (databaseDialog) {
        AlertDialog(
            onDismissRequest = { databaseDialog = false },
            title = { Text("Create database") },
            text = {
                OutlinedTextField(
                    value = databaseName,
                    onValueChange = { databaseName = it },
                    label = { Text("Database name") },
                    supportingText = { Text("Start with a letter or underscore; use up to 63 ASCII letters, digits, or underscores.") },
                    singleLine = true,
                )
            },
            confirmButton = { Button(onClick = { databaseDialog = false; viewModel.createDatabase(databaseName) }, enabled = databaseName.isNotBlank() && !state.busy) { Text("Create") } },
            dismissButton = { TextButton(onClick = { databaseDialog = false }) { Text("Cancel") } },
        )
    }

    databaseToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { databaseToDelete = null },
            icon = { Icon(Icons.Default.WarningAmber, null) },
            title = { Text("Delete database $name?") },
            text = { Text("This permanently deletes the database and its contents. Export a backup first if you may need it later.") },
            confirmButton = { Button(onClick = { databaseToDelete = null; viewModel.deleteDatabase(name) }) { Text("Delete database") } },
            dismissButton = { TextButton(onClick = { databaseToDelete = null }) { Text("Cancel") } },
        )
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            icon = { Icon(Icons.Default.WarningAmber, null) },
            title = { Text("Remove PostgreSQL binaries?") },
            text = { Text("This removes the Termux PostgreSQL package only. The app-private database cluster and credentials will be retained. The server must be stopped first.") },
            confirmButton = { Button(onClick = { confirmUninstall = false; viewModel.uninstallPackage() }) { Text("Remove binaries") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } },
        )
    }

    if (restoreDialog) {
        AlertDialog(
            onDismissRequest = { restoreDialog = false },
            title = { Text("Restore into a new database") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The selected custom-format backup will be restored into a new database. Existing databases are never overwritten.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = restoreDatabaseName,
                        onValueChange = { restoreDatabaseName = it },
                        label = { Text("New database name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { restoreDialog = false; importLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*")) }, enabled = restoreDatabaseName.isNotBlank() && !state.busy) { Text("Choose backup") }
            },
            dismissButton = { TextButton(onClick = { restoreDialog = false }) { Text("Cancel") } },
        )
    }

    if (logDialogOpen) {
        AlertDialog(
            onDismissRequest = { logDialogOpen = false; viewModel.dismissServiceLog() },
            title = { Text("Recent PostgreSQL logs") },
            text = {
                if (state.logLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading recent output…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        state.serviceLog ?: "No log output is available.",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.loadServiceLog() }) { Text("Refresh") } },
            dismissButton = { TextButton(onClick = { logDialogOpen = false; viewModel.dismissServiceLog() }) { Text("Close") } },
        )
    }
}

@Composable
private fun PostgresStatusCard(
    serverState: PostgresServerState,
    packageInstalled: Boolean,
    clusterInitialized: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onInitialize: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Local database service", style = MaterialTheme.typography.titleSmall)
                    val subtitle = when {
                        !packageInstalled -> "PostgreSQL tools are not installed"
                        !clusterInitialized -> "Package installed · cluster not initialized"
                        else -> when (serverState) {
                            PostgresServerState.RUNNING -> "Running · accepting local connections"
                            PostgresServerState.STARTING -> "Starting the server…"
                            PostgresServerState.STOPPING -> "Stopping the server…"
                            PostgresServerState.ERROR -> "Server reported an error"
                            PostgresServerState.STOPPED -> "Installed · server stopped"
                        }
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(
                    if (serverState == PostgresServerState.RUNNING) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (serverState == PostgresServerState.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !packageInstalled -> Button(onClick = onInstall, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Install tools") }
                    !clusterInitialized -> Button(onClick = onInitialize, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Initialize cluster") }
                    serverState == PostgresServerState.RUNNING -> OutlinedButton(onClick = onStop, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Stop server")
                    }
                    else -> Button(onClick = onStart, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Start server")
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerBudgetCard(
    configuredBytes: Long,
    maximumBytes: Long,
    usedBytes: Long,
    valueMb: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    enabled: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PostgreSQL storage budget", style = MaterialTheme.typography.titleSmall)
            Text(
                "Choose the shared PostgreSQL data budget before initializing the cluster. All named databases count together, up to ${formatBytes(POSTGRES_MAX_DATA_BYTES)}. The suggested maximum preserves ${formatBytes(POSTGRES_DEVICE_RESERVE_BYTES)} for Android plus startup and package-install headroom. This value cannot be changed after initialization.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = valueMb,
                onValueChange = onValueChange,
                label = { Text("Container limit (MB)") },
                supportingText = { Text("Current ${formatBytes(configuredBytes)} · used ${formatBytes(usedBytes)} · minimum ${formatBytes(POSTGRES_MIN_CONTAINER_BYTES)}") },
                singleLine = true,
                enabled = enabled,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onValueChange((maximumBytes / 1_000_000L).toString()) },
                    enabled = enabled && maximumBytes >= POSTGRES_MIN_CONTAINER_BYTES,
                ) { Text("Use suggested maximum") }
                Button(
                    onClick = onSave,
                    enabled = enabled && valueMb.toLongOrNull()?.let { it >= POSTGRES_MIN_CONTAINER_BYTES / 1_000_000L } == true,
                ) { Text("Save for setup") }
            }
            Text(
                "A background monitor requests a clean stop near 95% of this budget. Android does not enforce a per-directory quota, so fast writes may briefly exceed it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageCard(usedBytes: Long, limitBytes: Long, availableBytes: Long, serverRunning: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Database storage", style = MaterialTheme.typography.titleSmall)
                Text("${formatBytes(usedBytes)} / ${formatBytes(limitBytes)}", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(
                progress = (usedBytes.toFloat() / limitBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                color = if (usedBytes >= limitBytes * 95L / 100L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                "${formatBytes(availableBytes)} free on device${if (serverRunning) " · automatic safety monitoring active" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    state: PostgresState,
    selectedDatabase: String,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: (String) -> Unit,
    onCopyPsqlCommand: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleSmall)
            Text("Host  ${state.status.connection.host}    Port  ${state.status.connection.port}", style = MaterialTheme.typography.bodySmall)
            Text("User  ${state.status.connection.user}    Database  ${state.revealedConnectionDatabase ?: selectedDatabase}", style = MaterialTheme.typography.bodySmall)
            Text("Connect using a client you choose. The server listens only on this device's 127.0.0.1; it is not exposed to your Wi-Fi/LAN. The app does not launch or manage clients. psql accepts the URI as a connection argument; Node's pg driver accepts it as connectionString.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.status.credentialsAvailable) {
                Text("The protected connection credential is unavailable, so a password-bearing URI cannot be shown. Export any important data before attempting credential recovery.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = if (state.revealedConnectionUri == null) onReveal else onHide) {
                    Icon(if (state.revealedConnectionUri == null) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp)); Text(if (state.revealedConnectionUri == null) "Show connection URI" else "Hide URI")
                }
                if (state.revealedConnectionUri != null) {
                    IconButton(onClick = { onCopy(state.revealedConnectionUri) }) { Icon(Icons.Default.ContentCopy, "Copy connection URI") }
                    TextButton(onClick = { onCopyPsqlCommand(state.revealedConnectionUri) }) { Text("Copy psql command") }
                }
            }
            state.revealedConnectionUri?.let { uri ->
                Text(
                    uri,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("This URI contains the generated database password. Share it only with trusted clients. Use it as a connection string or pass it to psql.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DatabaseRow(
    name: String,
    enabled: Boolean,
    builtIn: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var actionMenuOpen by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                if (builtIn) Text("Default database", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
            IconButton(onClick = { actionMenuOpen = true }, enabled = enabled) {
                Icon(Icons.Default.MoreVert, "Actions for $name")
            }
            DropdownMenu(expanded = actionMenuOpen, onDismissRequest = { actionMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Select for this project") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = { actionMenuOpen = false; onSelect() },
                )
                DropdownMenuItem(
                    text = { Text("Show connection details") },
                    leadingIcon = { Icon(Icons.Default.Visibility, null) },
                    onClick = { actionMenuOpen = false; onConnect() },
                )
                DropdownMenuItem(
                    text = { Text("Export backup") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { actionMenuOpen = false; onExport() },
                )
                if (!builtIn) {
                    DropdownMenuItem(
                        text = { Text("Delete database") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { actionMenuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            trailing?.invoke()
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun formatBytes(value: Long): String = when {
    value >= 1_000_000_000L -> String.format(Locale.ROOT, "%.2f GB", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format(Locale.ROOT, "%.0f MB", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.ROOT, "%.0f kB", value / 1_000.0)
    else -> "$value B"
}
