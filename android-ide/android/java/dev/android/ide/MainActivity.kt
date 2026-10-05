package dev.android.ide

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.runtime.BundledTermuxRuntimeInstaller
import dev.android.ide.ui.AppRoot
import dev.android.ide.viewmodel.IdeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val storageSetupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == STORAGE_SETUP_ACTION && intent.getStringExtra(STORAGE_SETUP_ACTION) == "storage") {
                requestStoragePermission()
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
        val filter = IntentFilter(STORAGE_SETUP_ACTION)
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

    private fun requestStoragePermission() {
        val requested = buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (requested.isEmpty() || requested.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            rebuildTermuxStorageLinks()
        } else {
            requestPermissions(requested.toTypedArray(), STORAGE_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                rebuildTermuxStorageLinks()
            } else {
                Toast.makeText(this, "Storage permission was not granted", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rebuildTermuxStorageLinks() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                BundledTermuxRuntimeInstaller(applicationContext).rebuildStorageLinks()
            }
            Toast.makeText(this@MainActivity, report.message, Toast.LENGTH_LONG).show()
            if (report.outcome != OperationOutcome.COMPLETE) {
                report.recoveryHint?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private companion object {
        const val STORAGE_SETUP_ACTION = "dev.android.ide.app.reload_style"
        const val STORAGE_PERMISSION_REQUEST = 4201
    }
}
