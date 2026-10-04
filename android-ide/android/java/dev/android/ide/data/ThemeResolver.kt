package dev.android.ide.data

import android.content.Context
import android.content.res.Configuration
import dev.android.ide.data.model.AppTheme

/** The concrete appearance consumed by UI domains. */
enum class ResolvedTheme {
    DARK,
    LIGHT,
}

/**
 * Resolves the global application theme and each domain's independent theme
 * mode. Consumers use the concrete result and do not repeat inheritance or
 * device-theme checks.
 */
class ThemeResolver(
    context: Context,
    private val applicationThemeProvider: (() -> AppTheme)? = null,
) {
    private val applicationContext = context.applicationContext
    private val themeRepository = ThemeRepository(applicationContext)

    fun getAppTheme(): ResolvedTheme = resolveAppTheme(currentApplicationTheme())

    fun getBrowserTheme(mode: String): ResolvedTheme = resolveDomainTheme(mode, currentApplicationTheme())

    fun getEditorTheme(mode: String): ResolvedTheme = resolveDomainTheme(mode, currentApplicationTheme())

    fun getTerminalTheme(mode: String): ResolvedTheme = resolveDomainTheme(mode, currentApplicationTheme())

    private fun currentApplicationTheme(): AppTheme =
        applicationThemeProvider?.invoke() ?: themeRepository.get()

    private fun resolveDomainTheme(mode: String, applicationTheme: AppTheme): ResolvedTheme = when (mode.lowercase()) {
        "dark" -> ResolvedTheme.DARK
        "light" -> ResolvedTheme.LIGHT
        "device", "system" -> deviceTheme()
        // Every domain defaults to following the global application theme.
        "application", "app", "" -> resolveAppTheme(applicationTheme)
        else -> resolveAppTheme(applicationTheme)
    }

    private fun resolveAppTheme(setting: AppTheme): ResolvedTheme = when (setting) {
        AppTheme.DARK -> ResolvedTheme.DARK
        AppTheme.LIGHT -> ResolvedTheme.LIGHT
        AppTheme.DEVICE -> deviceTheme()
    }

    private fun deviceTheme(): ResolvedTheme =
        if ((applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            ResolvedTheme.DARK
        } else {
            ResolvedTheme.LIGHT
        }
}
