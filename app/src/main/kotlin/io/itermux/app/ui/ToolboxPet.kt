package io.itermux.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The collapsed toolbox: a little ghost floating at the edge with a wrench, drawn without a
 * button frame. It pops in, bobs over its shadow, sways, ripples its hem, blinks and now and
 * then waves the wrench. Infinite animations are disabled under Compose tests, so it stays still there.
 */
@Composable
internal fun ToolboxPet(description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.4f, stiffness = 900f), label = "press")
    val hop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        hop.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        hop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 450f))
    }
    val idle = rememberInfiniteTransition(label = "pet")
    val float by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val sway by idle.animateFloat(-5f, 5f, infiniteRepeatable(tween(2300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val hem by idle.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "hem")
    val eyes by idle.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 3600
        1f at 3250
        0.1f at 3330
        1f at 3420
    }), label = "blink")
    val wave by idle.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 5200
        0f at 3000
        -30f at 3180
        14f at 3380
        -30f at 3580
        14f at 3780
        0f at 3980
    }), label = "wave")
    val wrench = MaterialTheme.colorScheme.primary
    Box(Modifier.size(48.dp)
        .clickable(interaction, indication = null, onClick = onClick)
        .semantics { contentDescription = description }.testTag("restore-dock"),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp)) {
            // A 26 x 26 grid keeps the drawing readable; everything below is in grid units.
            val unit = size.width / 26f
            scale(unit, unit, pivot = Offset.Zero) {
                val lift = float * 1.6f + hop.value * 4f
                // The shadow stays on the ground and shrinks as the ghost rises.
                val shadow = 1f - lift / 9f
                drawOval(Color.Black.copy(alpha = 0.22f * shadow), Offset(12f - 5f * shadow, 23.6f), Size(10f * shadow, 1.6f))
                translate(top = 0.4f - lift) {
                    scale(press, press, pivot = Offset(12f, 14f)) {
                        rotate(sway, pivot = Offset(12f, 21f)) { drawGhost(wrench, hem, eyes, wave) }
                    }
                }
            }
        }
    }
}

private val GhostBody = Color(0xFFF8F7FF)
private val GhostInk = Color(0xFF3B3550)
private val GhostBlush = Color(0xFFFF9EB5)
private val WrenchMetal = Color(0xFFD9DEE8)

private fun DrawScope.drawGhost(wrench: Color, hem: Float, eyes: Float, wave: Float) {
    val line = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Wrench behind the paw, swinging around the grip.
    rotate(wave, pivot = Offset(19.6f, 14.2f)) {
        drawLine(GhostInk, Offset(19.6f, 14.2f), Offset(22.4f, 7.6f), strokeWidth = 2.8f, cap = StrokeCap.Round)
        drawLine(wrench, Offset(19.6f, 14.2f), Offset(22.4f, 7.6f), strokeWidth = 1.4f, cap = StrokeCap.Round)
        // Open-ended head, its jaw facing away from the grip.
        val head = Rect(Offset(23.2f, 5.4f), 2.1f)
        drawArc(GhostInk, -22f, 270f, false, head.topLeft, head.size, style = Stroke(3f, cap = StrokeCap.Round))
        drawArc(WrenchMetal, -22f, 270f, false, head.topLeft, head.size, style = Stroke(1.5f, cap = StrokeCap.Round))
    }
    // Dome head, straight sides and a hem that ripples as it floats.
    val body = Path().apply {
        moveTo(4f, 20.4f)
        lineTo(4f, 11f)
        cubicTo(4f, 6.2f, 7.6f, 3f, 12f, 3f)
        cubicTo(16.4f, 3f, 20f, 6.2f, 20f, 11f)
        lineTo(20f, 20.4f)
        val steps = 24
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            lineTo(20f - 16f * t, 20.4f + 1.3f * sin(t * 3f * 2f * PI.toFloat() + hem))
        }
        close()
    }
    drawPath(body, GhostBody)
    drawPath(body, GhostInk, style = line)
    // Left arm waves a little with the wrench; the right paw grips it.
    drawCircle(GhostBody, 1.7f, Offset(3.9f, 14.6f - wave / 40f))
    drawCircle(GhostInk, 1.7f, Offset(3.9f, 14.6f - wave / 40f), style = line)
    drawCircle(GhostBody, 1.8f, Offset(19.6f, 14.2f))
    drawCircle(GhostInk, 1.8f, Offset(19.6f, 14.2f), style = line)
    // Eyes close from the top and bottom at once.
    listOf(9.2f, 14.8f).forEach { x ->
        scale(1f, eyes, pivot = Offset(x, 11.2f)) {
            drawOval(GhostInk, Offset(x - 1.1f, 9.6f), Size(2.2f, 3.2f))
            drawCircle(Color.White, 0.45f, Offset(x + 0.35f, 10.4f))
        }
    }
    drawOval(GhostBlush.copy(alpha = 0.6f), Offset(6.4f, 13f), Size(2.4f, 1.3f))
    drawOval(GhostBlush.copy(alpha = 0.6f), Offset(15.2f, 13f), Size(2.4f, 1.3f))
    drawOval(GhostInk, Offset(11.2f, 13.6f), Size(1.6f, 1.8f))
}
