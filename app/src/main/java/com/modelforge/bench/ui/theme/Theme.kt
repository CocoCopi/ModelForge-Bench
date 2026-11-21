package com.modelforge.bench.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D4DE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0A1A6B),
    secondary = Color(0xFF0E7C66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F2E6),
    onSecondaryContainer = Color(0xFF00382D),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3C4),
    onTertiaryContainer = Color(0xFF4A2600),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF14161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Color(0xFF4B5265),
    outline = Color(0xFFB9C0D0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEBDFF),
    onPrimary = Color(0xFF09206E),
    primaryContainer = Color(0xFF243B9E),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF8ED9C6),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF005344),
    onSecondaryContainer = Color(0xFFC9F2E6),
    tertiary = Color(0xFFFFB877),
    onTertiary = Color(0xFF4A2600),
    tertiaryContainer = Color(0xFF6B3A00),
    onTertiaryContainer = Color(0xFFFFE3C4),
    background = Color(0xFF0F1320),
    onBackground = Color(0xFFE7EAF2),
    surface = Color(0xFF161B2B),
    onSurface = Color(0xFFE7EAF2),
    surfaceVariant = Color(0xFF232A40),
    onSurfaceVariant = Color(0xFFB7BFD4),
    outline = Color(0xFF46506B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A)
)

/** Tabular numerals matter here: scores and timings are read in columns. */
val BenchTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    )
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp
)

@Composable
fun BenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = BenchTypography,
        content = content
    )
}
