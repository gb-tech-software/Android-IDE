package dev.android.ide.runtime

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.RuntimeCapabilities
import dev.android.ide.contracts.RuntimeAvailability
import dev.android.ide.contracts.RuntimeWorkspaceAdapter
import dev.android.ide.contracts.SessionAvailability
import dev.android.ide.contracts.SessionDescriptor
import dev.android.ide.contracts.TerminalProjectAccess
import dev.android.ide.contracts.TerminalRuntimeAdapter
import dev.android.ide.contracts.TerminalStorageSetupPermission
import dev.android.ide.saf.AndroidIdeDocumentsProvider
import dev.android.ide.filesystem.AndroidIdeFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifecycle-owned terminal runtime backed by the pinned Termux terminal-emulator.
 *
 * The terminal emulator owns ANSI parsing, scrollback, keyboard encoding and PTY
 * I/O. This adapter owns only Android IDE session identity, runtime paths and
 * lifecycle. The Termux bootstrap remains a separate, ABI-selected build asset.
 */
class TerminalRuntimeAdapterImpl(context: Context) : TerminalRuntimeAdapter, RuntimeWorkspaceAdapter {
    private val appContext = context.applicationContext
    private val sessionStore = RuntimeSessionStore(appContext)
    private val bundledInstaller = BundledTermuxRuntimeInstaller(appContext)
    private val fileSystem = AndroidIdeFileSystem.from(appContext).resources
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val viewInvalidators = ConcurrentHashMap<String, () -> Unit>()
    private val _initializationProgress = MutableStateFlow<String?>(null)
    val initializationProgress = _initializationProgress.asStateFlow()
    @Volatile private var terminalFeedbackListener: ((OperationReport) -> Unit)? = null
    @Volatile private var explicitlyShuttingDown = false
    @Volatile private var runtimeReady = false
    private val termuxPrefix get() = bundledInstaller.prefix()
    private val shell get() = File(termuxPrefix, "bin/sh")
    private val runtimeHome get() = bundledInstaller.home()

    override suspend fun initialize(): OperationReport = withContext(Dispatchers.IO) {
        runtimeReady = false
        val onProgress: (String) -> Unit = { message ->
            _initializationProgress.value = message
        }
        val bootstrap = bundledInstaller.initialize(onProgress)
        if (bootstrap.outcome != OperationOutcome.COMPLETE) {
            _initializationProgress.value = null
            return@withContext bootstrap
        }
        val shellFailure = bundledInstaller.shellStartupFailure()
        if (shellFailure != null) {
            _initializationProgress.value = null
            return@withContext OperationReport(
                OperationOutcome.BLOCKED,
                shellFailure,
                ErrorCategory.UNAVAILABLE_RUNTIME,
                recoveryHint = "Retry Terminal initialization after the bundled shell and terminal home are repaired.",
            )
        }
        runtimeReady = true
        // A TerminalSession owns an in-process PTY and cannot be reattached after
        // Android recreates the process. Preserve the descriptor as unavailable so
        // the UI can explain what happened and the user can explicitly close it.
        sessionStore.markAvailableUnavailable("The Android process was recreated; start a new terminal session")
        _initializationProgress.value = null
        OperationReport(
            OperationOutcome.COMPLETE,
            "Terminal runtime is ready. Install or remove packages with pkg when you choose.",
        )
    }

    fun setTerminalFeedbackListener(listener: ((OperationReport) -> Unit)?) {
        terminalFeedbackListener = listener
    }

    override suspend fun providerRootLocation(): ProjectLocation? =
        bundledInstaller.userFilesRoot().takeIf { it.exists() }?.let {
            ProjectLocation(
                stableId = AndroidIdeDocumentsProvider.rootTreeUri(),
                displayLabel = "Android IDE",
                userVisiblePath = AndroidIdeDocumentsProvider.rootTreeUri(),
            )
        }

