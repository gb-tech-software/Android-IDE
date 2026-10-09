package dev.android.ide.workspace

import dev.android.ide.contracts.FileSystemAdapter
import dev.android.ide.contracts.FileSystemLocation
import dev.android.ide.contracts.FileSystemResolution
import dev.android.ide.contracts.ProjectIdentity

/**
 * Resolves a registered project into the shared filesystem view used by tools.
 *
 * This service owns no project registry state and does not mutate files. It
 * simply gives tool domains one authoritative interpretation of provider access,
 * native-path availability, and terminal suitability.
 */
class ProjectWorkspaceService(
    private val fileSystem: FileSystemAdapter,
) {
    suspend fun resolve(project: ProjectIdentity): FileSystemResolution =
        fileSystem.resolve(
            FileSystemLocation(
                resourceUri = project.location.userVisiblePath ?: project.location.stableId,
                displayLabel = project.location.displayLabel,
            ),
        )
}
