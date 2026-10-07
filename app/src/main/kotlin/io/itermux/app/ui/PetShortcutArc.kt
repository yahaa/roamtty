package io.itermux.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.KeyEvent
import io.itermux.app.session.ScreenApp
import io.itermux.app.tr
import io.itermux.app.trf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** What an item on the ring does when the finger lets go of it. */
internal sealed interface PetAction {
    /** Written to the terminal as is; [repeat] sends it again after a short pause, so TUIs that time double presses (Esc Esc) see two keys. */
    data class Keys(val keys: String, val repeat: Boolean = false) : PetAction
    /** An Android key code the terminal encodes itself (function keys). */
    data class Key(val keyCode: Int) : PetAction
    /** Arms Ctrl for the next key typed. */
    data object Ctrl : PetAction
    /** Arms Shift for the next key typed. */
    data object Shift : PetAction
    /** Resting on the item swaps the ring for [items]. */
    data class Menu(val items: List<PetShortcut>) : PetAction
}

internal data class PetShortcut(val label: String, val hint: String, val description: String, val tag: String, val action: PetAction)

private fun keys(label: String, hint: String, description: String, tag: String, keys: String, repeat: Boolean = false) =
    PetShortcut(label, tr(hint), tr(description), tag, PetAction.Keys(keys, repeat))

/**
 * The ring for the program in front, top to bottom, most used in the middle. Plain Esc stays on the cursor row. Key tables come from each
 * CLI's built-in defaults: Claude Code 2.1, Codex 0.160, Grok Build 1.0, Gemini CLI. A plain shell gets the keys a
 * phone keyboard lacks.
 */
internal fun petShortcuts(app: ScreenApp): List<PetShortcut> = when (app) {
    ScreenApp.CLAUDE -> listOf(
        keys("^T", "任务", "Ctrl+T：显示或隐藏任务列表", "pet-ctrl-t", "\u0014"),
        keys("^O", "详情", "Ctrl+O：展开完整对话记录", "pet-ctrl-o", "\u000f"),
        keys("Esc²", "回退", "Esc Esc：回退到之前的消息", "pet-escape-twice", "\u001b", repeat = true),
        keys("⇧Tab", "模式", "Shift+Tab：切换模式（默认 / 接受编辑 / 计划）", "pet-shift-tab", "\u001b[Z"),
        keys("^C", "取消", "Ctrl+C：清空输入或取消", "pet-ctrl-c", "\u0003"),
        keys("^B", "后台", "Ctrl+B：把运行中的任务转到后台", "pet-ctrl-b", "\u0002"),
        keys("⌥P", "模型", "Alt+P：切换模型", "pet-alt-p", "\u001bp"),
    )
    ScreenApp.CODEX -> listOf(
        keys("^T", "记录", "Ctrl+T：打开对话记录", "pet-ctrl-t", "\u0014"),
        keys("^O", "复制", "Ctrl+O：复制最后一条回复", "pet-ctrl-o", "\u000f"),
        keys("Esc²", "编辑", "Esc Esc：编辑上一条消息", "pet-escape-twice", "\u001b", repeat = true),
        keys("⇧Tab", "计划", "Shift+Tab：切换 Plan 模式", "pet-shift-tab", "\u001b[Z"),
        keys("⇧←", "回答", "Shift+←：回答提问", "pet-shift-left", "\u001b[1;2D"),
        keys("⇧→", "返回", "Shift+→：回到输入框", "pet-shift-right", "\u001b[1;2C"),
        keys("^C", "取消", "Ctrl+C：清空输入或退出", "pet-ctrl-c", "\u0003"),
    )
    ScreenApp.GROK -> listOf(
        keys("^T", "任务", "Ctrl+T：显示或隐藏任务面板", "pet-ctrl-t", "\u0014"),
        keys("^O", "自动", "Ctrl+O：开关自动批准", "pet-ctrl-o", "\u000f"),
        keys("^P", "命令", "Ctrl+P：打开命令面板", "pet-ctrl-p", "\u0010"),
        keys("⇧Tab", "模式", "Shift+Tab：切换模式（普通 / 计划 / 自动批准）", "pet-shift-tab", "\u001b[Z"),
        keys("^C", "中断", "Ctrl+C：中断当前回复", "pet-ctrl-c", "\u0003"),
        keys("^X", "快捷", "Ctrl+X：查看全部快捷键", "pet-ctrl-x", "\u0018"),
    )
    ScreenApp.GEMINI -> listOf(
        keys("^T", "任务", "Ctrl+T：查看完整任务列表", "pet-ctrl-t", "\u0014"),
        keys("^O", "展开", "Ctrl+O：显示更多内容", "pet-ctrl-o", "\u000f"),
        keys("^Y", "YOLO", "Ctrl+Y：开关 YOLO 模式", "pet-ctrl-y", "\u0019"),
        keys("⇧Tab", "模式", "Shift+Tab：切换批准模式", "pet-shift-tab", "\u001b[Z"),
        keys("Esc²", "回退", "Esc Esc：清空输入或回退", "pet-escape-twice", "\u001b", repeat = true),
        keys("^L", "清屏", "Ctrl+L：清屏", "pet-ctrl-l", "\u000c"),
        keys("^C", "取消", "Ctrl+C：取消或退出", "pet-ctrl-c", "\u0003"),
    )
    ScreenApp.SHELL -> listOf(
        PetShortcut("Fn", "F1–12", tr("功能键：停在这里展开 F1–F12"), "pet-fn", PetAction.Menu(functionKeys())),
        PetShortcut("Ctrl", tr("下一键"), tr("Ctrl，作用于下一次键盘字符"), "pet-ctrl", PetAction.Ctrl),
        PetShortcut("Shift", tr("下一键"), tr("Shift，作用于下一次按键"), "pet-shift", PetAction.Shift),
        keys("Tab", "补全", "Tab：补全命令或路径", "pet-tab", "\t"),
        keys("^C", "中断", "Ctrl+C：中断当前命令", "pet-ctrl-c", "\u0003"),
        keys("^R", "历史", "Ctrl+R：搜索历史命令", "pet-ctrl-r", "\u0012"),
        keys("^D", "退出", "Ctrl+D：结束输入或退出", "pet-ctrl-d", "\u0004"),
        keys("^Z", "挂起", "Ctrl+Z：挂起前台程序", "pet-ctrl-z", "\u001a"),
    )
}

