package com.ysajang.ariavoice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AriaDarkColorScheme = darkColorScheme(
    primary = AriaBlue,
    onPrimary = AriaSurface,
    primaryContainer = AriaBlueDark,
    onPrimaryContainer = AriaBlueLight,
    secondary = AriaBlue,
    onSecondary = AriaSurface,
    background = AriaSurface,
    onBackground = AriaOnSurface,
    surface = AriaSurface,
    onSurface = AriaOnSurface,
    surfaceVariant = AriaSurfaceVariant,
    onSurfaceVariant = AriaOnSurfaceDim,
    error = AriaError,
    onError = AriaSurface
)

@Composable
fun AriaVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AriaDarkColorScheme,
        typography = AriaTypography,
        content = content
    )
}
