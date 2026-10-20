package dev.android.ide.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.app.TerminalStorageSetupRequest
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.TerminalStorageSetupPermission

/**
 * Android platform adapter for the permission stage of termux-setup-storage.
 * Completion is routed back to the waiting shell command through the runtime adapter.
 */
@Composable
internal fun TerminalStorageSetupPermissionHost(
    viewModel: AppShellViewModel,
    request: TerminalStorageSetupRequest?,
) {
    val context = LocalContext.current
    var launchedRequestId by remember { mutableStateOf<String?>(null) }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requestId = launchedRequestId ?: viewModel.state.value.terminalStorageSetupRequest?.id
            ?: return@rememberLauncherForActivityResult
        launchedRequestId = null
        val decision = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            TerminalStorageSetupPermission.Granted
        } else {
            TerminalStorageSetupPermission.Denied(
                OperationReport(
                    OperationOutcome.BLOCKED,
                    "All files access was not granted. No storage links were changed.",
                    ErrorCategory.PERMISSION_LOST,
                ),
            )
        }
        viewModel.completeTerminalStorageSetup(requestId, decision)
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val requestId = launchedRequestId ?: viewModel.state.value.terminalStorageSetupRequest?.id
            ?: return@rememberLauncherForActivityResult
        launchedRequestId = null
        val decision = if (grants.isNotEmpty() && grants.values.all { it }) {
            TerminalStorageSetupPermission.Granted
        } else {
            TerminalStorageSetupPermission.Denied(
                OperationReport(
                    OperationOutcome.BLOCKED,
                    "Storage permission was not granted. No storage links were changed.",
                    ErrorCategory.PERMISSION_LOST,
                ),
            )
        }
        viewModel.completeTerminalStorageSetup(requestId, decision)
    }

    LaunchedEffect(request?.id) {
        val requestId = request?.id ?: run {
            return@LaunchedEffect
        }
        launchedRequestId = requestId
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    launchedRequestId = null
                    viewModel.completeTerminalStorageSetup(requestId, TerminalStorageSetupPermission.Granted)
                } else {
                    runCatching {
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            },
                        )
                    }.onFailure { error ->
                        launchedRequestId = null
                        viewModel.completeTerminalStorageSetup(
                            requestId,
                            TerminalStorageSetupPermission.Denied(
                                OperationReport(
                                    OperationOutcome.BLOCKED,
                                    "Android Settings could not open. No storage links were changed.",
                                    ErrorCategory.PERMISSION_LOST,
                                    recoveryHint = error.message,
                                ),
                            ),
                        )
                    }
                }
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                launchedRequestId = null
                viewModel.completeTerminalStorageSetup(
                    requestId,
                    TerminalStorageSetupPermission.Denied(
                        OperationReport(
                            OperationOutcome.BLOCKED,
                            "Android 10 scoped storage cannot expose shared folders as terminal symlinks. Use the IDE's SAF import/export controls.",
                            ErrorCategory.UNSUPPORTED_PROVIDER_CAPABILITY,
                        ),
                    ),
                )
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> {
                launchedRequestId = null
                viewModel.completeTerminalStorageSetup(requestId, TerminalStorageSetupPermission.Granted)
            }
            else -> {
                val permissions = buildList {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                    launchedRequestId = null
                    viewModel.completeTerminalStorageSetup(requestId, TerminalStorageSetupPermission.Granted)
                } else {
                    legacyPermissionLauncher.launch(permissions.toTypedArray())
                }
            }
        }
    }
}
