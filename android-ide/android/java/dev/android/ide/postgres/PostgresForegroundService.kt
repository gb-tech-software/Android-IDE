package dev.android.ide.postgres

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.android.ide.MainActivity
import dev.android.ide.app.ApplicationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the user-requested PostgreSQL daemon independently of interactive terminal sessions. */
class PostgresForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private lateinit var postgres: PostgresApplicationService
    private var commandJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Local PostgreSQL", NotificationManager.IMPORTANCE_LOW),
            )
        }
        startForeground(NOTIFICATION_ID, notification(this, "Preparing local PostgreSQL…", canStop = false))
        postgres = ApplicationServices.from(application as android.app.Application).postgresApplicationService
        serviceScope.launch { monitorWhileRequested() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDatabase(startId)
            ACTION_STOP -> stopDatabase(startId)
            null -> {
                if (postgres.shouldResumeAfterSystemRestart()) startDatabase(startId)
                else stopSelfResult(startId)
            }
            else -> stopSelfResult(startId)
        }
        return START_STICKY
    }

    private fun startDatabase(startId: Int) {
        commandJob = serviceScope.launch {
            commandMutex.withLock {
                val result = runCatching { postgres.startFromForegroundService() }
                result.onSuccess {
                    val status = postgres.inspect()
                    updateNotification(this@PostgresForegroundService, runningMessage(status), canStop = true)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    postgres.markDesiredRunning(false)
                    runCatching { postgres.stopFromForegroundService(error.message ?: "PostgreSQL failed to start") }
                    updateNotification(this@PostgresForegroundService, error.message ?: "PostgreSQL failed to start", canStop = false)
                    delay(2_500)
                    stopSelfResult(startId)
                }
            }
        }
    }

    private fun stopDatabase(startId: Int) {
        commandJob = serviceScope.launch {
            commandMutex.withLock {
                val result = runCatching { postgres.stopFromForegroundService() }
                if (result.isFailure) {
                    postgres.stopRequestFailed()
                    updateNotification(this@PostgresForegroundService, result.exceptionOrNull()?.message ?: "PostgreSQL could not stop", canStop = true)
                    return@withLock
                }
                postgres.markDesiredRunning(false)
                updateNotification(this@PostgresForegroundService, "PostgreSQL stopped", canStop = false)
                delay(350)
                stopForeground(true)
                stopSelfResult(startId)
            }
        }
    }

    private suspend fun monitorWhileRequested() {
        while (serviceScope.isActive) {
            delay(8_000)
            if (!postgres.shouldResumeAfterSystemRestart()) continue
            val guarded = runCatching { postgres.inspectStorageGuard() }.getOrNull() ?: continue
            val status = guarded.first
            val stopReason = guarded.second
            if (stopReason != null) {
                var stopped = false
                commandMutex.withLock {
                    if (postgres.shouldResumeAfterSystemRestart()) {
                        val result = runCatching { postgres.stopFromForegroundService(stopReason) }
                        if (result.isSuccess) {
                            postgres.markDesiredRunning(false)
                            stopped = true
                        } else {
                            updateNotification(this@PostgresForegroundService, result.exceptionOrNull()?.message ?: "Storage safety shutdown failed; retrying", canStop = true)
                        }
                    }
                }
                if (stopped) {
                    updateNotification(this@PostgresForegroundService, stopReason, canStop = false)
                    delay(1_500)
                    stopForeground(true)
                    stopSelf()
                    return
                }
                continue
            }
            if (status.serverState == PostgresServerState.RUNNING) {
                updateNotification(
                    this@PostgresForegroundService,
                    "${runningMessage(status)} · ${formatBytes(status.dataBytes)} of ${formatBytes(status.containerLimitBytes)}",
                    canStop = true,
                )
            } else {
                updateNotification(this@PostgresForegroundService, "Waiting for PostgreSQL to recover…", canStop = false)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.android.ide.postgres.START"
        const val ACTION_STOP = "dev.android.ide.postgres.STOP"
        const val CHANNEL_ID = "android_ide_postgresql"
        const val NOTIFICATION_ID = 4107

        private fun runningMessage(status: PostgresStatus): String {
            val endpoint = when (status.configuration.networkAccess) {
                PostgresNetworkAccess.LOCAL_ONLY -> "127.0.0.1:${status.configuration.port}"
                PostgresNetworkAccess.ALLOWED_HOSTS -> "LAN allowlist · port ${status.configuration.port}"
                PostgresNetworkAccess.ALL_NETWORKS -> "all network interfaces · port ${status.configuration.port}"
            }
            val tls = if (status.configuration.tlsEnabled && status.configuration.networkAccess != PostgresNetworkAccess.LOCAL_ONLY) " · TLS required for remote clients" else ""
            return "PostgreSQL running · $endpoint$tls"
        }

        fun updateNotification(context: Context, text: String, canStop: Boolean) {
            val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, notification(context.applicationContext, text, canStop))
        }

        private fun notification(context: Context, text: String, canStop: Boolean): Notification {
            val openIntent = PendingIntent.getActivity(
                context,
                4108,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            builder.setContentTitle("Android IDE · PostgreSQL")
                .setContentText(text.take(180))
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentIntent(openIntent)
                .setOngoing(canStop)
                .setOnlyAlertOnce(true)
            if (canStop) {
                val stopIntent = PendingIntent.getService(
                    context,
                    4109,
                    Intent(context, PostgresForegroundService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(Notification.Action.Builder(null, "Stop database", stopIntent).build())
            }
            return builder.build()
        }

        private fun formatBytes(value: Long): String = "%.2f".format(java.util.Locale.ROOT, value / 1_000_000_000.0)
    }
}
