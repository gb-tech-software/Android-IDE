package dev.android.ide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.contracts.TerminalBrowserRequestProtocol
import dev.android.ide.contracts.TerminalStorageSetupProtocol
import dev.android.ide.ui.AppRoot
import dev.android.ide.viewmodel.IdeViewModel

class MainActivity : ComponentActivity() {
    private val storageSetupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                STORAGE_SETUP_ACTION -> {
                    if (intent.getStringExtra(STORAGE_SETUP_ACTION) == "storage") {
                        intent.getStringExtra(TerminalStorageSetupProtocol.REQUEST_ID_EXTRA)
                            ?.let(appShellViewModel::requestTerminalStorageSetup)
                    }
                }
                TerminalBrowserRequestProtocol.ACTION -> intent.getStringExtra(TerminalBrowserRequestProtocol.URL_EXTRA)
                    ?.let(appShellViewModel::requestTerminalBrowserNavigation)
            }
        }
    }

    val appShellViewModel: AppShellViewModel by lazy {
        ViewModelProvider(this)[AppShellViewModel::class.java]
    }
    val ideViewModel: IdeViewModel by lazy {
        ViewModelProvider(this)[IdeViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(32, 33, 36)
        window.navigationBarColor = Color.BLACK
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!appShellViewModel.back()) {
                    appShellViewModel.requestExitConfirmation()
                }
            }
        })
        setContent {
            AppRoot(viewModel = appShellViewModel, ideViewModel = ideViewModel, onExit = ::finish)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> ideViewModel.handleVolumeKey(up = true) || super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_VOLUME_DOWN -> ideViewModel.handleVolumeKey(up = false) || super.onKeyDown(keyCode, event)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(STORAGE_SETUP_ACTION)
            addAction(TerminalBrowserRequestProtocol.ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(storageSetupReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(storageSetupReceiver, filter)
        }
        appShellViewModel.foreground()
    }

    override fun onStop() {
        unregisterReceiver(storageSetupReceiver)
        appShellViewModel.background()
        super.onStop()
    }

    private companion object {
        const val STORAGE_SETUP_ACTION = "dev.android.ide.app.reload_style"
    }
}
