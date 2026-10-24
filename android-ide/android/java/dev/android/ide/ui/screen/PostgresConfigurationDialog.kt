package dev.android.ide.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.android.ide.postgres.PostgresConfiguration
import dev.android.ide.postgres.PostgresNetworkAccess

@Composable
internal fun PostgresConfigurationDialog(
    configuration: PostgresConfiguration,
    serverRunning: Boolean,
    busy: Boolean,
    onSave: (PostgresConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember(configuration) { mutableStateOf(configuration.networkAccess) }
    var tlsEnabled by remember(configuration) { mutableStateOf(configuration.tlsEnabled) }
    var allowedHosts by remember(configuration) { mutableStateOf(configuration.allowedHosts.joinToString("\n")) }
    var port by remember(configuration) { mutableStateOf(configuration.port.toString()) }
    var maxConnections by remember(configuration) { mutableStateOf(configuration.maxConnections.toString()) }
    var sharedBuffers by remember(configuration) { mutableStateOf(configuration.sharedBuffersMb.toString()) }
    var workMem by remember(configuration) { mutableStateOf(configuration.workMemMb.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network and server settings") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Choose which devices can reach PostgreSQL. The server still requires the database password with SCRAM authentication.", style = MaterialTheme.typography.bodySmall)
                PostgresAccessChoice(mode, PostgresNetworkAccess.LOCAL_ONLY, "This phone only", "Keep TCP access on this device.") { mode = it }
                PostgresAccessChoice(mode, PostgresNetworkAccess.ALLOWED_HOSTS, "Only listed clients", "Manually allow client IP addresses, CIDR ranges, or hostnames.") { mode = it }
                PostgresAccessChoice(mode, PostgresNetworkAccess.ALL_NETWORKS, "All reachable networks", "Accept attempts from any routed IPv4/IPv6 address; password authentication remains required.") { mode = it }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Require TLS for remote clients", style = MaterialTheme.typography.titleSmall)
                        Text("Encrypt network traffic. The app creates a self-signed server certificate; export it and trust it on each client.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = tlsEnabled, onCheckedChange = { tlsEnabled = it })
                }
                if (tlsEnabled) {
                    Text(
                        "Plaintext remote connections will be rejected. After saving, export the server certificate and configure each client to trust it. The certificate covers the phone IP addresses available when it is created; renew it after changing networks if you need hostname verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (mode == PostgresNetworkAccess.ALLOWED_HOSTS) {
                    OutlinedTextField(
                        value = allowedHosts,
                        onValueChange = { allowedHosts = it.take(800) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Client IPs, networks, or hostnames") },
                        supportingText = { Text("Enter one client per line. Examples: 192.168.43.20, 2001:db8::15, 192.168.43.0/24, or tablet.home.example. A single IP limits access to that device; a CIDR range such as /24 covers a subnet. Works with a computer or another Android phone. On a hotspot, enter the client's address, not this phone's. PostgreSQL hostname rules need forward and reverse DNS; hotspots often lack it, so IP addresses are more reliable.") },
                        minLines = 2,
                    )
                }
                if (mode == PostgresNetworkAccess.ALL_NETWORKS) {
                    PostgresConfigNotice(
                        "High exposure",
                        if (tlsEnabled) "Any device that can route to this phone can attempt to connect. TLS encrypts remote traffic, but you still need to distribute and trust the certificate. Use this only on a trusted private network or hotspot; do not expose the port to the public internet."
                        else "Any device that can route to this phone can attempt to connect. Traffic is not TLS-encrypted. Use this only on a trusted private network or hotspot; do not expose the port to the public internet.",
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Port") }, supportingText = { Text("1024–65535") }, singleLine = true)
                    OutlinedTextField(maxConnections, { maxConnections = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Connections") }, supportingText = { Text("1–50") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sharedBuffers, { sharedBuffers = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Shared buffers (MB)") }, supportingText = { Text("16–256") }, singleLine = true)
                    OutlinedTextField(workMem, { workMem = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Work memory (MB)") }, supportingText = { Text("Per query operation · 1–32") }, singleLine = true)
                }
                Text("These settings are saved to the app-managed PostgreSQL configuration. Stop the server before saving; restart it to apply changes. Higher connection and memory values increase phone resource use.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (serverRunning) Text("Stop PostgreSQL first. Existing clients will disconnect when you stop it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val hosts = allowedHosts.split('\n', ',', ' ', '\t').filter(String::isNotBlank)
                    onSave(
                        configuration.copy(
                            networkAccess = mode,
                            allowedHosts = hosts,
                            tlsEnabled = tlsEnabled,
                            port = port.toIntOrNull() ?: configuration.port,
                            maxConnections = maxConnections.toIntOrNull() ?: configuration.maxConnections,
                            sharedBuffersMb = sharedBuffers.toIntOrNull() ?: configuration.sharedBuffersMb,
                            workMemMb = workMem.toIntOrNull() ?: configuration.workMemMb,
                        ),
                    )
                    onDismiss()
                },
                enabled = !busy && !serverRunning && port.toIntOrNull()?.let { it in 1024..65535 } == true &&
                    maxConnections.toIntOrNull()?.let { it in 1..50 } == true && sharedBuffers.toIntOrNull()?.let { it in 16..256 } == true &&
                    workMem.toIntOrNull()?.let { it in 1..32 } == true && (mode != PostgresNetworkAccess.ALLOWED_HOSTS || allowedHosts.isNotBlank()),
            ) { Text("Save settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PostgresAccessChoice(
    selected: PostgresNetworkAccess,
    value: PostgresNetworkAccess,
    title: String,
    summary: String,
    onSelect: (PostgresNetworkAccess) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) },
        colors = CardDefaults.cardColors(containerColor = if (selected == value) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == value, onClick = { onSelect(value) })
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PostgresConfigNotice(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
