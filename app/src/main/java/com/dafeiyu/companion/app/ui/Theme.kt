package com.dafeiyu.companion.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF2F2A26)
private val Paper = Color(0xFFFFF8F1)
private val Coral = Color(0xFFB5543B)
private val Moss = Color(0xFF4F6A63)
private val Sand = Color(0xFFF3E8DD)
private val WarmWhite = Color(0xFFFFFDF9)

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6D9CF),
    onPrimaryContainer = Color(0xFF4A1B0F),
    secondary = Moss,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E6E1),
    onSecondaryContainer = Color(0xFF17332D),
    background = Paper,
    onBackground = Ink,
    surface = WarmWhite,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = Color(0xFF6F6257),
    outline = Color(0xFFD8C8B8),
    outlineVariant = Color(0xFFE9DDD0),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8A087),
    onPrimary = Color(0xFF3B1208),
    primaryContainer = Color(0xFF552418),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFF9DC4B8),
    onSecondary = Color(0xFF0F2C25),
    secondaryContainer = Color(0xFF21443B),
    onSecondaryContainer = Color(0xFFC8E8DD),
    background = Color(0xFF16110E),
    onBackground = Color(0xFFF2E7DD),
    surface = Color(0xFF211A16),
    onSurface = Color(0xFFF2E7DD),
    surfaceVariant = Color(0xFF2C241E),
    onSurfaceVariant = Color(0xFFCBB9A9),
    outline = Color(0xFF5A4A3E),
    outlineVariant = Color(0xFF3B2F27),
    error = Color(0xFFFFB4AB)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
