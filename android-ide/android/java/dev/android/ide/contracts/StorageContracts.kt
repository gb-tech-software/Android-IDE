// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

enum class OperationOutcome {
    COMPLETE,
    PARTIAL,
    BLOCKED,
    INTERRUPTED,
    FAILED,
    CANCELLED,
}

enum class ErrorCategory {
    PERMISSION_LOST,
    UNSUPPORTED_PROVIDER_CAPABILITY,
    DESTINATION_CONFLICT,
    INVALID_ARCHIVE,
    UNAVAILABLE_RUNTIME,
    PACKAGE_FAILURE,
    PROCESS_LOSS,
    MALFORMED_METADATA,
    EXTERNAL_FILE_CHANGE,
    CREDENTIAL_FAILURE,
    USER_CANCELLED,
}

data class StorageLocation(
    val stableId: String,
    val displayLabel: String,
    val userVisiblePath: String? = null,
)

/** Generic provider capabilities; this type has no project or Git semantics. */
data class StorageCapabilities(
    val state: CapabilityState,
    val readable: Boolean,
    val writable: Boolean,
    val canCreate: Boolean,
    val canRename: Boolean,
    val canDelete: Boolean,
    val canObserveChanges: Boolean,
    val explanation: String? = null,
    val localStorageConfirmed: Boolean = false,
    val localWorkspaceAvailable: Boolean = false,
    val localWorkspaceExplanation: String? = null,
)

data class OperationReport(
    val outcome: OperationOutcome,
    val message: String,
    val errorCategory: ErrorCategory? = null,
    val affectedIds: List<String> = emptyList(),
    val recoveryHint: String? = null,
)

data class MutationPreflight(
    val allowed: Boolean,
    val message: String,
    val errorCategory: ErrorCategory? = null,
)

enum class ProjectMutationIntent { CREATE, WRITE, DELETE }

data class ProjectMutationRequest(
    val project: ProjectIdentity,
    val path: ProjectRelativePath,
    val intent: ProjectMutationIntent,
    val directory: Boolean = false,
)

enum class LocationOperationKind {
    COPY,
    MOVE,
    IMPORT,
    EXPORT,
    RELOCATE,
    DELETE,
}

data class LocationOperationRequest(
    val kind: LocationOperationKind,
    val source: ProjectLocation?,
    val destination: ProjectLocation?,
    val projectId: String,
)

/** Project-relative path used by adapters; provider-specific URIs stay behind them. */
typealias ProjectRelativePath = String

/** Stable provider location identity used only for same-root/ancestry comparisons. */
typealias ProjectLocationId = String

/** A selected provider resource presented to the shared filesystem boundary. */
data class FileSystemLocation(
    val resourceUri: String,
    val displayLabel: String,
)

/** Stable application-facing identity for any mounted file or directory. */
data class FileSystemNode(
    val virtualPath: String,
    val displayLabel: String,
    val isDirectory: Boolean,
    val parentVirtualPath: String? = null,
)

data class FileSystemResolution(
    val node: FileSystemNode?,
    val readable: Boolean,
    val writable: Boolean,
    val terminalAccessible: Boolean,
    /** A local path is returned only when the terminal runtime can use it. */
    val terminalPath: String? = null,
    val explanation: String? = null,
)

/** One provider-backed filesystem boundary for app files and picker-selected resources. */
interface FileSystemAdapter {
    suspend fun resolve(location: FileSystemLocation): FileSystemResolution
    suspend fun mountedRoots(): List<FileSystemNode> = emptyList()
    suspend fun children(parent: FileSystemNode): List<FileSystemNode> = emptyList()
}

interface ProjectStorageAdapter {
    suspend fun inspectProjectStorage(location: ProjectLocation): StorageCapabilities
    /** Returns null when the provider cannot safely inspect either location. */
    suspend fun isSameOrDescendant(root: ProjectLocationId, candidate: ProjectLocationId): Boolean?
    /** Null means an inspection/permission failure; an empty list means a verified empty directory. */
    suspend fun list(project: ProjectIdentity, path: ProjectRelativePath): List<ProjectRelativePath>?
    /** Null means unavailable/inaccessible; a zero-byte array is a valid empty document. */
    suspend fun read(project: ProjectIdentity, path: ProjectRelativePath): ByteArray?
    suspend fun write(project: ProjectIdentity, path: ProjectRelativePath, content: ByteArray): OperationReport
    suspend fun preflightMutation(request: ProjectMutationRequest): MutationPreflight
    suspend fun preflightLocationOperation(request: LocationOperationRequest): MutationPreflight
    suspend fun executeLocationOperation(request: LocationOperationRequest): OperationReport
    suspend fun verifyLocationOperation(request: LocationOperationRequest): OperationReport
    /** Null means observation is unsupported or could not be established. Close to release observers. */
    suspend fun observeChanges(project: ProjectIdentity, listener: (ProjectRelativePath) -> Unit): AutoCloseable?
}

