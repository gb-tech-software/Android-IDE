package dev.android.ide.postgres

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/** Validation and rendering rules for the app-managed PostgreSQL server configuration. */
object PostgresConfigurationPolicy {
    fun validate(configuration: PostgresConfiguration): PostgresConfiguration {
        require(configuration.port in 1024..65535) { "Use a PostgreSQL port between 1024 and 65535" }
        require(configuration.maxConnections in 1..50) { "Maximum connections must be between 1 and 50" }
        require(configuration.sharedBuffersMb in 16..256) { "Shared buffers must be between 16 and 256 MB" }
        require(configuration.workMemMb in 1..32) { "Per-operation work memory must be between 1 and 32 MB" }
        val hosts = configuration.allowedHosts
            .flatMap { it.split(',', '\n', '\r', '\t', ' ') }
            .filter(String::isNotBlank)
            .map(::normalizeNetwork)
            .distinct()
        if (configuration.networkAccess == PostgresNetworkAccess.ALLOWED_HOSTS) {
            require(hosts.isNotEmpty()) { "Add at least one client IP address, CIDR network, or hostname" }
        }
        return configuration.copy(allowedHosts = hosts)
    }

    fun listenAddresses(configuration: PostgresConfiguration): String =
        if (configuration.networkAccess == PostgresNetworkAccess.LOCAL_ONLY) "127.0.0.1" else "*"

    fun hostRules(configuration: PostgresConfiguration): List<String> = when (configuration.networkAccess) {
        PostgresNetworkAccess.LOCAL_ONLY -> emptyList()
        PostgresNetworkAccess.ALLOWED_HOSTS -> configuration.allowedHosts.map {
            "host${if (configuration.tlsEnabled) "ssl" else ""} all all $it scram-sha-256"
        }
        PostgresNetworkAccess.ALL_NETWORKS -> listOf(
            "host${if (configuration.tlsEnabled) "ssl" else ""} all all 0.0.0.0/0 scram-sha-256",
            "host${if (configuration.tlsEnabled) "ssl" else ""} all all ::0/0 scram-sha-256",
        )
    }

    private fun normalizeNetwork(value: String): String {
        val addressPart = value.substringBefore('/')
        if (!addressPart.contains(':') && !addressPart.matches(Regex("[0-9.]+"))) {
            require(!value.contains('/')) { "CIDR suffixes are for numeric IP addresses; enter a hostname without a suffix: $value" }
            return normalizeHostname(addressPart)
        }
        if (!addressPart.contains(':')) {
            val octets = addressPart.split('.')
            require(octets.size == 4 && octets.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }) {
                "Enter IPv4 addresses as four decimal octets: $value"
            }
        }
        val address = runCatching { InetAddress.getByName(addressPart) }
            .getOrElse { throw IllegalArgumentException("Invalid IP address: $value") }
        val prefix = value.substringAfter('/', if (address is Inet4Address) "32" else "128").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid CIDR prefix: $value")
        val bytes = address.address
        val maximum = bytes.size * 8
        require(prefix in 0..maximum) { "CIDR prefix must be between 0 and $maximum: $value" }
        val networkBytes = bytes.copyOf()
        for (index in networkBytes.indices) {
            val bitsToKeep = (prefix - index * 8).coerceIn(0, 8)
            val mask = if (bitsToKeep == 0) 0 else (0xff shl (8 - bitsToKeep)) and 0xff
            networkBytes[index] = (networkBytes[index].toInt() and mask).toByte()
        }
        val network = InetAddress.getByAddress(networkBytes).hostAddress.substringBefore('%')
        return "$network/$prefix"
    }

    private fun normalizeHostname(value: String): String {
        val suffixRule = value.startsWith('.')
        val hostname = value.removePrefix(".").removeSuffix(".").lowercase(Locale.ROOT)
        val labels = hostname.split('.')
        require(hostname.length in 1..253 && labels.all { label ->
            label.length in 1..63 && label.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
        }) { "Enter a valid ASCII DNS hostname (optionally prefix a domain suffix with a dot): $value" }
        return (if (suffixRule) "." else "") + hostname
    }
}
