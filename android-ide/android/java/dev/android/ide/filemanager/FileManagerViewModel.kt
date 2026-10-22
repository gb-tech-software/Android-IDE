package dev.android.ide.filemanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.android.ide.app.ApplicationServices
import dev.android.ide.contracts.FileSystemNode
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class FileManagerViewModel(application: Application) : AndroidViewModel(application), FileManagerStateSource {
    private val services = ApplicationServices.from(application)
    private val service = services.fileManagerApplicationService
    private val operations = services.projectOperations
    private val mutableState = MutableStateFlow(FileManagerState(loading = true))
    override val state: StateFlow<FileManagerState> = mutableState.asStateFlow()
    private var sortMetadataJob: Job? = null
    private var sortMetadataRequest: Set<String> = emptySet()

    init {
        refreshRoots()
        viewModelScope.launch {
            operations.operations.collect { active ->
                val fileOperations = active.filter { it.kind == FILE_MANAGER_OPERATION_KIND }
                mutableState.update { it.copy(busy = fileOperations.isNotEmpty(), operationLabel = fileOperations.firstOrNull()?.label) }
            }
        }
    }

    fun refreshRoots() = viewModelScope.launch(Dispatchers.IO) {
        sortMetadataJob?.cancel()
        sortMetadataRequest = emptySet()
        mutableState.update { it.copy(sortMetadata = emptyMap(), sortMetadataLoading = false) }
        mutableState.update { it.copy(loading = true, error = null) }
        runCatching { service.roots() }
            .onSuccess { roots ->
                mutableState.update { it.copy(roots = roots, loading = false) }
                val current = mutableState.value.current
                if (current == null && roots.isNotEmpty()) openRoot(roots.first()) else current?.let(::open)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = error.message ?: "Storage could not be read") }
            }
    }

    fun openRoot(node: FileSystemNode) {
        mutableState.update { it.copy(ancestors = emptyList()) }
        open(node)
    }

    fun openChild(node: FileSystemNode) {
        if (!node.isDirectory) {
            mutableState.update { it.copy(feedback = "Files are available through the editor and Android share actions") }
            return
        }
        val current = mutableState.value.current ?: return
        mutableState.update { it.copy(ancestors = it.ancestors + current) }
        open(node)
    }

    fun openBreadcrumb(index: Int) {
        val chain = mutableState.value.breadcrumbs
        if (index !in chain.indices) return
        mutableState.update { it.copy(ancestors = chain.take(index)) }
        open(chain[index])
    }

    fun createFolder(name: String) {
        val parent = mutableState.value.current ?: return
        runInBackground("Creating folder") { service.createFolder(parent, name) }
    }

    fun rename(node: FileSystemNode, name: String) =
        runInBackground("Renaming ${node.displayLabel}") { service.rename(node, name) }

    fun delete(node: FileSystemNode) =
        runInBackground("Deleting ${node.displayLabel}") { service.delete(node) }

    fun deleteMany(nodes: List<FileSystemNode>) = runInBackground("Deleting ${nodes.size} items") {
        nodes.isNotEmpty() && nodes.all { service.delete(it) }
    }

    /** Resolves an OpenDocumentTree result, then runs the existing secure operation. */
    fun copyOrMoveToPickedFolder(node: FileSystemNode, resourceUri: String, move: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val destination = runCatching { service.resolveLocation(resourceUri) }.getOrNull()
        if (destination == null) {
            mutableState.update { it.copy(error = "The selected destination folder is not accessible") }
        } else if (move) {
            moveTo(node, destination)
        } else {
            copyTo(node, destination)
        }
    }

    fun copyTo(node: FileSystemNode, destination: FileSystemNode) =
        runInBackground("Copying ${node.displayLabel}") { service.copy(node, destination) }

    fun moveTo(node: FileSystemNode, destination: FileSystemNode) =
        runInBackground("Moving ${node.displayLabel}") { service.move(node, destination) }

    fun importFrom(uris: List<String>) {
        val destination = mutableState.value.current ?: return
        runInBackground("Importing ${uris.size} item${if (uris.size == 1) "" else "s"}") {
            uris.isNotEmpty() && uris.all { service.import(it, destination) }
        }
    }

    fun export(nodes: List<FileSystemNode>, destinationUri: String) =
        runInBackground("Exporting ${nodes.size} item${if (nodes.size == 1) "" else "s"}") { service.export(nodes, destinationUri) }

    fun share(nodes: List<FileSystemNode>) {
        operations.start(
            label = "Preparing share",
            kind = FILE_MANAGER_OPERATION_KIND,
            resources = emptyList(),
            prompt = "Preparing selected files for Android sharing. This continues if you leave File Manager.",
            background = true,
            operation = {
                val uris = service.prepareShare(nodes)
                if (uris.isEmpty()) throw IOException("No files could be prepared for sharing")
                OperationReport(OperationOutcome.COMPLETE, "Ready to share", affectedIds = uris)
            },
            onComplete = { _, report ->
                if (report.outcome == OperationOutcome.COMPLETE) mutableState.update { it.copy(pendingShareUris = report.affectedIds) }
                else mutableState.update { it.copy(error = report.message) }
            },
        )
    }

    fun openWith(node: FileSystemNode) {
        operations.start(
            label = "Preparing ${node.displayLabel}",
            kind = FILE_MANAGER_OPERATION_KIND,
            resources = emptyList(),
            prompt = "Preparing this file for an Android app. This continues if you leave File Manager.",
            background = true,
            operation = {
                val uri = service.prepareShare(listOf(node)).firstOrNull() ?: throw IOException("Could not prepare the file")
                val mime = service.details(node).mimeType ?: "*/*"
                OperationReport(OperationOutcome.COMPLETE, "File ready", affectedIds = listOf(uri, mime))
            },
            onComplete = { _, report ->
                if (report.outcome == OperationOutcome.COMPLETE && report.affectedIds.size >= 2) {
                    mutableState.update { it.copy(pendingOpenUri = report.affectedIds[0], pendingOpenMimeType = report.affectedIds[1]) }
                } else mutableState.update { it.copy(error = report.message) }
            },
        )
    }

    fun loadDetails(node: FileSystemNode) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { service.details(node) }
            .onSuccess { mutableState.update { state -> state.copy(itemDetails = it) } }
            .onFailure { mutableState.update { state -> state.copy(error = it.message ?: "Details are unavailable") } }
    }

    fun loadSortMetadata(nodes: List<FileSystemNode>) {
        val current = mutableState.value
        val missing = nodes.filter { it.virtualPath !in current.sortMetadata }
        if (missing.isEmpty()) return
        val request = nodes.map { it.virtualPath }.toSet()
        if (sortMetadataRequest == request && sortMetadataJob?.isActive == true) return
        sortMetadataJob?.cancel()
        sortMetadataRequest = request
        mutableState.update { it.copy(sortMetadataLoading = true) }
        sortMetadataJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = missing.chunked(4).flatMap { batch ->
                    coroutineScope {
                        batch.map { node ->
                            async { node.virtualPath to runCatching { service.details(node) }.getOrNull() }
                        }.awaitAll()
                    }
                }.associate { (path, details) ->
                    path to FileManagerSortMetadata(details?.sizeBytes, details?.modifiedMillis)
                }
                mutableState.update { it.copy(sortMetadata = it.sortMetadata + loaded, sortMetadataLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(sortMetadataLoading = false) }
            }
        }
    }

    fun clearDetails() = mutableState.update { it.copy(itemDetails = null) }
    fun consumeShare() = mutableState.update { it.copy(pendingShareUris = null) }
    fun consumeOpenWith() = mutableState.update { it.copy(pendingOpenUri = null, pendingOpenMimeType = null) }

    fun report(message: String) = mutableState.update { it.copy(feedback = message) }
    fun clearFeedback() = mutableState.update { it.copy(feedback = null, error = null) }

    private fun runInBackground(label: String, operation: suspend () -> Boolean) {
        val current = mutableState.value.current
        operations.start(
            label = label,
            kind = FILE_MANAGER_OPERATION_KIND,
            resources = emptyList(),
            prompt = "$label. This operation continues if you leave File Manager. A persistent notification is shown while it runs.",
            background = true,
            operation = {
                if (!operation()) throw IOException("$label could not be completed. Check storage access and destination permissions.")
                OperationReport(OperationOutcome.COMPLETE, "$label complete")
            },
            onComplete = { _, report ->
                val succeeded = report.outcome == OperationOutcome.COMPLETE
                if (succeeded) {
                    sortMetadataJob?.cancel()
                    sortMetadataRequest = emptySet()
                }
                mutableState.update {
                    it.copy(
                        feedback = if (succeeded) report.message else null,
                        error = if (succeeded) null else report.message,
                        sortMetadata = if (succeeded) emptyMap() else it.sortMetadata,
                        sortMetadataLoading = if (succeeded) false else it.sortMetadataLoading,
                    )
                }
                if (succeeded) current?.let(::open)
            },
        )
    }

    private fun open(node: FileSystemNode) = viewModelScope.launch(Dispatchers.IO) {
        mutableState.update { it.copy(current = node, loading = true, error = null) }
        runCatching { service.children(node) }
            .onSuccess { children ->
                mutableState.update {
                    it.copy(
                        entries = children.sortedWith(
                            compareByDescending<FileSystemNode> { child -> child.isDirectory }
                                .thenBy { child -> child.displayLabel.lowercase(Locale.ROOT) },
                        ),
                        loading = false,
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update { it.copy(entries = emptyList(), loading = false, error = error.message ?: "This location is no longer accessible") }
            }
    }

    private companion object {
        const val FILE_MANAGER_OPERATION_KIND = "file-manager"
    }
}
