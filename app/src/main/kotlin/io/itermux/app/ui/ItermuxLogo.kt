package io.itermux.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI

private val LogoTerminal = Color(0xFF252728)
private val LogoText = Color(0xFFF3F0EA)
private val LogoAccent = Color(0xFFEFA783)

/**
 * The launcher icon drawn live: the toolbox ghost over a terminal prompt, on the visible 72 units of the
 * adaptive icon (scripts/render-launcher-icon.py draws the same picture). Animated, the ghost bobs, ripples its
 * hem, blinks and waves the wrench while the cursor blinks; still, it matches the launcher icon.
 */
@Composable
internal fun ItermuxLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    animated: Boolean = false,
) {
    val idle = if (animated) rememberInfiniteTransition(label = "logo") else null
    val float = idle?.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val hem = idle?.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "hem")
    val eyes = idle?.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 3600
        1f at 3250
        0.1f at 3330
        1f at 3420
    }), label = "blink")
    val wave = idle?.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 5200
        0f at 3000
        -30f at 3180
        14f at 3380
        -30f at 3580
        14f at 3780
        0f at 3980
    }), label = "wave")
    val cursor = idle?.animateFloat(1f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 1060
        1f at 529
        0f at 530
    }), label = "cursor")
    Canvas(modifier.size(52.dp).semantics {
        contentDescription?.let { this.contentDescription = it }
    }) {
        // Everything below is in the launcher icon's units; read animation state only while drawing.
        val unit = size.minDimension / 72f
        scale(unit, unit, pivot = Offset.Zero) {
            drawRoundRect(LogoTerminal, size = Size(72f, 72f), cornerRadius = CornerRadius(19f))
            for ((x1, x2, y) in listOf(Triple(12f, 26f, 9f), Triple(7f, 19f, 16f), Triple(52f, 62f, 16f), Triple(6f, 14f, 23f))) {
                drawLine(LogoText.copy(alpha = 0.1f), Offset(x1, y), Offset(x2, y), 2.4f, StrokeCap.Round)
            }
            val prompt = Path().apply { moveTo(28f, 58f); lineTo(33f, 62.5f); lineTo(28f, 67f) }
            drawPath(prompt, LogoAccent, style = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawRoundRect(LogoAccent.copy(alpha = 0.85f * (cursor?.value ?: 1f)), Offset(37f, 57.5f), Size(6f, 10f), CornerRadius(1f))
            translate(12.375f, 7.5f) {
                scale(1.75f, 1.75f, pivot = Offset.Zero) {
                    val lift = (float?.value ?: 0f) * 1.6f
                    val shadow = 1f - lift / 9f
                    drawOval(Color.Black.copy(alpha = 0.35f * shadow), Offset(12f - 5f * shadow, 23.6f), Size(10f * shadow, 1.6f))
                    translate(top = 0.4f - lift) {
                        drawGhost(LogoAccent, hem?.value ?: 0f, eyes?.value ?: 1f, wave?.value ?: 0f)
                    }
                }
            }
        }
    }
}
