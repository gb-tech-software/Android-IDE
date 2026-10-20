package dev.android.ide.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.android.ide.MainActivity

/** Keeps a non-dismissible notification visible while project work is backgrounded. */
class ProjectOperationForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Project operations", NotificationManager.IMPORTANCE_LOW),
            )
        }
        startForeground(NOTIFICATION_ID, notification(this, 1, "Project operation running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Project operation running"
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this, count, label))
        // The manager owns the operation state. Restarting this notification
        // service without that state would create an orphaned notification.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(context: Context, count: Int, label: String): Notification {
        val openIntent = android.app.PendingIntent.getActivity(
            context,
            4201,
            Intent(context, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (count == 1) label else "$count project operations running"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder
            .setContentTitle("Android IDE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "android_ide_project_operations"
        const val NOTIFICATION_ID = 4202
        const val EXTRA_COUNT = "count"
        const val EXTRA_LABEL = "label"
    }
}
