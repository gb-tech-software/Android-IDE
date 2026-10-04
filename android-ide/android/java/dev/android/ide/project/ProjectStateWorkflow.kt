// ProjectStateWorkflow owns project registration, capability reporting, and
// metadata initialization without taking ownership of user project contents.
package dev.android.ide.project

import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.ProjectStorageCapabilities
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.ProjectMetadataAdapter
import dev.android.ide.contracts.ProjectRegistryAdapter
import dev.android.ide.contracts.ProjectStorageAdapter
import java.time.Instant

class ProjectStateWorkflow(
    private val registry: ProjectRegistryAdapter,
    private val storage: ProjectStorageAdapter,
    private val metadata: ProjectMetadataAdapter,
) {
    /**
     * Project-list reads are registry reads only. Storage permission and metadata
     * checks belong to an explicit project-open or project-operation request.
     */
    suspend fun registeredProjects(): List<ProjectIdentity> = registry.listRegistered()

    suspend fun inspectProjectStorage(location: ProjectLocation): ProjectStorageCapabilities =
        storage.inspectProjectStorage(location)

    suspend fun restore(projectId: String): ProjectRestoreResult {
        val existing = registry.listRegistered().firstOrNull { it.id == projectId }
            ?: return ProjectRestoreResult.Unavailable("Project is not registered")
        val capabilities = storage.inspectProjectStorage(existing.location)
        if (capabilities.state != CapabilityState.SUPPORTED || !capabilities.readable || !capabilities.writable) {
            registry.markUnavailable(
                projectId,
                capabilities.explanation ?: "Project location is unavailable",
                capabilities.state,
            )
            return ProjectRestoreResult.Unavailable(
                capabilities.explanation ?: "Project location is unavailable",
            )
        }
        val identity = metadata.readIdentity(existing.location) ?: existing
        val initialized = metadata.ensurePortableState(identity)
        if (initialized.outcome != OperationOutcome.COMPLETE) {
            registry.markUnavailable(projectId, initialized.message)
            return ProjectRestoreResult.Unavailable(initialized.message)
        }
        val restoredIdentity = identity.copy(
            location = identity.location.copy(
                capabilityState = capabilities.state,
                capabilityExplanation = capabilities.explanation,
            ),
            lastOpenedAt = Instant.now(),
        )
        registry.register(restoredIdentity)
        return ProjectRestoreResult.Restored(restoredIdentity, capabilities)
    }

    suspend fun register(
        location: ProjectLocation,
        name: String,
        description: String,
    ): OperationReport {
        val capabilities = storage.inspectProjectStorage(location)
        if (capabilities.state != CapabilityState.SUPPORTED || !capabilities.readable || !capabilities.writable) {
            return OperationReport(
                outcome = OperationOutcome.BLOCKED,
                message = capabilities.explanation ?: "Project files must be readable and writable to register this location",
            )
        }
        val identity = ProjectIdentity(
            id = location.stableId,
            name = name,
            description = description,
            location = location.copy(
                capabilityState = capabilities.state,
                capabilityExplanation = capabilities.explanation,
            ),
            registeredAt = Instant.now(),
            lastOpenedAt = Instant.now(),
        )
        val containment = registry.preflightRegistration(identity)
        if (!containment.allowed) {
            return OperationReport(
                outcome = OperationOutcome.BLOCKED,
                message = containment.message,
                errorCategory = containment.errorCategory ?: dev.android.ide.contracts.ErrorCategory.DESTINATION_CONFLICT,
            )
        }
        val metadataResult = metadata.ensurePortableState(identity)
        if (metadataResult.outcome != OperationOutcome.COMPLETE) return metadataResult
        val written = metadata.writeIdentity(identity)
        if (written.outcome != OperationOutcome.COMPLETE) return written
        val persisted = metadata.readIdentity(identity.location)
        if (persisted == null || persisted.id != identity.id || persisted.name != identity.name ||
            persisted.description != identity.description ||
            persisted.location.stableId != identity.location.stableId
        ) {
            return OperationReport(
                outcome = OperationOutcome.FAILED,
                message = "The project identity could not be verified after writing portable metadata",
                errorCategory = dev.android.ide.contracts.ErrorCategory.MALFORMED_METADATA,
                recoveryHint = "Keep the project files unchanged, restore storage access, and retry registration",
            )
        }
        return registry.register(identity)
    }
}

sealed interface ProjectRestoreResult {
    data class Restored(
        val identity: ProjectIdentity,
        val capabilities: ProjectStorageCapabilities,
    ) : ProjectRestoreResult

    data class Unavailable(val reason: String) : ProjectRestoreResult
}
