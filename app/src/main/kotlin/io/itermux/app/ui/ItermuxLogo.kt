package io.itermux.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import kotlin.math.PI
import kotlin.math.cos

/** The SSH signal mark, still for branding and breathing while work is in progress. */
@Composable
internal fun ItermuxLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    animated: Boolean = false,
) {
    val cycle = if (animated) rememberInfiniteTransition(label = "logo-pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "signal-cycle",
    ) else null
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(52.dp).semantics {
        contentDescription?.let { this.contentDescription = it }
    }) {
        val unit = size.minDimension / 52f
        // Freeze the middle signal at its peak for a balanced, readable static mark.
        val phase = cycle?.value ?: (1f / 3f)
        val breath = (0.5 - 0.5 * cos(phase * 2 * PI)).toFloat()
        drawRoundRect(accent.copy(alpha = 0.07f + 0.03f * breath), cornerRadius = CornerRadius(14 * unit))
        val stroke = 2.2f * unit
        drawLine(accent, Offset(15 * unit, 15 * unit), Offset(22 * unit, 22 * unit), stroke, StrokeCap.Round)
        drawLine(accent, Offset(22 * unit, 22 * unit), Offset(15 * unit, 29 * unit), stroke, StrokeCap.Round)
        drawLine(accent.copy(alpha = 0.5f + 0.5f * breath), Offset(28 * unit, 29 * unit),
            Offset(37 * unit, 29 * unit), stroke, StrokeCap.Round)
        // Read animation state only while drawing, avoiding per-frame recomposition.
        repeat(3) { index ->
            val pulse = (0.5 + 0.5 * cos((phase - index / 3f) * 2 * PI)).toFloat()
            drawCircle(accent.copy(alpha = 0.2f + 0.7f * pulse), (1.3f + 0.5f * pulse) * unit,
                Offset((18 + index * 8) * unit, 39 * unit))
        }
    }
}

/** The launcher icon: the toolbox ghost on the terminal, cropped to the adaptive icon's visible 72 of 108 units. */
@Composable
internal fun RoamttyAppIcon(modifier: Modifier = Modifier, contentDescription: String? = null) {
    val crop = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
    Box(modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).semantics {
        contentDescription?.let { this.contentDescription = it }
    }) {
        Image(painterResource(R.drawable.ic_launcher_terminal), null, crop)
        Image(painterResource(R.drawable.ic_launcher_ghost), null, crop)
    }
}
