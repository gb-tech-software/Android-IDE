package dev.android.ide.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.android.ide.MainActivity
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
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

        val resultChannel = TerminalStorageSetupResultChannel(File(context.filesDir, "home"))
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    action = TerminalStorageSetupProtocol.ACTION
                    putExtra(TerminalStorageSetupProtocol.REQUEST_KIND_EXTRA, TerminalStorageSetupProtocol.REQUEST_KIND_STORAGE)
                    putExtra(TerminalStorageSetupProtocol.REQUEST_ID_EXTRA, requestId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
            resultChannel.publishAccepted(requestId)
        } catch (error: Exception) {
            Log.e(TAG, "Could not open Android IDE for terminal storage setup request $requestId", error)
            runCatching {
                resultChannel.publish(
                    requestId,
                    OperationReport(
                        OperationOutcome.BLOCKED,
                        "Android IDE could not open the storage setup screen.",
                        ErrorCategory.UNAVAILABLE_RUNTIME,
                        recoveryHint = error.message,
                    ),
                )
            }.onFailure { publishError ->
                Log.e(TAG, "Could not publish storage setup launch failure for request $requestId", publishError)
            }
        }
    }

    private companion object {
        const val TAG = "TerminalStorageSetup"
        val REQUEST_ID_PATTERN = Regex("[0-9]+")
    }
}
