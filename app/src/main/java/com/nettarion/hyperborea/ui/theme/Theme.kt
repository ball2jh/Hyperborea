package com.nettarion.hyperborea.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val ColorScheme = darkColorScheme(
    background = BackgroundDeep,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    primary = ElectricBlue,
    onBackground = TextHigh,
    onSurface = TextHigh,
    onSurfaceVariant = TextMedium,
    error = StatusError,
    errorContainer = ErrorContainer,
    onError = TextHigh,
    onErrorContainer = StatusError,
)

@Immutable
data class HyperboreaColors(
    val divider: Color = Divider,
    val textHigh: Color = TextHigh,
    val textMedium: Color = TextMedium,
    val textLow: Color = TextLow,
    val accentWarm: Color = Amber,
    val electricBlue: Color = ElectricBlue,
    val statusActive: Color = StatusActive,
    val statusError: Color = StatusError,
    val statusIdle: Color = StatusIdle,
)

val LocalHyperboreaColors = staticCompositionLocalOf { HyperboreaColors() }

@Composable
fun HyperboreaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHyperboreaColors provides HyperboreaColors()) {
        MaterialTheme(
            colorScheme = ColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
