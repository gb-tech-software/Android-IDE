package dev.android.ide.runtime

import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.LanguageServerAdapter
import dev.android.ide.contracts.LanguageServerFeature
import dev.android.ide.contracts.LanguageServerStatus
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.editor.LanguageServerRegistry
import dev.android.ide.workspace.ProjectWorkspaceService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a terminal-managed language server over stdio JSON-RPC.
 *
 * The server uses the selected provider-backed directory as its workspace. No
 * project copy is created for language intelligence, and a server is not
 * reported ready until its initialize response has been received.
 */
class LanguageServerRuntimeAdapterImpl(
    private val workspace: ProjectWorkspaceService,
    private val installer: BundledTermuxRuntimeInstaller,
    private val packageInstaller: LanguageServerPackageInstaller,
    private val onMessage: (projectId: String, serverId: String, message: ByteArray) -> Unit = { _, _, _ -> },
) : LanguageServerAdapter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextRequestId = AtomicInteger(1)
    private val processes = ConcurrentHashMap<Key, LiveServer>()

    override suspend fun start(project: ProjectIdentity, languageId: String): LanguageServerStatus {
        val definition = LanguageServerRegistry.forLanguage(languageId)
            ?: return unavailable(project, languageId, "No language server is registered for $languageId")
        if (!packageInstaller.arePackagesInstalled()) {
            return unavailable(project, languageId, "Pinned language-server packages are not installed yet")
        }
        val resolution = workspace.resolve(project)
        val cwd = resolution.terminalPath
            ?.let(::File)
            ?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
            ?: return unavailable(
                project,
                languageId,
                resolution.explanation ?: "This project is on a storage path that language servers cannot access. Move it to Android IDE storage or Android external storage for full access.",
            )
        val key = Key(project.id, definition.id)
        processes[key]?.let { live ->
            return if (live.initialized.isCompleted) live.status.copy(initialized = true)
            else unavailable(project, languageId, "${definition.id} is still starting")
        }

        return runCatching {
            val executable = packageInstaller.executablePath(definition.requiredExecutable)
            if (!executable.canExecute()) throw IOException("Pinned language-server command is missing: ${executable.name}")
            val command = listOf(executable.absolutePath) + definition.command.drop(1)
            val process = ProcessBuilder(command)
                .directory(cwd)
                .apply {
                    environment()["HOME"] = installer.home().absolutePath
                    environment()["PREFIX"] = installer.prefix().absolutePath
                    environment()["PATH"] = "${File(installer.prefix(), "lib/android-ide/bin").absolutePath}:${File(installer.prefix(), "bin").absolutePath}:/system/bin:/system/xbin"
                    environment()["TERM"] = "xterm-256color"
                    environment()["LANG"] = "C.UTF-8"
                }
                .start()
            val status = LanguageServerStatus(definition.id, project.id, languageId, available = true)
            val initializeId = nextRequestId.getAndIncrement()
            val transport = LanguageServerProtocolTransport(process.inputStream, process.outputStream)
            val live = LiveServer(process, transport, status, initializeId)
            processes[key] = live
            scope.launch { readMessages(key, transport) }
            scope.launch { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { /* server diagnostics stay out of stdout */ } } }
            transport.write(initializeRequest(initializeId, project, cwd))

            val initialization = withTimeoutOrNull(INITIALIZE_TIMEOUT_MS) { live.initialized.await() }
            if (initialization == null || !initialization.successful) {
                processes.remove(key)
                runCatching { process.destroy() }
                return@runCatching unavailable(project, languageId, "${definition.id} did not complete LSP initialization")
            }
            transport.write(initializedNotification())
            status.copy(initialized = true, supportedFeatures = initialization.features)
        }.getOrElse { error ->
            processes.remove(key)
            unavailable(project, languageId, "Could not start ${definition.id}: ${error.message ?: "unknown error"}")
        }
    }

    override suspend fun send(projectId: String, serverId: String, message: ByteArray): OperationReport {
        val key = Key(projectId, serverId)
        val live = processes[key] ?: return unavailableReport("Language server is unavailable")
        if (!live.initialized.isCompleted) return unavailableReport("Language server is still initializing")
        return runCatching {
            live.transport.write(message)
            OperationReport(OperationOutcome.COMPLETE, "Language-server message sent")
        }.getOrElse {
            processes.remove(key)
            runCatching { live.process.destroy() }
            OperationReport(OperationOutcome.FAILED, "Language-server transport failed", ErrorCategory.PROCESS_LOSS)
        }
    }

    override suspend fun stop(projectId: String, serverId: String): OperationReport {
        val key = Key(projectId, serverId)
        val live = processes.remove(key) ?: return OperationReport(OperationOutcome.COMPLETE, "Language server already stopped")
        runCatching {
            if (live.initialized.isCompleted) {
                val shutdownId = nextRequestId.getAndIncrement()
                live.transport.write(request(shutdownId, "shutdown", JSONObject()))
                live.transport.write(notification("exit", JSONObject()))
            }
            live.process.destroy()
        }
        return OperationReport(OperationOutcome.COMPLETE, "Language server stopped")
    }

    override suspend fun status(projectId: String): List<LanguageServerStatus> =
        processes.filterKeys { it.projectId == projectId }.values.map { live ->
            live.status.copy(initialized = live.initialized.isCompleted)
        }

    override suspend fun stopAll(): OperationReport {
        processes.keys.toList().forEach { key -> stop(key.projectId, key.serverId) }
        return OperationReport(OperationOutcome.COMPLETE, "All language servers stopped")
    }

    fun shutdown() {
        processes.values.forEach { live -> runCatching { live.process.destroy() } }
        processes.clear()
        scope.cancel()
    }

    private suspend fun readMessages(key: Key, transport: LanguageServerProtocolTransport) {
        try {
            transport.readFrames { body ->
                val text = body.toString(Charsets.UTF_8)
                val live = processes[key]
                if (live != null) {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (json?.opt("id")?.toString() == live.initializeId.toString() &&
                        (json.has("result") || json.has("error"))) {
                        val successful = !json.has("error")
                        val features = if (successful) serverFeatures(json.optJSONObject("result")?.optJSONObject("capabilities"))
                        else emptySet()
                        live.status = live.status.copy(initialized = successful, supportedFeatures = features)
                        live.initialized.complete(InitializationResult(successful, features))
                    }
                }
                onMessage(key.projectId, key.serverId, body)
            }
        } finally {
            processes.remove(key)
        }
    }

    private fun initializeRequest(id: Int, project: ProjectIdentity, cwd: File): ByteArray =
        request(id, "initialize", JSONObject().apply {
            put("processId", android.os.Process.myPid())
            put("clientInfo", JSONObject().apply {
                put("name", "Android IDE")
                put("version", "1.0.0-alpha")
            })
            put("rootUri", cwd.toURI().toString())
            put("workspaceFolders", org.json.JSONArray().put(JSONObject().apply {
                put("uri", cwd.toURI().toString())
                put("name", project.name)
            }))
            put("capabilities", JSONObject().apply {
                put("workspace", JSONObject().apply {
                    put("workspaceFolders", true)
                    put("configuration", true)
                })
                put("textDocument", JSONObject().apply {
                    put("completion", JSONObject().apply { put("completionItem", JSONObject().apply { put("snippetSupport", true) }) })
                    put("publishDiagnostics", JSONObject())
                    put("hover", JSONObject())
                    put("definition", JSONObject())
                    put("references", JSONObject())
                    put("rename", JSONObject())
                    put("formatting", JSONObject())
                    put("codeAction", JSONObject())
                })
            })
        })

    private fun initializedNotification(): ByteArray = notification("initialized", JSONObject())

    private fun serverFeatures(capabilities: JSONObject?): Set<LanguageServerFeature> {
        if (capabilities == null) return emptySet()
        fun supports(key: String): Boolean = when (val value = capabilities.opt(key)) {
            is JSONObject -> true
            is Boolean -> value
            else -> false
        }
        return buildSet {
            if (supports("completionProvider")) add(LanguageServerFeature.COMPLETION)
            if (supports("signatureHelpProvider")) add(LanguageServerFeature.SIGNATURE_HELP)
            if (supports("hoverProvider")) add(LanguageServerFeature.HOVER)
            if (supports("definitionProvider")) add(LanguageServerFeature.DEFINITION)
            if (supports("declarationProvider")) add(LanguageServerFeature.DECLARATION)
            if (supports("typeDefinitionProvider")) add(LanguageServerFeature.TYPE_DEFINITION)
            if (supports("implementationProvider")) add(LanguageServerFeature.IMPLEMENTATION)
            if (supports("referencesProvider")) add(LanguageServerFeature.REFERENCES)
            if (supports("renameProvider")) add(LanguageServerFeature.RENAME)
            if (supports("documentFormattingProvider")) add(LanguageServerFeature.DOCUMENT_FORMATTING)
            if (supports("documentRangeFormattingProvider")) add(LanguageServerFeature.RANGE_FORMATTING)
            if (supports("codeActionProvider")) add(LanguageServerFeature.CODE_ACTION)
        }
    }

    private fun request(id: Int, method: String, params: JSONObject): ByteArray =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString().toByteArray(Charsets.UTF_8)

    private fun notification(method: String, params: JSONObject): ByteArray =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString().toByteArray(Charsets.UTF_8)

    private fun unavailable(project: ProjectIdentity, languageId: String, explanation: String) =
        LanguageServerStatus(
            serverId = LanguageServerRegistry.forLanguage(languageId)?.id ?: languageId,
            projectId = project.id,
            languageId = languageId,
            available = false,
            explanation = explanation,
        )

    private fun unavailableReport(message: String) =
        OperationReport(OperationOutcome.BLOCKED, message, ErrorCategory.UNAVAILABLE_RUNTIME)

    private data class Key(val projectId: String, val serverId: String)
    private data class InitializationResult(
        val successful: Boolean,
        val features: Set<LanguageServerFeature>,
    )
    private data class LiveServer(
        val process: Process,
        val transport: LanguageServerProtocolTransport,
        @Volatile var status: LanguageServerStatus,
        val initializeId: Int,
        val initialized: CompletableDeferred<InitializationResult> = CompletableDeferred(),
    )

    private companion object {
        const val INITIALIZE_TIMEOUT_MS = 10_000L
    }
}
