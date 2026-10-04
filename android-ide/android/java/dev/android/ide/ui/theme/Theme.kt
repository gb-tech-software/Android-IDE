// Material 3 theme wrapper supporting Dark, Light, and device-following preferences.
package dev.android.ide.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import dev.android.ide.data.ResolvedTheme

private val IdeDarkColorScheme = darkColorScheme(
    primary = darkIdeColors.primary,
    onPrimary = darkIdeColors.onPrimary,
    primaryContainer = darkIdeColors.primaryContainer,
    onPrimaryContainer = darkIdeColors.onPrimaryContainer,
    secondary = darkIdeColors.secondary,
    onSecondary = darkIdeColors.onSecondary,
    secondaryContainer = darkIdeColors.secondaryContainer,
    onSecondaryContainer = darkIdeColors.onSecondaryContainer,
    tertiary = darkIdeColors.tertiary,
    onTertiary = darkIdeColors.onTertiary,
    tertiaryContainer = darkIdeColors.tertiaryContainer,
    onTertiaryContainer = darkIdeColors.onTertiaryContainer,
    background = darkIdeColors.background,
    onBackground = darkIdeColors.textPrimary,
    surface = darkIdeColors.surface,
    onSurface = darkIdeColors.textPrimary,
    surfaceVariant = darkIdeColors.surfaceVariant,
    onSurfaceVariant = darkIdeColors.textSecondary,
    outline = darkIdeColors.separator,
    outlineVariant = darkIdeColors.separator,
    error = darkIdeColors.error,
    onError = darkIdeColors.onError,
    errorContainer = darkIdeColors.errorContainer,
    onErrorContainer = darkIdeColors.onErrorContainer,
    inverseSurface = darkIdeColors.inverseSurface,
    inverseOnSurface = darkIdeColors.inverseOnSurface,
    inversePrimary = darkIdeColors.inversePrimary,
    surfaceTint = darkIdeColors.primary,
    scrim = Color.Black,
)

private val IdeLightColorScheme = lightColorScheme(
    primary = lightIdeColors.primary,
    onPrimary = lightIdeColors.onPrimary,
    primaryContainer = lightIdeColors.primaryContainer,
    onPrimaryContainer = lightIdeColors.onPrimaryContainer,
    secondary = lightIdeColors.secondary,
    onSecondary = lightIdeColors.onSecondary,
    secondaryContainer = lightIdeColors.secondaryContainer,
    onSecondaryContainer = lightIdeColors.onSecondaryContainer,
    tertiary = lightIdeColors.tertiary,
    onTertiary = lightIdeColors.onTertiary,
    tertiaryContainer = lightIdeColors.tertiaryContainer,
    onTertiaryContainer = lightIdeColors.onTertiaryContainer,
    background = lightIdeColors.background,
    onBackground = lightIdeColors.textPrimary,
    surface = lightIdeColors.surface,
    onSurface = lightIdeColors.textPrimary,
    surfaceVariant = lightIdeColors.surfaceVariant,
    onSurfaceVariant = lightIdeColors.textSecondary,
    outline = lightIdeColors.separator,
    outlineVariant = lightIdeColors.separator,
    error = lightIdeColors.error,
    onError = lightIdeColors.onError,
    errorContainer = lightIdeColors.errorContainer,
    onErrorContainer = lightIdeColors.onErrorContainer,
    inverseSurface = lightIdeColors.inverseSurface,
    inverseOnSurface = lightIdeColors.inverseOnSurface,
    inversePrimary = lightIdeColors.inversePrimary,
    surfaceTint = lightIdeColors.primary,
    scrim = Color.Black,
)

/** Root theme composable; theme changes apply without restarting the app. */
@Composable
fun AndroidIDETheme(
    resolvedTheme: ResolvedTheme,
    content: @Composable () -> Unit,
) {
    val isDark = resolvedTheme == ResolvedTheme.DARK
    val colorScheme = if (isDark) IdeDarkColorScheme else IdeLightColorScheme
    val ideColors = if (isDark) darkIdeColors else lightIdeColors

    CompositionLocalProvider(LocalIdeColors provides ideColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = IdeTypography,
            content = content,
        )
    }
}
