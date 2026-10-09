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
import org.mozilla.geckoview.GeckoRuntime
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
import dev.android.ide.runtime.LanguageServerRuntimeAdapterImpl
import dev.android.ide.workspace.ProjectWorkspaceService
import dev.android.ide.runtime.TerminalRuntimeAdapterImpl

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
    val browserStateStore = BrowserStateStore(context)
    val browserRuntime = GeckoRuntime.create(context)

    val applicationStateStore = ApplicationStateStore(context)
    val runtimeStateStore = RuntimeStateStore(context)
    val lifecycleCoordinator = LifecycleCoordinatorImpl(context)
    val themeRepository = ThemeRepository(context)
    val terminalSettingsRepository = TerminalSettingsRepository(context)
    val projectWorkspace = ProjectWorkspaceService(fileSystem.resources)
    val bundledRuntimeInstaller = BundledTermuxRuntimeInstaller(context)
    val terminalRuntime = TerminalRuntimeAdapterImpl(context, projectWorkspace, bundledRuntimeInstaller)

    val projectStorage = ProjectStorageAdapterImpl(fileSystem.documents, fileSystem.operations)
    val projectRegistry = ProjectRegistryStore(projectRepository, projectStorage)
    val projectMetadata = ProjectMetadataStore(fileSystem.operations)
    val gitMetadata = GitMetadataReader(fileSystem.operations)
    val projectManagement = ProjectManagementService(
        projectRegistry,
        projectStorage,
        fileSystem.operations,
        projectMetadata,
        gitMetadata,
    )

    fun languageServerAdapter(
        onMessage: (projectId: String, serverId: String, message: ByteArray) -> Unit,
    ) = LanguageServerRuntimeAdapterImpl(projectWorkspace, bundledRuntimeInstaller, onMessage)

    companion object {
        private val instances = java.util.WeakHashMap<Application, ApplicationServices>()

        @Synchronized
        fun from(application: Application): ApplicationServices =
            instances[application] ?: ApplicationServices(application).also { instances[application] = it }
    }
}
