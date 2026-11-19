package com.turbooptimizer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object TurboColors {
    val Good = Color(0xFF2ECC71)
    val Warn = Color(0xFFF5B041)
    val Bad = Color(0xFFE74C3C)
    val Teal = Color(0xFF00D1B2)
    val Violet = Color(0xFF7C4DFF)
    val Amber = Color(0xFFFFB300)
    val Sky = Color(0xFF4FC3F7)
    val Pink = Color(0xFFFF4081)

    /** Verde → ámbar → rojo según el nivel de carga (0..1). */
    fun level(fraction: Float, warnAt: Float = 0.7f, badAt: Float = 0.88f) = when {
        fraction >= badAt -> Bad
        fraction >= warnAt -> Warn
        else -> Good
    }

    fun temp(celsius: Float?) = when {
        celsius == null -> Sky
        celsius >= 45f -> Bad
        celsius >= 40f -> Warn
        else -> Good
    }
}

private val DarkColors = darkColorScheme(
    primary = TurboColors.Teal,
    onPrimary = Color(0xFF00201A),
    primaryContainer = Color(0xFF00382F),
    onPrimaryContainer = Color(0xFF7CF8DC),
    secondary = TurboColors.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2B1C5C),
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = TurboColors.Amber,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE3E7EC),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE3E7EC),
    surfaceVariant = Color(0xFF1C242E),
    onSurfaceVariant = Color(0xFF9AA6B2),
    surfaceContainer = Color(0xFF151C25),
    surfaceContainerHigh = Color(0xFF1A222C),
    surfaceContainerHighest = Color(0xFF212B36),
    outline = Color(0xFF34404D),
    outlineVariant = Color(0xFF26303B),
    error = TurboColors.Bad,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5A),
    primaryContainer = Color(0xFF7CF8DC),
    secondary = Color(0xFF5B3FD1),
    tertiary = Color(0xFF8A5A00),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6EBEF),
    surfaceContainer = Color(0xFFF0F3F6),
    surfaceContainerHigh = Color(0xFFEAEEF2),
)

private val TurboTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

@Composable
fun TurboTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TurboTypography,
        content = content,
    )
}
