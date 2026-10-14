package dev.android.ide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.app.ApplicationServices
import dev.android.ide.app.ExternalContentRoute
import dev.android.ide.app.ExternalContentRouter
import dev.android.ide.browser.AndroidBrowserDownloadService
import dev.android.ide.browser.BrowserViewModel
import dev.android.ide.contracts.OpenUrlRequestProtocol
import dev.android.ide.contracts.Surface
import dev.android.ide.contracts.TerminalStorageSetupProtocol
import dev.android.ide.runtime.TerminalForegroundService
import dev.android.ide.ui.AppRoot
import dev.android.ide.viewmodel.IdeViewModel

class MainActivity : ComponentActivity() {
    private val externalContentRouter = ExternalContentRouter()
    private val storageSetupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == STORAGE_SETUP_ACTION && intent.getStringExtra(STORAGE_SETUP_ACTION) == "storage") {
                intent.getStringExtra(TerminalStorageSetupProtocol.REQUEST_ID_EXTRA)
                    ?.let(appShellViewModel::requestTerminalStorageSetup)
            }
        }
    }

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
        when (intent.action) {
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
                ?.takeIf { REQUEST_ID_PATTERN.matches(it) }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> ideViewModel.handleVolumeKey(up = true) || super.onKeyDown(keyCode, event)
        KeyEvent.KEYCODE_VOLUME_DOWN -> ideViewModel.handleVolumeKey(up = false) || super.onKeyDown(keyCode, event)
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply { addAction(STORAGE_SETUP_ACTION) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // termux-setup-storage invokes `am broadcast` from the bundled shell.
            // That sender has a different UID, so NOT_EXPORTED rejects the request
            // before the permission host can show any UI.
            registerReceiver(storageSetupReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(storageSetupReceiver, filter)
        }
        appShellViewModel.foreground()
    }

    override fun onStop() {
        unregisterReceiver(storageSetupReceiver)
        appShellViewModel.background()
        ApplicationServices.from(application).browserPersistenceCoordinator.flush()
        super.onStop()
    }

    private companion object {
        const val STORAGE_SETUP_ACTION = "dev.android.ide.app.reload_style"
        val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,80}")
    }
}