private fun functionKeys() = List(12) { i ->
    PetShortcut("F${i + 1}", "", trf("发送 F%d", i + 1), "pet-f${i + 1}", PetAction.Key(KeyEvent.KEYCODE_F1 + i))
}

/** Item angles in degrees (0° points right, y grows downward), top item first, all on the pet's left. */
internal data class PetArc(val radius: Float, val angles: List<Float>)

private const val MaxTilt = 80.0

/**
 * Lays [count] items on a left-facing arc around a pet at [centerY], keeping every item centre within [top]..[bottom].
 * Starts at [maxRadius] and shrinks toward [minRadius] until the visible part of the arc fits [spacing] per gap: in a
 * short viewport a smaller circle keeps more of its half-turn on screen. Without such a radius it takes the longest arc.
 */
internal fun petArcLayout(count: Int, centerY: Float, top: Float, bottom: Float, maxRadius: Float, minRadius: Float,
    spacing: Float): PetArc {
    fun reach(room: Float, radius: Float) = asin((room / radius).toDouble().coerceIn(0.0, sin(MaxTilt * PI / 180)))
    var best: Triple<Float, Double, Double>? = null
    var radius = maxRadius
    while (true) {
        val up = reach(centerY - top, radius)
        val down = reach(bottom - centerY, radius)
        val length = radius * (up + down)
        if (best == null || length > best.first * (best.second + best.third)) best = Triple(radius, up, down)
        if (length >= spacing * (count - 1) || radius <= minRadius) break
        radius = (radius - maxRadius / 32f).coerceAtLeast(minRadius)
    }
    val (r, up, down) = best!!
    val first = 180.0 + up * 180 / PI
    val last = 180.0 - down * 180 / PI
    val angles = if (count == 1) listOf(180f) else List(count) { i -> (first + (last - first) * i / (count - 1)).toFloat() }
    return PetArc(r, angles)
}

