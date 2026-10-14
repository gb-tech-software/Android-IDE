package dev.android.ide.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.android.ide.MainActivity
import dev.android.ide.contracts.TerminalStorageSetupProtocol
import java.io.File

/**
 * Durable entry point for the bundled termux-setup-storage command.
 *
 * This must be manifest-backed rather than registered by MainActivity because
 * Android stops an activity while the user is in the system permission screen.
 */
class TerminalStorageSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TerminalStorageSetupProtocol.ACTION) return
        if (intent.getStringExtra(TerminalStorageSetupProtocol.REQUEST_KIND_EXTRA) !=
            TerminalStorageSetupProtocol.REQUEST_KIND_STORAGE
        ) return
        val requestId = intent.getStringExtra(TerminalStorageSetupProtocol.REQUEST_ID_EXTRA)
            ?.takeIf { it.matches(REQUEST_ID_PATTERN) }
            ?: return

        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = TerminalStorageSetupProtocol.ACTION
                putExtra(TerminalStorageSetupProtocol.REQUEST_KIND_EXTRA, TerminalStorageSetupProtocol.REQUEST_KIND_STORAGE)
                putExtra(TerminalStorageSetupProtocol.REQUEST_ID_EXTRA, requestId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        runCatching {
            TerminalStorageSetupResultChannel(File(context.filesDir, "home"))
                .publishAccepted(requestId)
        }
    }

    private companion object {
        val REQUEST_ID_PATTERN = Regex("[0-9]+")
    }
}
