package dev.android.ide.postgres

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

const val POSTGRES_DEVICE_RESERVE_BYTES: Long = 500_000_000L
/** One cluster may contain multiple logical databases, but their aggregate data is capped at 5 GB. */
const val POSTGRES_MAX_DATA_BYTES: Long = 5_000_000_000L
const val POSTGRES_MIN_CONTAINER_BYTES: Long = 256_000_000L
const val POSTGRES_MIN_PACKAGE_SPACE_BYTES: Long = 256L * 1024L * 1024L
const val POSTGRES_MIN_START_SPACE_BYTES: Long = 96L * 1024L * 1024L

enum class PostgresServerState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }
enum class PostgresPendingAction { INSTALL, UPDATE, INITIALIZE, START }

enum class PostgresNetworkAccess { LOCAL_ONLY, ALLOWED_HOSTS, ALL_NETWORKS }

data class PostgresConfiguration(
    val networkAccess: PostgresNetworkAccess = PostgresNetworkAccess.LOCAL_ONLY,
    /** Client IP/CIDR rules or hostname rules. Hostnames rely on PostgreSQL DNS lookup behavior. */
    val allowedHosts: List<String> = emptyList(),
    val tlsEnabled: Boolean = false,
    val port: Int = 5432,
    val maxConnections: Int = 10,
    val sharedBuffersMb: Int = 64,
    val workMemMb: Int = 4,
)

data class PostgresConnectionInfo(
    val host: String = "127.0.0.1",
    val port: Int = 5432,
    val user: String = "postgres",
    val database: String = "postgres",
)

data class PostgresStatus(
    val packageInstalled: Boolean = false,
    val clusterInitialized: Boolean = false,
    val serverState: PostgresServerState = PostgresServerState.STOPPED,
    val connection: PostgresConnectionInfo = PostgresConnectionInfo(),
    val databases: List<String> = emptyList(),
    val dataBytes: Long = 0L,
    val containerLimitBytes: Long = 1_000_000_000L,
    val availableBytes: Long = 0L,
    val credentialsAvailable: Boolean = false,
    val lastStopReason: String? = null,
    val configuration: PostgresConfiguration = PostgresConfiguration(),
    val tlsCertificateAvailable: Boolean = false,
) {
    val maximumContainerBytes: Long get() = minOf(
        POSTGRES_MAX_DATA_BYTES,
        (
            availableBytes + dataBytes - POSTGRES_DEVICE_RESERVE_BYTES -
                POSTGRES_MIN_START_SPACE_BYTES -
                (if (packageInstalled) 0L else POSTGRES_MIN_PACKAGE_SPACE_BYTES)
            ).coerceAtLeast(0L),
    )
    val dataLimitBytes: Long get() = minOf(containerLimitBytes, POSTGRES_MAX_DATA_BYTES)
    val storageWarning: Boolean get() = maximumContainerBytes < containerLimitBytes
    val nearLimit: Boolean get() = dataBytes >= containerLimitBytes * 95L / 100L
}

data class PostgresState(
    val status: PostgresStatus = PostgresStatus(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val progress: String? = null,
    val feedback: String? = null,
    val error: String? = null,
    /** Populated only after an explicit reveal/copy request; never written to disk. */
    val revealedConnectionUri: String? = null,
    val revealedConnectionDatabase: String? = null,
    val serviceLog: String? = null,
    val logLoading: Boolean = false,
    /** A newly rotated secret is held only in transient UI state until dismissed. */
    val rotatedPassword: String? = null,
)

interface PostgresStateSource {
    val state: StateFlow<PostgresState>
}

/** Public domain boundary for local PostgreSQL package, cluster, process, and data operations. */
interface PostgresService {
    suspend fun inspect(): PostgresStatus
    suspend fun readServiceLog(): String
    fun hasStopRequestFailed(): Boolean
    fun setContainerLimit(bytes: Long)
    suspend fun installPackage(onProgress: (String) -> Unit = {})
    suspend fun updatePackage(onProgress: (String) -> Unit = {})
    suspend fun initializeCluster(onProgress: (String) -> Unit = {})
    fun requestStart()
    fun requestStop()
    suspend fun uninstallPackage()
    suspend fun createDatabase(name: String)
    suspend fun deleteDatabase(name: String)
    suspend fun exportDatabase(name: String, destination: Uri, onProgress: (String) -> Unit = {})
    suspend fun importDatabase(source: Uri, newDatabaseName: String, onProgress: (String) -> Unit = {})
    suspend fun connectionUri(database: String): String?
    suspend fun clearStopReason()
    suspend fun saveConfiguration(configuration: PostgresConfiguration)
    suspend fun rotatePassword(): String
    suspend fun exportTlsCertificate(destination: Uri)
    suspend fun renewTlsCertificate()
}
