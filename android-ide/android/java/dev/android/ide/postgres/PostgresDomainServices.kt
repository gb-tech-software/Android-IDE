package dev.android.ide.postgres

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.ContextCompat
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.runtime.BundledTermuxRuntimeInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.NetworkInterface
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private PostgreSQL paths. Cluster data is intentionally outside the replaceable Termux prefix. */
class PostgresStorageAdapter(context: Context) {
    val root = File(context.filesDir, "postgresql")
    val data = File(root, "data")
    val socketDirectory = File(root, "socket")
    val log = File(root, "postgresql.log")
    val passwordFile = File(root, ".pgpass")
    val serverCertificate = File(root, "postgres-server.crt")
    val serverPrivateKey = File(root, "postgres-server.key")
    private val temporaryPasswordFile = File(root, ".initdb-password")

    fun availableBytes(): Long = root.parentFile?.usableSpace ?: root.usableSpace

    fun usedBytes(): Long {
        if (!root.exists()) return 0L
        var total = 0L
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile) total = (total + attributes.size()).coerceAtMost(Long.MAX_VALUE)
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    fun ensurePrivateDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create ${directory.name}")
        Os.chmod(directory.absolutePath, 0b111000000)
        if (!directory.isDirectory || !directory.canRead() || !directory.canWrite()) {
            throw IOException("PostgreSQL storage is not readable and writable")
        }
    }

    fun writePrivate(file: File, value: String) {
        file.parentFile?.let(::ensurePrivateDirectory)
        file.writeText(value, Charsets.UTF_8)
        Os.chmod(file.absolutePath, 0b110000000)
    }

    fun createTemporaryPasswordFile(password: String): File {
        ensurePrivateDirectory(root)
        writePrivate(temporaryPasswordFile, "$password\n")
        return temporaryPasswordFile
    }

    fun removeTemporaryPasswordFile() { temporaryPasswordFile.delete() }

    fun rotateManagerLog() {
        if (!log.isFile || log.length() <= MAX_MANAGER_LOG_BYTES) return
        val previous = File(root, "postgresql-previous.log")
        previous.delete()
        if (!log.renameTo(previous)) throw IOException("Could not rotate the PostgreSQL service log")
    }

    fun pruneDatabaseLogs(keep: Int = 14) {
        File(data, "log").listFiles()
            ?.filter { it.isFile && it.name.startsWith("postgresql-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
        listOf("command.log" to MAX_COMMAND_LOG_BYTES, "restore.log" to MAX_RESTORE_LOG_BYTES).forEach { (name, limit) ->
            val target = File(root, name)
            if (target.isFile && target.length() > limit) {
                try {
                    RandomAccessFile(target, "rw").use { file ->
                    file.seek(file.length() - limit)
                    val remaining = ByteArray(limit.toInt())
                    file.readFully(remaining)
                    file.setLength(0)
                    file.write(remaining)
                    }
                } catch (error: IOException) {
                    throw IOException("Could not rotate the PostgreSQL $name output log", error)
                }
            }
        }
    }

    private companion object {
        const val MAX_MANAGER_LOG_BYTES = 4L * 1024L * 1024L
        const val MAX_COMMAND_LOG_BYTES = 2L * 1024L * 1024L
        const val MAX_RESTORE_LOG_BYTES = 4L * 1024L * 1024L
    }
}

/** Stores the cluster's generated SCRAM password encrypted with an Android Keystore key. */
class PostgresCredentialVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("postgres_credentials", Context.MODE_PRIVATE)
    private val alias = "android_ide_postgres_password_v1"

    @Synchronized
    fun read(): String? {
        return runCatching {
            val encoded = preferences.getString(PASSWORD_KEY, null) ?: return null
            val payload = Base64.getDecoder().decode(encoded)
            if (payload.size <= IV_LENGTH) return null
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val cipherText = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(cipherText).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun create(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        store(password)
        return password
    }

    @Synchronized
    fun store(password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(PASSWORD_KEY, Base64.getEncoder().encodeToString(encrypted)).commit()) {
            "The PostgreSQL credential could not be saved"
        }
    }

    @Synchronized
    fun clear() { preferences.edit().remove(PASSWORD_KEY).commit() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PASSWORD_KEY = "cluster_password_gcm_v1"
        const val IV_LENGTH = 12
    }
}

/** Coordinates PostgreSQL's Termux package, private cluster, daemon, and portable database files. */
class PostgresApplicationService(
    context: Context,
    private val runtime: BundledTermuxRuntimeInstaller,
) : PostgresService {
    private val appContext = context.applicationContext
    private val storage = PostgresStorageAdapter(appContext)
    private val credentials = PostgresCredentialVault(appContext)
    private val preferences = appContext.getSharedPreferences("postgres_state", Context.MODE_PRIVATE)
    private val mutationLock = Mutex()
    private val prefix get() = runtime.prefix()
    private val home get() = runtime.home()
    private val shell get() = File(prefix, "bin/sh")
    private val pgCtl get() = File(prefix, "bin/pg_ctl")
    private val initDb get() = File(prefix, "bin/initdb")
    private val psql get() = File(prefix, "bin/psql")
    private val pgDump get() = File(prefix, "bin/pg_dump")
    private val pgRestore get() = File(prefix, "bin/pg_restore")
    private val createDb get() = File(prefix, "bin/createdb")
    private val dropDb get() = File(prefix, "bin/dropdb")
    private val openssl get() = File(prefix, "bin/openssl")
    private val requiredTools get() = listOf(pgCtl, initDb, psql, pgDump, pgRestore, createDb, dropDb)
    private val socketPath get() = storage.socketDirectory.absolutePath
    private val serviceLock = Mutex()

    private fun postgresEnvironment(): Array<String> = runtime.sessionEnvironment(shell.absolutePath)
        .filterNot { it.startsWith("IDE_TOOLS_AUTH_TOKEN=") || it.startsWith("IDE_RECEIPT_DIRECTORY=") }
        .toTypedArray()

    override suspend fun inspect(): PostgresStatus = withContext(Dispatchers.IO) {
        storage.pruneDatabaseLogs()
        val installed = requiredTools.all { it.canExecute() }
        val initialized = File(storage.data, "PG_VERSION").isFile
        val running = initialized && installed && runCatching {
            val result = runProcess(listOf(pgCtl.absolutePath, "-D", storage.data.absolutePath, "status"), timeoutSeconds = 8)
            result.exitCode == 0
        }.getOrDefault(false)
        val password = credentials.read()
        val serverConfiguration = configuredServerConfiguration()
        PostgresStatus(
            packageInstalled = installed,
            clusterInitialized = initialized,
            serverState = if (running) PostgresServerState.RUNNING else PostgresServerState.STOPPED,
            databases = if (running && storage.passwordFile.isFile) runCatching { listDatabaseNames() }.getOrDefault(emptyList()) else emptyList(),
            dataBytes = runCatching { storage.usedBytes() }.getOrDefault(0L),
            containerLimitBytes = configuredContainerLimit(),
            availableBytes = storage.availableBytes(),
            credentialsAvailable = password != null && storage.passwordFile.isFile,
            lastStopReason = preferences.getString(STOP_REASON_KEY, null),
            configuration = serverConfiguration,
            connection = PostgresConnectionInfo(port = serverConfiguration.port),
            tlsCertificateAvailable = storage.serverCertificate.isFile && storage.serverPrivateKey.isFile,
        )
    }

    override suspend fun readServiceLog(): String = withContext(Dispatchers.IO) {
        val files = listOf(
            "PostgreSQL server" to storage.log,
            "PostgreSQL database log" to File(storage.data, "log"),
            "Recent command output" to File(storage.root, "command.log"),
            "Recent restore output" to File(storage.root, "restore.log"),
        )
        files.mapNotNull { (label, file) ->
            val target = if (file.isDirectory) file.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() } else file.takeIf { it.isFile }
            target?.let { "$label · ${it.name}\n${readTail(it, MAX_LOG_CHARS_PER_FILE)}" }
        }.joinToString("\n\n").ifBlank { "No PostgreSQL log output is available yet." }.takeLast(MAX_LOG_CHARS_TOTAL)
    }

    override fun setContainerLimit(bytes: Long) {
        if (File(storage.data, "PG_VERSION").isFile) {
            throw IllegalStateException("The PostgreSQL storage budget cannot be changed after cluster initialization")
        }
        val usedBytes = storage.usedBytes()
        val installHeadroom = if (requiredTools.all { it.canExecute() }) 0L else MIN_PACKAGE_SPACE_BYTES
        val maximum = minOf(
            POSTGRES_MAX_DATA_BYTES,
            (storage.availableBytes() + usedBytes - POSTGRES_DEVICE_RESERVE_BYTES - MIN_START_SPACE_BYTES - installHeadroom).coerceAtLeast(0L),
        )
        if (bytes < POSTGRES_MIN_CONTAINER_BYTES) throw IllegalArgumentException("Choose a PostgreSQL storage limit of at least 256 MB")
        if (bytes > POSTGRES_MAX_DATA_BYTES) throw IllegalArgumentException("PostgreSQL databases share one aggregate 5 GB data cap")
        if (bytes > maximum) throw IllegalArgumentException("The maximum available container size is ${maximum / 1_000_000} MB while preserving 0.5 GB for Android")
        if (bytes < usedBytes) throw IllegalArgumentException("The selected limit is smaller than current database usage (${usedBytes / 1_000_000} MB). Export or remove data first.")
        if (!preferences.edit().putLong(CONTAINER_LIMIT_KEY, bytes).commit()) throw IOException("The PostgreSQL storage limit could not be saved")
    }

    override suspend fun saveConfiguration(configuration: PostgresConfiguration): Unit = mutationLock.withLock {
        serviceLock.withLock {
            withContext(Dispatchers.IO) {
                if (!File(storage.data, "PG_VERSION").isFile) throw IllegalStateException("Initialize the PostgreSQL cluster before configuring its network or server settings")
                if (isServerRunning()) throw IllegalStateException("Stop PostgreSQL before saving settings; changes take effect on the next start")
                val validated = PostgresConfigurationPolicy.validate(configuration)
                val previous = configuredServerConfiguration()
                try {
                    if (validated.tlsEnabled) ensureTlsCertificate()
                    writeManagedConfiguration(validated)
                    if (!storeServerConfiguration(validated)) throw IOException("PostgreSQL settings could not be saved")
                    credentials.read()?.let { password ->
                        storage.writePrivate(storage.passwordFile, "127.0.0.1:${validated.port}:*:postgres:$password\n[::1]:${validated.port}:*:postgres:$password\n")
                    }
                } catch (error: Throwable) {
                    runCatching { storeServerConfiguration(previous) }
                    runCatching { writeManagedConfiguration(previous) }
                    credentials.read()?.let { password ->
                        runCatching { storage.writePrivate(storage.passwordFile, "127.0.0.1:${previous.port}:*:postgres:$password\n[::1]:${previous.port}:*:postgres:$password\n") }
                    }
                    throw error
                }
                Unit
            }
        }
    }

    private fun storeServerConfiguration(configuration: PostgresConfiguration): Boolean = preferences.edit()
        .putString(NETWORK_ACCESS_KEY, configuration.networkAccess.name)
        .putStringSet(ALLOWED_HOSTS_KEY, configuration.allowedHosts.toSet())
        .putBoolean(TLS_ENABLED_KEY, configuration.tlsEnabled)
        .putInt(PORT_KEY, configuration.port)
        .putInt(MAX_CONNECTIONS_KEY, configuration.maxConnections)
        .putInt(SHARED_BUFFERS_MB_KEY, configuration.sharedBuffersMb)
        .putInt(WORK_MEM_MB_KEY, configuration.workMemMb)
        .commit()

    private fun configuredServerConfiguration(): PostgresConfiguration = PostgresConfigurationPolicy.validate(
        PostgresConfiguration(
            networkAccess = runCatching {
                PostgresNetworkAccess.valueOf(preferences.getString(NETWORK_ACCESS_KEY, null) ?: PostgresNetworkAccess.LOCAL_ONLY.name)
            }.getOrDefault(PostgresNetworkAccess.LOCAL_ONLY),
            allowedHosts = preferences.getStringSet(ALLOWED_HOSTS_KEY, emptySet()).orEmpty().toList(),
            tlsEnabled = preferences.getBoolean(TLS_ENABLED_KEY, false),
            port = preferences.getInt(PORT_KEY, 5432),
            maxConnections = preferences.getInt(MAX_CONNECTIONS_KEY, 10),
            sharedBuffersMb = preferences.getInt(SHARED_BUFFERS_MB_KEY, 64),
            workMemMb = preferences.getInt(WORK_MEM_MB_KEY, 4),
        ),
    )

    private fun writeManagedConfiguration(configuration: PostgresConfiguration) {
        val file = File(storage.data, "postgresql.conf")
        val begin = "# BEGIN ANDROID IDE MANAGED SETTINGS"
        val end = "# END ANDROID IDE MANAGED SETTINGS"
        val settings = mutableListOf(
            begin,
            "listen_addresses = '${PostgresConfigurationPolicy.listenAddresses(configuration)}'",
            "port = ${configuration.port}",
            "unix_socket_directories = '${socketPath.replace("'", "''")}'",
            "ssl = ${if (configuration.tlsEnabled) "on" else "off"}",
        )
        if (configuration.tlsEnabled) {
            settings += "ssl_cert_file = '${storage.serverCertificate.absolutePath}'"
            settings += "ssl_key_file = '${storage.serverPrivateKey.absolutePath}'"
        }
        settings += listOf(
            "max_connections = ${configuration.maxConnections}",
            "shared_buffers = '${configuration.sharedBuffersMb}MB'",
            "work_mem = '${configuration.workMemMb}MB'",
            "max_wal_size = '256MB'",
            "temp_file_limit = '512MB'",
            "password_encryption = 'scram-sha-256'",
            "logging_collector = on",
            "log_directory = 'log'",
            "log_filename = 'postgresql-%Y-%m-%d_%H%M%S.log'",
            "log_rotation_age = '1d'",
            "log_rotation_size = '10MB'",
            "log_truncate_on_rotation = on",
            end,
        )
        val block = settings.joinToString("\n")
        val current = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        val managed = Regex("(?s)\\Q$begin\\E.*?\\Q$end\\E\\s*")
        val next = if (managed.containsMatchIn(current)) managed.replace(current, "$block\n") else "${current.trimEnd()}\n\n$block\n"
        storage.writePrivate(file, next)

        val hba = buildString {
            appendLine("# Managed by Android IDE. Remote hosts always require SCRAM-SHA-256.")
            appendLine("local all all scram-sha-256")
            appendLine("host all all 127.0.0.1/32 scram-sha-256")
            appendLine("host all all ::1/128 scram-sha-256")
            PostgresConfigurationPolicy.hostRules(configuration).forEach { appendLine(it) }
        }
        storage.writePrivate(File(storage.data, "pg_hba.conf"), hba)
    }

    private fun ensureTlsCertificate(force: Boolean = false) {
        if (!force && storage.serverCertificate.isFile && storage.serverPrivateKey.isFile) return
        if (!openssl.canExecute()) throw IOException("The bundled OpenSSL command is unavailable; install or update the PostgreSQL tools before enabling TLS")
        storage.ensurePrivateDirectory(storage.root)
        val temporaryCertificate = File(storage.root, ".postgres-server-next.crt")
        val temporaryKey = File(storage.root, ".postgres-server-next.key")
        val previousCertificate = storage.serverCertificate.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        val previousKey = storage.serverPrivateKey.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        temporaryCertificate.delete()
        temporaryKey.delete()
        try {
            val sanEntries = buildList {
                add("DNS:android-ide-postgres")
                add("IP:127.0.0.1")
                val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
                if (interfaces != null) {
                    java.util.Collections.list(interfaces)
                        .filter { runCatching { it.isUp }.getOrDefault(false) }
                        .flatMap { java.util.Collections.list(it.inetAddresses) }
                        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isMulticastAddress }
                        .map { "IP:${it.hostAddress.substringBefore('%')}" }
                        .forEach { add(it) }
                }
            }.distinct().joinToString(",")
            runProcess(
                listOf(
                    openssl.absolutePath, "req", "-new", "-x509", "-nodes", "-days", "3650",
                    "-keyout", temporaryKey.absolutePath,
                    "-out", temporaryCertificate.absolutePath,
                    "-subj", "/CN=android-ide-postgres",
                    "-addext", "subjectAltName=$sanEntries",
                    "-addext", "basicConstraints=critical,CA:TRUE",
                    "-addext", "keyUsage=critical,digitalSignature,keyCertSign,keyEncipherment",
                    "-addext", "extendedKeyUsage=serverAuth",
                ),
                timeoutSeconds = 45,
            ).requireSuccess("The PostgreSQL TLS certificate could not be generated")
            if (!temporaryCertificate.isFile || !temporaryKey.isFile) throw IOException("OpenSSL did not create the PostgreSQL TLS certificate and key")
            Os.chmod(temporaryCertificate.absolutePath, 0b110000000)
            Os.chmod(temporaryKey.absolutePath, 0b110000000)
            Files.move(temporaryCertificate.toPath(), storage.serverCertificate.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporaryKey.toPath(), storage.serverPrivateKey.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            runCatching {
                if (previousCertificate != null) storage.writePrivate(storage.serverCertificate, previousCertificate) else storage.serverCertificate.delete()
                if (previousKey != null) storage.writePrivate(storage.serverPrivateKey, previousKey) else storage.serverPrivateKey.delete()
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            temporaryCertificate.delete()
            temporaryKey.delete()
        }
    }

    private fun configuredContainerLimit(): Long {
        if (preferences.contains(CONTAINER_LIMIT_KEY)) return preferences.getLong(CONTAINER_LIMIT_KEY, DEFAULT_CONTAINER_LIMIT_BYTES)
            .coerceIn(POSTGRES_MIN_CONTAINER_BYTES, POSTGRES_MAX_DATA_BYTES)
        val installHeadroom = if (requiredTools.all { it.canExecute() }) 0L else MIN_PACKAGE_SPACE_BYTES
        val maximum = minOf(
            POSTGRES_MAX_DATA_BYTES,
            (storage.availableBytes() + storage.usedBytes() - POSTGRES_DEVICE_RESERVE_BYTES - MIN_START_SPACE_BYTES - installHeadroom).coerceAtLeast(0L),
        )
        return minOf(DEFAULT_CONTAINER_LIMIT_BYTES, maximum).coerceAtLeast(POSTGRES_MIN_CONTAINER_BYTES)
    }

    private fun ensureBudgetFits(extraBytes: Long = 0L) {
        val capacity = storage.availableBytes() + storage.usedBytes()
        val required = configuredContainerLimit() + POSTGRES_DEVICE_RESERVE_BYTES + extraBytes
        if (capacity < required) {
            val maximum = (capacity - POSTGRES_DEVICE_RESERVE_BYTES - extraBytes).coerceAtLeast(0L)
            throw IOException("The configured PostgreSQL budget no longer fits. Current suggested maximum is ${maximum / 1_000_000} MB while preserving 0.5 GB for Android.")
        }
    }

    override suspend fun installPackage(onProgress: (String) -> Unit) = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            ensureRuntime()
            if (isServerRunning()) throw IOException("Stop PostgreSQL before installing or updating packages")
            if (requiredTools.all { it.canExecute() }) return@withContext
            ensureBudgetFits(MIN_PACKAGE_SPACE_BYTES)
            if (storage.availableBytes() < MIN_PACKAGE_SPACE_BYTES) throw IOException("Free at least 256 MB of device storage before installing PostgreSQL")
            onProgress("Refreshing the Termux package index…")
            runShell("pkg update -y && pkg install -y postgresql", timeoutMinutes = 45)
            if (requiredTools.any { !it.canExecute() }) {
                throw IOException("The package manager completed without installing all required PostgreSQL tools")
            }
        }
    }

    override suspend fun updatePackage(onProgress: (String) -> Unit) = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            ensureRuntime()
            if (isServerRunning()) throw IOException("Stop PostgreSQL before updating its package")
            if (!requiredTools.all { it.canExecute() }) throw IOException("Install PostgreSQL before updating it")
            ensureBudgetFits(MIN_PACKAGE_SPACE_BYTES)
            if (storage.availableBytes() < MIN_PACKAGE_SPACE_BYTES) throw IOException("Free at least 256 MB of device storage before updating PostgreSQL")
            onProgress("Checking for PostgreSQL package updates…")
            runShell("pkg update -y && apt install --only-upgrade -y postgresql", timeoutMinutes = 45)
            if (requiredTools.any { !it.canExecute() }) {
                throw IOException("The PostgreSQL update did not leave all required tools available")
            }
        }
    }

    override suspend fun initializeCluster(onProgress: (String) -> Unit) = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            ensureRuntime()
            if (requiredTools.any { !it.canExecute() }) throw IOException("Install PostgreSQL before initializing a database")
            ensureBudgetFits()
            if (File(storage.data, "PG_VERSION").isFile) throw IOException("A PostgreSQL cluster already exists")
            if (storage.availableBytes() < MIN_START_SPACE_BYTES) {
                throw IOException("Not enough free device storage to safely initialize PostgreSQL")
            }
            storage.ensurePrivateDirectory(storage.root)
            storage.ensurePrivateDirectory(storage.socketDirectory)
            if (!storage.data.exists() && !storage.data.mkdirs()) throw IOException("Could not create the PostgreSQL data directory")
            Os.chmod(storage.data.absolutePath, 0b111000000)
            val password = credentials.create()
            val passwordFile = storage.createTemporaryPasswordFile(password)
            try {
                onProgress("Creating a private PostgreSQL cluster…")
                runProcess(
                    listOf(
                        File(prefix, "bin/initdb").absolutePath,
                        "--pgdata=${storage.data.absolutePath}",
                        "--username=postgres",
                        "--auth-local=scram-sha-256",
                        "--auth-host=scram-sha-256",
                        "--pwfile=${passwordFile.absolutePath}",
                        "--encoding=UTF8",
                    ),
                    timeoutSeconds = 180,
                ).requireSuccess("PostgreSQL cluster initialization failed")
                storage.writePrivate(storage.passwordFile, "127.0.0.1:5432:*:postgres:$password\n[::1]:5432:*:postgres:$password\n")
                onProgress("Applying local-only server defaults…")
                writeManagedConfiguration(configuredServerConfiguration())
                preferences.edit().remove(STOP_REASON_KEY).commit()
                Unit
            } catch (error: Throwable) {
                storage.removeTemporaryPasswordFile()
                credentials.clear()
                storage.data.deleteRecursively()
                throw error
            } finally {
                storage.removeTemporaryPasswordFile()
            }
        }
    }

    /** Called only by the visible foreground service after it has entered foreground mode. */
    suspend fun startFromForegroundService() = serviceLock.withLock {
        withContext(Dispatchers.IO) {
            ensureRuntime()
            if (!File(storage.data, "PG_VERSION").isFile) throw IOException("Initialize the PostgreSQL cluster before starting the server")
            if (!storage.passwordFile.isFile) throw IOException("PostgreSQL connection credentials are missing. Export the cluster or initialize it again.")
            if (isServerRunning()) return@withContext
            if (storage.usedBytes() >= POSTGRES_MAX_DATA_BYTES) {
                throw IOException("PostgreSQL has reached the shared aggregate data cap of 5 GB. Export or remove data before restarting.")
            }
            if (storage.usedBytes() >= configuredContainerLimit() * 95L / 100L) {
                throw IOException("PostgreSQL data is already near its configured storage limit. Export or remove data before restarting.")
            }
            ensureBudgetFits()
            if (storage.availableBytes() < MIN_START_SPACE_BYTES) throw IOException("Free device storage is critically low; PostgreSQL was not started")
            storage.ensurePrivateDirectory(storage.socketDirectory)
            storage.rotateManagerLog()
            preferences.edit().remove(STOP_REASON_KEY).putBoolean(STOP_FAILURE_KEY, false).commit()
            val options = "-c unix_socket_directories=$socketPath"
            runProcess(
                listOf(
                    pgCtl.absolutePath, "-D", storage.data.absolutePath,
                    "-l", storage.log.absolutePath,
                    "-o", options,
                    "-w", "-t", "45", "start",
                ),
                timeoutSeconds = 60,
            ).requireSuccess("PostgreSQL failed to start")
        }
    }

    /** Called by the foreground service for explicit stop and storage protection. */
    suspend fun stopFromForegroundService(reason: String? = null) = serviceLock.withLock {
        withContext(Dispatchers.IO) {
            if (isServerRunning()) {
                runProcess(
                    listOf(pgCtl.absolutePath, "-D", storage.data.absolutePath, "-m", "fast", "-w", "-t", "45", "stop"),
                    timeoutSeconds = 60,
                ).requireSuccess("PostgreSQL could not shut down cleanly")
            }
            preferences.edit().putBoolean(STOP_FAILURE_KEY, false).apply {
                reason?.let { putString(STOP_REASON_KEY, it) }
            }.commit()
        }
    }

    override fun requestStart() {
        preferences.edit().putBoolean(DESIRED_RUNNING_KEY, true).putBoolean(STOP_FAILURE_KEY, false).remove(STOP_REASON_KEY).commit()
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, PostgresForegroundService::class.java).setAction(PostgresForegroundService.ACTION_START),
        )
    }

    override fun requestStop() {
        preferences.edit().putBoolean(STOP_REQUESTED_KEY, true).commit()
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, PostgresForegroundService::class.java).setAction(PostgresForegroundService.ACTION_STOP),
        )
    }

    fun shouldResumeAfterSystemRestart(): Boolean = preferences.getBoolean(DESIRED_RUNNING_KEY, false)
    fun markDesiredRunning(value: Boolean) { preferences.edit().putBoolean(DESIRED_RUNNING_KEY, value).putBoolean(STOP_REQUESTED_KEY, false).commit() }
    fun stopRequestFailed() { preferences.edit().putBoolean(DESIRED_RUNNING_KEY, true).putBoolean(STOP_FAILURE_KEY, true).putString(STOP_REASON_KEY, "PostgreSQL could not shut down cleanly; check recent logs and try again.").commit() }
    override fun hasStopRequestFailed(): Boolean = preferences.getBoolean(STOP_FAILURE_KEY, false)

    override suspend fun uninstallPackage() = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            ensureRuntime()
            if (isServerRunning()) throw IOException("Stop PostgreSQL before uninstalling its package")
            if (!pgCtl.exists()) return@withContext
            runShell("pkg uninstall -y postgresql", timeoutMinutes = 15)
            // The database cluster and exports are deliberately retained.
        }
    }

    override suspend fun createDatabase(name: String) = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            requireValidDatabaseName(name)
            requireServerReady()
            runProcess(listOf(createDb.absolutePath, "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--no-password", name), timeoutSeconds = 60, usePasswordFile = true)
                .requireSuccess("Database could not be created")
        }
    }

    override suspend fun deleteDatabase(name: String) = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            requireValidDatabaseName(name)
            if (name == "postgres" || name == "template0" || name == "template1") throw IOException("Built-in PostgreSQL databases cannot be removed")
            requireServerReady()
            runProcess(listOf(dropDb.absolutePath, "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--no-password", name), timeoutSeconds = 60, usePasswordFile = true)
                .requireSuccess("Database could not be removed")
        }
    }

    override suspend fun exportDatabase(name: String, destination: Uri, onProgress: (String) -> Unit) = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            requireValidDatabaseName(name)
            requireServerReady()
            val output = appContext.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("The selected export destination could not be opened")
            onProgress("Streaming a PostgreSQL custom-format backup…")
            val process = processBuilder(
                listOf(pgDump.absolutePath, "--format=custom", "--file=-", "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--no-password", "--dbname=$name"),
                usePasswordFile = true,
                mergeError = false,
            ).redirectError(storage.log).start()
            try {
                output.use { target -> process.inputStream.use { source -> source.copyTo(target, DEFAULT_BUFFER_SIZE * 8) } }
                if (!process.waitFor(EXPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    throw IOException("PostgreSQL export timed out")
                }
                if (process.exitValue() != 0) throw IOException("PostgreSQL export failed. Review the database status and retry.")
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    override suspend fun importDatabase(source: Uri, newDatabaseName: String, onProgress: (String) -> Unit) = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            requireValidDatabaseName(newDatabaseName)
            requireServerReady()
            if (newDatabaseName in listDatabaseNames()) throw IOException("That database already exists. Restore only into a new database name.")
            if (storage.availableBytes() < MIN_START_SPACE_BYTES) throw IOException("Not enough free device storage to restore a database")
            val input = appContext.contentResolver.openInputStream(source) ?: throw IOException("The selected backup file could not be opened")
            try {
                runProcess(listOf(createDb.absolutePath, "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--no-password", newDatabaseName), 60, usePasswordFile = true)
                    .requireSuccess("Could not create the destination database")
                onProgress("Restoring into the new database; existing databases are unchanged…")
                val process = processBuilder(
                    listOf(pgRestore.absolutePath, "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--no-password", "--no-owner", "--exit-on-error", "--dbname=$newDatabaseName"),
                    usePasswordFile = true,
                ).redirectErrorStream(true).redirectOutput(storage.root.resolve("restore.log")).start()
                input.use { sourceStream -> process.outputStream.use { target -> sourceStream.copyTo(target, DEFAULT_BUFFER_SIZE * 8) } }
                if (!process.waitFor(EXPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    throw IOException("PostgreSQL restore timed out")
                }
                if (process.exitValue() != 0) throw IOException("The backup could not be restored. The incomplete destination will be removed.")
                if (storage.usedBytes() > configuredContainerLimit()) {
                    throw IOException("The restore would exceed the shared PostgreSQL data budget of ${configuredContainerLimit() / 1_000_000} MB")
                }
            } catch (error: Throwable) {
                runCatching {
                    runProcess(listOf(dropDb.absolutePath, "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--no-password", newDatabaseName), 30, usePasswordFile = true)
                }
                if (error is CancellationException) throw error
                throw error
            }
        }
    }

    override suspend fun connectionUri(database: String): String? = withContext(Dispatchers.IO) {
        if (!database.matches(DATABASE_NAME_PATTERN)) return@withContext null
        val password = credentials.read() ?: return@withContext null
        "postgresql://postgres:$password@127.0.0.1:${configuredServerConfiguration().port}/$database"
    }

    override suspend fun rotatePassword(): String = mutationLock.withLock {
        serviceLock.withLock {
            withContext(Dispatchers.IO) {
                requireServerReady()
                val previousPassword = credentials.read() ?: throw IOException("The protected PostgreSQL password is unavailable")
                val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
                val nextPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                val sqlFile = File(storage.root, ".rotate-password.sql")
                val nextPasswordFile = File(storage.root, ".pgpass-next")
                var databasePasswordChanged = false
                try {
                    storage.writePrivate(sqlFile, "ALTER ROLE postgres WITH PASSWORD '$nextPassword';\n")
                    storage.writePrivate(
                        nextPasswordFile,
                        "127.0.0.1:${configuredServerConfiguration().port}:*:postgres:$nextPassword\n[::1]:${configuredServerConfiguration().port}:*:postgres:$nextPassword\n",
                    )
                    credentials.store(nextPassword)
                    runProcess(
                        listOf(psql.absolutePath, "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--dbname=postgres", "--no-password", "--no-psqlrc", "--set=ON_ERROR_STOP=1", "--file=${sqlFile.absolutePath}"),
                        timeoutSeconds = 30,
                        usePasswordFile = true,
                    ).requireSuccess("PostgreSQL password rotation failed")
                    databasePasswordChanged = true
                    if (!nextPasswordFile.renameTo(storage.passwordFile)) {
                        storage.writePrivate(storage.passwordFile, nextPasswordFile.readText(Charsets.UTF_8))
                        nextPasswordFile.delete()
                    }
                    nextPassword
                } catch (error: Throwable) {
                    // Restore the old vault value only when PostgreSQL rejected the change.
                    if (!databasePasswordChanged) runCatching { credentials.store(previousPassword) }
                    throw error
                } finally {
                    sqlFile.delete()
                    nextPasswordFile.delete()
                }
            }
        }
    }

    override suspend fun exportTlsCertificate(destination: Uri) {
        withContext(Dispatchers.IO) {
            if (!storage.serverCertificate.isFile) throw IOException("No PostgreSQL TLS certificate is available to export")
            val output = appContext.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("The selected certificate destination could not be opened")
            storage.serverCertificate.inputStream().use { source -> output.use { source.copyTo(it) } }
        }
    }

    override suspend fun renewTlsCertificate(): Unit = mutationLock.withLock {
        serviceLock.withLock {
            withContext(Dispatchers.IO) {
                if (!File(storage.data, "PG_VERSION").isFile) throw IOException("Initialize the PostgreSQL cluster before creating its TLS certificate")
                if (!configuredServerConfiguration().tlsEnabled) throw IOException("Enable TLS before renewing the server certificate")
                if (isServerRunning()) throw IOException("Stop PostgreSQL before renewing its TLS certificate")
                ensureTlsCertificate(force = true)
                Unit
            }
        }
    }

    override suspend fun clearStopReason() { preferences.edit().remove(STOP_REASON_KEY).putBoolean(STOP_FAILURE_KEY, false).commit() }

    suspend fun inspectStorageGuard(): Pair<PostgresStatus, String?> {
        val current = inspect()
        val reason = when {
            current.dataBytes >= POSTGRES_MAX_DATA_BYTES -> "PostgreSQL paused at the shared aggregate 5 GB data cap. Export or remove data before restarting."
            current.dataBytes >= current.containerLimitBytes * 95L / 100L -> "PostgreSQL paused near its configured ${current.containerLimitBytes / 1_000_000} MB storage limit. Export or remove data before restarting."
            current.storageWarning -> "PostgreSQL paused because the configured container budget would no longer leave the required 0.5 GB device reserve. Lower the budget or free device space before restarting."
            else -> null
        }
        return current to reason
    }

    private suspend fun requireServerReady() {
        if (!File(storage.data, "PG_VERSION").isFile) throw IOException("Initialize the PostgreSQL cluster first")
        if (!isServerRunning()) throw IOException("Start PostgreSQL before running this database operation")
        if (!storage.passwordFile.isFile) throw IOException("PostgreSQL password-file credentials are unavailable")
    }

    private fun listDatabaseNames(): List<String> {
        val query = "SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate ORDER BY 1"
        val result = runProcess(
            listOf(psql.absolutePath, "--host=127.0.0.1", "--port=${configuredServerConfiguration().port}", "--username=postgres", "--dbname=postgres", "--no-password", "--no-psqlrc", "--tuples-only", "--no-align", "--command=$query"),
            12,
            usePasswordFile = true,
        )
        if (result.exitCode != 0) return emptyList()
        return result.output.lineSequence().map(String::trim).filter { it.matches(DATABASE_NAME_PATTERN) }.toList()
    }

    private fun isServerRunning(): Boolean = runCatching {
        runProcess(listOf(pgCtl.absolutePath, "-D", storage.data.absolutePath, "status"), timeoutSeconds = 8).exitCode == 0
    }.getOrDefault(false)

    private fun ensureRuntime() {
        if (shell.canExecute() && File(prefix, "bin/pkg").canExecute()) return
        val report = runtime.initialize()
        if (report.outcome != OperationOutcome.COMPLETE) throw IOException(report.message.ifBlank { "Bundled terminal runtime is unavailable" })
    }

    private fun processBuilder(arguments: List<String>, usePasswordFile: Boolean = false, mergeError: Boolean = true): ProcessBuilder =
        ProcessBuilder(arguments).directory(home).apply {
            environment().putAll(postgresEnvironment().associate { item ->
                val separator = item.indexOf('=')
                item.substring(0, separator) to item.substring(separator + 1)
            })
            if (usePasswordFile && storage.passwordFile.isFile) environment()["PGPASSFILE"] = storage.passwordFile.absolutePath
            redirectErrorStream(mergeError)
        }

    private fun runShell(script: String, timeoutMinutes: Long) {
        runProcess(listOf(shell.absolutePath, "-c", script), timeoutSeconds = TimeUnit.MINUTES.toSeconds(timeoutMinutes).toInt())
            .requireSuccess("Termux PostgreSQL package operation failed")
    }

    private fun runProcess(
        arguments: List<String>,
        timeoutSeconds: Int,
        usePasswordFile: Boolean = false,
    ): ProcessResult {
        val logFile = File(storage.root, "command.log")
        storage.ensurePrivateDirectory(storage.root)
        val process = processBuilder(arguments, usePasswordFile).redirectOutput(logFile).start()
        process.outputStream.close()
        if (!process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("PostgreSQL command timed out")
        }
        val output = if (logFile.isFile) logFile.readText(Charsets.UTF_8).takeLast(MAX_COMMAND_OUTPUT_CHARS) else ""
        return ProcessResult(process.exitValue(), output)
    }

    private fun requireValidDatabaseName(name: String) {
        if (!name.matches(DATABASE_NAME_PATTERN)) throw IllegalArgumentException("Use a database name with 1–63 ASCII letters, numbers, or underscores; it must start with a letter or underscore")
    }

    private fun readTail(file: File, limit: Int): String = runCatching {
        RandomAccessFile(file, "r").use { stream ->
            val start = (stream.length() - limit).coerceAtLeast(0L)
            stream.seek(start)
            ByteArray((stream.length() - start).toInt()).also { stream.readFully(it) }.toString(Charsets.UTF_8)
        }
    }.getOrDefault("[Log could not be read]")

    private data class ProcessResult(val exitCode: Int, val output: String) {
        fun requireSuccess(message: String) {
            if (exitCode != 0) throw IOException(output.lineSequence().filter(String::isNotBlank).toList().takeLast(6).joinToString("\n").ifBlank { message })
        }
    }

    private companion object {
        val DATABASE_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]{0,62}")
        const val DESIRED_RUNNING_KEY = "desired_running"
        const val STOP_REQUESTED_KEY = "stop_requested"
        const val STOP_FAILURE_KEY = "stop_failure"
        const val STOP_REASON_KEY = "stop_reason"
        const val CONTAINER_LIMIT_KEY = "container_limit_bytes"
        const val NETWORK_ACCESS_KEY = "network_access"
        const val ALLOWED_HOSTS_KEY = "allowed_hosts"
        const val PORT_KEY = "postgres_port"
        const val MAX_CONNECTIONS_KEY = "max_connections"
        const val SHARED_BUFFERS_MB_KEY = "shared_buffers_mb"
        const val WORK_MEM_MB_KEY = "work_mem_mb"
        const val TLS_ENABLED_KEY = "tls_enabled"
        const val DEFAULT_CONTAINER_LIMIT_BYTES = 1_000_000_000L
        const val MIN_PACKAGE_SPACE_BYTES = POSTGRES_MIN_PACKAGE_SPACE_BYTES
        const val MIN_START_SPACE_BYTES = POSTGRES_MIN_START_SPACE_BYTES
        const val MAX_COMMAND_OUTPUT_CHARS = 4_000
        const val MAX_LOG_CHARS_PER_FILE = 6_000
        const val MAX_LOG_CHARS_TOTAL = 20_000
        const val EXPORT_TIMEOUT_MINUTES = 180L
    }
}
