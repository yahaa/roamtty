package io.itermux.app.ui

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.itermux.app.tr
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.desktop.DesktopSettings
import io.itermux.desktop.PipLayout
import io.itermux.desktop.PipRect
import kotlin.math.roundToInt

/**
 * Where the small window sits ([x], [y]: fractions of the room it moves in, 0, 0 the top left) and how wide it is
 * ([fraction] of the screen), remembered under [key] once moved or resized.
 */
@Stable
internal class PipState(private val key: String, private val settings: DesktopSettings, defaultFraction: Float) {
    private val saved = settings.pipPosition(key)
    var x by mutableFloatStateOf(saved?.first ?: 1f)
    var y by mutableFloatStateOf(saved?.second ?: 0f)
    var fraction by mutableFloatStateOf(settings.pipFraction(key) ?: defaultFraction)
    fun save() = settings.setPip(key, x to y, fraction)
}

@Composable
internal fun rememberPipState(key: String, defaultFraction: Float): PipState {
    val context = LocalContext.current
    return remember(key) { PipState(key, DesktopSettings(context), defaultFraction) }
}

private fun Rect.pip() = PipRect(left, top, right, bottom)

/**
 * The small window: a host's desktop over its terminal.
 *
 * Not [focused], the window is a thing to place: a drag puts it anywhere in [room] (dropped on the close target that
 * appears at the bottom, it is [onClose]), a pinch or the handle in its corner resizes it, and a tap is [onTap]
 * (which focuses it). [focused], every touch on the picture is the desktop's, exactly as on the desktop page; the
 * window is then moved by the bar above it, which also holds the keyboard ([onKeyboard], lit while [keyboardOpen])
 * and full screen ([onFull]), and closes it ([onClose]); it also shows the connection's [status] colour and a [detail]
 * (the latency). [onBounds] gets the window and its bar in this box's pixels.
 *
 * [room] is in this box's pixels; [screenWidth] is what [PipState.fraction] is a fraction of, [pictureAspect] the
 * picture's width / height.
 */
@Composable
internal fun BoxScope.PipWindow(state: PipState, room: Rect, screenWidth: Float, pictureAspect: Float, tag: String, description: String,
    onTap: () -> Unit, onClose: () -> Unit, focused: Boolean = false, label: String = "", keyboardOpen: Boolean = false,
    onKeyboard: (() -> Unit)? = null, onFull: (() -> Unit)? = null, onBounds: (Rect) -> Unit = {},
    status: Color? = null, detail: String = "",
    content: @Composable BoxScope.() -> Unit) {
    // Gestures read these as they run (a turn of the phone or a new display changes them).
    val reference by rememberUpdatedState(screenWidth)
    val aspect by rememberUpdatedState(pictureAspect)
    if (room.width <= 0f || room.height <= 0f) return
    val density = LocalDensity.current
    val view = LocalView.current
    val r = room.pip()
    val live = remember { PipGestureState() }
    val (w, h) = PipLayout.size(r, reference, live.resizing?.first?.div(reference) ?: state.fraction, aspect)
    val placed = PipLayout.place(r, w, h, state.x, state.y)
    val topLeft = live.dragPos ?: live.resizing?.let { (_, fixed) ->
        Offset(if (live.handleSide == true) fixed.x - w else fixed.x, fixed.y)
    } ?: Offset(placed.left, placed.top)
    val window = PipRect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h)
    val onLeft = live.handleSide ?: PipLayout.handleOnLeft(r, window)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
    val closeRadius = with(density) { 24.dp.toPx() }
    val target = Offset(room.center.x, room.bottom - with(density) { 40.dp.toPx() })
    val currentTap by rememberUpdatedState(onTap)
    val currentClose by rememberUpdatedState(onClose)
    // Read when a gesture starts: keying the gestures on them would restart one as it resizes the window.
    val now by rememberUpdatedState(PipGeometry(w, h, placed, r, topLeft, onLeft, target))
    val gestures = remember(state) {
        PipGestures(state, live, view, ViewConfiguration.get(view.context).scaledTouchSlop.toFloat(), closeRadius,
            reference = { reference }, aspect = { aspect }, now = { now }, onTap = { currentTap() }, onClose = { currentClose() })
    }
    val shape = RoundedCornerShape(14.dp)
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier
        .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
        .graphicsLayer {
            val lift = if (live.dragPos != null) 1.04f else 1f
            val shrink = if (live.hot) 0.6f else 1f
            scaleX = lift * shrink * (0.6f + 0.4f * appear.value); scaleY = scaleX
            alpha = appear.value
        }
        .shadow(4.dp, shape).clip(shape)
        .background(Color.Black)
        .testTag(tag)
        .semantics { contentDescription = description; onClick { currentTap(); true } }) {
        content()
        // Focused, the picture takes every touch; otherwise this layer takes them to place the window.
        if (!focused) Box(Modifier.matchParentSize().pointerInput(gestures) { with(gestures) { place(true) } }.testTag("$tag-place"))
        Box(Modifier.matchParentSize().border(if (focused) 2.dp else 0.5.dp,
            if (focused) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape))
    }
    // Focused: the bar above the window (below it when there is no room above, over its top edge when there is room for
    // neither), translucent and low, moves it and holds its buttons.
    val barPx = with(density) { PipBarHeight.toPx() }
    val barTop = PipLayout.barTop(r, window, barPx, with(density) { 4.dp.toPx() })
    if (focused) PipBar(tag, Offset(topLeft.x, barTop), w, gestures, PipBarContent(label, status, detail, keyboardOpen, onKeyboard, onFull) { currentClose() })
    if (focused) SideEffect { onBounds(Rect(topLeft.x, minOf(topLeft.y, barTop), topLeft.x + w, maxOf(topLeft.y + h, barTop + barPx))) }
    else SideEffect { onBounds(Rect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h)) }
    if (live.dragPos == null) PipResizeHandle(tag, window, onLeft, gestures)
    // The dock's close button, alone, while a drag can end on it.
    else PipCloseTarget(target, closeRadius, live.hot)
}

