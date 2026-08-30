package com.melody.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

val PageBackground = Color(0xFFFFFAF7)
val CardSurface = Color(0xFFFFFFFF)
val SoftSurface = Color(0xFFFFF0EB)
val TextPrimary = Color(0xFF29252E)
val TextSecondary = Color(0xFF716B76)

val Ink = TextPrimary
val InkSoft = SoftSurface
val SurfaceRaised = Color(0xFFEDE3E1)
val Coral = Color(0xFFF87555)
val CoralSoft = Color(0xFFC85238)
val Violet = Color(0xFF8E7AE6)
val Cream = TextPrimary
val Muted = TextSecondary

private val MelodyColors = lightColorScheme(
    primary = Coral,
    onPrimary = Ink,
    primaryContainer = Color(0xFFFFDED4),
    onPrimaryContainer = Color(0xFF6E2414),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE4FF),
    onSecondaryContainer = Color(0xFF35236F),
    background = PageBackground,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = SoftSurface,
    onSurfaceVariant = Muted,
    outline = Color(0xFFD4C9CA),
    outlineVariant = Color(0xFFE9DEDC),
    error = Color(0xFFB3261E),
    onError = Color.White,
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
