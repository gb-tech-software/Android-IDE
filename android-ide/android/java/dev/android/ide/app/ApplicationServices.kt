package dev.android.ide.app

import android.app.Application
import dev.android.ide.data.CrashRecoveryRepository
import dev.android.ide.data.EditorSettingsRepository
import dev.android.ide.data.ProjectRepository
import dev.android.ide.data.SessionRepository
import dev.android.ide.data.TerminalSettingsRepository
import dev.android.ide.data.ThemeRepository
import dev.android.ide.data.ThemeResolver
import dev.android.ide.browser.BrowserStateStore
import dev.android.ide.browser.BrowserPersistenceCoordinator
import dev.android.ide.browser.BrowserApplicationService
import dev.android.ide.browser.BrowserEngine
import dev.android.ide.browser.GeckoViewBrowserEngine
import dev.android.ide.browser.GeckoBrowserDeveloperToolsService
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import dev.android.ide.filesystem.AndroidIdeFileSystem
import dev.android.ide.lifecycle.LifecycleCoordinatorImpl
import dev.android.ide.lifecycle.LifecycleStateStore
import dev.android.ide.project.ProjectManagementService
import dev.android.ide.project.ProjectMetadataStore
import dev.android.ide.project.ProjectRegistryStore
import dev.android.ide.project.ProjectStorageAdapterImpl
import dev.android.ide.project.GitMetadataReader
import dev.android.ide.runtime.RuntimeStateStore
import dev.android.ide.runtime.BundledTermuxRuntimeInstaller
import dev.android.ide.runtime.LanguageServerPackageInstaller
import dev.android.ide.runtime.LanguageServerRuntimeAdapterImpl
import dev.android.ide.workspace.ProjectWorkspaceService
import dev.android.ide.runtime.TerminalRuntimeAdapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application composition root.
 *
 * This is the only application-owned construction boundary for shared stores,
 * provider adapters, and project/runtime services. ViewModels coordinate state
 * and user intent; they do not choose alternate implementations or construct
 * parallel sources of truth.
 */
class ApplicationServices private constructor(application: Application) {
    private val context = application.applicationContext

    val fileSystem = AndroidIdeFileSystem.from(context)
    val projectRepository = ProjectRepository(context)
    val sessionRepository = SessionRepository(context)
    val editorSettingsRepository = EditorSettingsRepository(context)
    val crashRecoveryRepository = CrashRecoveryRepository(context)
    val lifecycleStateStore = LifecycleStateStore(context)
    val themeResolver = ThemeResolver(context)
    val browserStateStore by lazy { BrowserStateStore(context, browserEngine) }
    val browserRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .forceUserScalableEnabled(true)
            .doubleTapZoomingEnabled(true)
            .build()
        GeckoRuntime.create(context, settings)
    }
    val browserEngine: BrowserEngine by lazy { GeckoViewBrowserEngine(browserRuntime, context) }
    val browserDeveloperTools by lazy { GeckoBrowserDeveloperToolsService(browserRuntime) }
    private val browserApplicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val projectLiveServer by lazy {
        ProjectLiveServerService(
            bundledRuntimeInstaller.prefix(),
            bundledRuntimeInstaller.home(),
            bundledRuntimeInstaller.sessionEnvironment(),
        )
    }
    val browserNavigationReceipts by lazy { BrowserNavigationReceiptStore(context) }
    val ideToolsCommandReceipts by lazy { dev.android.ide.contracts.IdeToolsCommandProtocol.Receipts(context) }
    val browserPersistenceCoordinator by lazy { BrowserPersistenceCoordinator(browserApplicationScope) }
    val browserApplicationService by lazy {
        BrowserApplicationService(
            context,
            browserStateStore,
            browserPersistenceCoordinator,
            browserEngine,
            browserDeveloperTools,
            browserApplicationScope,
        )
    }

    val applicationStateStore = ApplicationStateStore(context)
    val runtimeStateStore = RuntimeStateStore(context)
    val lifecycleCoordinator = LifecycleCoordinatorImpl(context)
    val themeRepository = ThemeRepository(context)
    val terminalSettingsRepository = TerminalSettingsRepository(context)
    val projectWorkspace = ProjectWorkspaceService(fileSystem.resources)
    val bundledRuntimeInstaller = BundledTermuxRuntimeInstaller(context)
    val languageServerPackageInstaller by lazy { LanguageServerPackageInstaller(context, bundledRuntimeInstaller) }
    val terminalRuntime = TerminalRuntimeAdapterImpl(context, projectWorkspace, bundledRuntimeInstaller)

    val projectStorage = ProjectStorageAdapterImpl(fileSystem.documents, fileSystem.operations)
    val projectRegistry = ProjectRegistryStore(projectRepository, projectStorage)
    val projectMetadata = ProjectMetadataStore(fileSystem.operations)
    val gitMetadata = GitMetadataReader(fileSystem.operations)
    val projectManagement = ProjectManagementService(
        context,
        projectRegistry,
        projectStorage,
        fileSystem.operations,
        projectMetadata,
        gitMetadata,
    )

    fun languageServerAdapter(
        onMessage: (projectId: String, serverId: String, message: ByteArray) -> Unit,
        onUnavailable: (projectId: String, serverId: String, explanation: String) -> Unit = { _, _, _ -> },
    ) = LanguageServerRuntimeAdapterImpl(
        projectWorkspace,
        bundledRuntimeInstaller,
        languageServerPackageInstaller,
        onMessage,
        onUnavailable,
    )

    companion object {
        private val instances = java.util.WeakHashMap<Application, ApplicationServices>()

        @Synchronized
        fun from(application: Application): ApplicationServices =
            instances[application] ?: ApplicationServices(application).also { instances[application] = it }
    }
}
