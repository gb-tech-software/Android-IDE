package dev.android.ide.postgres

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.android.ide.app.ApplicationServices
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PostgresViewModel(application: Application) : AndroidViewModel(application), PostgresStateSource {
    private val service = ApplicationServices.from(application).postgresApplicationService
    private val projectDatabasePreferences = application.getSharedPreferences(
        "postgres_project_database_selection",
        android.content.Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(PostgresState())
    override val state: StateFlow<PostgresState> = mutableState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                if (!mutableState.value.busy) refresh()
            }
        }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val status = runCatching { service.inspect() }
        status.onSuccess { latest ->
            mutableState.update { current -> current.copy(status = latest, loading = false) }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            mutableState.update { it.copy(loading = false, error = error.message ?: "PostgreSQL status is unavailable") }
        }
    }

    fun installPackage() = requestWithSpaceCheck(PostgresPendingAction.INSTALL)
    fun initializeCluster() = requestWithSpaceCheck(PostgresPendingAction.INITIALIZE)
    fun startServer() = requestWithSpaceCheck(PostgresPendingAction.START)

    fun saveContainerLimit(megabytes: String) = viewModelScope.launch(Dispatchers.IO) {
        val parsed = megabytes.toLongOrNull()
        if (parsed == null || parsed > Long.MAX_VALUE / 1_000_000L) {
            mutableState.update { it.copy(error = "Enter a whole-number container size in MB") }
            return@launch
        }
        if (mutableState.value.status.clusterInitialized) {
            mutableState.update { it.copy(error = "The PostgreSQL storage budget is fixed after cluster initialization") }
            return@launch
        }
        if (parsed * 1_000_000L > POSTGRES_MAX_DATA_BYTES) {
            mutableState.update { it.copy(error = "PostgreSQL databases share one aggregate 5 GB data cap") }
            return@launch
        }
        runCatching { service.setContainerLimit(parsed * 1_000_000L) }
            .onSuccess {
                mutableState.update { it.copy(feedback = "PostgreSQL storage budget saved") }
                refresh()
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.message ?: "The PostgreSQL storage budget could not be saved") }
            }
    }

    fun stopServer() = perform("Stopping PostgreSQL") {
        updateProgress("Requesting a clean PostgreSQL shutdown…")
        service.requestStop()
        withTimeout(70_000) {
            while (true) {
                val latest = service.inspect()
                mutableState.update { it.copy(status = latest) }
                if (latest.serverState == PostgresServerState.STOPPED) return@withTimeout
                if (service.hasStopRequestFailed()) throw IOException(latest.lastStopReason ?: "PostgreSQL could not shut down cleanly; check recent logs.")
                delay(1_500)
            }
        }
    }

    fun uninstallPackage() = perform("Removing PostgreSQL binaries") {
        service.uninstallPackage()
    }

    fun updatePackage() = requestWithSpaceCheck(PostgresPendingAction.UPDATE)

    fun createDatabase(name: String) = perform("Creating database") {
        service.createDatabase(name.trim())
    }

    fun deleteDatabase(name: String) = perform("Removing database") {
        service.deleteDatabase(name)
    }

    fun selectedDatabaseForProject(projectId: String?): String? =
        projectDatabasePreferences.getString(projectDatabasePreferenceKey(projectId), null)

    fun selectDatabaseForProject(projectId: String?, database: String) {
        projectDatabasePreferences.edit()
            .putString(projectDatabasePreferenceKey(projectId), database)
            .apply()
    }

    private fun projectDatabasePreferenceKey(projectId: String?): String =
        "database:${projectId?.takeIf(String::isNotBlank) ?: "_default_project_"}"

    fun exportDatabase(name: String, destination: Uri) = perform("Exporting $name") {
        service.exportDatabase(name, destination, ::updateProgress)
    }

    fun importDatabase(source: Uri, newDatabaseName: String) = perform("Restoring database") {
        service.importDatabase(source, newDatabaseName.trim(), ::updateProgress)
    }

    fun loadConnectionUri(database: String) = viewModelScope.launch(Dispatchers.IO) {
        val uri = runCatching { service.connectionUri(database) }.getOrNull()
        mutableState.update {
            it.copy(
                revealedConnectionUri = uri,
                revealedConnectionDatabase = database.takeIf { uri != null },
                error = if (uri == null) "Connection credentials are unavailable" else null,
            )
        }
    }

    fun hideConnectionUri() = mutableState.update { it.copy(revealedConnectionUri = null, revealedConnectionDatabase = null) }

    fun clearFeedback() = mutableState.update { it.copy(feedback = null, error = null) }
    fun reportFeedback(message: String) = mutableState.update { it.copy(feedback = message, error = null) }
    fun loadServiceLog() = viewModelScope.launch(Dispatchers.IO) {
        mutableState.update { it.copy(logLoading = true, serviceLog = null) }
        runCatching { service.readServiceLog() }
            .onSuccess { value -> mutableState.update { it.copy(logLoading = false, serviceLog = value) } }
            .onFailure { error ->
                if (error is CancellationException) throw error
                val message = error.message ?: "The PostgreSQL log could not be read"
                mutableState.update { it.copy(logLoading = false, serviceLog = message, error = message) }
            }
    }
    fun dismissServiceLog() = mutableState.update { it.copy(serviceLog = null) }
    fun clearStopReason() = viewModelScope.launch(Dispatchers.IO) {
        service.clearStopReason()
        refresh()
    }

    private fun requestWithSpaceCheck(action: PostgresPendingAction) = viewModelScope.launch(Dispatchers.IO) {
        val latest = runCatching { service.inspect() }.getOrElse { error ->
            if (error is CancellationException) throw error
            mutableState.update { it.copy(error = error.message ?: "PostgreSQL status is unavailable", loading = false) }
            return@launch
        }
        mutableState.update { it.copy(status = latest, loading = false) }
        val minimumBytes = when (action) {
            PostgresPendingAction.INSTALL, PostgresPendingAction.UPDATE -> POSTGRES_MIN_PACKAGE_SPACE_BYTES
            PostgresPendingAction.INITIALIZE, PostgresPendingAction.START -> POSTGRES_MIN_START_SPACE_BYTES
        }
        if (latest.availableBytes < minimumBytes) {
            mutableState.update { it.copy(error = "Free at least ${minimumBytes / (1024L * 1024L)} MB before this PostgreSQL operation") }
            return@launch
        }
        if (latest.storageWarning) {
            mutableState.update {
                it.copy(error = "The selected ${latest.containerLimitBytes / 1_000_000} MB PostgreSQL budget exceeds the current maximum of ${latest.maximumContainerBytes / 1_000_000} MB while preserving Android's 0.5 GB reserve. Lower and save the budget or free device space.")
            }
            return@launch
        }
        when (action) {
            PostgresPendingAction.INSTALL -> perform("Installing PostgreSQL") { service.installPackage(::updateProgress) }
            PostgresPendingAction.UPDATE -> perform("Updating PostgreSQL tools") { service.updatePackage(::updateProgress) }
            PostgresPendingAction.INITIALIZE -> perform("Creating PostgreSQL cluster") { service.initializeCluster(::updateProgress) }
            PostgresPendingAction.START -> startAndWait()
        }
    }

    private fun startAndWait() = perform("Starting PostgreSQL") {
        updateProgress("Starting the local database service…")
        service.requestStart()
        withTimeout(70_000) {
            while (true) {
                val latest = service.inspect()
                mutableState.update { it.copy(status = latest) }
                if (latest.serverState == PostgresServerState.RUNNING) return@withTimeout
                if (!latest.lastStopReason.isNullOrBlank()) throw IOException(latest.lastStopReason)
                delay(1_500)
            }
        }
    }

    private fun perform(successMessage: String, operation: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        mutableState.update { it.copy(busy = true, progress = successMessage, feedback = null, error = null, revealedConnectionUri = null) }
        runCatching { operation() }
            .onSuccess {
                val latest = runCatching { service.inspect() }.getOrNull()
                mutableState.update { it.copy(busy = false, progress = null, status = latest ?: it.status, feedback = successMessage) }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(busy = false, progress = null, error = error.message ?: "$successMessage failed")
                }
            }
    }

    private fun updateProgress(message: String) {
        mutableState.update { it.copy(progress = message.take(180)) }
    }
}
