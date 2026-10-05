package io.itermux.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The collapsed toolbox: a small cat holding a wrench, drawn on a key cap that matches the
 * cursor-row Enter key. It hops in, breathes, blinks and now and then waves the wrench.
 * Infinite animations are disabled under Compose tests, so the button stays idle there.
 */
@Composable
internal fun ToolboxPet(description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 900f), label = "press")
    val hop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        hop.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
        hop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 500f))
    }
    val idle = rememberInfiniteTransition(label = "pet")
    val breath by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val eyes by idle.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 3600
        1f at 3250
        0.1f at 3330
        1f at 3420
    }), label = "blink")
    val wave by idle.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 5200
        0f at 3000
        -28f at 3180
        12f at 3380
        -28f at 3580
        12f at 3780
        0f at 3980
    }), label = "wave")
    Box(Modifier.size(48.dp)
        .clickable(interaction, indication = null, onClick = onClick)
        .semantics { contentDescription = description }.testTag("restore-dock"),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.size(40.dp, 36.dp).graphicsLayer { scaleX = press; scaleY = press },
            shape = RoundedCornerShape(8.dp),
            color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
            val ink = MaterialTheme.colorScheme.primary
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(34.dp, 30.dp).graphicsLayer {
                    translationY = -(hop.value * 5f + breath * 0.8f).dp.toPx()
                }) {
                    // A 26 x 23.4 grid keeps the drawing readable; everything below is in grid units.
                    val unit = size.width / 26f
                    translate(left = 1f * unit, top = 0.6f * unit) { scale(unit, unit, pivot = Offset.Zero) {
                        drawPet(ink, squash = 1f - breath * 0.03f, eyes = eyes, wave = wave)
                    } }
                }
            }
        }
    }
}

private fun DrawScope.drawPet(ink: Color, squash: Float, eyes: Float, wave: Float) {
    val line = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Wrench behind the raised paw, swinging around the grip.
    rotate(wave, pivot = Offset(17.5f, 14.5f)) {
        drawLine(ink, Offset(17.5f, 14.5f), Offset(20.3f, 8f), strokeWidth = 1.7f, cap = StrokeCap.Round)
        // Open-ended head, its jaw facing away from the grip.
        drawArc(ink, startAngle = -22f, sweepAngle = 270f, useCenter = false,
            topLeft = Offset(19f, 3.4f), size = Size(4.4f, 4.4f), style = Stroke(width = 1.7f, cap = StrokeCap.Round))
    }
    scale(1f, squash, pivot = Offset(11f, 21f)) {
        val body = Path().apply {
            // Ears flow into a round body.
            moveTo(4.6f, 11f)
            lineTo(4.4f, 4.2f)
            lineTo(8.6f, 7.6f)
            quadraticTo(11f, 6.9f, 13.4f, 7.6f)
            lineTo(17.6f, 4.2f)
            lineTo(17.4f, 11f)
            cubicTo(19.4f, 14.4f, 18.6f, 20.6f, 11f, 20.6f)
            cubicTo(3.4f, 20.6f, 2.6f, 14.4f, 4.6f, 11f)
            close()
        }
        drawPath(body, ink.copy(alpha = 0.16f))
        drawPath(body, ink, style = line)
        // Eyes close from the top and bottom at once.
        listOf(8.2f, 13.8f).forEach { x ->
            translate(x, 13.2f) { scale(1f, eyes, pivot = Offset.Zero) {
                drawRoundRect(ink, Offset(-1.1f, -1.4f), Size(2.2f, 2.8f), CornerRadius(1.1f))
            } }
        }
        // Small "w" mouth.
        val mouth = Path().apply {
            moveTo(9.6f, 16f)
            quadraticTo(10.3f, 17f, 11f, 16.1f)
            quadraticTo(11.7f, 17f, 12.4f, 16f)
        }
        drawPath(mouth, ink, style = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Paw gripping the wrench.
        drawCircle(ink, radius = 1.6f, center = Offset(17.5f, 14.5f))
    }
}
