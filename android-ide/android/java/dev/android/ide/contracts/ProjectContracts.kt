// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

import java.time.Instant

enum class CapabilityState {
    NOT_YET_CHECKED,
    SUPPORTED,
    UNSUPPORTED,
    UNAVAILABLE,
    PERMISSION_LOST,
}

data class ProjectLocation(
    val stableId: String,
    val displayLabel: String,
    val userVisiblePath: String? = null,
    val capabilityState: CapabilityState = CapabilityState.NOT_YET_CHECKED,
    val capabilityExplanation: String? = null,
)

data class ProjectIdentity(
    val id: String,
    val name: String,
    val description: String,
    val location: ProjectLocation,
    val registeredAt: Instant,
    val lastOpenedAt: Instant? = null,
)

enum class Surface {
    HOME,
    DIAGNOSTICS,
    PROJECTS,
    PROJECT_DETAILS,
    EDITOR,
    STANDALONE_EDITOR,
    TERMINAL,
    BROWSER,
    SETTINGS,
}

data class NavigationState(
    val currentSurface: Surface = Surface.HOME,
    val selectedProjectId: String? = null,
    val previousSurface: Surface? = null,
)
