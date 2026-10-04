// android-ide/android/java/dev/android/ide/data/ThemeRepository.kt
//
// Persists the user's chosen application theme (Dark / Light / Follow device).

package dev.android.ide.data

import android.content.Context
import dev.android.ide.data.model.AppTheme

class ThemeRepository(context: Context) {

    companion object {
        private const val PREFS = "application_theme"
        private const val LEGACY_PREFS = "ide_theme"
        private const val KEY   = "application_theme"
        private const val LEGACY_KEY = "app_theme"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    fun get(): AppTheme = runCatching {
        val stored = prefs.getString(KEY, null)
            ?: legacyPrefs.getString(LEGACY_KEY, AppTheme.DEVICE.name)
            ?: AppTheme.DEVICE.name
        if (stored == "SYSTEM") AppTheme.DEVICE else AppTheme.valueOf(stored)
    }.getOrElse { AppTheme.DEVICE }

    fun set(theme: AppTheme) {
        prefs.edit().putString(KEY, theme.name).apply()
    }
}
