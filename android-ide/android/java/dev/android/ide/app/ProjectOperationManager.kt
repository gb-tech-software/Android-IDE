package dev.android.ide.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.android.ide.contracts.OperationReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/** A single independently tracked project-file operation. */
data class BackgroundProjectOperation(
    val id: String,
    val label: String,
    val kind: String,
    val resources: List<String>,
    val prompt: String,
    val background: Boolean = false,
)

/**
 * Application-scoped operation runner. Its scope is independent of any screen
 * ViewModel, so leaving a dialog or changing surfaces does not cancel work.
 * Resource exclusion is shared with the synchronous project-management API.
 */
class ProjectOperationManager(
    private val context: Context,
) {
    private val serviceHandler = Handler(Looper.getMainLooper())
    private val serviceRequestLock = Any()
    private val stopServiceIfIdle = Runnable {
        synchronized(serviceRequestLock) {
            if (_operations.value.none { it.background }) {
                context.stopService(android.content.Intent(context, ProjectOperationForegroundService::class.java))
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _operations = MutableStateFlow<List<BackgroundProjectOperation>>(emptyList())
    val operations: StateFlow<List<BackgroundProjectOperation>> = _operations.asStateFlow()

    fun start(
        label: String,
        kind: String,
        resources: Collection<String>,
        operation: suspend () -> OperationReport,
        onComplete: (String, OperationReport) -> Unit,
        prompt: String = buildString {
            append(label)
            append("\n\n")
            append(
                when (kind) {
                    "acquisition" -> "This workflow will create or acquire a project and register it in Android IDE."
                    "export" -> "This workflow will read the selected project files and write an archive to the chosen destination."
                    "file-import" -> "This workflow will copy the selected files into the project while protecting the destination from conflicting mutations."
                    else -> "This workflow will apply the requested project-file mutation while protecting its resources from conflicting operations."
                },
            )
            append("\n\nResources:\n")
            append(resources.joinToString("\n"))
        },
        background: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        _operations.update { it + BackgroundProjectOperation(id, label, kind, resources.toList(), prompt, background) }
        jobs[id] = scope.launch {
            val report = runCatching {
                // ProjectManagementService owns the same process-wide lock for
                // its workflow calls. The resource list is retained by the
                // caller for diagnostics and direct file operations.
                operation()
            }.getOrElse { error ->
                OperationReport(
                    dev.android.ide.contracts.OperationOutcome.FAILED,
                    error.message?.takeIf(String::isNotBlank) ?: "$label failed unexpectedly",
                    dev.android.ide.contracts.ErrorCategory.MALFORMED_METADATA,
                )
            }
            jobs.remove(id)
            _operations.update { it.filterNot { operation -> operation.id == id } }
            updateForegroundService()
            onComplete(id, report)
        }
        updateForegroundService()
        return id
    }

    fun keepInBackground(id: String) {
        _operations.update { operations -> operations.map {
            if (it.id == id) it.copy(background = true) else it
        } }
        updateForegroundService()
    }

    fun isRunning(id: String?): Boolean = id != null && _operations.value.any { it.id == id }

    private fun updateForegroundService() {
        serviceHandler.post {
            synchronized(serviceRequestLock) {
                val background = _operations.value.filter { it.background }
                if (background.isEmpty()) {
                    serviceHandler.removeCallbacks(stopServiceIfIdle)
                    serviceHandler.postDelayed(stopServiceIfIdle, SERVICE_STOP_GRACE_MS)
                } else {
                    serviceHandler.removeCallbacks(stopServiceIfIdle)
                    val intent = android.content.Intent(context, ProjectOperationForegroundService::class.java).apply {
                        putExtra(ProjectOperationForegroundService.EXTRA_COUNT, background.size)
                        putExtra(ProjectOperationForegroundService.EXTRA_LABEL, background.first().label)
                    }
                    // All start/stop requests are issued serially on the main looper.
                    // The grace-period stop lets a just-started service execute
                    // onCreate/startForeground before a fast operation can stop it.
                    ContextCompat.startForegroundService(context, intent)
                }
            }
        }
    }

    private companion object {
        const val SERVICE_STOP_GRACE_MS = 1_500L
    }
}
