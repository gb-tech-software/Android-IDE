package dev.android.ide.data

import android.content.Context
import dev.android.ide.data.model.TerminalSettings

class TerminalSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    fun get(): TerminalSettings = TerminalSettings(
        theme = prefs.getString("theme", "application")
            ?.takeIf { it in setOf("dark", "light", "application", "device") }
            ?: "application",
        textSize = prefs.getInt("text_size", 14).coerceIn(8, 32),
        shortcutToolbarEnabled = prefs.getBoolean("shortcut_toolbar_enabled", true),
        shortcutOrder = prefs.getString("shortcut_order", null)
            ?.split(',')?.filter(String::isNotBlank)?.distinct()
            ?: TerminalSettings.DEFAULT_SHORTCUT_ORDER,
    )

    fun set(settings: TerminalSettings) {
        prefs.edit()
            .putString("theme", settings.theme)
            .putInt("text_size", settings.textSize.coerceIn(8, 32))
            .putBoolean("shortcut_toolbar_enabled", settings.shortcutToolbarEnabled)
            .putString("shortcut_order", settings.shortcutOrder.joinToString(","))
            .apply()
    }
}