    override suspend fun capabilities(): RuntimeCapabilities {
        val available = runtimeReady && termuxPrefix.isDirectory && shell.canExecute() &&
            runtimeHome.isDirectory && runtimeHome.canRead() && runtimeHome.canWrite()
        return RuntimeCapabilities(
            availability = if (available) RuntimeAvailability.AVAILABLE else RuntimeAvailability.UNAVAILABLE,
            architecture = System.getProperty("os.arch"),
            shellAvailable = available,
            ptyAvailable = available,
            executableFilesSupported = available,
            symlinksSupported = available,
            explanation = if (available) null else "Terminal files were not found",
        )
    }


    override suspend fun inspectProjectAccess(project: ProjectIdentity): TerminalProjectAccess {
        if (capabilities().availability != RuntimeAvailability.AVAILABLE) return TerminalProjectAccess(false, "Terminal is unavailable")
        val resolution = fileSystem.resolveProject(project)
        return TerminalProjectAccess(
            available = resolution.terminalAccessible,
            explanation = resolution.explanation ?: "The selected project location is not an accessible terminal folder".takeUnless { resolution.terminalAccessible },
        )
    }

    override suspend fun workingDirectory(project: ProjectIdentity): String? =
        fileSystem.resolveProject(project).terminalPath
            ?.let(::File)
            ?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
            ?.absolutePath

    override suspend fun completeStorageSetupRequest(
        requestId: String,
        permission: TerminalStorageSetupPermission,
    ): OperationReport = withContext(Dispatchers.IO) {
        val report = when {
            permission is TerminalStorageSetupPermission.Denied -> permission.report
            !runtimeReady -> OperationReport(
                OperationOutcome.BLOCKED,
                "Terminal must finish startup checks before shared-storage links can be created",
                ErrorCategory.UNAVAILABLE_RUNTIME,
            )
            else -> bundledInstaller.rebuildStorageLinks()
        }
        bundledInstaller.publishStorageSetupResult(requestId, report)
        report
    }

    override suspend fun createSession(workingDirectory: String?, name: String): SessionDescriptor {
        val id = UUID.randomUUID().toString()
        val sessionName = name.trim().ifBlank { "Untitled session" }
        if (!runtimeReady || !shell.canExecute()) {
            return unavailableSession(id, sessionName, "Terminal is unavailable: the bundled shell has not passed startup checks")
        }
        if (workingDirectory != null && !File(workingDirectory).isAbsolute) {
            return unavailableSession(id, sessionName, "Terminal cannot open the requested working directory because it is not an absolute path: $workingDirectory")
        }
        val directory = if (workingDirectory == null) runtimeHome else File(workingDirectory)
        if (!isUsableWorkingDirectory(directory.absolutePath)) {
            return unavailableSession(
                id,
                sessionName,
                "Terminal cannot open the requested working directory: ${directory.absolutePath}",
            )
        }
        return runCatching {
            val session = TerminalSession(
                shell.absolutePath,
                directory.absolutePath,
                arrayOf(shell.absolutePath, "-i"),
                bundledInstaller.sessionEnvironment(),
                TRANSCRIPT_ROWS,
                sessionClient,
            )
            val descriptor = SessionDescriptor(id, "global", session.mHandle, sessionName, directory.absolutePath, Instant.now(), SessionAvailability.AVAILABLE)
            ContextCompat.startForegroundService(appContext, Intent(appContext, TerminalForegroundService::class.java))
            sessions[id] = LiveSession(descriptor, session)
            sessionStore.upsert(descriptor)
            descriptor
        }.getOrElse { error -> unavailableSession(id, sessionName, error.message ?: "Unable to start terminal session") }
    }

    /** Returns the live upstream session for the Android View bridge. */
    fun terminalSession(sessionId: String): TerminalSession? {
        val live = sessions[sessionId] ?: return null
        if (!live.session.isRunning()) {
            markUnavailable(sessionId, "The terminal process ended")
            return null
        }
        return live.session
    }

    fun bindTerminalView(sessionId: String, invalidate: () -> Unit) {
        viewInvalidators[sessionId] = invalidate
    }

