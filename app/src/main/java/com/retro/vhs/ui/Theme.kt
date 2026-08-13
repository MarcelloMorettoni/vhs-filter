package com.retro.vhs.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TapeRed = Color(0xFFE23B3B)
val TapeAmber = Color(0xFFFFB43A)
val TapeCyan = Color(0xFF3ED8D0)
val Chassis = Color(0xFF141414)
val ChassisLight = Color(0xFF232323)

private val colors = darkColorScheme(
    primary = TapeAmber,
    onPrimary = Color.Black,
    secondary = TapeCyan,
    background = Color.Black,
    onBackground = Color(0xFFE8E8E8),
    surface = Chassis,
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = ChassisLight,
    error = TapeRed
)

private val typography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 1.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 1.5.sp
    )
)

@Composable
fun VhsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
