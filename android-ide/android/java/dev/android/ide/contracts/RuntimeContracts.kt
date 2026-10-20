// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

import java.time.Instant

enum class SessionAvailability {
    AVAILABLE,
    UNAVAILABLE,
    EXPLICITLY_CLOSED,
    INVALIDATED,
}

enum class RuntimeAvailability {
    NOT_INITIALIZED,
    INITIALIZING,
    AVAILABLE,
    UNAVAILABLE,
    INVALIDATED,
}

data class RuntimeCapabilities(
    val availability: RuntimeAvailability,
    val architecture: String? = null,
    val nodeVersion: String? = null,
    val shellAvailable: Boolean = false,
    val ptyAvailable: Boolean = false,
    val executableFilesSupported: Boolean = false,
    val symlinksSupported: Boolean = false,
    val explanation: String? = null,
)

data class ChildProcessDescriptor(
    val id: String,
    val sessionId: String,
    val command: String,
    val processId: Long? = null,
    val workingDirectory: String? = null,
    val startedAt: Instant,
    val availability: SessionAvailability,
    val terminationReason: String? = null,
)

data class SessionDescriptor(
    val id: String,
    val ownerScope: String,
    val backendId: String? = null,
    val name: String = "Untitled session",
    val workingDirectory: String? = null,
    val createdAt: Instant,
    val availability: SessionAvailability,
    val terminationReason: String? = null,
)

data class EditorTabIdentity(
    val documentUri: String,
    val projectId: String,
    val isPinned: Boolean = false,
    val isDirty: Boolean = false,
)

data class BrowserTabIdentity(
    val id: String,
    val url: String?,
    val projectId: String? = null,
)
