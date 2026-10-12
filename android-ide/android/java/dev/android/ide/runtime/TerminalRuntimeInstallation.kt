package dev.android.ide.runtime

import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.RuntimeAvailability
import dev.android.ide.contracts.RuntimeCapabilities
import dev.android.ide.contracts.TerminalStorageSetupPermission
import dev.android.ide.saf.AndroidIdeDocumentsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Owns bundled runtime installation, readiness, environment, and storage setup. */
class TerminalRuntimeInstallation(
    private val installer: BundledTermuxRuntimeInstaller,
) {
    private val _progress = MutableStateFlow<String?>(null)
    val progress = _progress.asStateFlow()
    @Volatile private var ready = false

    val prefix: File get() = installer.prefix()
    val home: File get() = installer.home()
    /** Bash supplies readline-style handling for hardware cursor and editing keys. */
    val shell: File
        get() = File(prefix, "bin/bash").takeIf { it.canExecute() } ?: File(prefix, "bin/sh")
    val environment: Array<String> get() = installer.sessionEnvironment(shell.absolutePath)

    suspend fun initialize(persistedSessions: TerminalSessionPersistence): OperationReport = withContext(Dispatchers.IO) {
        ready = false
        val bootstrap = installer.initialize { _progress.value = it }
        if (bootstrap.outcome != OperationOutcome.COMPLETE) {
            _progress.value = null
            return@withContext bootstrap
        }
        val shellFailure = installer.shellStartupFailure()
        if (shellFailure != null) {
            _progress.value = null
            return@withContext OperationReport(
                OperationOutcome.BLOCKED,
                shellFailure,
                ErrorCategory.UNAVAILABLE_RUNTIME,
                recoveryHint = "Retry Terminal initialization after the bundled shell and terminal home are repaired.",
            )
        }
        ready = true
        persistedSessions.markAvailableUnavailable("The Android process was recreated; start a new terminal session")
        _progress.value = null
        OperationReport(OperationOutcome.COMPLETE, "Terminal runtime is ready. Install or remove packages with pkg when you choose.")
    }

    fun capabilities(): RuntimeCapabilities {
        val available = ready && prefix.isDirectory && shell.canExecute() &&
            home.isDirectory && home.canRead() && home.canWrite()
        return RuntimeCapabilities(
            availability = if (available) RuntimeAvailability.AVAILABLE else RuntimeAvailability.UNAVAILABLE,
            architecture = System.getProperty("os.arch"),
            shellAvailable = available,
            ptyAvailable = available,
            executableFilesSupported = available,
            symlinksSupported = available,
            explanation = if (available) null else "Terminal files were not found",
        )
    }

    fun providerRootLocation(): ProjectLocation? = installer.userFilesRoot().takeIf { it.exists() }?.let {
        ProjectLocation(
            stableId = AndroidIdeDocumentsProvider.rootTreeUri(),
            displayLabel = "Android IDE",
            userVisiblePath = AndroidIdeDocumentsProvider.rootTreeUri(),
        )
    }

    suspend fun completeStorageSetupRequest(
        requestId: String,
        permission: TerminalStorageSetupPermission,
    ): OperationReport = withContext(Dispatchers.IO) {
        val report = when {
            permission is TerminalStorageSetupPermission.Denied -> permission.report
            !ready -> OperationReport(
                OperationOutcome.BLOCKED,
                "Terminal must finish startup checks before shared-storage links can be created",
                ErrorCategory.UNAVAILABLE_RUNTIME,
            )
            else -> installer.rebuildStorageLinks()
        }
        installer.publishStorageSetupResult(requestId, report)
        report
    }

    fun shutdown() {
        ready = false
    }
}