    fun unbindTerminalView(sessionId: String) {
        viewInvalidators.remove(sessionId)
    }

    override suspend fun listSessions(): List<SessionDescriptor> {
        sessions.values.filterNot { it.session.isRunning() }.forEach { markUnavailable(it.descriptor.id, "The terminal process ended") }
        val live = sessions.values.map { it.descriptor }.associateBy { it.id }
        return (sessionStore.readAll().filterNot { it.id in live } + live.values).sortedBy { it.createdAt }
    }

    override suspend fun renameSession(sessionId: String, name: String): OperationReport {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return OperationReport(OperationOutcome.BLOCKED, "Enter a session name", ErrorCategory.PROCESS_LOSS)
        val live = sessions[sessionId]
        val stored = sessionStore.readAll().firstOrNull { it.id == sessionId }
        if (live == null && stored == null) return unavailable("The terminal session is unavailable")
        val updated = (live?.descriptor ?: stored!!).copy(name = cleanName)
        live?.descriptor = updated
        sessionStore.upsert(updated)
        return OperationReport(OperationOutcome.COMPLETE, "Terminal session renamed", affectedIds = listOf(sessionId))
    }

    override suspend fun sendInput(sessionId: String, input: ByteArray): OperationReport {
        val session = liveSession(sessionId)?.session ?: return unavailable("The terminal session is unavailable")
        return runCatching {
            session.write(input, 0, input.size)
            OperationReport(OperationOutcome.COMPLETE, "Input sent")
        }.getOrElse { OperationReport(OperationOutcome.FAILED, "Could not send terminal input", ErrorCategory.PROCESS_LOSS) }
    }

    override suspend fun readOutput(sessionId: String): ByteArray? = liveSession(sessionId)?.session?.let { snapshot(it).toByteArray(Charsets.UTF_8) }

    override suspend fun resize(sessionId: String, columns: Int, rows: Int): OperationReport {
        val session = liveSession(sessionId)?.session ?: return unavailable("The terminal session is unavailable")
        if (columns <= 0 || rows <= 0) return OperationReport(OperationOutcome.BLOCKED, "The terminal size is invalid", ErrorCategory.PROCESS_LOSS)
        return runCatching {
            session.updateSize(columns, rows, CELL_WIDTH_PX, CELL_HEIGHT_PX)
            OperationReport(OperationOutcome.COMPLETE, "Terminal size updated")
        }.getOrElse { OperationReport(OperationOutcome.FAILED, "Could not resize the terminal", ErrorCategory.PROCESS_LOSS) }
    }

    override suspend fun interrupt(sessionId: String): OperationReport {
        val session = liveSession(sessionId)?.session ?: return unavailable("The terminal session is unavailable")
        session.write(byteArrayOf(3), 0, 1)
        return OperationReport(OperationOutcome.COMPLETE, "Terminal session interrupted")
    }

