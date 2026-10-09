// ProjectDetailsWorkflow computes details from the authoritative project location.
// Metrics are intentionally not persisted because the user-visible project can
// change outside the application between openings.
package dev.android.ide.project

import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectRegistryAdapter
import dev.android.ide.data.model.Project
import dev.android.ide.data.model.ProjectDetails
import dev.android.ide.saf.FileManagementService
import dev.android.ide.contracts.StorageLocation

class ProjectDetailsWorkflow(
    private val registry: ProjectRegistryAdapter,
    private val files: FileManagementService,
    private val gitMetadata: GitMetadataReader,
) {
    suspend fun load(projectId: String): ProjectDetailsResult {
        val identity = registry.listRegistered().firstOrNull { it.id == projectId }
            ?: return ProjectDetailsResult.Unavailable("Project is not registered")
        val capabilities = files.inspectStorage(StorageLocation(identity.location.stableId, identity.location.displayLabel, identity.location.userVisiblePath))
        if (capabilities.state != CapabilityState.SUPPORTED) {
            return ProjectDetailsResult.Unavailable(
                capabilities.explanation ?: "Project location is unavailable",
            )
        }
        val metadata = files.inspectTree(identity.location.stableId)
            ?: return ProjectDetailsResult.Unavailable(
                "Project contents could not be inspected completely; metrics were not estimated",
            )
        val git = gitMetadata.read(identity.location.stableId)
        val project = Project(
            name = identity.name,
            description = identity.description,
            uri = identity.location.userVisiblePath ?: identity.location.stableId,
            lastOpenedMs = identity.lastOpenedAt?.toEpochMilli() ?: identity.registeredAt.toEpochMilli(),
            createdMs = identity.registeredAt.toEpochMilli(),
            stableLocationId = identity.location.stableId,
            locationLabel = identity.location.displayLabel,
            capabilityState = capabilities.state,
        )
        return ProjectDetailsResult.Loaded(
            ProjectDetails(
                project = project,
                description = metadata.description.ifBlank { identity.description },
                creationTimeMs = metadata.creationTimeMs ?: identity.registeredAt.toEpochMilli(),
                lastModifiedTimeMs = metadata.lastModifiedTimeMs,
                storageProvider = metadata.storageProvider,
                storagePath = metadata.storagePath,
                fileCount = metadata.fileCount,
                folderCount = metadata.folderCount,
                totalBytes = metadata.totalBytes,
                storageCapabilities = capabilities,
                languageBytes = metadata.languageBytes,
                git = git,
            ),
        )
    }
}

sealed interface ProjectDetailsResult {
    data class Loaded(val details: ProjectDetails) : ProjectDetailsResult
    data class Unavailable(val reason: String) : ProjectDetailsResult
}
