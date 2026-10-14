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
import kotlinx.coroutines.withContext
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
    private val onUnavailable: (projectId: String, serverId: String, explanation: String) -> Unit = { _, _, _ -> },
) : LanguageServerAdapter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextRequestId = AtomicInteger(1)
    private val processes = ConcurrentHashMap<Key, LiveServer>()

    override suspend fun start(project: ProjectIdentity, languageId: String): LanguageServerStatus = withContext(Dispatchers.IO) {
        val definition = LanguageServerRegistry.forLanguage(languageId)
            ?: return@withContext unavailable(project, languageId, "No language server is registered for $languageId")
        if (!packageInstaller.arePackagesInstalled()) {
            return@withContext unavailable(project, languageId, "Pinned language-server packages are not installed yet")
        }
        val resolution = workspace.resolve(project)
        val cwd = resolution.terminalPath
            ?.let(::File)
            ?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
            ?: return@withContext unavailable(
                project,
                languageId,
                resolution.explanation ?: "This project is on a storage path that language servers cannot access. Move it to Android IDE storage or Android external storage for full access.",
            )
        val key = Key(project.id, definition.id)
        processes[key]?.let { live ->
            return@withContext when (val initialization = live.initialization) {
                null -> unavailable(project, languageId, "${definition.id} is still starting")
                else -> live.status.copy(
                    available = initialization.successful,
                    initialized = initialization.successful,
                    supportedFeatures = initialization.features,
                    explanation = if (initialization.successful) null else "${definition.id} failed LSP initialization",
                )
            }
        }

        runCatching {
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
                onUnavailable(project.id, definition.id, "${definition.id} did not complete LSP initialization")
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
        val initialization = live.initialization
            ?: return unavailableReport("Language server is still initializing")
        if (!initialization.successful) return unavailableReport("Language server failed to initialize")
        return runCatching {
            live.transport.write(message)
            OperationReport(OperationOutcome.COMPLETE, "Language-server message sent")
        }.getOrElse {
            val removed = processes.remove(key)
            runCatching { live.process.destroy() }
            if (removed != null) onUnavailable(projectId, serverId, "Language-server transport failed")
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
            val initialization = live.initialization
            live.status.copy(
                available = initialization?.successful == true,
                initialized = initialization?.successful == true,
                supportedFeatures = initialization?.features.orEmpty(),
            )
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
                var internalInitializeResponse = false
                if (live != null) {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (json?.opt("id")?.toString() == live.initializeId.toString() &&
                        (json.has("result") || json.has("error"))) {
                        val successful = !json.has("error")
                        val features = if (successful) serverFeatures(json.optJSONObject("result")?.optJSONObject("capabilities"))
                        else emptySet()
                        live.status = live.status.copy(initialized = successful, supportedFeatures = features)
                        live.initialization = InitializationResult(successful, features)
                        live.initialized.complete(InitializationResult(successful, features))
                        // initialize is an Android IDE transport concern. It is
                        // not a Monaco request and must never be correlated by
                        // the editor's JSON-RPC pending-request table.
                        internalInitializeResponse = successful || json.has("error")
                    }
                }
                if (!internalInitializeResponse) onMessage(key.projectId, key.serverId, body)
            }
        } finally {
            val removed = processes.remove(key)
            if (removed != null) {
                val explanation = if (removed.initialization?.successful == true) {
                    "Language server process exited unexpectedly"
                } else {
                    "Language server process exited before initialization completed"
                }
                onUnavailable(key.projectId, key.serverId, explanation)
            }
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
                    put("applyEdit", true)
                    put("workspaceFolders", true)
                    put("configuration", true)
                    put("workspaceEdit", JSONObject().apply {
                        put("documentChanges", true)
                        put("resourceOperations", org.json.JSONArray().put("create").put("rename").put("delete"))
                    })
                })
                put("textDocument", JSONObject().apply {
                    put("completion", JSONObject().apply { put("completionItem", JSONObject().apply { put("snippetSupport", true) }) })
                    put("publishDiagnostics", JSONObject())
                    put("signatureHelp", JSONObject().apply {
                        put("signatureInformation", JSONObject().apply {
                            put("documentationFormat", org.json.JSONArray().put("markdown").put("plaintext"))
                            put("parameterInformation", JSONObject().put("labelOffsetSupport", true))
                        })
                        put("contextSupport", true)
                    })
                    put("hover", JSONObject())
                    put("definition", JSONObject().put("dynamicRegistration", true))
                    put("declaration", JSONObject().put("dynamicRegistration", true))
                    put("typeDefinition", JSONObject().put("dynamicRegistration", true))
                    put("implementation", JSONObject().put("dynamicRegistration", true))
                    put("references", JSONObject().put("dynamicRegistration", true))
                    put("rename", JSONObject())
                    put("formatting", JSONObject())
                    put("codeAction", JSONObject())
                    put("documentSymbol", JSONObject().put("dynamicRegistration", true))
                    put("semanticTokens", JSONObject().put("dynamicRegistration", true).put("requests", JSONObject().put("range", true).put("full", true)).put("tokenTypes", org.json.JSONArray()).put("tokenModifiers", org.json.JSONArray()))
                    put("codeLens", JSONObject().put("dynamicRegistration", true))
                    put("foldingRange", JSONObject().put("dynamicRegistration", true))
                    put("selectionRange", JSONObject().put("dynamicRegistration", true))
                    put("linkedEditingRange", JSONObject().put("dynamicRegistration", true))
                    put("inlayHint", JSONObject().put("dynamicRegistration", true))
                })
                put("window", JSONObject().put("workDoneProgress", true))
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
            if (supports("documentSymbolProvider")) add(LanguageServerFeature.DOCUMENT_SYMBOL)
            if (supports("workspaceSymbolProvider")) add(LanguageServerFeature.WORKSPACE_SYMBOL)
            if (supports("semanticTokensProvider")) add(LanguageServerFeature.SEMANTIC_TOKENS)
            if (supports("inlayHintProvider")) add(LanguageServerFeature.INLAY_HINTS)
            if (supports("codeLensProvider")) add(LanguageServerFeature.CODE_LENS)
            if (supports("foldingRangeProvider")) add(LanguageServerFeature.FOLDING_RANGE)
            if (supports("selectionRangeProvider")) add(LanguageServerFeature.SELECTION_RANGE)
            if (supports("linkedEditingRangeProvider")) add(LanguageServerFeature.LINKED_EDITING)
            if (supports("executeCommandProvider")) add(LanguageServerFeature.EXECUTE_COMMAND)
            // Workspace edits are returned by rename/code-action providers;
            // LSP does not expose a standalone workspaceEditProvider key.
            if (supports("workspaceEditProvider") || supports("codeActionProvider") || supports("renameProvider")) {
                add(LanguageServerFeature.WORKSPACE_EDIT)
            }
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
        @Volatile var initialization: InitializationResult? = null,
    )

    private companion object {
        const val INITIALIZE_TIMEOUT_MS = 10_000L
    }
}
