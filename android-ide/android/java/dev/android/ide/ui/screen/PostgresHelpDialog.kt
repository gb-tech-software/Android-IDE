package dev.android.ide.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.android.ide.postgres.PostgresConfiguration
import dev.android.ide.postgres.PostgresNetworkAccess

@Composable
internal fun PostgresHelpDialog(configuration: PostgresConfiguration, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect another device") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("This phone runs PostgreSQL. A client can be a laptop, desktop, or another Android phone running a PostgreSQL client. Both devices need a route to each other; internet access is not required for a local Wi-Fi or hotspot connection.", style = MaterialTheme.typography.bodySmall)
                HelpStep("1 · Put the devices on the same local network", "You can connect both devices to the same Wi-Fi. Or turn on this phone's hotspot and join it from the client device. If the client is another Android phone, join it to the PostgreSQL phone's hotspot just as you would a computer. Some hotspots isolate connected devices, so the network may still block peer connections.")
                HelpStep("2 · Choose which client devices are allowed", when (configuration.networkAccess) {
                    PostgresNetworkAccess.LOCAL_ONLY -> "The current setting is This phone only, so remote devices cannot connect yet. Open Network and server settings and choose Only listed clients or All reachable networks."
                    PostgresNetworkAccess.ALLOWED_HOSTS -> "The current allowlist is ${configuration.allowedHosts.joinToString()}. Enter the client device's own IP address or hostname, not the PostgreSQL phone's address. You can enter more than one client, one per line."
                    PostgresNetworkAccess.ALL_NETWORKS -> "The current setting accepts connection attempts from any routed network address. Prefer an allowlist on networks that are not fully trusted."
                })
                HelpStep("3 · Find the addresses", "Use this screen's possible phone addresses as a starting point, then confirm the active Wi-Fi or hotspot address in Android network settings. On the client device, check its Wi-Fi connection details to find the address to put in the allowlist. Addresses can change. IP addresses are more dependable than hostnames on a hotspot; PostgreSQL hostname rules need working DNS/reverse DNS.")
                HelpStep("4 · Save and start", "Stop PostgreSQL before saving network, port, or TLS changes. Start it again afterward. Active database clients disconnect when the server stops. The client connects to the PostgreSQL phone's address and configured port ${configuration.port}.")
                if (configuration.tlsEnabled) {
                    HelpStep("5 · Trust the TLS certificate on each client", "Choose PostgreSQL options > Export TLS certificate, then copy the public certificate to each client using a trusted method. Do not export or share the private key. With psql, use: psql \"host=<PHONE_IP> port=${configuration.port} dbname=<DATABASE> user=postgres sslmode=verify-ca sslrootcert=<PATH_TO_EXPORTED_CERT>\". verify-ca encrypts the connection and checks the exported self-signed certificate; it does not check that the host name matches. Use verify-full when connecting to an IP address present in the certificate's subject alternative names. Renew and redistribute the certificate if its IP names are stale.")
                } else {
                    HelpStep("5 · Connect from a client", "With psql installed, run: psql \"host=<PHONE_IP> port=${configuration.port} dbname=<DATABASE> user=postgres\". Enter the database password when prompted. Another Android phone can use a PostgreSQL client app or a terminal client such as psql if installed.")
                }
                HelpCaution("Security and reachability", buildString {
                    append("Network clients must authenticate with the PostgreSQL password. ")
                    if (configuration.tlsEnabled) {
                        append("TLS is enabled for remote connections permitted by the current network policy; clients must trust the exported certificate. ")
                    } else {
                        append("TLS is currently off, so database traffic is not encrypted. Enable TLS and distribute the certificate before using an untrusted network. ")
                    }
                    append("Do not expose the port to the public internet. All-network mode cannot bypass router/firewall rules, mobile carrier NAT, hotspot isolation, or Android network restrictions, and does not guarantee internet-hosted access to this phone.")
                })
                Text("If a client cannot connect, check that PostgreSQL is running, the phone IP and port are current, the client IP/hostname is included in the allowlist, and both devices can communicate on the selected Wi-Fi or hotspot. With TLS on, also check that the client can read the exported certificate and uses an SSL mode that verifies it.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun HelpStep(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HelpCaution(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
