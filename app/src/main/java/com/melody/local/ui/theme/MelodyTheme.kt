package com.melody.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

val Ink = Color(0xFF111016)
val InkSoft = Color(0xFF1A1821)
val SurfaceRaised = Color(0xFF24212D)
val Coral = Color(0xFFFF8A67)
val CoralSoft = Color(0xFFFFB39B)
val Violet = Color(0xFFA793FF)
val Cream = Color(0xFFFFF7F2)
val Muted = Color(0xFFAAA4B2)

private val MelodyColors = darkColorScheme(
    primary = Coral,
    onPrimary = Ink,
    primaryContainer = Color(0xFF4A271F),
    onPrimaryContainer = Color(0xFFFFD9CE),
    secondary = Violet,
    onSecondary = Ink,
    background = Ink,
    onBackground = Cream,
    surface = InkSoft,
    onSurface = Cream,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = Muted,
    outline = Color(0xFF4A4652),
    error = Color(0xFFFFB4AB),
)

private val MelodyTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun MelodyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MelodyColors,
        typography = MelodyTypography,
        content = content,
    )
}
