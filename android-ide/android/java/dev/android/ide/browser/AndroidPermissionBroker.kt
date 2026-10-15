package dev.android.ide.browser

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import org.mozilla.geckoview.GeckoSession
import java.util.ArrayDeque
import java.lang.ref.WeakReference

/** Bridges GeckoView Android runtime permission requests to the foreground Activity. */
object AndroidPermissionBroker {
    private const val REQUEST_CODE = 0x4A31

    private data class Pending(
        val activity: Activity,
        val permissions: Array<String>,
        val callback: GeckoSession.PermissionDelegate.Callback,
    )

    private val lock = Any()
    private val queued = ArrayDeque<Pending>()
    private var active: Pending? = null
    @Volatile private var foregroundActivity: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        foregroundActivity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (foregroundActivity?.get() === activity) foregroundActivity = null
    }

    fun request(
        context: Context,
        permissions: Array<String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        val activity = (context as? Activity) ?: foregroundActivity?.get()
        val requested = permissions.orEmpty().distinct()
        if (activity == null || requested.isEmpty() || requested.any { it !in SUPPORTED_PERMISSIONS }) {
            callback.reject()
            return
        }
        if (requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            callback.grant()
            return
        }
        synchronized(lock) { queued.addLast(Pending(activity, requested, callback)) }
        launchNext()
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val request = synchronized(lock) {
            active.also { active = null }
        } ?: run {
            launchNext()
            return true
        }
        val granted = request.permissions.all { permission ->
            if (request.activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return@all true
            val index = permissions.indexOf(permission)
            index >= 0 && grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) request.callback.grant() else request.callback.reject()
        launchNext()
        return true
    }

    private fun launchNext() {
        val request = synchronized(lock) {
            if (active != null || queued.isEmpty()) null
            else queued.removeFirst().also { active = it }
        } ?: return
        if (request.activity.isFinishing || request.activity.isDestroyed) {
            request.callback.reject()
            synchronized(lock) { active = null }
            launchNext()
            return
        }
        val missing = request.permissions.filter {
            request.activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            request.callback.grant()
            synchronized(lock) { active = null }
            launchNext()
            return
        }
        try {
            request.activity.requestPermissions(missing, REQUEST_CODE)
        } catch (_: RuntimeException) {
            request.callback.reject()
            synchronized(lock) { active = null }
            launchNext()
        }
    }

    private val SUPPORTED_PERMISSIONS = setOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
}