/** Where item [index] sits relative to the pet centre. */
internal fun PetArc.itemOffset(index: Int): Offset {
    val angle = angles[index] * PI / 180
    return Offset((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
}

/**
 * The item a finger at [drag] (relative to the pet centre) points at: the nearest by direction once it has left the
 * inner part of the circle, so a short flick toward an item is enough. Nothing while still over the pet or pointing
 * away from the arc.
 */
internal fun PetArc.hoveredItem(drag: Offset): Int? {
    if (angles.isEmpty() || hypot(drag.x, drag.y) < radius * 0.42f) return null
    val angle = (atan2(drag.y.toDouble(), drag.x.toDouble()) * 180 / PI).let { if (it < 0) it + 360 else it }
    val nearest = angles.indices.minBy { abs(angles[it] - angle) }
    val step = if (angles.size > 1) abs(angles[0] - angles[1]) else 40f
    return nearest.takeIf { abs(angles[it] - angle) <= maxOf(step * 0.6f, 15f) }
}

internal val PetArcItemSize = 40.dp
internal val PetArcMaxRadius = 100.dp
internal val PetArcMinRadius = 72.dp
internal val PetArcSpacing = 43.dp
private val PetArcCaptionWidth = 132.dp

/**
 * The shortcut ring drawn over the workspace while the pet is held; [center] is the pet centre in this box. A
 * caption says what the hovered item does. [armed] marks a sticky modifier that is already on. With [onTap] the ring stays up after the pet is let go: a tap on an item reports its
 * index, a tap anywhere else reports null and is kept from the terminal.
 */
@Composable
internal fun PetShortcutArc(shortcuts: List<PetShortcut>, center: Offset, topInset: Float, hovered: Int?,
    armed: (PetAction) -> Boolean = { false }, onTap: ((Int?) -> Unit)? = null, arcFor: (PetArc) -> Unit) {
    val density = LocalDensity.current
    val open = remember { Animatable(0f) }
    // A sub-ring (Fn) unfolds again from the pet.
    LaunchedEffect(shortcuts) {
        open.snapTo(0f)
        open.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f))
    }
    val tap by rememberUpdatedState(onTap)
    val outside = if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown().consume()
            tap?.invoke(null)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().then(outside).testTag("pet-shortcut-arc")) {
        val arc = with(density) {
            val half = PetArcItemSize.toPx() / 2 + 4.dp.toPx()
            // Long rings (F1–F12) get a wider circle so their items keep their spacing.
            val fullTurn = 2 * MaxTilt * PI / 180
            val maxRadius = maxOf(PetArcMaxRadius.toPx(), (PetArcSpacing.toPx() * (shortcuts.size - 1) / fullTurn).toFloat())
            petArcLayout(shortcuts.size, center.y, topInset + half, maxHeight.toPx() - half,
                maxRadius, PetArcMinRadius.toPx(), PetArcSpacing.toPx())
        }
        LaunchedEffect(arc) { arcFor(arc) }
        val colors = MaterialTheme.colorScheme
        Canvas(Modifier.fillMaxSize()) {
            val r = arc.radius * open.value
            if (r <= 0f || arc.angles.isEmpty()) return@Canvas
            drawArc(colors.primary.copy(alpha = 0.12f), arc.angles.last(), arc.angles.first() - arc.angles.last(), false,
                Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(width = 14.dp.toPx()))
        }
        // The caption sits just outside the ring, level with the pet, so it covers neither the keys nor the pet.
        val caption = hovered?.let(shortcuts::getOrNull)?.description.orEmpty()
        if (caption.isNotEmpty()) {
            val (left, width) = with(density) {
                val right = center.x - arc.radius - PetArcItemSize.toPx() / 2 - 8.dp.toPx()
                val width = minOf(PetArcCaptionWidth.toPx(), right - 8.dp.toPx()).coerceAtLeast(0f)
                (right - width) to width.toDp()
            }
            Box(Modifier.offset { IntOffset(left.roundToInt(), center.y.roundToInt()) }
                .graphicsLayer { alpha = open.value.coerceIn(0f, 1f); translationY = -size.height / 2 }
                .size(width = width, height = 84.dp), contentAlignment = Alignment.CenterEnd) {
                Surface(shape = MaterialTheme.shapes.small, color = colors.inverseSurface.copy(alpha = 0.75f),
                    contentColor = colors.inverseOnSurface, modifier = Modifier.testTag("pet-arc-caption")) {
                    Text(caption, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 5,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
        }
        val itemHalf = with(density) { PetArcItemSize.toPx() / 2 }
        shortcuts.forEachIndexed { index, shortcut ->
            Box(Modifier.offset {
                val at = center + arc.itemOffset(index) * open.value
                IntOffset((at.x - itemHalf).roundToInt(), (at.y - itemHalf).roundToInt())
            }) { PetShortcutItem(shortcut, hovered == index, armed(shortcut.action), open.value, onTap?.let { { it(index) } }) }
        }
    }
}

@Composable
private fun PetShortcutItem(shortcut: PetShortcut, hovered: Boolean, armed: Boolean, open: Float, onClick: (() -> Unit)?) {
    val grow by animateFloatAsState(if (hovered) 1.2f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "grow")
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.size(PetArcItemSize).graphicsLayer {
        val scale = grow * open.coerceIn(0f, 1.2f)
        scaleX = scale; scaleY = scale
    }.clip(CircleShape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = shortcut.description }.testTag(shortcut.tag),
        shape = CircleShape, shadowElevation = if (hovered) 4.dp else 0.dp,
        color = when {
            hovered -> colors.primary.copy(alpha = 0.9f)
            armed -> colors.primaryContainer.copy(alpha = 0.8f)
            else -> colors.surfaceVariant.copy(alpha = 0.7f)
        },
        contentColor = if (hovered) colors.onPrimary else colors.onSurfaceVariant,
        border = BorderStroke(1.dp, if (hovered) colors.primary else colors.outlineVariant.copy(alpha = 0.4f))) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(shortcut.label, fontSize = 11.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                color = if (hovered) colors.onPrimary else colors.primary, maxLines = 1)
            if (shortcut.hint.isNotEmpty()) Text(shortcut.hint, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
        }
    }
}
