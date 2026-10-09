package dev.android.ide.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

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
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // The PTYs are gone when the service is destroyed. Do not leave stale
        // descriptors that the next launch cannot reconnect to.
        sessionStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Android IDE terminal")
                .setContentText("Terminal work is active")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Android IDE terminal")
                .setContentText("Terminal work is active")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setOngoing(true)
                .build()
        }

    private companion object {
        const val CHANNEL_ID = "android_ide_terminal"
        const val NOTIFICATION_ID = 4103
    }
}