    override suspend fun listChildProcesses(sessionId: String): List<ChildProcessDescriptor> {
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

    override suspend fun terminateChildProcess(processId: String): OperationReport {
        val targetPid = processId.toLongOrNull()?.takeIf { it > 0 }
            ?: return OperationReport(OperationOutcome.BLOCKED, "The selected child process is invalid", ErrorCategory.PROCESS_LOSS)
        val owner = sessions.values.firstOrNull { live ->
            val shellPid = live.session.getPid().toLong()
            shellPid > 0 && targetPid != shellPid && processGroup(targetPid) == shellPid
        } ?: return OperationReport(OperationOutcome.BLOCKED, "That process is not owned by an active terminal session", ErrorCategory.PROCESS_LOSS)
        val groupId = processGroup(targetPid) ?: return unavailable("The child process is no longer available")
        return runCatching {
            Os.kill(-groupId.toInt(), OsConstants.SIGTERM)
            OperationReport(OperationOutcome.COMPLETE, "Owned child process group terminated")
        }.getOrElse { OperationReport(OperationOutcome.FAILED, "The owned child process group could not be terminated", ErrorCategory.PROCESS_LOSS) }
    }

    override suspend fun closeSession(sessionId: String): OperationReport {
        val live = sessions.remove(sessionId)
        live?.let(::terminateOwnedProcessGroup)
        val removed = sessionStore.remove(sessionId)
        if (sessions.isEmpty()) appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
        return if (live != null || removed) {
            OperationReport(OperationOutcome.COMPLETE, "Terminal session closed", affectedIds = listOf(sessionId))
        } else {
            OperationReport(OperationOutcome.BLOCKED, "The terminal session was not found", ErrorCategory.PROCESS_LOSS)
        }
    }

    override suspend fun closeAllSessions(): OperationReport {
        sessions.keys.toList().forEach { closeSession(it) }
        sessionStore.clear()
        appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
        return OperationReport(OperationOutcome.COMPLETE, "All terminal sessions closed")
    }

    fun outputSnapshot(sessionId: String): String = liveSession(sessionId)?.session?.let(::snapshot).orEmpty()

    fun shutdown() {
        explicitlyShuttingDown = true
        runtimeReady = false
        sessions.values.forEach(::terminateOwnedProcessGroup)
        sessions.clear()
        viewInvalidators.clear()
        // Explicit application shutdown is the terminal exit boundary. Do not
        // resurrect those live sessions on a later launch.
        sessionStore.clear()
        appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
        scope.cancel()
    }

    private fun snapshot(session: TerminalSession): String = session.getEmulator()?.getScreen()?.getTranscriptText().orEmpty()

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
        val unavailable = live.descriptor.copy(
            availability = SessionAvailability.UNAVAILABLE,
            terminationReason = reason,
        )
        live.descriptor = unavailable
        sessionStore.upsert(unavailable)
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
        if (shellPid > 0 && processGroup(shellPid) == shellPid) {
            runCatching { Os.kill(-shellPid.toInt(), OsConstants.SIGTERM) }
        }
        live.session.finishIfRunning()
    }


    private fun unavailable(message: String) = OperationReport(OperationOutcome.BLOCKED, message, ErrorCategory.UNAVAILABLE_RUNTIME)

    fun isUsableWorkingDirectory(path: String): Boolean = runCatching {
        if (path.isBlank()) return@runCatching false
        val directory = File(path)
        directory.isAbsolute && directory.isDirectory && directory.canRead() && directory.canWrite() && directory.canExecute()
    }.getOrDefault(false)

    private fun unavailableSession(id: String, name: String, reason: String): SessionDescriptor =
        SessionDescriptor(id, "global", name = name, createdAt = Instant.now(), availability = SessionAvailability.UNAVAILABLE, terminationReason = reason)

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            sessions.entries.firstOrNull { it.value.session === changedSession }?.key?.let { id ->
                viewInvalidators[id]?.invoke()
            }
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
            if (sessions.isEmpty()) {
                appContext.stopService(Intent(appContext, TerminalForegroundService::class.java))
            }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val result = runCatching {
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                    ?: throw IllegalStateException("Android clipboard service is unavailable")
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal selection", text))
            }
            terminalFeedbackListener?.invoke(
                if (result.isSuccess) OperationReport(OperationOutcome.COMPLETE, "Text copied")
                else OperationReport(OperationOutcome.FAILED, "Could not copy terminal selection to the system clipboard", ErrorCategory.UNAVAILABLE_RUNTIME),
            )
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val result = runCatching {
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                    ?: throw IllegalStateException("Android clipboard service is unavailable")
                val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    ?: throw IllegalStateException("The system clipboard is empty")
                val text = item.coerceToText(appContext)?.toString().orEmpty()
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty()) session.write(bytes, 0, bytes.size)
            }
            terminalFeedbackListener?.invoke(
                if (result.isSuccess) OperationReport(OperationOutcome.COMPLETE, "Text pasted")
                else OperationReport(OperationOutcome.BLOCKED, result.exceptionOrNull()?.message ?: "Could not read the system clipboard", ErrorCategory.UNAVAILABLE_RUNTIME),
            )
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
