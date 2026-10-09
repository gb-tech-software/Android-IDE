// ProjectRegistryStore owns registry persistence only; project workflows supply
// policy and use generic storage capabilities before persisting a registration.
package dev.android.ide.project

import dev.android.ide.data.ProjectRepository
import dev.android.ide.data.model.Project
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.MutationPreflight
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.ProjectRegistryAdapter
import dev.android.ide.contracts.ProjectStorageAdapter
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProjectRegistryStore(
    private val repository: ProjectRepository,
    private val storage: ProjectStorageAdapter,
) : ProjectRegistryAdapter {
    private val registrationMutex = Mutex()

    override suspend fun listRegistered(): List<ProjectIdentity> = repository.getAll().map(::toIdentity)

    fun warning(): String? = repository.readAll().warning

    fun findByLocation(value: String): ProjectIdentity? =
        repository.getAll()
            .firstOrNull { it.stableLocationId == value || it.uri == value }
            ?.let(::toIdentity)

    override suspend fun preflightRegistration(project: ProjectIdentity): MutationPreflight =
        registrationMutex.withLock { projectContainmentPreflight(project) }

    override suspend fun register(project: ProjectIdentity): OperationReport = registrationMutex.withLock {
        val capabilities = storage.inspectProjectStorage(project.location)
        if (capabilities.state != CapabilityState.SUPPORTED || !capabilities.readable || !capabilities.writable) {
            return@withLock blocked(
                capabilities.explanation ?: "Project files must be readable and writable to register this location",
                project.id,
            )
        }
        val preflight = projectContainmentPreflight(project)
        if (!preflight.allowed) return@withLock blocked(preflight.message, project.id)
        repository.upsert(project.toProject())
        complete("Project registered", project.id)
    }

    private suspend fun projectContainmentPreflight(project: ProjectIdentity): MutationPreflight {
        val existing = repository.getAll().filterNot { it.stableLocationId == project.location.stableId }
        for (other in existing) {
            val projectContainsOther = storage.isSameOrDescendant(
                project.location.stableId,
                other.stableLocationId,
            )
            val otherContainsProject = storage.isSameOrDescendant(
                other.stableLocationId,
                project.location.stableId,
            )
            if (projectContainsOther == null || otherContainsProject == null) {
                return MutationPreflight(
                    allowed = false,
                    message = "The new project cannot be registered until its location is verified against ${other.name}",
                    errorCategory = ErrorCategory.PERMISSION_LOST,
                )
            }
            if (projectContainsOther || otherContainsProject) {
                return MutationPreflight(
                    allowed = false,
                    message = "The project location overlaps the registered project ${other.name}",
                    errorCategory = ErrorCategory.DESTINATION_CONFLICT,
                )
            }
        }
        return MutationPreflight(true, "Project containment checks passed")
    }

    override suspend fun markUnavailable(
        projectId: String,
        reason: String,
        capabilityState: CapabilityState,
    ): OperationReport = registrationMutex.withLock {
        val project = repository.getAll().firstOrNull { it.stableLocationId == projectId }
            ?: return@withLock blocked("Project is not registered", projectId)
        repository.upsert(project.copy(
            capabilityState = capabilityState,
            capabilityMessage = reason,
        ))
        complete("Project marked unavailable", projectId)
    }

    override suspend fun remove(projectId: String): OperationReport = registrationMutex.withLock {
        if (!repository.remove(projectId)) blocked("Project is not registered", projectId)
        else complete("Project removed from registry", projectId)
    }

    private fun toIdentity(project: Project) = ProjectIdentity(
        id = project.stableLocationId,
        name = project.name,
        description = project.description,
        location = ProjectLocation(
            stableId = project.stableLocationId,
            displayLabel = project.locationLabel,
            userVisiblePath = project.uri,
            capabilityState = project.capabilityState,
            capabilityExplanation = project.capabilityMessage,
        ),
        registeredAt = Instant.ofEpochMilli(project.createdMs),
        lastOpenedAt = Instant.ofEpochMilli(project.lastOpenedMs),
    )

    private fun ProjectIdentity.toProject() = Project(
        name = name,
        description = description,
        uri = location.userVisiblePath ?: location.stableId,
        lastOpenedMs = lastOpenedAt?.toEpochMilli() ?: System.currentTimeMillis(),
        createdMs = registeredAt.toEpochMilli(),
        stableLocationId = location.stableId,
        locationLabel = location.displayLabel,
        capabilityState = location.capabilityState,
        capabilityMessage = location.capabilityExplanation,
    )

    private fun complete(message: String, id: String) = OperationReport(
        outcome = OperationOutcome.COMPLETE,
        message = message,
        affectedIds = listOf(id),
    )

    private fun blocked(message: String, id: String) = OperationReport(
        outcome = OperationOutcome.BLOCKED,
        message = message,
        affectedIds = listOf(id),
    )
}
