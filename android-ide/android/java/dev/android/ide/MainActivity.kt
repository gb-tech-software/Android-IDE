package dev.android.ide

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.app.ApplicationServices
import dev.android.ide.app.ExternalContentRoute
import dev.android.ide.app.ExternalContentRouter
import dev.android.ide.browser.AndroidBrowserDownloadService
import dev.android.ide.browser.BrowserViewModel
import dev.android.ide.contracts.OpenUrlRequestProtocol
import dev.android.ide.contracts.IdeToolsCommandProtocol
import dev.android.ide.contracts.Surface
import dev.android.ide.contracts.TerminalStorageSetupProtocol
import dev.android.ide.runtime.TerminalForegroundService
import dev.android.ide.ui.AppRoot
import dev.android.ide.viewmodel.IdeViewModel
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val externalContentRouter = ExternalContentRouter()
    val appShellViewModel: AppShellViewModel by lazy {
        ViewModelProvider(this)[AppShellViewModel::class.java]
    }
    val ideViewModel: IdeViewModel by lazy {
        ViewModelProvider(this)[IdeViewModel::class.java]
    }
    val browserViewModel: BrowserViewModel by lazy {
        ViewModelProvider(this)[BrowserViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        window.statusBarColor = Color.rgb(32, 33, 36)
        window.navigationBarColor = Color.BLACK
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!appShellViewModel.back()) appShellViewModel.requestExitConfirmation()
            }
        })
        setContent {
            AppRoot(
                viewModel = appShellViewModel,
                ideViewModel = ideViewModel,
                onExit = ::finish,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @Deprecated("Use Activity Result APIs for new callers; GeckoView 129 exposes this callback contract.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (!dev.android.ide.browser.AndroidPermissionBroker.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == IdeToolsCommandProtocol.ACTION) {
            handleIdeToolsCommand(intent)
            return
        }
        when (intent.action) {
            TerminalStorageSetupProtocol.ACTION -> {
                if (intent.getStringExtra(TerminalStorageSetupProtocol.REQUEST_KIND_EXTRA) ==
                    TerminalStorageSetupProtocol.REQUEST_KIND_STORAGE
                ) {
                    intent.getStringExtra(TerminalStorageSetupProtocol.REQUEST_ID_EXTRA)
                        ?.let(appShellViewModel::requestTerminalStorageSetup)
                }
                return
            }
            OpenUrlRequestProtocol.RELOAD_BROWSER_ACTION -> {
                browserViewModel.reload()
                appShellViewModel.navigate(Surface.BROWSER)
                return
            }
            OpenUrlRequestProtocol.CLEAR_BROWSER_DATA_ACTION -> {
                browserViewModel.clearBrowserData()
                appShellViewModel.navigate(Surface.BROWSER)
                return
            }
            TerminalForegroundService.ACTION_OPEN_TERMINAL -> {
                appShellViewModel.navigate(Surface.TERMINAL)
                return
            }
            TerminalForegroundService.ACTION_EXIT_ALL_TERMINALS -> {
                appShellViewModel.closeAllTerminalSessions()
                appShellViewModel.navigate(Surface.TERMINAL)
                return
            }
            TerminalForegroundService.ACTION_SHUTDOWN_APP -> {
                appShellViewModel.exit { finishAndRemoveTask() }
                return
            }
            AndroidBrowserDownloadService.ACTION_RETRY_DOWNLOAD -> {
                intent.getStringExtra(AndroidBrowserDownloadService.EXTRA_DOWNLOAD_ID)
                    ?.let(browserViewModel::retryDownload)
                browserViewModel.showDownloadsPage()
                appShellViewModel.navigate(Surface.BROWSER)
                return
            }
        }
        if (intent.action == AndroidBrowserDownloadService.ACTION_OPEN_DOWNLOADS) {
            browserViewModel.showDownloadsPage()
            appShellViewModel.navigate(Surface.BROWSER)
            return
        }
        if (intent.action == OpenUrlRequestProtocol.ACTION) {
            val requestId = intent.getStringExtra(OpenUrlRequestProtocol.REQUEST_ID_EXTRA)
                ?.takeIf { IdeToolsCommandProtocol.isValidRequestId(it) }
            val url = intent.getStringExtra(OpenUrlRequestProtocol.URL_EXTRA)?.trim().orEmpty()
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            val receipts = ApplicationServices.from(application).browserNavigationReceipts
            if (requestId != null && receipts.status(requestId) in setOf("dispatched", "rejected")) return
            if (url.isBlank() || uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                receipts.record(requestId, "rejected")
                return
            }
            appShellViewModel.requestBrowserNavigation(
                url = url,
                openInNewTab = intent.getBooleanExtra(OpenUrlRequestProtocol.OPEN_IN_NEW_TAB_EXTRA, true),
                requestId = requestId,
            )
            return
        }
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return
        when (val route = externalContentRouter.route(this, intent)) {
            is ExternalContentRoute.Browser -> appShellViewModel.requestBrowserNavigation(route.request.url, route.request.openInNewTab)
            ExternalContentRoute.Unsupported -> Unit
            else -> appShellViewModel.requestExternalContent(route)
        }
    }

    private data class ToolResolvedPath(
        val uri: String?,
        val parentUri: String,
        val leafName: String,
        val isDirectory: Boolean = false,
    )

    private suspend fun resolveToolPath(projectRoot: String, relativePath: String): ToolResolvedPath? {
        val operations = ApplicationServices.from(application).fileSystem.operations
        if (relativePath.isBlank()) return ToolResolvedPath(projectRoot, projectRoot, "", true)
        if (relativePath.startsWith('/') || relativePath.contains('\\') || relativePath.any { it.isISOControl() }) return null
        val segments = relativePath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        var parent = projectRoot
        for (segment in segments.dropLast(1)) {
            val inspection = operations.inspectChildren(parent) as? dev.android.ide.saf.ChildrenInspectionResult.Success ?: return null
            val child = inspection.children.firstOrNull { it.displayName == segment && it.isDirectory } ?: return null
            if (operations.isSameOrDescendant(projectRoot, child.documentUri) != true) return null
            parent = child.documentUri
        }
        val leaf = segments.last()
        val inspection = operations.inspectChildren(parent) as? dev.android.ide.saf.ChildrenInspectionResult.Success ?: return null
        val child = inspection.children.firstOrNull { it.displayName == leaf }
        if (child != null && operations.isSameOrDescendant(projectRoot, child.documentUri) != true) return null
        return ToolResolvedPath(child?.documentUri, parent, leaf, child?.isDirectory == true)
    }

    private data class ToolFileInventory(
        val files: List<Pair<String, String>>,
        val truncated: Boolean,
        val inaccessible: Boolean,
    )

    private suspend fun collectToolFiles(projectRoot: String): ToolFileInventory {
        val operations = ApplicationServices.from(application).fileSystem.operations
        val pending = mutableListOf(projectRoot to "")
        val files = mutableListOf<Pair<String, String>>()
        var cursor = 0
        var truncated = false
        var inaccessible = false
        while (cursor < pending.size) {
            if (cursor >= 2_000) { truncated = true; break }
            val (directoryUri, relativeDirectory) = pending[cursor++]
            val depth = relativeDirectory.count { it == '/' } + if (relativeDirectory.isBlank()) 0 else 1
            if (depth > 32) { truncated = true; continue }
            when (val inspection = operations.inspectChildren(directoryUri)) {
                is dev.android.ide.saf.ChildrenInspectionResult.Failed -> inaccessible = true
                is dev.android.ide.saf.ChildrenInspectionResult.Success -> inspection.children.forEach { child ->
                    if (operations.isSameOrDescendant(projectRoot, child.documentUri) != true) {
                        inaccessible = true
                        return@forEach
                    }
                    val relative = if (relativeDirectory.isBlank()) child.displayName else "$relativeDirectory/${child.displayName}"
                    if (child.isDirectory) pending += child.documentUri to relative
                    else if (files.size < 2_000) files += child.documentUri to relative else truncated = true
                }
            }
        }
        return ToolFileInventory(files, truncated, inaccessible)
    }

    /** Executes only explicit, allow-listed commands from the bundled Android IDE tools CLI. */
    private fun handleIdeToolsCommand(intent: Intent) {
        val requestId = intent.getStringExtra(IdeToolsCommandProtocol.REQUEST_ID_EXTRA)
        val command = intent.getStringExtra(IdeToolsCommandProtocol.COMMAND_EXTRA).orEmpty()
        val target = intent.getStringExtra(IdeToolsCommandProtocol.TARGET_EXTRA).orEmpty()
        val receiptStore = ApplicationServices.from(application).ideToolsCommandReceipts
        if (!receiptStore.isValidRequestId(requestId) || command !in IDE_TOOL_COMMANDS) return
        if (!receiptStore.isAuthorized(intent.getStringExtra(IdeToolsCommandProtocol.AUTH_TOKEN_EXTRA))) return
        val args = runCatching { JSONArray(intent.getStringExtra(IdeToolsCommandProtocol.PAYLOAD_EXTRA) ?: "[]") }
            .getOrElse { JSONArray() }
        fun argument(index: Int, fallback: String = ""): String = args.optString(index).takeUnless { it == "null" } ?: fallback
        lifecycleScope.launch {
            val services = ApplicationServices.from(application)
            var receiptData: String? = null
            suspend fun openTabsAffectedBy(uri: String, includeDescendants: Boolean): List<String> = ideViewModel.uiState.value.openTabs
                .filter { tab ->
                    tab.documentUri == uri || (includeDescendants && runCatching {
                        services.fileSystem.operations.isSameOrDescendant(uri, tab.documentUri)
                    }.getOrNull() != false)
                }
                .map { it.displayName }
            fun terminalPackageVersion(name: String): String {
                val executable = File(services.bundledRuntimeInstaller.prefix(), "bin/$name")
                if (!executable.canExecute()) return "missing"
                return runCatching {
                    ProcessBuilder(executable.absolutePath, "--version")
                        .redirectErrorStream(true)
                        .start()
                        .also { process -> process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
                        .inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
                        .ifBlank { "unknown" }
                }.getOrDefault("unknown")
            }
            val (exitCode, message) = runCatching {
                when (command) {
                    "app.status" -> {
                        val terminal = services.terminalRuntime.capabilities()
                        val preview = services.projectLiveServer.status()
                        0 to "app=ready terminal=${terminal.availability.name.lowercase()} node=${terminal.nodeVersion ?: "unknown"} shell=${terminal.shellAvailable} pty=${terminal.ptyAvailable} preview=${if (preview.running) "running" else "stopped"}"
                    }
                    "app.test" -> {
                        val terminal = services.terminalRuntime.capabilities()
                        val browserReady = services.browserEngine.capabilities.containsAll(
                            setOf(dev.android.ide.browser.BrowserEngineCapability.HTTP, dev.android.ide.browser.BrowserEngineCapability.HTTPS),
                        )
                        val previewStateReadable = runCatching { services.projectLiveServer.status() }.isSuccess
                        if (!terminal.shellAvailable) 3 to "FAIL app test: bundled terminal shell is unavailable"
                        else if (!browserReady) 3 to "FAIL app test: browser HTTP/HTTPS capability is unavailable"
                        else if (!previewStateReadable) 3 to "FAIL app test: preview service state could not be read"
                        else 0 to "PASS app test: authenticated bridge, terminal shell, browser HTTP/HTTPS, and preview service status are readable"
                    }
                    "preview.start" -> {
                        val state = withTimeoutOrNull(12_000) { ideViewModel.uiState.first { it.projectRootUri != null } }
                        if (state == null) 3 to "No active editor project is ready; open a project and retry"
                        else {
                            val target = ideViewModel.resolveActivePreviewTarget()
                            if (target == null) 3 to (ideViewModel.uiState.value.statusMessage.ifBlank { "Unable to start preview for the active project" })
                            else 0 to "Live Server ready at ${target.url}"
                        }
                    }
                    "preview.status" -> {
                        val status = services.projectLiveServer.status()
                        0 to if (status.running) "running url=${status.url} root=${status.projectRoot}" else status.message.orEmpty()
                    }
                    "preview.test" -> {
                        val status = services.projectLiveServer.status()
                        if (status.running) 0 to "PASS preview: Live Server process and localhost HTTP probe are healthy at ${status.url}"
                        else 3 to "FAIL preview: ${status.message ?: "Live Server is stopped; run ide preview start"}"
                    }
                    "preview.stop" -> {
                        services.projectLiveServer.stop()
                        0 to "IDE-managed Live Server stopped"
                    }
                    "browser.reload" -> {
                        if (browserViewModel.uiState.value.selectedTabId == null) 3 to "No selected browser tab to reload"
                        else {
                            browserViewModel.reload()
                            appShellViewModel.navigate(Surface.BROWSER)
                            0 to "Browser reload requested"
                        }
                    }
                    "browser.back" -> {
                        val state = browserViewModel.uiState.value
                        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
                        if (selected?.canGoBack != true) 3 to "The selected browser tab has no back history"
                        else { browserViewModel.goBack(); 0 to "Browser back navigation started" }
                    }
                    "browser.forward" -> {
                        val state = browserViewModel.uiState.value
                        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
                        if (selected?.canGoForward != true) 3 to "The selected browser tab has no forward history"
                        else { browserViewModel.goForward(); 0 to "Browser forward navigation started" }
                    }
                    "browser.stop" -> {
                        if (!browserViewModel.uiState.value.loading) 3 to "The browser is not currently loading"
                        else { browserViewModel.stop(); 0 to "Browser navigation stopped" }
                    }
                    "browser.status" -> {
                        val state = browserViewModel.uiState.value
                        0 to "browser=tabs:${state.tabs.size} selected=${state.selectedTabId ?: "none"} loading=${state.loading} url=${state.tabs.firstOrNull { it.id == state.selectedTabId }?.url?.take(240) ?: "none"} downloads=${state.downloads.size} pendingPermission=${state.permissionPrompt != null} error=${(state.error ?: state.navigationFailure?.message ?: "none").take(200)}"
                    }
                    "editor.status", "editor.test" -> {
                        val state = ideViewModel.uiState.value
                        val root = state.projectRootUri
                        if (command == "editor.test") {
                            if (root == null) 3 to "FAIL editor workspace: no active project"
                            else if (!state.localWorkspaceAvailable) 3 to "FAIL editor workspace: ${state.localWorkspaceExplanation ?: "native workspace access unavailable"}"
                            else if (!state.isEditorReady) 3 to "FAIL editor bridge: project is loaded but Monaco has not reported ready"
                            else 0 to "PASS editor workspace: active project loaded; tabs=${state.openTabs.size}; editorReady=true"
                        } else {
                            val readiness = if (root == null) "idle" else if (state.isEditorReady) "ready" else "loading"
                            0 to "editor=$readiness tabs=${state.openTabs.size} active=${state.activeTabId ?: "none"} project=${root ?: "none"}"
                        }
                    }
                    "editor.refresh" -> {
                        if (ideViewModel.uiState.value.projectRootUri == null) 3 to "No active project to refresh"
                        else if (ideViewModel.refreshProjectFromTools()) 0 to "Project file tree refreshed"
                        else 1 to (ideViewModel.uiState.value.statusMessage.ifBlank { "Project file tree refresh failed" })
                    }
                    "editor.tabs.list" -> {
                        val tabs = ideViewModel.uiState.value.openTabs
                        0 to if (tabs.isEmpty()) "No editor tabs" else "tabs=${tabs.size} truncated=${tabs.size > 10} " + tabs.take(10).joinToString(" | ") { "${it.id}\t${if (it.isActive) "active" else ""}\t${if (it.isDirty) "dirty" else "clean"}\t${it.displayName.take(50)}\t${it.documentUri.take(100)}" }
                    }
                    "editor.tabs.select" -> {
                        val exists = ideViewModel.uiState.value.openTabs.any { it.id == target }
                        if (!exists) 3 to "Editor tab '$target' was not found"
                        else { ideViewModel.selectTab(target); 0 to "Selected editor tab $target" }
                    }
                    "editor.tabs.close" -> {
                        val tab = ideViewModel.uiState.value.openTabs.firstOrNull { it.id == target }
                        if (tab == null) 3 to "Editor tab '$target' was not found"
                        else if (tab.isDirty && argument(0) == "save") {
                            ideViewModel.saveTabById(target)
                            val saved = withTimeoutOrNull(25_000) {
                                ideViewModel.uiState.first { state -> state.openTabs.firstOrNull { it.id == target }?.let { !it.isDirty && !it.isSaving } == true }
                            }
                            if (saved == null) 1 to (ideViewModel.uiState.value.statusMessage ?: "Tab '$target' could not be saved; it remains open")
                            else { ideViewModel.closeTab(target); 0 to "Saved and closed editor tab $target" }
                        } else if (tab.isDirty && argument(0) != "discard") 4 to "Tab has unsaved changes; specify --dirty save or --dirty discard"
                        else { ideViewModel.closeTab(target); 0 to "Closed editor tab $target" }
                    }
                    "editor.open" -> {
                        val uri = argument(0)
                        if (ideViewModel.uiState.value.projectRootUri == null) 3 to "Open a registered project before opening a file"
                        else if (uri.isBlank()) 2 to "A file document URI is required"
                        else {
                            ideViewModel.openFilePermanent(uri)
                            val opened = withTimeoutOrNull(20_000) {
                                ideViewModel.uiState.first { state -> state.openTabs.any { it.documentUri == uri && it.isActive } && !state.editorFileLoading }
                            }
                            if (opened == null) 1 to (ideViewModel.uiState.value.statusMessage ?: "Editor did not finish opening the requested file")
                            else 0 to "Opened ${opened.openTabs.first { it.documentUri == uri }.displayName}"
                        }
                    }
                    "editor.save", "editor.save-all" -> {
                        if (ideViewModel.uiState.value.projectRootUri == null) 3 to "No active editor project"
                        else {
                            val tabId = if (command == "editor.save") target.ifBlank { ideViewModel.uiState.value.activeTabId.orEmpty() } else ""
                            val tab = if (tabId.isNotBlank()) ideViewModel.uiState.value.openTabs.firstOrNull { it.id == tabId } else null
                            if (command == "editor.save" && tab == null) 3 to "Editor tab '${target.ifBlank { "active" }}' was not found"
                            else if (command == "editor.save" && tab?.isDirty == false) 0 to "Editor tab is already saved"
                            else {
                                if (command == "editor.save") ideViewModel.saveTabById(tabId) else ideViewModel.saveAllFiles()
                                val saved = withTimeoutOrNull(25_000) {
                                    ideViewModel.uiState.first { state ->
                                        if (command == "editor.save") state.openTabs.firstOrNull { it.id == tabId }?.let { !it.isDirty && !it.isSaving } == true
                                        else state.openTabs.none { it.isDirty || it.isSaving }
                                    }
                                }
                                if (saved == null) 1 to (ideViewModel.uiState.value.statusMessage ?: "Editor save did not complete successfully")
                                else 0 to if (command == "editor.save") "Saved editor tab $tabId" else "All editor tabs are saved"
                            }
                        }
                    }
                    "editor.format" -> {
                        val state = ideViewModel.uiState.value
                        val tabId = target.ifBlank { state.activeTabId.orEmpty() }
                        val tab = state.openTabs.firstOrNull { it.id == tabId }
                        if (!state.isEditorReady || tab == null) 3 to "A ready editor tab is required for formatting"
                        else {
                            if (state.activeTabId != tabId) ideViewModel.selectTab(tabId)
                            val features = withTimeoutOrNull(8_000) {
                                ideViewModel.uiState.first { it.activeTabId == tabId && it.languageServerFeatures.isNotEmpty() }
                            }?.languageServerFeatures.orEmpty()
                            if (dev.android.ide.contracts.LanguageServerFeature.DOCUMENT_FORMATTING !in features) 3 to "No installed language server advertises document formatting for ${tab.language}"
                            else {
                                ideViewModel.sendEditorCommand(dev.android.ide.editor.EditorOutbound.ExecuteCommand("editor.action.formatDocument"))
                                0 to "Formatting action dispatched to the registered Monaco/LSP formatter for ${tab.displayName}"
                            }
                        }
                    }
                    "browser.test" -> {
                        val state = browserViewModel.uiState.value
                        val capabilities = services.browserEngine.capabilities
                        val engineReady = dev.android.ide.browser.BrowserEngineCapability.HTTP in capabilities &&
                            dev.android.ide.browser.BrowserEngineCapability.HTTPS in capabilities
                        val checks = mutableListOf<String>()
                        checks += if (engineReady) "engine=PASS(http,https)" else "engine=FAIL(http/https unavailable)"
                        val selectedConsistent = state.selectedTabId == null && state.tabs.isEmpty() || state.tabs.any { it.id == state.selectedTabId }
                        checks += if (selectedConsistent) "tabs=PASS(${state.tabs.size})" else "tabs=FAIL(selected ID is not present)"
                        val malformedDownload = state.downloads.any { it.id.isBlank() || it.name.isBlank() }
                        val downloadsReady = dev.android.ide.browser.BrowserEngineCapability.DOWNLOADS in capabilities && !malformedDownload
                        checks += if (downloadsReady) "downloads=PASS(${state.downloads.size})" else "downloads=FAIL(capability unavailable or malformed record)"
                        val permissionsReady = dev.android.ide.browser.BrowserEngineCapability.PERMISSIONS in capabilities
                        checks += "permissions=${if (permissionsReady) "PASS" else "FAIL"}(engine=${permissionsReady},saved=${state.sitePermissions.size},pending=${state.permissionPrompt != null})"
                        val navigationError = state.navigationFailure?.message ?: state.error
                        if (navigationError != null) checks += "navigationState=FAIL($navigationError)" else checks += "navigationState=PASS(no current failure)"
                        val failed = !engineReady || !selectedConsistent || !downloadsReady || !permissionsReady || navigationError != null
                        (if (failed) 1 else 0) to "${if (failed) "FAIL" else "PASS"} browser checks: ${checks.joinToString("; ")}"
                    }
                    "browser.test.tabs" -> {
                        val state = browserViewModel.uiState.value
                        val consistent = state.selectedTabId == null && state.tabs.isEmpty() || state.tabs.any { it.id == state.selectedTabId }
                        if (!consistent) 1 to "FAIL browser tabs: selected tab is missing from the tab set"
                        else if (state.error != null) 1 to "FAIL browser tabs: ${state.error}"
                        else 0 to "PASS browser tabs: ${state.tabs.size} tab(s); selection consistent"
                    }
                    "browser.test.downloads" -> {
                        val state = browserViewModel.uiState.value
                        val malformed = state.downloads.firstOrNull { it.id.isBlank() || it.name.isBlank() }
                        if (dev.android.ide.browser.BrowserEngineCapability.DOWNLOADS !in services.browserEngine.capabilities) 1 to "FAIL downloads: browser engine does not advertise download support"
                        else if (malformed != null) 1 to "FAIL downloads: record has no ID or filename"
                        else if (state.error != null) 1 to "FAIL downloads: ${state.error}"
                        else 0 to "PASS downloads: ${state.downloads.size} record(s); state is readable"
                    }
                    "browser.test.permissions" -> {
                        val state = browserViewModel.uiState.value
                        if (dev.android.ide.browser.BrowserEngineCapability.PERMISSIONS !in services.browserEngine.capabilities) 1 to "FAIL permissions: browser engine does not advertise permission support"
                        else if (state.error != null) 1 to "FAIL permissions: ${state.error}"
                        else 0 to "PASS permissions: ${state.sitePermissions.size} saved site permission(s); pending=${state.permissionPrompt != null}"
                    }
                    "browser.downloads.list" -> {
                        val state = browserViewModel.uiState.value
                        browserViewModel.showDownloadsPage()
                        appShellViewModel.navigate(Surface.BROWSER)
                        0 to if (state.downloads.isEmpty()) "No browser downloads" else state.downloads.joinToString("\n") { "${it.id}\t${it.status.name.lowercase()}\t${it.name}\t${it.size} bytes" }
                    }
                    "browser.downloads.open" -> {
                        browserViewModel.showDownloadsPage()
                        appShellViewModel.navigate(Surface.BROWSER)
                        0 to "Opened Browser downloads"
                    }
                    "browser.tabs.list" -> {
                        val state = browserViewModel.uiState.value
                        0 to if (state.tabs.isEmpty()) "No browser tabs" else state.tabs.joinToString(" | ") { "${it.id}\t${if (it.id == state.selectedTabId) "active" else ""}\t${it.title}\t${it.url}" }
                    }
                    "browser.tabs.select" -> {
                        val exists = browserViewModel.uiState.value.tabs.any { it.id == target }
                        if (!exists) 3 to "Browser tab '$target' was not found"
                        else { browserViewModel.selectTab(target); appShellViewModel.navigate(Surface.BROWSER); 0 to "Selected browser tab $target" }
                    }
                    "browser.tabs.close" -> {
                        if (browserViewModel.closeTab(target)) 0 to "Closed browser tab $target"
                        else 3 to "Browser tab '$target' could not be closed"
                    }
                    "browser.downloads.open-file" -> {
                        val download = browserViewModel.uiState.value.downloads.firstOrNull { it.id == target && it.available }
                        if (download == null) 3 to "Available download '$target' was not found"
                        else {
                            val openIntent = browserViewModel.openDownload(download.id)
                            if (openIntent == null) 3 to "Download '${download.name}' cannot be opened"
                            else {
                                startActivity(openIntent)
                                0 to "Opened download ${download.name}"
                            }
                        }
                    }
                    "browser.downloads.pause", "browser.downloads.resume", "browser.downloads.cancel", "browser.downloads.retry", "browser.downloads.delete" -> {
                        val ok = when (command) {
                            "browser.downloads.pause" -> browserViewModel.pauseDownload(target)
                            "browser.downloads.resume" -> browserViewModel.resumeDownload(target)
                            "browser.downloads.cancel" -> browserViewModel.cancelDownload(target)
                            "browser.downloads.retry" -> browserViewModel.retryDownload(target)
                            else -> browserViewModel.deleteDownload(target)
                        }
                        if (ok) 0 to "${command.substringAfterLast('.')} requested for download $target"
                        else 3 to "Download '$target' is unavailable for ${command.substringAfterLast('.')}"
                    }
                    "files.status", "files.test", "files.list", "files.read", "files.write", "files.create", "files.mkdir", "files.rename", "files.copy", "files.move", "files.delete" -> {
                        val root = ideViewModel.uiState.value.projectRootUri
                        if (root == null) 3 to "No active editor project; file commands are scoped to the active project"
                        else {
                            val operations = services.fileSystem.operations
                            val path = argument(0)
                            when (command) {
                                "files.status", "files.test" -> {
                                    val state = ideViewModel.uiState.value
                                    val inspection = operations.inspectChildren(root)
                                    val capabilities = services.projectManagement.inspectStorage(
                                        dev.android.ide.contracts.ProjectLocation(root, state.projectName, root),
                                    )
                                    val readable = inspection is dev.android.ide.saf.ChildrenInspectionResult.Success && capabilities.readable
                                    if (command == "files.status") 0 to "files=root:$root accessible=$readable localWorkspace=${capabilities.localWorkspaceAvailable} writable=${capabilities.writable}"
                                    else if (!readable) 3 to "FAIL files: project root cannot be read (${capabilities.explanation ?: capabilities.state.name.lowercase()})"
                                    else 0 to "PASS files: active project root is readable and bounded filesystem inspection succeeded"
                                }
                                "files.list" -> {
                                    val resolved = resolveToolPath(root, path)
                                    if (resolved?.uri == null || !resolved.isDirectory) 3 to "Directory '$path' was not found or is inaccessible"
                                    else when (val inspection = operations.inspectChildren(resolved.uri)) {
                                        is dev.android.ide.saf.ChildrenInspectionResult.Failed -> 1 to "Could not inspect directory '$path'"
                                        is dev.android.ide.saf.ChildrenInspectionResult.Success -> {
                                            val displayed = inspection.children.take(15).joinToString(" | ") { "${if (it.isDirectory) "dir" else "file"}\t${it.displayName.take(120)}" }
                                            0 to if (inspection.children.isEmpty()) "Directory is empty" else "entries=${inspection.children.size} truncated=${inspection.children.size > 15} $displayed"
                                        }
                                    }
                                }
                                "files.read" -> {
                                    val resolved = resolveToolPath(root, path)
                                    if (resolved?.uri == null || resolved.isDirectory) 3 to "File '$path' was not found or is not a file"
                                    else {
                                        val bytes = operations.read(resolved.uri, 36_000)
                                        if (bytes == null) 3 to "File '$path' is inaccessible or exceeds the 36 KB CLI read limit"
                                        else {
                                            receiptData = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                            0 to "Read ${bytes.size} bytes from $path; decoded content is attached to this CLI result"
                                        }
                                    }
                                }
                                "files.write" -> {
                                    val resolved = resolveToolPath(root, path)
                                    val encoded = argument(1)
                                    if (resolved?.uri == null || resolved.isDirectory) 3 to "Existing file '$path' was not found"
                                    else if (ideViewModel.uiState.value.openTabs.any { it.documentUri == resolved.uri }) 4 to "Close the open editor tab for '$path' before writing through the files domain"
                                    else {
                                        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                                        if (bytes == null) 2 to "File input payload is not valid Base64"
                                        else if (bytes.size > 36_000) 4 to "File write exceeds the 36 KB CLI limit"
                                        else if (!operations.write(resolved.uri, bytes, dev.android.ide.saf.WriteVerification.STRICT)) 1 to "Write verification failed for '$path'"
                                        else 0 to "Wrote and verified ${bytes.size} bytes to $path"
                                    }
                                }
                                "files.create", "files.mkdir" -> {
                                    val resolved = resolveToolPath(root, path)
                                    if (resolved == null || resolved.uri != null || resolved.leafName.isBlank()) 3 to "Parent directory for '$path' is missing or target already exists"
                                    else {
                                        val created = if (command == "files.mkdir") operations.createFolder(resolved.parentUri, resolved.leafName)
                                        else operations.createFile(resolved.parentUri, resolved.leafName, dev.android.ide.editor.EditorLanguageRegistry.mimeTypeForFileName(resolved.leafName))
                                        when (created) {
                                            is dev.android.ide.saf.ExactCreateResult.Created -> 0 to "Created ${if (command == "files.mkdir") "directory" else "file"} '$path'"
                                            dev.android.ide.saf.ExactCreateResult.Duplicate -> 4 to "A file or directory named '${resolved.leafName}' already exists"
                                            dev.android.ide.saf.ExactCreateResult.InspectionFailed -> 1 to "Could not verify the destination directory"
                                            dev.android.ide.saf.ExactCreateResult.Failed -> 1 to "Could not create '$path'"
                                            is dev.android.ide.saf.ExactCreateResult.Partial -> 1 to "Creation was partial; recovery required at ${created.recoveryHint}"
                                        }
                                    }
                                }
                                "files.rename" -> {
                                    val resolved = resolveToolPath(root, path)
                                    val newName = argument(1)
                                    if (resolved?.uri == null || path.isBlank()) 3 to "File '$path' was not found or project root cannot be renamed"
                                    else if (newName.isBlank() || newName.contains('/') || newName.contains('\\')) 2 to "New name must be one path component"
                                    else {
                                        val affectedTabs = openTabsAffectedBy(resolved.uri, resolved.isDirectory)
                                        if (affectedTabs.isNotEmpty()) 4 to "Close affected editor tab(s) before renaming '$path': ${affectedTabs.joinToString(", ")}"
                                        else if (operations.rename(resolved.uri, newName) == null) 1 to "Could not rename '$path' to '$newName'"
                                        else 0 to "Renamed '$path' to '$newName'"
                                    }
                                }
                                "files.copy", "files.move" -> {
                                    val source = resolveToolPath(root, path)
                                    val destination = resolveToolPath(root, argument(1))
                                    if (source?.uri == null || path.isBlank()) 3 to "Source '$path' was not found or project root cannot be copied/moved"
                                    else if (destination?.uri == null || !destination.isDirectory) 3 to "Destination '${argument(1)}' is not an existing project directory"
                                    else {
                                        val affectedTabs = if (command == "files.move") openTabsAffectedBy(source.uri, source.isDirectory) else emptyList()
                                        if (affectedTabs.isNotEmpty()) 4 to "Close affected editor tab(s) before moving '$path': ${affectedTabs.joinToString(", ")}"
                                        else {
                                            val result = if (command == "files.copy") operations.copy(source.uri, destination.uri)
                                            else operations.move(source.uri, source.parentUri, destination.uri)
                                            when (result) {
                                                is dev.android.ide.saf.SafeMutationResult.Created -> 0 to "${command.substringAfter('.')} completed: ${result.documentUri}"
                                                is dev.android.ide.saf.SafeMutationResult.Partial -> 1 to "Operation was partial: ${result.recoveryHint}"
                                                dev.android.ide.saf.SafeMutationResult.Duplicate -> 4 to "Destination already contains an item with that name"
                                                dev.android.ide.saf.SafeMutationResult.InspectionFailed -> 1 to "Could not verify source/destination postconditions"
                                                dev.android.ide.saf.SafeMutationResult.Failed -> 1 to "${command.substringAfter('.')} failed"
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    val confirmation = argument(1)
                                    val resolved = resolveToolPath(root, path)
                                    if (confirmation != path) 2 to "Delete requires --confirm with the exact project-relative path"
                                    else if (path.isBlank() || resolved?.uri == null) 3 to "File or directory '$path' was not found; project root cannot be deleted"
                                    else {
                                        val affectedTabs = openTabsAffectedBy(resolved.uri, resolved.isDirectory)
                                        if (affectedTabs.isNotEmpty()) 4 to "Close affected editor tab(s) before deleting '$path': ${affectedTabs.joinToString(", ")}"
                                        else {
                                            runCatching { operations.delete(resolved.uri) }
                                            when (runCatching { operations.presence(resolved.uri) }.getOrDefault(dev.android.ide.saf.DocumentPresence.INACCESSIBLE)) {
                                                dev.android.ide.saf.DocumentPresence.ABSENT -> 0 to "Deleted '$path' (absence verified)"
                                                dev.android.ide.saf.DocumentPresence.EXISTS -> 1 to "Delete failed: '$path' is still present"
                                                dev.android.ide.saf.DocumentPresence.INACCESSIBLE -> 1 to "Delete result is unknown because '$path' cannot be inspected"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "search.status", "search.test", "search.files", "search.content" -> {
                        val root = ideViewModel.uiState.value.projectRootUri
                        if (root == null) 3 to "No active project; search is scoped to the editor workspace"
                        else if (command == "search.status") 0 to "search=root:$root scope=active-project maximumFiles=2000 maximumDepth=32"
                        else {
                            val inventory = collectToolFiles(root)
                            if (command == "search.test") {
                                if (inventory.inaccessible) 1 to "FAIL search: one or more project directories could not be inspected"
                                else 0 to "PASS search: project tree is enumerable; files=${inventory.files.size} truncated=${inventory.truncated}"
                            } else {
                                val query = argument(0)
                                if (query.isEmpty()) 2 to "Search query must not be empty"
                                else {
                                    val caseSensitive = args.optString(1) == "--case-sensitive" || args.optString(2) == "--case-sensitive" || args.optString(3) == "--case-sensitive"
                                    val regex = args.optString(1) == "--regex" || args.optString(2) == "--regex" || args.optString(3) == "--regex"
                                    val wholeWord = args.optString(1) == "--whole-word" || args.optString(2) == "--whole-word" || args.optString(3) == "--whole-word"
                                    val expression = if (regex) query else Regex.escape(query)
                                    val compiled = runCatching {
                                        Regex(if (wholeWord) "\\b(?:$expression)\\b" else expression,
                                            if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                                    }.getOrNull()
                                    if (compiled == null) 2 to "Search expression is not a valid regular expression"
                                    else if (command == "search.files") {
                                        val matches = inventory.files.filter { (_, path) -> compiled.containsMatchIn(path.substringAfterLast('/')) }
                                        0 to "matches=${matches.size} filesScanned=${inventory.files.size} truncated=${inventory.truncated || inventory.inaccessible || matches.size > 15} " +
                                            (matches.take(15).joinToString(" | ") { it.second.take(120) }.ifBlank { "No filenames matched" })
                                    } else {
                                        val operations = services.fileSystem.operations
                                        val matches = mutableListOf<String>()
                                        var matchCount = 0
                                        var scanned = 0
                                        var bytesScanned = 0L
                                        var capped = inventory.truncated || inventory.inaccessible
                                        for ((uri, relative) in inventory.files) {
                                            if (scanned >= 200 || bytesScanned >= 8L * 1024 * 1024 || matches.size >= 100) { capped = true; break }
                                            val bytes = operations.read(uri, 64 * 1024)
                                            if (bytes == null) { capped = true; continue }
                                            scanned++
                                            bytesScanned += bytes.size
                                            if (bytes.any { it == 0.toByte() }) continue
                                            val text = String(bytes, Charsets.UTF_8)
                                            text.lineSequence().forEachIndexed { index, line ->
                                                if (compiled.containsMatchIn(line)) {
                                                    matchCount++
                                                    if (matches.size < 12) matches += "${relative.take(80)}:${index + 1}: ${line.take(100)}"
                                                    else capped = true
                                                }
                                            }
                                        }
                                        0 to "matches=$matchCount filesScanned=$scanned bytesScanned=$bytesScanned truncated=$capped " +
                                            (matches.joinToString(" | ").ifBlank { "No content matched" })
                                    }
                                }
                            }
                        }
                    }
                    "terminal.status", "terminal.test" -> {
                        val runtime = services.terminalRuntime.capabilities()
                        val sessions = services.terminalRuntime.listSessions()
                        if (command == "terminal.status") {
                            0 to "terminal=${runtime.availability.name.lowercase()} shell=${runtime.shellAvailable} pty=${runtime.ptyAvailable} activeSessions=${sessions.count { it.availability == dev.android.ide.contracts.SessionAvailability.AVAILABLE }} savedSessions=${sessions.size}"
                        } else if (!runtime.ptyAvailable) 3 to "FAIL terminal PTY: ${runtime.explanation ?: "runtime unavailable"}"
                        else {
                            val probe = withTimeoutOrNull(8_000) { services.terminalRuntime.createSession(null, "IDE tools self-test") }
                            if (probe == null) 3 to "FAIL terminal PTY: temporary shell session did not start"
                            else {
                                val closed = services.terminalRuntime.closeSession(probe.id)
                                if (closed.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE) 0 to "PASS terminal PTY: temporary shell session started and closed"
                                else 1 to "FAIL terminal PTY cleanup: ${closed.message}"
                            }
                        }
                    }
                    "terminal.sessions.list" -> {
                        val sessions = services.terminalRuntime.listSessions()
                        0 to if (sessions.isEmpty()) "No terminal sessions" else "sessions=${sessions.size} truncated=${sessions.size > 10} " + sessions.take(10).joinToString(" | ") { "${it.id}\t${it.availability.name.lowercase()}\t${it.name.take(40)}\t${it.workingDirectory.orEmpty().take(100)}" }
                    }
                    "terminal.sessions.close" -> {
                        val report = services.terminalRuntime.closeSession(target)
                        operationExitCode(report.outcome) to report.message
                    }
                    "storage.status", "storage.test" -> {
                        val storage = File(services.bundledRuntimeInstaller.home(), "storage")
                        val required = listOf("shared", "documents", "downloads", "dcim", "pictures", "music", "movies")
                        val missing = required.filterNot { Files.isSymbolicLink(File(storage, it).toPath()) }
                        if (command == "storage.status") 0 to "storage=${if (missing.isEmpty()) "ready" else "incomplete"} path=${storage.absolutePath} missing=${missing.joinToString(",").ifBlank { "none" }}"
                        else if (!services.terminalRuntime.capabilities().shellAvailable) 3 to "FAIL storage: terminal runtime is unavailable"
                        else if (missing.isNotEmpty()) 4 to "FAIL storage links: ${missing.joinToString(", ")}; run ide storage setup"
                        else 0 to "PASS storage: ${required.size} standard shared-storage links exist"
                    }
                    "tools.status", "tools.test" -> {
                        val lspStatus = services.languageServerPackageInstaller.packageStatus()
                        val previewStatus = services.projectLiveServer.packageStatus()
                        val packageNames = listOf("git", "node", "npm", "pnpm", "zip", "unzip")
                        val present = packageNames.filter { File(services.bundledRuntimeInstaller.prefix(), "bin/$it").canExecute() }
                        val missing = packageNames - present.toSet()
                        val terminalVersions = packageNames.joinToString(",") { "$it=${terminalPackageVersion(it)}" }
                        val managedVersions = "node=${services.nodeRuntime.nodeVersion() ?: "unknown"} " +
                            "lsp=${if (lspStatus.installed) lspStatus.dependencyVersions.entries.joinToString("|") { "${it.key}@${it.value}" } else "missing"} " +
                            "preview=${if (previewStatus.installed) previewStatus.dependencyVersions.entries.joinToString("|") { "${it.key}@${it.value}" } else "missing"}"
                        val summary = "[terminal packages] $terminalVersions [app-managed services] $managedVersions"
                        if (command == "tools.status") 0 to summary
                        else if (!services.terminalRuntime.capabilities().shellAvailable) 3 to "FAIL tools: terminal runtime is unavailable"
                        else if (missing.isNotEmpty()) 4 to "FAIL terminal packages: missing ${missing.joinToString(", ")}; run ide init; [app-managed services] lsp=${lspStatus.installed} preview=${previewStatus.installed}"
                        else 0 to "PASS terminal packages: ${packageNames.joinToString(", ")} are executable; [app-managed services] lsp=${lspStatus.installed} preview=${previewStatus.installed}"
                    }
                    "projects.status", "projects.test", "projects.list" -> {
                        val registered = services.projectManagement.registeredProjects()
                        val state = ideViewModel.uiState.value
                        val active = state.projectRootUri?.let { root -> registered.firstOrNull { it.location.stableId == root } }
                        when (command) {
                            "projects.list" -> 0 to if (registered.isEmpty()) "No registered projects" else "projects=${registered.size} truncated=${registered.size > 10} " + registered.take(10).joinToString(" | ") { "${it.id}\t${it.name.take(40)}\t${it.location.stableId.take(60)}" }
                            "projects.status" -> 0 to "projects=${registered.size} active=${active?.id ?: "none"} selected=${appShellViewModel.state.value.selectedProjectId ?: "none"} editorRoot=${state.projectRootUri ?: "none"} dirtyTabs=${state.openTabs.count { it.isDirty }}"
                            else -> {
                                val root = state.projectRootUri
                                if (root == null) 3 to "FAIL project workspace: no active project"
                                else {
                                    val capabilities = services.projectManagement.inspectStorage(
                                        dev.android.ide.contracts.ProjectLocation(root, state.projectName, root),
                                    )
                                    if (active == null) 1 to "FAIL project registry: active editor root is not registered"
                                    else if (capabilities.state != dev.android.ide.contracts.CapabilityState.SUPPORTED || !capabilities.readable) 3 to "FAIL project storage: ${capabilities.explanation ?: capabilities.state.name.lowercase()}"
                                    else 0 to "PASS project: ${active.name} is registered and readable; localWorkspace=${capabilities.localWorkspaceAvailable}; writable=${capabilities.writable}"
                                }
                            }
                        }
                    }
                    "projects.open" -> {
                        val identity = services.projectManagement.registeredProjects().firstOrNull { it.id == target }
                        if (identity == null) 3 to "Project '$target' is not registered"
                        else when (val restored = services.projectManagement.restore(target)) {
                            is dev.android.ide.project.ProjectRestoreResult.Unavailable -> 3 to restored.reason
                            is dev.android.ide.project.ProjectRestoreResult.Restored -> {
                                val failure = ideViewModel.openProjectFromTools(restored.identity.location.stableId, argument(0, "cancel"))
                                if (failure != null) 4 to failure
                                else {
                                    appShellViewModel.openProject(target)
                                    0 to "Opened project ${identity.name} in the editor workspace"
                                }
                            }
                        }
                    }
                    "projects.close" -> {
                        val failure = ideViewModel.closeProjectFromTools(argument(0))
                        if (failure != null) 4 to failure
                        else {
                            appShellViewModel.navigate(Surface.PROJECTS)
                            0 to "Editor workspace closed; project-scoped Live Server, LSP, search, tabs, and project terminal sessions were stopped"
                        }
                    }
                    "projects.create" -> {
                        val report = services.projectManagement.createBlankProject(argument(0), argument(1), argument(2))
                        appShellViewModel.refreshProjectList()
                        operationExitCode(report.outcome) to report.message
                    }
                    "projects.import-folder" -> {
                        val report = services.projectManagement.importExistingFolder(
                            argument(0), argument(1).ifBlank { null }, argument(2).ifBlank { null },
                        )
                        appShellViewModel.refreshProjectList()
                        operationExitCode(report.outcome) to report.message
                    }
                    "projects.import-zip" -> {
                        val report = services.projectManagement.importZip(argument(0), argument(1), argument(2), argument(3))
                        appShellViewModel.refreshProjectList()
                        operationExitCode(report.outcome) to report.message
                    }
                    "projects.rename", "projects.relocate", "projects.duplicate", "projects.export" -> {
                        val project = services.projectManagement.registeredProjects().firstOrNull { it.id == target }
                        if (project == null) 3 to "Project '$target' is not registered"
                        else {
                            val active = ideViewModel.uiState.value.projectRootUri == project.location.stableId
                            val dirty = ideViewModel.uiState.value.openTabs.any { it.isDirty }
                            val changesActiveLocation = command == "projects.rename" || command == "projects.relocate"
                            val disposition = argument(if (command == "projects.rename") 1 else 2)
                            val dirtyFailure = if (active && dirty && changesActiveLocation) {
                                when (disposition) {
                                    "save" -> ideViewModel.saveDirtyTabsFromTools()
                                    "discard" -> null
                                    else -> "Project has unsaved tabs; specify --dirty save or --dirty discard"
                                }
                            } else null
                            if (dirtyFailure != null) 4 to dirtyFailure
                            else {
                                val report = when (command) {
                                    "projects.rename" -> services.projectManagement.renameProject(target, argument(0))
                                    "projects.relocate" -> services.projectManagement.relocateProject(target, argument(0), argument(1))
                                    "projects.duplicate" -> services.projectManagement.duplicateProject(target, argument(0), argument(1), argument(2).ifBlank { null })
                                    else -> services.projectManagement.exportProject(target, argument(0))
                                }
                                if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && active && command != "projects.duplicate" && command != "projects.export") {
                                    ideViewModel.resetWorkspaceAfterProjectRemoval()
                                }
                                appShellViewModel.refreshProjectList()
                                operationExitCode(report.outcome) to report.message
                            }
                        }
                    }
                    "projects.unregister", "projects.delete" -> {
                        val project = services.projectManagement.registeredProjects().firstOrNull { it.id == target }
                        if (argument(0) != target) 2 to "Destructive project action requires the exact ID in --confirm"
                        else if (project == null) 3 to "Project '$target' is not registered"
                        else {
                            val active = ideViewModel.uiState.value.projectRootUri == project.location.stableId
                            val dirty = ideViewModel.uiState.value.openTabs.any { it.isDirty }
                            val dirtyPolicy = argument(1)
                            val dirtyFailure = if (active && dirty) {
                                when (dirtyPolicy) {
                                    "save" -> ideViewModel.saveDirtyTabsFromTools()
                                    "discard" -> null
                                    else -> "Project has unsaved tabs; specify --dirty save or --dirty discard"
                                }
                            } else null
                            if (dirtyFailure != null) 4 to dirtyFailure
                            else {
                                val report = if (command == "projects.delete") services.projectManagement.permanentlyDelete(target)
                                else services.projectManagement.removeFromRegistry(target)
                                if (report.outcome == dev.android.ide.contracts.OperationOutcome.COMPLETE && active) {
                                    ideViewModel.resetWorkspaceAfterProjectRemoval()
                                    appShellViewModel.navigate(Surface.PROJECTS)
                                }
                                appShellViewModel.refreshProjectList()
                                operationExitCode(report.outcome) to report.message
                            }
                        }
                    }
                    "tools.lsp.install" -> {
                        val installer = services.languageServerPackageInstaller
                        installer.installExplicitly()
                        val started = withTimeoutOrNull(15_000) {
                            installer.state.first { it.state in setOf(
                                dev.android.ide.runtime.LanguageServerPackageInstaller.State.INSTALLING,
                                dev.android.ide.runtime.LanguageServerPackageInstaller.State.READY,
                                dev.android.ide.runtime.LanguageServerPackageInstaller.State.FAILED,
                            ) }
                        }
                        if (started == null) {
                            val queued = installer.state.value
                            4 to (queued.message ?: "Language-server packages are queued until a validated network is available")
                        } else if (started.state == dev.android.ide.runtime.LanguageServerPackageInstaller.State.READY) {
                            0 to (started.message ?: "Language-server packages installed")
                        } else if (started.state == dev.android.ide.runtime.LanguageServerPackageInstaller.State.FAILED) {
                            1 to (started.message ?: "Language-server package installation failed")
                        } else {
                            val completed = withTimeoutOrNull(180_000) {
                                installer.state.first { it.state in setOf(
                                    dev.android.ide.runtime.LanguageServerPackageInstaller.State.READY,
                                    dev.android.ide.runtime.LanguageServerPackageInstaller.State.FAILED,
                                ) }
                            }
                            if (completed == null) 1 to "Language-server package installation timed out"
                            else if (completed.state == dev.android.ide.runtime.LanguageServerPackageInstaller.State.READY) 0 to (completed.message ?: "Language-server packages installed")
                            else 1 to (completed.message ?: "Language-server package installation failed")
                        }
                    }
                    "tools.lsp.status" -> {
                        val installer = services.languageServerPackageInstaller
                        val status = installer.state.value
                        val installed = installer.arePackagesInstalled()
                        0 to "lsp=${status.state.name.lowercase()} installed=$installed message=${status.message.orEmpty()} retry=${status.canRetry}"
                    }
                    "tools.lsp.remove" -> {
                        if (ideViewModel.removeLanguageServerTools()) 0 to "Pinned language-server packages removed"
                        else 1 to "Could not remove the pinned language-server package directory"
                    }
                    "tools.preview.status" -> {
                        val status = services.projectLiveServer.packageStatus()
                        0 to "preview-package=${status.installed} version=${status.version ?: "unknown"} message=${status.message.orEmpty()}"
                    }
                    "tools.preview.install" -> {
                        val status = services.projectLiveServer.install()
                        if (status.installed) 0 to (status.message ?: "IDE-managed preview package installed")
                        else 1 to (status.message ?: "IDE-managed preview package installation failed")
                    }
                    "tools.preview.remove" -> {
                        if (services.projectLiveServer.remove()) 0 to "IDE-managed preview package removed"
                        else 1 to "Could not remove the IDE-managed preview package"
                    }
                    else -> 2 to "Unsupported Android IDE tools command"
                }
            }.getOrElse { 1 to (it.message ?: it.javaClass.simpleName) }
            services.ideToolsCommandReceipts.record(requestId, exitCode, message, receiptData)
        }
    }

    private fun operationExitCode(outcome: dev.android.ide.contracts.OperationOutcome): Int = when (outcome) {
        dev.android.ide.contracts.OperationOutcome.COMPLETE -> 0
        dev.android.ide.contracts.OperationOutcome.BLOCKED,
        dev.android.ide.contracts.OperationOutcome.CANCELLED -> 4
        dev.android.ide.contracts.OperationOutcome.PARTIAL,
        dev.android.ide.contracts.OperationOutcome.INTERRUPTED,
        dev.android.ide.contracts.OperationOutcome.FAILED -> 1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> ideViewModel.handleVolumeKey(up = true) || super.onKeyDown(keyCode, event)
        KeyEvent.KEYCODE_VOLUME_DOWN -> ideViewModel.handleVolumeKey(up = false) || super.onKeyDown(keyCode, event)
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        appShellViewModel.foreground()
    }

    override fun onResume() {
        super.onResume()
        dev.android.ide.browser.AndroidPermissionBroker.attach(this)
    }

    override fun onStop() {
        appShellViewModel.background()
        ApplicationServices.from(application).browserPersistenceCoordinator.flush()
        super.onStop()
    }

    override fun onDestroy() {
        dev.android.ide.browser.AndroidPermissionBroker.detach(this)
        super.onDestroy()
    }

    private companion object {
        val IDE_TOOL_COMMANDS = setOf(
            "app.status", "app.test", "preview.start", "preview.status", "preview.test", "preview.stop",
            "browser.reload", "browser.status", "browser.back", "browser.forward", "browser.stop",
            "browser.tabs.list", "browser.tabs.select", "browser.tabs.close",
            "browser.downloads.pause", "browser.downloads.resume", "browser.downloads.cancel", "browser.downloads.retry", "browser.downloads.delete",
            "editor.status", "editor.test", "editor.refresh", "editor.tabs.list", "editor.tabs.select", "editor.tabs.close",
            "editor.open", "editor.save", "editor.save-all", "editor.format", "browser.test",
            "browser.test.tabs", "browser.test.downloads", "browser.test.permissions",
            "browser.downloads.list", "browser.downloads.open", "browser.downloads.open-file",
            "files.status", "files.test", "files.list", "files.read", "files.write", "files.create", "files.mkdir",
            "files.rename", "files.copy", "files.move", "files.delete",
            "search.status", "search.test", "search.files", "search.content",
            "terminal.status", "terminal.test", "terminal.sessions.list", "terminal.sessions.close",
            "storage.status", "storage.test", "tools.status", "tools.test",
            "projects.status", "projects.test", "projects.list", "projects.open", "projects.close",
            "projects.create", "projects.import-folder", "projects.import-zip", "projects.rename",
            "projects.duplicate", "projects.relocate", "projects.unregister", "projects.delete", "projects.export",
            "tools.lsp.install", "tools.lsp.status", "tools.lsp.remove",
            "tools.preview.install", "tools.preview.status", "tools.preview.remove",
        )
    }
}
