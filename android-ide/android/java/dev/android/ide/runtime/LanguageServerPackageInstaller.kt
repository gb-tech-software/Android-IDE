package dev.android.ide.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.editor.LanguageServerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Installs only the editor's pinned language-server tools, and only after the
 * user enables LSP. The terminal bootstrap remains independent and minimal.
 * Enabling the editor preference only validates existing packages. Installation
 * is authorized separately by the authenticated `ide tools install lsp` command;
 * an explicitly requested offline install may resume when validated connectivity returns.
 */
class LanguageServerPackageInstaller(
    private val context: Context,
    private val terminalInstaller: BundledTermuxRuntimeInstaller,
) {
    enum class State { DISABLED, QUEUED, INSTALLING, READY, FAILED }

    data class Snapshot(
        val state: State,
        val message: String?,
        val canRetry: Boolean = false,
    )
    private val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java)) {
        "Android connectivity service is unavailable"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val callbackLock = Any()
    @Volatile private var enabled = false
    @Volatile private var explicitInstallRequested = false
    @Volatile private var activeProcess: Process? = null
    private var networkCallbackRegistered = false

    private val mutableState = MutableStateFlow(Snapshot(State.DISABLED, null))
    val state: StateFlow<Snapshot> = mutableState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleIfValidated()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (isValidated(capabilities)) scheduleAttempt()
        }

        override fun onLost(network: Network) {
            if (enabled && !hasValidatedInternet()) {
                mutableState.value = Snapshot(State.QUEUED, WAITING_MESSAGE)
            }
        }
    }

    /** Enables editor LSP use and checks installed packages, but never installs them. */
    fun setEnabled(value: Boolean) {
        if (enabled == value) {
            if (value) scheduleAttempt()
            return
        }
        enabled = value
        if (!value) {
            if (!explicitInstallRequested) {
                activeProcess?.destroyForcibly()
                unregisterNetworkCallback()
                mutableState.value = Snapshot(State.DISABLED, null)
            }
            return
        }
        mutableState.value = Snapshot(State.QUEUED, CHECKING_MESSAGE)
        scheduleAttempt()
    }

    /** Invoked only by the authenticated `ide tools install lsp` command. */
    fun installExplicitly() {
        explicitInstallRequested = true
        mutableState.value = Snapshot(State.QUEUED, CHECKING_MESSAGE)
        registerNetworkCallback()
        scheduleAttempt()
    }

    fun retry() {
        if (!enabled) return
        mutableState.value = Snapshot(State.QUEUED, CHECKING_MESSAGE)
        scheduleAttempt()
    }

    fun refresh() {
        if (enabled && mutableState.value.state != State.FAILED) scheduleAttempt()
    }

    /** Remove the app-managed pinned LSP package set after an explicit tools command. */
    fun removePackages(): Boolean {
        explicitInstallRequested = false
        activeProcess?.destroyForcibly()
        unregisterNetworkCallback()
        setEnabled(false)
        mutableState.value = Snapshot(State.DISABLED, null)
        val root = packageInstallRoot()
        if (!root.exists()) return true
        return root.deleteRecursively()
    }

    suspend fun arePackagesInstalled(): Boolean = withContext(Dispatchers.IO) {
        validateInstalledPackages()
    }

    fun executablePath(command: String): File =
        File(packageInstallRoot(), "node_modules/.bin/$command")

    private fun scheduleIfValidated() {
        if (hasValidatedInternet()) scheduleAttempt()
    }

    private fun scheduleAttempt() {
        scope.launch {
            installMutex.withLock {
                if (!enabled && !explicitInstallRequested) return@withLock
                val alreadyInstalled = validateInstalledPackages()
                if (!enabled && !explicitInstallRequested) return@withLock
                if (alreadyInstalled) {
                    explicitInstallRequested = false
                    mutableState.value = Snapshot(State.READY, READY_MESSAGE)
                    unregisterNetworkCallback()
                    return@withLock
                }
                if (!explicitInstallRequested) {
                    mutableState.value = Snapshot(State.FAILED, INSTALL_REQUIRED_MESSAGE)
                    unregisterNetworkCallback()
                    return@withLock
                }
                if (!hasValidatedInternet()) {
                    mutableState.value = Snapshot(State.QUEUED, WAITING_MESSAGE)
                    registerNetworkCallback()
                    return@withLock
                }

                mutableState.value = Snapshot(State.INSTALLING, INSTALLING_MESSAGE)
                try {
                    installPackages()
                    if (!enabled && !explicitInstallRequested) return@withLock
                    if (!validateInstalledPackages()) throw IOException("Installed versions or command entry points did not pass validation")
                    explicitInstallRequested = false
                    mutableState.value = Snapshot(State.READY, READY_MESSAGE)
                    unregisterNetworkCallback()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!enabled && !explicitInstallRequested) return@withLock
                    explicitInstallRequested = false
                    mutableState.value = Snapshot(
                        State.FAILED,
                        "Language-server package setup failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                    unregisterNetworkCallback()
                }
            }
        }
    }

    private fun installPackages() {
        if (!hasValidatedInternet()) throw IOException("A validated internet connection is no longer available")
        val bootstrap = terminalInstaller.initialize()
        if (bootstrap.outcome != OperationOutcome.COMPLETE) {
            throw IOException(bootstrap.message.ifBlank { "The default Termux bootstrap is unavailable" })
        }
        ensureInstallAuthorized()

        val prefix = terminalInstaller.prefix()
        val node = File(prefix, "bin/node")
        val npm = File(prefix, "bin/npm")
        val nodeVersion = nodeVersion()
        if (!node.canExecute() || !npm.canExecute() || !supportsRequiredNodeVersion(nodeVersion)) {
            val update = runCommand("pkg update -y")
            ensureSuccess("Termux package index update", update)
            ensureInstallAuthorized()
            val install = runCommand("pkg install -y nodejs-lts")
            ensureSuccess("Node.js LTS installation", install)
        }
        val verifiedNode = nodeVersion()
        if (!supportsRequiredNodeVersion(verifiedNode)) {
            throw IOException("LSP packages require Node.js 24.18 or newer; the available version is $verifiedNode")
        }
        if (!npm.canExecute()) throw IOException("npm is unavailable after installing the LSP Node.js prerequisite")
        ensureInstallAuthorized()
        if (!hasValidatedInternet()) throw IOException("A validated internet connection is no longer available")

        val installRoot = packageInstallRoot()
        if (!installRoot.isDirectory && !installRoot.mkdirs()) throw IOException("Unable to create the private LSP package directory")
        copyAsset(PACKAGE_MANIFEST_ASSET, File(installRoot, "package.json"))
        copyAsset(PACKAGE_LOCK_ASSET, File(installRoot, "package-lock.json"))
        val npmInstall = runCommand(
            "npm ci --prefix ${shellQuote(installRoot.absolutePath)} --omit=dev --ignore-scripts --no-audit --no-fund",
        )
        ensureSuccess("Pinned language-server package installation", npmInstall)
    }

    private fun nodeVersion(): String {
        val result = runCommand("node -p 'process.versions.node'")
        if (result.exitCode != 0) return ""
        return result.output.trim().lineSequence().lastOrNull().orEmpty()
    }

    private fun supportsRequiredNodeVersion(value: String): Boolean {
        val parts = value.removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return false
        return parts[0] > MIN_NODE_MAJOR || (parts[0] == MIN_NODE_MAJOR && parts[1] >= MIN_NODE_MINOR)
    }

    private fun validateInstalledPackages(): Boolean {
        return runCatching {
            val manifest = JSONObject(context.assets.open(PACKAGE_MANIFEST_ASSET).bufferedReader().use { it.readText() })
            val dependencies = manifest.optJSONObject("dependencies") ?: return@runCatching false
            if (REQUIRED_PACKAGES.any { !dependencies.has(it) }) return@runCatching false
            if (dependencies.length() != REQUIRED_PACKAGES.size) return@runCatching false
            val modules = File(packageInstallRoot(), "node_modules")
            for (packageName in REQUIRED_PACKAGES) {
                val expectedVersion = dependencies.optString(packageName)
                if (!EXACT_VERSION.matches(expectedVersion)) return@runCatching false
                val installedManifest = File(modules, "$packageName/package.json")
                if (!installedManifest.isFile) return@runCatching false
                val installedVersion = JSONObject(installedManifest.readText()).optString("version")
                if (installedVersion != expectedVersion) return@runCatching false
            }
            val node = File(terminalInstaller.prefix(), "bin/node")
            val npm = File(terminalInstaller.prefix(), "bin/npm")
            if (!node.canExecute() || !npm.canExecute() || !supportsRequiredNodeVersion(readNodeVersionForValidation())) {
                return@runCatching false
            }
            LanguageServerRegistry.definitions.all { executablePath(it.requiredExecutable).canExecute() }
        }.getOrDefault(false)
    }

    private fun readNodeVersionForValidation(): String = runCatching {
        val output = File(context.cacheDir, "android-ide-node-version.txt")
        val process = ProcessBuilder(File(terminalInstaller.prefix(), "bin/node").absolutePath, "-p", "process.versions.node")
            .redirectErrorStream(true)
            .redirectOutput(output)
            .apply { applyRuntimeEnvironment(environment()) }
            .start()
        if (!process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return@runCatching ""
        }
        if (process.exitValue() != 0) return@runCatching ""
        output.readText().trim()
    }.getOrDefault("")

    private fun runCommand(command: String): CommandResult {
        val log = File(context.cacheDir, "language-server-package-install.log")
        val shell = File(terminalInstaller.prefix(), "bin/sh")
        val process = ProcessBuilder(shell.absolutePath, "-c", command)
            .directory(terminalInstaller.home())
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply { applyRuntimeEnvironment(environment()) }
            .start()
        activeProcess = process
        try {
            if (!explicitInstallRequested) {
                process.destroyForcibly()
                throw CancellationException("Explicit LSP package installation was cancelled")
            }
            if (!process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw IOException("Command timed out: $command")
            }
            val output = if (log.isFile) log.readText().takeLast(MAX_LOG_CHARS) else ""
            return CommandResult(process.exitValue(), output)
        } finally {
            if (activeProcess === process) activeProcess = null
        }
    }

    private fun applyRuntimeEnvironment(environment: MutableMap<String, String>) {
        terminalInstaller.sessionEnvironment().forEach { entry ->
            val separator = entry.indexOf('=')
            environment[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        environment["PATH"] = "${File(packageInstallRoot(), "node_modules/.bin").absolutePath}:${environment["PATH"].orEmpty()}"
    }

    private fun ensureSuccess(step: String, result: CommandResult) {
        if (result.exitCode != 0) {
            val detail = result.output.trim().takeLast(1_500).takeIf { it.isNotEmpty() }
            throw IOException("$step failed (exit ${result.exitCode})${detail?.let { ": $it" }.orEmpty()}")
        }
    }

    private fun ensureInstallAuthorized() {
        if (!explicitInstallRequested) throw CancellationException("Explicit LSP package installation was cancelled")
    }

    private fun copyAsset(asset: String, destination: File) {
        context.assets.open(asset).buffered().use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        }
    }

    private fun packageInstallRoot(): File = File(terminalInstaller.prefix(), "lib/android-ide/language-servers")

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun hasValidatedInternet(): Boolean =
        isValidated(connectivity.getNetworkCapabilities(connectivity.activeNetwork))

    private fun isValidated(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun registerNetworkCallback() {
        synchronized(callbackLock) {
            if (networkCallbackRegistered) return
            runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
                .onSuccess { networkCallbackRegistered = true }
                .onFailure {
                    mutableState.value = Snapshot(
                        State.QUEUED,
                        "$WAITING_MESSAGE Android could not monitor network changes; run `ide tools install lsp` again when online.",
                    )
                }
        }
    }

    private fun unregisterNetworkCallback() {
        synchronized(callbackLock) {
            if (!networkCallbackRegistered) return
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private companion object {
        const val PACKAGE_MANIFEST_ASSET = "language-servers/package.json"
        const val PACKAGE_LOCK_ASSET = "language-servers/package-lock.json"
        const val MIN_NODE_MAJOR = 24
        const val MIN_NODE_MINOR = 18
        const val COMMAND_TIMEOUT_MINUTES = 30L
        const val MAX_LOG_CHARS = 16_000
        val EXACT_VERSION = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")
        val REQUIRED_PACKAGES = setOf("pyright", "typescript", "typescript-language-server", "vscode-langservers-extracted")
        const val CHECKING_MESSAGE = "Checking for the pinned language-server packages…"
        const val WAITING_MESSAGE = "Waiting for a validated internet connection to install the pinned language-server packages."
        const val INSTALLING_MESSAGE = "Installing and validating the pinned language-server packages…"
        const val READY_MESSAGE = "Pinned language-server packages are installed and validated."
        const val INSTALL_REQUIRED_MESSAGE = "Language-server packages are not installed. Run `ide tools install lsp` to install them explicitly."
    }
}
