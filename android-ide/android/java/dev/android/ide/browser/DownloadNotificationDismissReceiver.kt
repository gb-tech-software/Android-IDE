package dev.android.ide.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager

/** Notification dismiss action; the notification manager removes the auto-cancelled item. */
class DownloadNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent?.getIntExtra(AndroidBrowserDownloadService.EXTRA_NOTIFICATION_ID, -1)
            ?.takeIf { it >= 0 }
            ?.let { context.getSystemService(NotificationManager::class.java)?.cancel(it) }
    }
}