interface ProjectRegistryAdapter {
    suspend fun listRegistered(): List<ProjectIdentity>
    /** Checks project-root containment only; domain-specific runtime access is deliberately excluded. */
    suspend fun preflightRegistration(project: ProjectIdentity): MutationPreflight
    suspend fun register(project: ProjectIdentity): OperationReport
    suspend fun markUnavailable(
        projectId: String,
        reason: String,
        capabilityState: CapabilityState = CapabilityState.UNAVAILABLE,
    ): OperationReport
    suspend fun remove(projectId: String): OperationReport
}

interface ProjectMetadataAdapter {
    suspend fun ensurePortableState(project: ProjectIdentity): OperationReport
    suspend fun readIdentity(location: ProjectLocation): ProjectIdentity?
    suspend fun writeIdentity(project: ProjectIdentity): OperationReport
    suspend fun readWorkspaceDescriptor(projectId: String): ByteArray?
    suspend fun writeWorkspaceDescriptor(projectId: String, descriptor: ByteArray): OperationReport
}

interface RuntimeWorkspaceAdapter {
    suspend fun initialize(): OperationReport
    /** Runtime-owned storage is exposed as an ordinary provider location, not a project class. */
    suspend fun providerRootLocation(): ProjectLocation?
    suspend fun capabilities(): RuntimeCapabilities
}

interface TerminalRuntimeAdapter {
    /** Per-project terminal suitability; failure never affects project registration or editor access. */
    suspend fun inspectProjectAccess(project: ProjectIdentity): TerminalProjectAccess
    suspend fun workingDirectory(project: ProjectIdentity): String?
    suspend fun createSession(workingDirectory: String?, name: String = ""): SessionDescriptor
    /** Refreshes the selected live session's cwd from its shell process before inheritance. */
    suspend fun refreshSessionWorkingDirectory(sessionId: String): SessionDescriptor?
    /** Completes a shell-originated setup request and publishes its result to that command's output. */
    suspend fun completeStorageSetupRequest(
        requestId: String,
        permission: TerminalStorageSetupPermission,
    ): OperationReport
    suspend fun listSessions(): List<SessionDescriptor>
    suspend fun renameSession(sessionId: String, name: String): OperationReport
    suspend fun capabilities(): RuntimeCapabilities
    suspend fun sendInput(sessionId: String, input: ByteArray): OperationReport
    suspend fun readOutput(sessionId: String): ByteArray?
    suspend fun resize(sessionId: String, columns: Int, rows: Int): OperationReport
    suspend fun interrupt(sessionId: String): OperationReport
    suspend fun listChildProcesses(sessionId: String): List<ChildProcessDescriptor>
    suspend fun terminateChildProcess(processId: String): OperationReport
    suspend fun closeSession(sessionId: String): OperationReport
    suspend fun closeAllSessions(): OperationReport
}

data class TerminalProjectAccess(
    val available: Boolean,
    val explanation: String? = null,
)

sealed interface TerminalStorageSetupPermission {
    data object Granted : TerminalStorageSetupPermission
    data class Denied(val report: OperationReport) : TerminalStorageSetupPermission
}

object TerminalStorageSetupProtocol {
    const val ACTION = "dev.android.ide.action.TERMINAL_STORAGE_SETUP"
    const val REQUEST_KIND_EXTRA = "dev.android.ide.extra.STORAGE_SETUP_KIND"
    const val REQUEST_KIND_STORAGE = "storage"
    const val REQUEST_ID_EXTRA = "dev.android.ide.extra.STORAGE_SETUP_REQUEST_ID"
    const val RESULT_DIRECTORY = ".android-ide-storage-setup"
    const val ACCEPTED_SUFFIX = ".accepted"
}
