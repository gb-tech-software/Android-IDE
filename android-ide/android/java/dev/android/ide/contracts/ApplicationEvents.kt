// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

sealed interface ApplicationEvent {
    val entityId: String

    data class ProjectRegistered(override val entityId: String) : ApplicationEvent
    data class ProjectUnavailable(override val entityId: String, val reason: String) : ApplicationEvent
    data class StoragePermissionLost(override val entityId: String) : ApplicationEvent
    data class ProjectFileChanged(override val entityId: String, val path: ProjectRelativePath) : ApplicationEvent
    data class SessionAvailabilityChanged(
        override val entityId: String,
        val availability: SessionAvailability,
    ) : ApplicationEvent
    data class RuntimeAvailabilityChanged(
        override val entityId: String,
        val availability: RuntimeAvailability,
        val explanation: String? = null,
    ) : ApplicationEvent
    data class PackageOperationCompleted(
        override val entityId: String,
        val packages: List<String>,
        val outcome: OperationOutcome,
    ) : ApplicationEvent
    data class ChildProcessStarted(
        override val entityId: String,
        val sessionId: String,
    ) : ApplicationEvent
    data class ChildProcessExited(override val entityId: String, val exitCode: Int?) : ApplicationEvent
    data class BrowserTabUnavailable(override val entityId: String) : ApplicationEvent
    data class LifecycleChanged(override val entityId: String, val state: String) : ApplicationEvent
}

