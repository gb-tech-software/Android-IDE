package dev.android.ide.browser

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import org.mozilla.geckoview.GeckoSession

/** Bridges GeckoView Android permission requests to the foreground Activity. */
object AndroidPermissionBroker {
    private const val REQUEST_CODE = 0x4A31

    private data class Pending(
        val permissions: Array<String>,
        val callback: GeckoSession.PermissionDelegate.Callback,
    )

    @Volatile
    private var pending: Pending? = null

    fun request(
        context: Context,
        permissions: Array<String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        val activity = context as? Activity
        val requested = permissions.orEmpty()
            .filter { it in SUPPORTED_PERMISSIONS }
            .distinct()
            .toTypedArray()
        if (activity == null || requested.isEmpty()) {
            callback.reject()
            return
        }
        if (requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            callback.grant()
            return
        }
        pending = Pending(requested, callback)
        activity.requestPermissions(requested, REQUEST_CODE)
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val request = pending ?: return true
        pending = null
        val granted = request.permissions.all { permission ->
            val index = permissions.indexOf(permission)
            index >= 0 && grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) request.callback.grant() else request.callback.reject()
        return true
    }

    private val SUPPORTED_PERMISSIONS = setOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
}
