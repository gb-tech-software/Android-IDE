package dev.android.ide.project

import android.content.Context
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.StorageCapabilities
import dev.android.ide.data.model.ProjectDetails
import dev.android.ide.saf.FileManagementService

/**
 * Single project-domain entry point used by application surfaces.
 *
 * This coordinator owns project acquisition, metadata policy, registry state,
 * lifecycle mutations, inspection, export, and details. Internal workflows
 * remain implementation details; surfaces do not select one independently.
 */
class ProjectManagementService(
    context: Context,
    registry: ProjectRegistryStore,
    storage: ProjectStorageAdapterImpl,
    files: FileManagementService,
    metadata: ProjectMetadataStore,
    gitMetadata: GitMetadataReader,
) {
    private val registryStore = registry
    private val storageAdapter = storage
    private val state = ProjectStateWorkflow(registry, storage, metadata)
    private val acquisition = ProjectAcquisitionWorkflow(registry, storage, metadata, files, ProjectTemplateCatalog(context.assets))
    private val mutations = ProjectMutationWorkflow(registry, storage, metadata, files)
    private val details = ProjectDetailsWorkflow(registry, files, gitMetadata)

    suspend fun registeredProjects(): List<ProjectIdentity> = state.registeredProjects()
    fun registeredProjectAt(value: String): ProjectIdentity? = registryStore.findByLocation(value)
    suspend fun restore(projectId: String): ProjectRestoreResult = state.restore(projectId)
    suspend fun inspectStorage(location: ProjectLocation): StorageCapabilities = state.inspectProjectStorage(location)
    suspend fun isSameOrDescendant(rootUri: String, candidateUri: String): Boolean? =
        storageAdapter.isSameOrDescendant(rootUri, candidateUri)
    suspend fun registryWarning(): String? = registryStore.warning()
    suspend fun register(location: ProjectLocation, name: String, description: String): OperationReport =
        state.register(location, name, description)

    suspend fun createBlankProject(destinationParentUri: String, name: String, description: String, template: CreateProjectTemplate = CreateProjectTemplate.FROM_SCRATCH) =
        acquisition.createBlankProject(destinationParentUri, name, description, template)
    suspend fun importExistingFolder(projectRootUri: String, name: String? = null, description: String? = null) =
        acquisition.importExistingFolder(projectRootUri, name, description)
    suspend fun importZip(archiveUri: String, destinationParentUri: String, name: String, description: String) =
        acquisition.importZip(archiveUri, destinationParentUri, name, description)

    suspend fun removeFromRegistry(projectId: String) = mutations.removeFromRegistry(projectId)
    suspend fun permanentlyDelete(projectId: String) = mutations.permanentlyDelete(projectId)
    suspend fun exportProject(projectId: String, destinationFileUri: String) = mutations.exportProject(projectId, destinationFileUri)
    suspend fun duplicateProject(projectId: String, destinationParentUri: String, name: String, description: String? = null) =
        mutations.duplicateProject(projectId, destinationParentUri, name, description)
    suspend fun relocateProject(projectId: String, destinationParentUri: String, name: String) =
        mutations.relocateProject(projectId, destinationParentUri, name)
    suspend fun renameProject(projectId: String, name: String) = mutations.renameProject(projectId, name)
    suspend fun batchRemoveFromRegistry(projectIds: List<String>) = mutations.batchRemoveFromRegistry(projectIds)
    suspend fun exportProjects(projectIds: List<String>, destinationParentUri: String) = mutations.exportProjects(projectIds, destinationParentUri)
    suspend fun batchPermanentlyDelete(projectIds: List<String>) = mutations.batchPermanentlyDelete(projectIds)

    suspend fun loadDetails(projectId: String): ProjectDetailsResult = details.load(projectId)
}
