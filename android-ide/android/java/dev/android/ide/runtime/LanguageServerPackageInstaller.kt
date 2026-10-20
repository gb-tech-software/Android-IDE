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
    data class Snapshot(val state: State, val message: String?, val canRetry: Boolean = false)

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

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            explicitInstallRequested = false
            mutableState.value = Snapshot(State.DISABLED, null)
            return
        }
        mutableState.value = Snapshot(State.QUEUED, CHECKING_MESSAGE)
        refresh()
    }

    fun installExplicitly() {
        explicitInstallRequested = true
        mutableState.value = Snapshot(State.QUEUED, INSTALLING_MESSAGE)
        scope.launch { installNow() }
    }

    fun retry() {
        if (!enabled) return
        mutableState.value = Snapshot(State.QUEUED, CHECKING_MESSAGE)
        refresh()
    }

    fun refresh() {
        if (!enabled && !explicitInstallRequested) return
        scope.launch {
            if (packageManager.validate(spec).installed) {
                explicitInstallRequested = false
                mutableState.value = Snapshot(State.READY, READY_MESSAGE)
            } else if (explicitInstallRequested) {
                installNow()
            } else {
                mutableState.value = Snapshot(State.FAILED, INSTALL_REQUIRED_MESSAGE, canRetry = true)
            }
        }
    }

    suspend fun arePackagesInstalled(): Boolean = packageManager.validate(spec).installed
    fun packageStatus(): IdePackageManager.PackageStatus = packageManager.validate(spec)

    fun executablePath(command: String): File = File(root, "node_modules/.bin/$command")

    suspend fun removePackages(): Boolean {
        return lifecycleLock.withLock {
            explicitInstallRequested = false
            enabled = false
            val removed = packageManager.remove(spec)
            mutableState.value = Snapshot(State.DISABLED, null)
            removed
        }
    }

    private suspend fun installNow() {
        lifecycleLock.withLock {
            if (!explicitInstallRequested && !enabled) return
            mutableState.value = Snapshot(State.INSTALLING, INSTALLING_MESSAGE)
            runCatching { packageManager.install(spec) }
                .onSuccess {
                    if (explicitInstallRequested || enabled) {
                        explicitInstallRequested = false
                        mutableState.value = Snapshot(State.READY, READY_MESSAGE)
                    }
                }
                .onFailure { error ->
                    if (explicitInstallRequested || enabled) {
                        explicitInstallRequested = false
                        mutableState.value = Snapshot(
                            State.FAILED,
                            "Language-server package setup failed: ${error.message ?: error.javaClass.simpleName}",
                            canRetry = true,
                        )
                    }
                }
        }
    }

    companion object {
        private const val PACKAGE_MANIFEST_ASSET = "language-servers/package.json"
        private const val PACKAGE_LOCK_ASSET = "language-servers/package-lock.json"
        private const val CHECKING_MESSAGE = "Checking language-support packages…"
        private const val INSTALLING_MESSAGE = "Installing language-support packages…"
        private const val READY_MESSAGE = "Language support is ready."
        private const val INSTALL_REQUIRED_MESSAGE = "Language tools are not installed. Open Editor Services to install them."
    }
}
