package dev.android.ide.runtime

import dev.android.ide.editor.LanguageServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Owns the pinned language-server package set independently of Terminal. */
class LanguageServerPackageInstaller(
    private val packageManager: IdePackageManager,
    private val runtime: IdeNodeRuntime,
) {
    enum class State { DISABLED, QUEUED, INSTALLING, READY, FAILED }
    enum class Operation { NONE, INSTALL, UPDATE, CHECK, REMOVE }
    data class Snapshot(
        val state: State,
        val message: String?,
        val canRetry: Boolean = false,
        val operation: Operation = Operation.NONE,
        val installed: Boolean = false,
        val version: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Mutex()
    private val root = File(runtime.root, "language-servers")
    private val spec = IdePackageManager.PackageSpec(
        id = "language-servers",
        root = root,
        manifestAsset = PACKAGE_MANIFEST_ASSET,
        lockfileAsset = PACKAGE_LOCK_ASSET,
        requiredExecutables = LanguageServerRegistry.definitions.map { it.requiredExecutable }.toSet(),
    )
    private val mutableState = MutableStateFlow(Snapshot(State.DISABLED, null))
    val state: StateFlow<Snapshot> = mutableState.asStateFlow()
    @Volatile private var enabled = false
    @Volatile private var explicitInstallRequested = false
    @Volatile private var activeOperation: Operation? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            explicitInstallRequested = false
            if (activeOperation == null) {
                val status = packageManager.validate(spec)
                mutableState.value = Snapshot(State.DISABLED, null, installed = status.installed, version = status.version)
            }
            return
        }
        refresh()
    }

    fun installExplicitly(operation: Operation = Operation.INSTALL): Boolean {
        synchronized(this) {
            if (activeOperation != null) return false
            activeOperation = operation
            explicitInstallRequested = true
            mutableState.value = Snapshot(State.QUEUED, operation.message(), operation = operation)
        }
        scope.launch { installNow(operation) }
        return true
    }

    fun retry(): Boolean {
        if (!enabled) return false
        return refresh()
    }

    fun refresh(): Boolean {
        if (!enabled && !explicitInstallRequested) return false
        synchronized(this) {
            if (activeOperation != null) return false
            activeOperation = Operation.CHECK
            mutableState.value = Snapshot(State.QUEUED, CHECKING_MESSAGE, operation = Operation.CHECK)
        }
        scope.launch {
            lifecycleLock.withLock {
                val status = packageManager.validate(spec)
                if (status.installed) {
                    explicitInstallRequested = false
                    mutableState.value = Snapshot(State.READY, READY_MESSAGE, operation = Operation.CHECK, installed = true, version = status.version)
                } else {
                    mutableState.value = Snapshot(State.FAILED, INSTALL_REQUIRED_MESSAGE, canRetry = true, operation = Operation.CHECK)
                }
            }
            activeOperation = null
        }
        return true
    }

    suspend fun arePackagesInstalled(): Boolean = packageManager.validate(spec).installed
    fun packageStatus(): IdePackageManager.PackageStatus = packageManager.validate(spec)

    fun executablePath(command: String): File = File(root, "node_modules/.bin/$command")

    suspend fun removePackages(): Boolean {
        synchronized(this) {
            if (activeOperation != null) return false
            activeOperation = Operation.REMOVE
            mutableState.value = Snapshot(State.QUEUED, REMOVING_MESSAGE, operation = Operation.REMOVE)
        }
        return try {
            lifecycleLock.withLock {
                explicitInstallRequested = false
                enabled = false
                mutableState.value = Snapshot(State.INSTALLING, REMOVING_MESSAGE, operation = Operation.REMOVE, installed = true)
                val removed = packageManager.remove(spec)
                mutableState.value = Snapshot(State.DISABLED, null, operation = Operation.REMOVE)
                removed
            }
        } finally {
            activeOperation = null
        }
    }

    private suspend fun installNow(operation: Operation) {
        try {
            lifecycleLock.withLock {
                if (!explicitInstallRequested && !enabled) return@withLock
                mutableState.value = Snapshot(State.INSTALLING, operation.message(), operation = operation)
                runCatching {
                    if (operation == Operation.UPDATE) packageManager.update(spec) else packageManager.install(spec)
                }
                .onSuccess {
                    if (explicitInstallRequested || enabled) {
                        explicitInstallRequested = false
                        val status = packageManager.validate(spec)
                        mutableState.value = Snapshot(State.READY, operation.readyMessage(), operation = operation, installed = true, version = status.version)
                    }
                }
                .onFailure { error ->
                    if (explicitInstallRequested || enabled) {
                        explicitInstallRequested = false
                        mutableState.value = Snapshot(
                            State.FAILED,
                            "Language-server package setup failed: ${error.message ?: error.javaClass.simpleName}",
                            canRetry = true,
                            operation = operation,
                        )
                    }
                }
            }
        } finally {
            activeOperation = null
        }
    }

    private fun Operation.message(): String = when (this) {
        Operation.INSTALL -> INSTALLING_MESSAGE
        Operation.UPDATE -> UPDATING_MESSAGE
        Operation.CHECK -> CHECKING_MESSAGE
        Operation.REMOVE -> REMOVING_MESSAGE
        Operation.NONE -> CHECKING_MESSAGE
    }

    private fun Operation.readyMessage(): String = when (this) {
        Operation.UPDATE -> UPDATED_MESSAGE
        else -> READY_MESSAGE
    }

    companion object {
        private const val PACKAGE_MANIFEST_ASSET = "language-servers/package.json"
        private const val PACKAGE_LOCK_ASSET = "language-servers/package-lock.json"
        private const val CHECKING_MESSAGE = "Checking language-support packages…"
        private const val INSTALLING_MESSAGE = "Installing language-support packages…"
        private const val UPDATING_MESSAGE = "Updating language-support packages…"
        private const val REMOVING_MESSAGE = "Removing language-support packages…"
        private const val READY_MESSAGE = "Language support is ready."
        private const val UPDATED_MESSAGE = "Language-support packages are up to date."
        private const val INSTALL_REQUIRED_MESSAGE = "Language tools are not installed. Open Editor Services to install them."
    }
}
