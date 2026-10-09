package dev.android.ide.runtime

import android.content.Context
import com.termux.terminal.TerminalSession
import dev.android.ide.contracts.ChildProcessDescriptor
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.RuntimeCapabilities
import dev.android.ide.contracts.RuntimeAvailability
import dev.android.ide.contracts.RuntimeWorkspaceAdapter
import dev.android.ide.contracts.SessionDescriptor
import dev.android.ide.contracts.TerminalProjectAccess
import dev.android.ide.contracts.TerminalRuntimeAdapter
import dev.android.ide.contracts.TerminalStorageSetupPermission
import dev.android.ide.workspace.ProjectWorkspaceService
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Contract-facing coordinator for terminal installation, workspace access, PTYs, and persistence. */
class TerminalRuntimeAdapterImpl(
    context: Context,
    private val workspace: ProjectWorkspaceService,
    installer: BundledTermuxRuntimeInstaller,
) : TerminalRuntimeAdapter, RuntimeWorkspaceAdapter {
    private val persistence = TerminalSessionPersistence(context.applicationContext)
    private var terminalFeedbackListener: ((OperationReport) -> Unit)? = null
    private val installation = TerminalRuntimeInstallation(installer)
    private val pty = TerminalPtyLifecycle(
        context,
        installation,
        persistence,
    ) { report -> terminalFeedbackListener?.invoke(report) }

    val initializationProgress: StateFlow<String?> = installation.progress

    override suspend fun initialize(): OperationReport = installation.initialize(persistence)

    fun setTerminalFeedbackListener(listener: ((OperationReport) -> Unit)?) {
        terminalFeedbackListener = listener
    }

    override suspend fun providerRootLocation(): ProjectLocation? = installation.providerRootLocation()

    override suspend fun capabilities(): RuntimeCapabilities = installation.capabilities()

    override suspend fun inspectProjectAccess(project: ProjectIdentity): TerminalProjectAccess {
        if (capabilities().availability != RuntimeAvailability.AVAILABLE) return TerminalProjectAccess(false, "Terminal is unavailable")
        val resolution = workspace.resolve(project)
        return TerminalProjectAccess(
            available = resolution.terminalAccessible,
            explanation = resolution.explanation ?: "The selected project location is not an accessible terminal folder".takeUnless { resolution.terminalAccessible },
        )
    }

    override suspend fun workingDirectory(project: ProjectIdentity): String? =
        workspace.resolve(project).terminalPath
            ?.let(::File)
            ?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
            ?.absolutePath

    override suspend fun completeStorageSetupRequest(
        requestId: String,
        permission: TerminalStorageSetupPermission,
    ): OperationReport = installation.completeStorageSetupRequest(requestId, permission)

    override suspend fun createSession(workingDirectory: String?, name: String): SessionDescriptor =
        pty.createSession(workingDirectory, name)

    fun terminalSession(sessionId: String): TerminalSession? = pty.terminalSession(sessionId)
    fun bindTerminalView(sessionId: String, invalidate: () -> Unit) = pty.bindView(sessionId, invalidate)
    fun unbindTerminalView(sessionId: String) = pty.unbindView(sessionId)
    fun outputSnapshot(sessionId: String): String = pty.outputSnapshot(sessionId)
    fun isUsableWorkingDirectory(path: String): Boolean = pty.isUsableWorkingDirectory(path)

    override suspend fun listSessions(): List<SessionDescriptor> {
        val live = pty.liveDescriptors()
        val liveIds = live.mapTo(hashSetOf()) { it.id }
        return (persistence.readAll().filterNot { it.id in liveIds } + live).sortedBy { it.createdAt }
    }

    override suspend fun renameSession(sessionId: String, name: String): OperationReport {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return OperationReport(OperationOutcome.BLOCKED, "Enter a session name", ErrorCategory.PROCESS_LOSS)
        val live = pty.liveDescriptors().firstOrNull { it.id == sessionId }
        val stored = persistence.readAll().firstOrNull { it.id == sessionId }
        if (live == null && stored == null) return unavailable("The terminal session is unavailable")
        val updated = (live ?: stored!!).copy(name = cleanName)
        pty.updateDescriptor(sessionId, updated)
        persistence.upsert(updated)
        return OperationReport(OperationOutcome.COMPLETE, "Terminal session renamed", affectedIds = listOf(sessionId))
    }

    override suspend fun sendInput(sessionId: String, input: ByteArray): OperationReport = pty.sendInput(sessionId, input)
    override suspend fun readOutput(sessionId: String): ByteArray? = pty.readOutput(sessionId)
    override suspend fun resize(sessionId: String, columns: Int, rows: Int): OperationReport = pty.resize(sessionId, columns, rows)
    override suspend fun interrupt(sessionId: String): OperationReport = pty.interrupt(sessionId)
    override suspend fun listChildProcesses(sessionId: String): List<ChildProcessDescriptor> = pty.listChildProcesses(sessionId)
    override suspend fun terminateChildProcess(processId: String): OperationReport = pty.terminateChildProcess(processId)
    override suspend fun closeSession(sessionId: String): OperationReport = pty.closeSession(sessionId)
    override suspend fun closeAllSessions(): OperationReport = pty.closeAllSessions()

    fun shutdown() {
        pty.shutdown()
        installation.shutdown()
        terminalFeedbackListener = null
    }

    private fun unavailable(message: String) = OperationReport(OperationOutcome.BLOCKED, message, ErrorCategory.UNAVAILABLE_RUNTIME)

}
