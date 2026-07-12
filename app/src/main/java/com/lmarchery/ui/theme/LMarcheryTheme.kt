package com.lmarchery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C4F),
    onPrimary = Color.White,
    secondary = Color(0xFF4D6357),
    surfaceContainer = Color(0xFFEFF3ED),
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
