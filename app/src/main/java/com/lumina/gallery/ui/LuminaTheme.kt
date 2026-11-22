package com.lumina.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LuminaColors = darkColorScheme(
    primary = Color(0xFFB7A1FF),
    onPrimary = Color(0xFF21153F),
    secondary = Color(0xFF9FC8FF),
    onSecondary = Color(0xFF071D34),
    tertiary = Color(0xFF8DE4D0),
    background = Color(0xFF050506),
    onBackground = Color(0xFFF3F0FA),
    surface = Color(0xFF101014),
    onSurface = Color(0xFFF3F0FA),
    surfaceVariant = Color(0xFF1B1B22),
    onSurfaceVariant = Color(0xFFB9B5C4),
    outline = Color(0xFF45414F),
    error = Color(0xFFFF7777)
)

@Composable
fun LuminaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LuminaColors, content = content)
}
