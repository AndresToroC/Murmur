package com.nochesonora.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val NightColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = SurfaceHigh,
    onPrimaryContainer = Primary,
    secondary = SnoreAmber,
    secondaryContainer = Color(0xFF1A2742),
    onSecondaryContainer = Primary,
    background = Background,
    onBackground = OnSurface,
    surface = Background,
    onSurface = OnSurface,
    surfaceVariant = Surface,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = Surface,
    outline = BorderStrong,
    outlineVariant = Border,
    error = CoughRed,
)

/** Tema oscuro siempre (no sigue al sistema: de noche no debe deslumbrar). */
@Composable
fun MurmurTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightColors, typography = AppTypography) {
        // Color de texto por defecto claro también fuera de un Scaffold/Surface.
        CompositionLocalProvider(LocalContentColor provides OnSurface, content = content)
    }
}
