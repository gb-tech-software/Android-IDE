package dev.android.ide.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.android.ide.contracts.ChildProcessDescriptor
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.SessionAvailability
import dev.android.ide.contracts.SessionDescriptor
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns live Termux PTYs, process groups, view invalidation, and terminal callbacks. */
class TerminalPtyLifecycle(
    context: Context,
    private val installation: TerminalRuntimeInstallation,
    private val persistence: TerminalSessionPersistence,
    private val onFeedback: (OperationReport) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val viewInvalidators = ConcurrentHashMap<String, () -> Unit>()
    @Volatile private var explicitlyShuttingDown = false

    fun createSession(workingDirectory: String?, name: String): SessionDescriptor {
        val id = UUID.randomUUID().toString()
        val sessionName = name.trim().ifBlank { "Untitled session" }
        if (!installation.capabilities().shellAvailable) {
            return unavailable(id, sessionName, "Terminal is unavailable: the bundled shell has not passed startup checks")
        }
        if (workingDirectory != null && !File(workingDirectory).isAbsolute) {
            return unavailable(id, sessionName, "Terminal cannot open the requested working directory because it is not an absolute path: $workingDirectory")
        }
        val directory = if (workingDirectory == null) installation.home else File(workingDirectory)
        if (!isUsableWorkingDirectory(directory.absolutePath)) {
            return unavailable(id, sessionName, "Terminal cannot open the requested working directory: ${directory.absolutePath}")
        }
        return runCatching {
            val session = TerminalSession(
                installation.shell.absolutePath,
                directory.absolutePath,
                arrayOf(installation.shell.absolutePath, "-i"),
                installation.environment,
                TRANSCRIPT_ROWS,
                sessionClient,
            )
            val descriptor = SessionDescriptor(
                id, "global", session.mHandle, sessionName, directory.absolutePath,
                Instant.now(), SessionAvailability.AVAILABLE,
            )
            ContextCompat.startForegroundService(appContext, Intent(appContext, TerminalForegroundService::class.java))
            sessions[id] = LiveSession(descriptor, session)
            persistence.upsert(descriptor)
            TerminalForegroundService.updateNotification(appContext)
            descriptor
        }.getOrElse { error -> unavailable(id, sessionName, error.message ?: "Unable to start terminal session") }
    }

    fun terminalSession(sessionId: String): TerminalSession? = liveSession(sessionId)?.session

    fun bindView(sessionId: String, invalidate: () -> Unit) { viewInvalidators[sessionId] = invalidate }
    fun unbindView(sessionId: String) { viewInvalidators.remove(sessionId) }

    fun liveDescriptors(): List<SessionDescriptor> {
        sessions.values.filterNot { it.session.isRunning() }
            .forEach { markUnavailable(it.descriptor.id, "The terminal process ended") }
        return sessions.values.map { it.descriptor }
    }

    fun updateDescriptor(sessionId: String, descriptor: SessionDescriptor) {
        sessions[sessionId]?.descriptor = descriptor
    }

    fun outputSnapshot(sessionId: String): String = liveSession(sessionId)?.session
        ?.getEmulator()?.getScreen()?.getTranscriptText().orEmpty()

    fun sendInput(sessionId: String, input: ByteArray): OperationReport {
        val session = liveSession(sessionId)?.session ?: return unavailable("The terminal session is unavailable")
        return runCatching {
            session.write(input, 0, input.size)
            OperationReport(OperationOutcome.COMPLETE, "Input sent")
        }.getOrElse { OperationReport(OperationOutcome.FAILED, "Could not send terminal input", ErrorCategory.PROCESS_LOSS) }
    }

    fun readOutput(sessionId: String): ByteArray? = liveSession(sessionId)?.session
        ?.let { outputSnapshot(sessionId).toByteArray(Charsets.UTF_8) }

    fun resize(sessionId: String, columns: Int, rows: Int): OperationReport {
        val session = liveSession(sessionId)?.session ?: return unavailable("The terminal session is unavailable")
        if (columns <= 0 || rows <= 0) return OperationReport(OperationOutcome.BLOCKED, "The terminal size is invalid", ErrorCategory.PROCESS_LOSS)
        return runCatching {
            session.updateSize(columns, rows, CELL_WIDTH_PX, CELL_HEIGHT_PX)
            OperationReport(OperationOutcome.COMPLETE, "Terminal size updated")
        }.getOrElse { OperationReport(OperationOutcome.FAILED, "Could not resize the terminal", ErrorCategory.PROCESS_LOSS) }
    }

    fun interrupt(sessionId: String): OperationReport {
        val session = liveSession(sessionId)?.session ?: return unavailable("The terminal session is unavailable")
        session.write(byteArrayOf(3), 0, 1)
        return OperationReport(OperationOutcome.COMPLETE, "Terminal session interrupted")
    }

    fun listChildProcesses(sessionId: String): List<ChildProcessDescriptor> {
        val live = liveSession(sessionId) ?: return emptyList()
        return runCatching {
            val shellPid = live.session.getPid().toLong()
            ProcessBuilder("/system/bin/ps", "-o", "PID,PPID,PGID,ARGS").redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().lineSequence().drop(1).mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 4)
                    val pid = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val pgid = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                    if (pid == shellPid || pgid != shellPid) return@mapNotNull null
                    ChildProcessDescriptor(pid.toString(), sessionId, parts.getOrNull(3).orEmpty(), pid, live.descriptor.workingDirectory, Instant.now(), SessionAvailability.AVAILABLE)
                }.toList()
        }.getOrDefault(emptyList())
    }

    fun terminateChildProcess(processId: String): OperationReport {
        val targetPid = processId.toLongOrNull()?.takeIf { it > 0 }
            ?: return OperationReport(OperationOutcome.BLOCKED, "The selected child process is invalid", ErrorCategory.PROCESS_LOSS)
        if (sessions.values.firstOrNull { live ->
            val shellPid = live.session.getPid().toLong()
            shellPid > 0 && targetPid != shellPid && processGroup(targetPid) == shellPid
        } == null) return OperationReport(OperationOutcome.BLOCKED, "That process is not owned by an active terminal session", ErrorCategory.PROCESS_LOSS)
        val groupId = processGroup(targetPid) ?: return unavailable("The child process is no longer available")
        return runCatching {
            Os.kill(-groupId.toInt(), OsConstants.SIGTERM)
            OperationReport(OperationOutcome.COMPLETE, "Owned child process group terminated")
        }.getOrElse { OperationReport(OperationOutcome.FAILED, "The owned child process group could not be terminated", ErrorCategory.PROCESS_LOSS) }
    }

    fun closeSession(sessionId: String): OperationReport {
        val live = sessions.remove(sessionId)
        viewInvalidators.remove(sessionId)
        live?.let(::terminateOwnedProcessGroup)
        val removed = persistence.remove(sessionId)
        TerminalForegroundService.updateNotification(appContext)
        if (sessions.isEmpty()) appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
        return if (live != null || removed) OperationReport(OperationOutcome.COMPLETE, "Terminal session closed", affectedIds = listOf(sessionId))
        else OperationReport(OperationOutcome.BLOCKED, "The terminal session was not found", ErrorCategory.PROCESS_LOSS)
    }

    fun closeAllSessions(): OperationReport {
        sessions.keys.toList().forEach(::closeSession)
        persistence.clear()
        appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
        return OperationReport(OperationOutcome.COMPLETE, "All terminal sessions closed")
    }

    fun shutdown() {
        explicitlyShuttingDown = true
        sessions.values.forEach(::terminateOwnedProcessGroup)
        sessions.clear()
        viewInvalidators.clear()
        persistence.clear()
        appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
    }

    fun isUsableWorkingDirectory(path: String): Boolean = runCatching {
        if (path.isBlank()) return@runCatching false
        val directory = File(path)
        directory.isAbsolute && directory.isDirectory && directory.canRead() && directory.canWrite() && directory.canExecute()
    }.getOrDefault(false)

    private fun liveSession(sessionId: String): LiveSession? {
        val live = sessions[sessionId]
        if (live == null || !live.session.isRunning()) {
            if (live != null) markUnavailable(sessionId, "The terminal process ended")
            return null
        }
        return live
    }

    private fun markUnavailable(sessionId: String, reason: String) {
        val live = sessions.remove(sessionId) ?: return
        val unavailable = live.descriptor.copy(availability = SessionAvailability.UNAVAILABLE, terminationReason = reason)
        live.descriptor = unavailable
        persistence.upsert(unavailable)
        TerminalForegroundService.updateNotification(appContext)
        viewInvalidators.remove(sessionId)?.invoke()
    }

    private fun processGroup(pid: Long): Long? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val endOfCommand = stat.lastIndexOf(')')
        if (endOfCommand < 0) return@runCatching null
        stat.substring(endOfCommand + 2).trim().split(Regex("\\s+")).getOrNull(2)?.toLongOrNull()
    }.getOrNull()

    private fun terminateOwnedProcessGroup(live: LiveSession) {
        val shellPid = live.session.getPid().toLong()
        if (shellPid > 0 && processGroup(shellPid) == shellPid) runCatching { Os.kill(-shellPid.toInt(), OsConstants.SIGTERM) }
        live.session.finishIfRunning()
    }

    private fun unavailable(message: String) = OperationReport(OperationOutcome.BLOCKED, message, ErrorCategory.UNAVAILABLE_RUNTIME)

    private fun unavailable(id: String, name: String, reason: String) =
        SessionDescriptor(id, "global", name = name, createdAt = Instant.now(), availability = SessionAvailability.UNAVAILABLE, terminationReason = reason)

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            sessions.entries.firstOrNull { it.value.session === changedSession }?.key?.let { viewInvalidators[it]?.invoke() }
        }
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            val entry = sessions.entries.firstOrNull { it.value.session === finishedSession } ?: return
            val id = entry.key
            if (explicitlyShuttingDown) {
                sessions.remove(id)
                viewInvalidators.remove(id)?.invoke()
                return
            }
            markUnavailable(id, "Terminal process ended with exit code ${finishedSession.exitStatus}")
            onFeedback(OperationReport(
                OperationOutcome.COMPLETE,
                "Terminal session closed",
                affectedIds = listOf(id),
            ))
            if (sessions.isEmpty()) appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val result = runCatching {
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                    ?: throw IllegalStateException("Android clipboard service is unavailable")
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal selection", text))
            }
            onFeedback(if (result.isSuccess) OperationReport(OperationOutcome.COMPLETE, "Text copied") else OperationReport(OperationOutcome.FAILED, "Could not copy terminal selection to the system clipboard", ErrorCategory.UNAVAILABLE_RUNTIME))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val result = runCatching {
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                    ?: throw IllegalStateException("Android clipboard service is unavailable")
                val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    ?: throw IllegalStateException("The system clipboard is empty")
                val bytes = item.coerceToText(appContext)?.toString().orEmpty().toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty()) session.write(bytes, 0, bytes.size)
            }
            onFeedback(if (result.isSuccess) OperationReport(OperationOutcome.COMPLETE, "Text pasted") else OperationReport(OperationOutcome.BLOCKED, result.exceptionOrNull()?.message ?: "Could not read the system clipboard", ErrorCategory.UNAVAILABLE_RUNTIME))
        }
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }

    private data class LiveSession(var descriptor: SessionDescriptor, val session: TerminalSession)

    private companion object {
        const val TRANSCRIPT_ROWS = 2_000
        const val CELL_WIDTH_PX = 8
        const val CELL_HEIGHT_PX = 18
    }
}
