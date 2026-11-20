package com.lmarchery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF125845),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9EBDE),
    onPrimaryContainer = Color(0xFF102D23),
    secondary = Color(0xFF446356),
    secondaryContainer = Color(0xFFDBEAE0),
    onSecondaryContainer = Color(0xFF1C352B),
    background = Color(0xFFF7FAF6),
    surface = Color(0xFFF7FAF6),
    surfaceContainerLow = Color(0xFFEAF2EC),
    surfaceContainer = Color(0xFFE3EEE7),
    surfaceContainerHigh = Color(0xFFDDE9E1),
    surfaceVariant = Color(0xFFE1EEE5),
    onSurface = Color(0xFF18251F),
    onSurfaceVariant = Color(0xFF3C5148),
    outline = Color(0xFF657C71),
    outlineVariant = Color(0xFFB7CBC0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF65DBAE),
    onPrimary = Color(0xFF003828),
    secondary = Color(0xFFB4CCBE),
    surfaceContainer = Color(0xFF1C211E),
)

@Composable
fun LMarcheryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
