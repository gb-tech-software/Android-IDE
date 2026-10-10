package dev.android.ide.app

import android.content.Context
import java.io.File

/** Receipts let the bundled Terminal helper distinguish an accepted request from a merely launched intent. */
class BrowserNavigationReceiptStore(context: Context) {
    val directory: File = File(context.applicationContext.filesDir, "browser-navigation-receipts")

    fun status(requestId: String?): String? {
        val id = requestId?.takeIf { REQUEST_ID.matches(it) } ?: return null
        return runCatching { File(directory, "$id.status").readText().takeIf { it in STATUSES } }.getOrNull()
    }

    @Synchronized
    fun record(requestId: String?, status: String) {
        val id = requestId?.takeIf { REQUEST_ID.matches(it) } ?: return
        if (status !in STATUSES) return
        if (!directory.isDirectory && !directory.mkdirs()) return
        directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > RECEIPT_TTL_MS }
            ?.forEach { runCatching { it.delete() } }
        val target = File(directory, "$id.status")
        val temporary = File(directory, "$id.tmp")
        runCatching {
            temporary.writeText(status)
            if (!temporary.renameTo(target)) {
                target.writeText(status)
                temporary.delete()
            }
        }
    }

    companion object {
        private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,80}")
        private val STATUSES = setOf("accepted", "dispatched", "rejected")
        private const val RECEIPT_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
