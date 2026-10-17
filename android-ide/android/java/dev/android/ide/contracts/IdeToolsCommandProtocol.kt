package dev.android.ide.contracts

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private command bridge from the bundled `ide` CLI to app-owned services. */
object IdeToolsCommandProtocol {
    const val ACTION = "dev.android.ide.action.IDE_TOOLS_COMMAND"
    const val COMMAND_EXTRA = "dev.android.ide.extra.IDE_TOOLS_COMMAND"
    const val REQUEST_ID_EXTRA = "dev.android.ide.extra.IDE_TOOLS_REQUEST_ID"
    const val TARGET_EXTRA = "dev.android.ide.extra.IDE_TOOLS_TARGET"
    const val AUTH_TOKEN_EXTRA = "dev.android.ide.extra.IDE_TOOLS_AUTH_TOKEN"
    const val PAYLOAD_EXTRA = "dev.android.ide.extra.IDE_TOOLS_PAYLOAD"
    private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,80}")
    private val AUTH_TOKEN_LOCK = Any()
    @Volatile private var cachedAuthToken: String? = null

    class Receipts(context: Context) {
        private val appFiles = context.applicationContext.filesDir
        val directory = File(appFiles, "ide-tools-receipts")
        private val tokenFile = File(appFiles, "ide-tools-auth.token")
        val authToken: String
            get() = synchronized(AUTH_TOKEN_LOCK) {
                cachedAuthToken ?: tokenFile.takeIf { it.isFile }?.readText()?.takeIf { it.length >= 32 }
                    ?.also { cachedAuthToken = it }
                    ?: UUID.randomUUID().toString().replace("-", "").also {
                        tokenFile.writeText(it)
                        cachedAuthToken = it
                    }
        }

        fun isAuthorized(candidate: String?): Boolean {
            if (candidate.isNullOrBlank()) return false
            return MessageDigest.isEqual(authToken.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
        }

        fun isValidRequestId(candidate: String?): Boolean = candidate != null && REQUEST_ID.matches(candidate)

        @Synchronized
        fun record(requestId: String?, exitCode: Int, message: String, data: String? = null) {
            val id = requestId?.takeIf(REQUEST_ID::matches) ?: return
            if (exitCode !in 0..255 || (!directory.isDirectory && !directory.mkdirs())) return
            val destination = File(directory, "$id.result")
            val temporary = File(directory, "$id.tmp")
            runCatching {
                temporary.writeText("$exitCode\n${message.replace('\n', ' ').replace('\r', ' ').take(3_000)}\n${data.orEmpty().filterNot { it.isISOControl() }.take(48_000)}\n")
                if (!temporary.renameTo(destination)) {
                    destination.writeText(temporary.readText())
                    temporary.delete()
                }
                directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1_000 }
                    ?.forEach { it.delete() }
            }
        }
    }
}
