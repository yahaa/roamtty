package io.itermux.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.ssh.Phase

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
    background = Color(0xFFF3F0EB), onBackground = Color(0xFF2B2A27),
    surface = Color(0xFFFFFDF9), onSurface = Color(0xFF2B2A27),
    surfaceDim = Color(0xFFE9E5DE), surfaceBright = Color(0xFFFFFDF9),
    surfaceContainerLowest = Color(0xFFFFFDF9), surfaceContainerLow = Color(0xFFF6F3EE),
    surfaceContainer = Color(0xFFF1EEE8), surfaceContainerHigh = Color(0xFFEBE7E0),
    surfaceContainerHighest = Color(0xFFE5E0D8),
    surfaceVariant = Color(0xFFF1EEE8), onSurfaceVariant = Color(0xFF5F5B54),
    outline = Color(0xFF827A70), outlineVariant = Color(0xFFD8D1C8),
    error = Color(0xFFB32631), onError = Color.White
)

/**
 * One corner family for the whole app: fields and menus 10, rows 12, grouped panels and cards 16,
 * floating notices 20, dialogs and sheets 28. Screens use these instead of local radii.
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * A compact, content-first scale modelled on Apple's text styles: a large title per page,
 * semibold row titles, and nothing smaller than 12 sp for information people need to read.
 */
internal val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        headlineSmall = headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    )
}

/** Uppercase-free section caption used above grouped panels. */
internal val Typography.sectionHeader: TextStyle
    get() = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)

/** Status hues tuned for both themes; connection state never relies on the accent colour alone. */
internal object StatusColors {
    @Composable @ReadOnlyComposable
    private fun light() = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val success: Color @Composable @ReadOnlyComposable get() = if (light()) Color(0xFF2E7D4F) else Color(0xFF7FD19B)
    val warning: Color @Composable @ReadOnlyComposable get() = if (light()) Color(0xFF9A6200) else Color(0xFFF2C46D)
    val danger: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
    val idle: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

    @Composable @ReadOnlyComposable
    fun of(phase: Phase): Color = when (phase) {
        Phase.CONNECTED -> success
        Phase.CONNECTING, Phase.VERIFYING, Phase.AUTHENTICATING -> warning
        Phase.DISCONNECTED -> idle
        else -> danger
    }
}