private val PipBarHeight = 32.dp

/** What the focused window's bar says and does. */
private class PipBarContent(val label: String, val status: Color?, val detail: String, val keyboardOpen: Boolean,
    val onKeyboard: (() -> Unit)?, val onFull: (() -> Unit)?, val onClose: () -> Unit)

/** The focused window's bar, [width] pixels wide at [topLeft]: a handle to move it by, its name and state, its buttons. */
@Composable
private fun PipBar(tag: String, topLeft: Offset, width: Float, gestures: PipGestures, bar: PipBarContent) {
    val density = LocalDensity.current
    Row(Modifier
        .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
        .size(with(density) { width.toDp() }, PipBarHeight)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
        .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
        .testTag("$tag-bar"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().pointerInput(gestures) { with(gestures) { place(false) } }.testTag("$tag-move")
            .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Six dots: a handle to drag.
            Canvas(Modifier.size(8.dp, 13.dp)) {
                val c = Color.White.copy(alpha = 0.5f)
                for (col in 0..1) for (row in 0..2) drawCircle(c, 1.2.dp.toPx(), Offset(size.width * (0.25f + 0.5f * col), size.height * (0.2f + 0.3f * row)))
            }
            bar.status?.let { Box(Modifier.padding(start = 8.dp).size(6.dp).clip(CircleShape).background(it)) }
            // Narrow windows drop the latency first, then the name; the buttons always stay.
            if (width >= with(density) { 160.dp.toPx() }) Text(bar.label, Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bar.detail.isNotEmpty() && width >= with(density) { 220.dp.toPx() }) Text(bar.detail, Modifier.padding(start = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        bar.onKeyboard?.let { BarButton(R.drawable.ic_keyboard, tr("键盘"), "$tag-keyboard", lit = bar.keyboardOpen, onClick = it) }
        bar.onFull?.let { BarButton(R.drawable.ic_fullscreen, tr("打开全屏桌面"), "$tag-full", onClick = it) }
        BarButton(R.drawable.ic_close, tr("关闭桌面小窗"), "$tag-close", onClick = bar.onClose)
    }
}

/** The resize handle, in the bottom corner of [window] with room to grow. */
@Composable
private fun PipResizeHandle(tag: String, window: PipRect, onLeft: Boolean, gestures: PipGestures) {
    val handle = 32.dp
    val handlePx = with(LocalDensity.current) { handle.toPx() }
    Box(Modifier
        .offset { IntOffset((if (onLeft) window.left else window.right - handlePx).roundToInt(), (window.bottom - handlePx).roundToInt()) }
        .size(handle)
        .testTag("$tag-resize")
        .pointerInput(gestures) { with(gestures) { resize() } }, contentAlignment = if (onLeft) Alignment.BottomStart else Alignment.BottomEnd) {
        // Two short diagonal strokes: a window's corner grip.
        Canvas(Modifier.padding(5.dp).size(14.dp)) {
            val stroke = 1.8.dp.toPx()
            val s = size.width
            fun line(a: Offset, b: Offset) {
                drawLine(Color.Black.copy(alpha = 0.45f), a, b, stroke + 2.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.85f), a, b, stroke, StrokeCap.Round)
            }
            if (onLeft) { line(Offset(0f, s * 0.35f), Offset(s * 0.65f, s)); line(Offset(0f, s * 0.75f), Offset(s * 0.25f, s)) }
            else { line(Offset(s, s * 0.35f), Offset(s * 0.35f, s)); line(Offset(s, s * 0.75f), Offset(s * 0.75f, s)) }
        }
    }
}

/** Where a drag closes the window, lit while the window is over it ([hot]). */
@Composable
private fun PipCloseTarget(target: Offset, radius: Float, hot: Boolean) {
    Box(Modifier.offset { IntOffset((target.x - radius).roundToInt(), (target.y - radius).roundToInt()) }
        .size(48.dp).shadow(4.dp, CircleShape).clip(CircleShape)
        .background(if (hot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .then(if (hot) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
        .testTag("pip-close-target"), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_close), null, Modifier.size(20.dp),
            tint = if (hot) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

/** A button on the focused window's bar, in the primary colour while [lit]. */
@Composable
private fun BarButton(icon: Int, label: String, tag: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(36.dp, 32.dp).clickable(onClickLabel = label, onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(18.dp),
            tint = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
