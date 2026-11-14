package com.reivex.browser.ui.theme

import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ReiVexLightColorScheme = lightColorScheme(
    primary = ReiVexTokens.Colors.lightPrimary,
    onPrimary = ReiVexTokens.Colors.lightOnPrimary,
    primaryContainer = ReiVexTokens.Colors.lightPrimaryContainer,
    onPrimaryContainer = ReiVexTokens.Colors.lightOnPrimaryContainer,
    secondary = ReiVexTokens.Colors.lightSecondary,
    onSecondary = ReiVexTokens.Colors.lightOnSecondary,
    secondaryContainer = ReiVexTokens.Colors.lightSecondaryContainer,
    onSecondaryContainer = ReiVexTokens.Colors.lightOnSecondaryContainer,
    tertiary = ReiVexTokens.Colors.lightTertiary,
    onTertiary = ReiVexTokens.Colors.lightOnTertiary,
    tertiaryContainer = ReiVexTokens.Colors.lightTertiaryContainer,
    onTertiaryContainer = ReiVexTokens.Colors.lightOnTertiaryContainer,
    error = ReiVexTokens.Colors.lightError,
    onError = ReiVexTokens.Colors.lightOnError,
    errorContainer = ReiVexTokens.Colors.lightErrorContainer,
    onErrorContainer = ReiVexTokens.Colors.lightOnErrorContainer,
    background = ReiVexTokens.Colors.lightBackground,
    onBackground = ReiVexTokens.Colors.lightOnBackground,
    surface = ReiVexTokens.Colors.lightSurface,
    onSurface = ReiVexTokens.Colors.lightOnSurface,
    surfaceVariant = ReiVexTokens.Colors.lightSurfaceVariant,
    surfaceDim = ReiVexTokens.Colors.lightSurfaceVariant,
    surfaceBright = ReiVexTokens.Colors.lightSurface,
    surfaceContainerLowest = ReiVexTokens.Colors.lightSurface,
    surfaceContainerLow = ReiVexTokens.Colors.lightSurfaceVariant,
    surfaceContainer = ReiVexTokens.Colors.lightSurfaceVariant,
    surfaceContainerHigh = ReiVexTokens.Colors.lightSurfaceVariant,
    surfaceContainerHighest = ReiVexTokens.Colors.lightSurfaceDialog,
    onSurfaceVariant = ReiVexTokens.Colors.lightMuted,
    outline = ReiVexTokens.Colors.lightOutline,
    outlineVariant = ReiVexTokens.Colors.lightDivider,
    inverseSurface = ReiVexTokens.Colors.inverseSurface,
    inverseOnSurface = ReiVexTokens.Colors.inverseOnSurface,
    inversePrimary = ReiVexTokens.Colors.inversePrimary,
    scrim = ReiVexTokens.Colors.overlay,
)

private val ReiVexDarkColorScheme = darkColorScheme(
    primary = ReiVexTokens.Colors.primary,
    onPrimary = ReiVexTokens.Colors.onPrimary,
    primaryContainer = ReiVexTokens.Colors.primaryContainer,
    onPrimaryContainer = ReiVexTokens.Colors.onPrimaryContainer,
    secondary = ReiVexTokens.Colors.secondary,
    onSecondary = ReiVexTokens.Colors.onSecondary,
    secondaryContainer = ReiVexTokens.Colors.secondaryContainer,
    onSecondaryContainer = ReiVexTokens.Colors.onSecondaryContainer,
    tertiary = ReiVexTokens.Colors.tertiary,
    onTertiary = ReiVexTokens.Colors.onTertiary,
    tertiaryContainer = ReiVexTokens.Colors.tertiaryContainer,
    onTertiaryContainer = ReiVexTokens.Colors.onTertiaryContainer,
    error = ReiVexTokens.Colors.error,
    onError = ReiVexTokens.Colors.onError,
    errorContainer = ReiVexTokens.Colors.errorContainer,
    onErrorContainer = ReiVexTokens.Colors.onErrorContainer,
    background = ReiVexTokens.Colors.background,
    onBackground = ReiVexTokens.Colors.onBackground,
    surface = ReiVexTokens.Colors.surface,
    onSurface = ReiVexTokens.Colors.onSurface,
    surfaceVariant = ReiVexTokens.Colors.surfaceVariant,
    surfaceDim = ReiVexTokens.Colors.background,
    surfaceBright = ReiVexTokens.Colors.surfaceRaised,
    surfaceContainerLowest = ReiVexTokens.Colors.background,
    surfaceContainerLow = ReiVexTokens.Colors.backgroundSecondary,
    surfaceContainer = ReiVexTokens.Colors.surface,
    surfaceContainerHigh = ReiVexTokens.Colors.surfaceSheet,
    surfaceContainerHighest = ReiVexTokens.Colors.surfaceDialog,
    onSurfaceVariant = ReiVexTokens.Colors.muted,
    outline = ReiVexTokens.Colors.outline,
    outlineVariant = ReiVexTokens.Colors.divider,
    inverseSurface = ReiVexTokens.Colors.inverseSurface,
    inverseOnSurface = ReiVexTokens.Colors.inverseOnSurface,
    inversePrimary = ReiVexTokens.Colors.inversePrimary,
    scrim = ReiVexTokens.Colors.overlay,
)

/**
 * Shared Material 3 theme for all future native ReiVex screens.
 *
 * This step establishes visual tokens only. It does not select a screen,
 * create navigation, or connect to application/domain state.
 */
@Keep
@Composable
fun ReiVexComposeTheme(
    themeMode: String? = null,
    content: @Composable () -> Unit,
) {
    val normalizedMode = themeMode?.trim()?.lowercase().orEmpty()
    val darkTheme = when (normalizedMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) ReiVexDarkColorScheme else ReiVexLightColorScheme,
        typography = ReiVexTokens.typography,
        shapes = ReiVexTokens.shapes,
        content = content,
    )
}
