package io.itermux.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.itermux.app.session.duration
import io.itermux.app.trf

/**
 * Claude Code's spinner, for a busy agent: a star that grows and shrinks through its frames beside [label] ("思考中…"
 * between commands) with a highlight sweeping across, both in [color].
 */
@Composable
internal fun WorkingSpinner(color: Color, label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "working")
    val frame by transition.animateValue(0, SPINNER_FRAMES.size, Int.VectorConverter,
        infiniteRepeatable(tween(SPINNER_FRAMES.size * 120, easing = LinearEasing)), label = "frame")
    val sweep by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "sweep")
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        // The frames differ in width: a fixed box keeps the words beside them still.
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            Text(SPINNER_FRAMES[frame.coerceIn(SPINNER_FRAMES.indices)], style = style, color = color, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        val dim = color.copy(alpha = 0.6f)
        Text(label, maxLines = 1, style = style.copy(brush = object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val x = sweep * size.width
                return LinearGradientShader(Offset(x - size.width / 3, 0f), Offset(x + size.width / 3, 0f),
                    listOf(dim, color, dim))
            }
        }))
    }
}

/** Claude Code's line after a turn: the resting star and how long the turn worked ("✻ 2m54s"), in the done colour. */
@Composable
internal fun TurnDone(seconds: Long, modifier: Modifier = Modifier) {
    val color = StatusColors.success
    Row(modifier.clearAndSetSemantics { contentDescription = trf("已完成 · 用时 %s", duration(seconds)) },
        verticalAlignment = Alignment.CenterVertically) {
        Text("✻", style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Text(shortDuration(seconds), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}

/**
 * The frames of [WorkingSpinner]'s star, out and back as Claude Code draws it; it rests on the full star, which is all
 * that shows with animations turned off.
 */
private val SPINNER_FRAMES = listOf("·", "✢", "✳", "✶", "✻", "✽").let { it + it.reversed().drop(1).dropLast(1) }
    .let { it.drop(4) + it.take(4) }
