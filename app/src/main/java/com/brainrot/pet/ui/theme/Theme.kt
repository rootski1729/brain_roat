package com.brainrot.pet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFB3586F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E0019),
    secondary = Color(0xFF75565D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E0),
    onSecondaryContainer = Color(0xFF2B151B),
    tertiary = Color(0xFF7C5635),
    tertiaryContainer = Color(0xFFFFDCC1),
    background = Color(0xFFFFF7FA),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF7FA),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF3DDE2),
    onSurfaceVariant = Color(0xFF514347),
    surfaceContainer = Color(0xFFF9EBEF),
    surfaceContainerHigh = Color(0xFFF3E5E9),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB1C5),
    onPrimary = Color(0xFF5F112E),
    primaryContainer = Color(0xFF7D2A45),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFE3BDC5),
    onSecondary = Color(0xFF422930),
    secondaryContainer = Color(0xFF5A3F46),
    onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = Color(0xFFEFBD94),
    tertiaryContainer = Color(0xFF613F20),
    background = Color(0xFF17111C),
    onBackground = Color(0xFFEFDFE3),
    surface = Color(0xFF17111C),
    onSurface = Color(0xFFEFDFE3),
    surfaceVariant = Color(0xFF514347),
    onSurfaceVariant = Color(0xFFD6C2C6),
    surfaceContainer = Color(0xFF241C24),
    surfaceContainerHigh = Color(0xFF2F262F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun BrainRotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
