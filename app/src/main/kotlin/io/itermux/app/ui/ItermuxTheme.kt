package io.itermux.app.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal enum class AppTheme(val label: String) {
    SYSTEM("跟随系统"), DARK("深色"), LIGHT("浅色");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: DARK
    }
}

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFEFA783), onPrimary = Color(0xFF442315),
    primaryContainer = Color(0xFF5B3C30), onPrimaryContainer = Color(0xFFF9D9C9),
    secondary = Color(0xFFD1C2B8), onSecondary = Color(0xFF382F29),
    secondaryContainer = Color(0xFF47413B), onSecondaryContainer = Color(0xFFECE2D8),
    background = Color(0xFF252728), onBackground = Color(0xFFF3F0EA),
    surface = Color(0xFF303230), onSurface = Color(0xFFF3F0EA),
    surfaceDim = Color(0xFF252728), surfaceBright = Color(0xFF484844),
    surfaceContainerLowest = Color(0xFF252728), surfaceContainerLow = Color(0xFF303230),
    surfaceContainer = Color(0xFF353632), surfaceContainerHigh = Color(0xFF3D3E39),
    surfaceContainerHighest = Color(0xFF484944),
    surfaceVariant = Color(0xFF353632), onSurfaceVariant = Color(0xFFC7C4BD),
    outline = Color(0xFF9A9890), outlineVariant = Color(0xFF50514B),
    error = Color(0xFFFFB4A8), onError = Color(0xFF5E1C17)
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF9E462C), onPrimary = Color.White,
    primaryContainer = Color(0xFFF4DED3), onPrimaryContainer = Color(0xFF682F20),
    secondary = Color(0xFF6D5D52), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE4DB), onSecondaryContainer = Color(0xFF3D332D),
    background = Color(0xFFF7F5F1), onBackground = Color(0xFF2B2A27),
    surface = Color(0xFFFFFDF9), onSurface = Color(0xFF2B2A27),
    surfaceDim = Color(0xFFE9E5DE), surfaceBright = Color(0xFFFFFDF9),
    surfaceContainerLowest = Color(0xFFFFFDF9), surfaceContainerLow = Color(0xFFF7F5F1),
    surfaceContainer = Color(0xFFF1EEE8), surfaceContainerHigh = Color(0xFFEBE7E0),
    surfaceContainerHighest = Color(0xFFE5E0D8),
    surfaceVariant = Color(0xFFF1EEE8), onSurfaceVariant = Color(0xFF5F5B54),
    outline = Color(0xFF827A70), outlineVariant = Color(0xFFD8D1C8),
    error = Color(0xFFB32631), onError = Color.White
)
