package dev.android.ide.runtime

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
import dev.android.ide.contracts.SessionAvailability

/** Visible lifecycle boundary for long-running terminal work. */
class TerminalForegroundService : Service() {
    private val sessionStore by lazy { TerminalSessionPersistence(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Terminal sessions", NotificationManager.IMPORTANCE_LOW),
            )
        }
        startForeground(NOTIFICATION_ID, notification(this, activeSessionCount(this)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification(this)
        return START_STICKY
    }

    override fun onDestroy() {
        // The PTYs are gone when the service is destroyed. Do not leave stale
        // descriptors that the next launch cannot reconnect to.
        sessionStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_OPEN_TERMINAL = "dev.android.ide.action.OPEN_TERMINAL"
        const val ACTION_EXIT_ALL_TERMINALS = "dev.android.ide.action.EXIT_ALL_TERMINALS"
        const val ACTION_SHUTDOWN_APP = "dev.android.ide.action.SHUTDOWN_APP"
        const val CHANNEL_ID = "android_ide_terminal"
        const val NOTIFICATION_ID = 4103

        /** Refreshes the ongoing notification after a session descriptor changes. */
        fun updateNotification(context: Context) {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, notification(appContext, activeSessionCount(appContext)))
        }

        private fun activeSessionCount(context: Context): Int =
            TerminalSessionPersistence(context.applicationContext).readAll()
                .count { it.availability == SessionAvailability.AVAILABLE }

        private fun notification(context: Context, count: Int): Notification {
            val openIntent = pendingActivity(context, 4104, ACTION_OPEN_TERMINAL)
            val exitIntent = pendingActivity(context, 4105, ACTION_EXIT_ALL_TERMINALS)
            val shutdownIntent = pendingActivity(context, 4106, ACTION_SHUTDOWN_APP)
            val text = if (count == 1) "1 active terminal session" else "$count active terminal sessions"
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            return builder
                .setContentTitle("Android IDE terminal")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Exit all sessions", exitIntent).build())
                .addAction(Notification.Action.Builder(null, "Shut down app", shutdownIntent).build())
                .build()
        }

        private fun pendingActivity(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    this.action = action
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
