package io.itermux.app.ui

import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.itermux.app.tr
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
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
    /** Set while the handle is dragged: the width so far, and the top corner that stays where it is. */
    var resizing by remember { mutableStateOf<Pair<Float, Offset>?>(null) }
    val (w, h) = PipLayout.size(r, reference, resizing?.first?.div(reference) ?: state.fraction, aspect)
    val placed = PipLayout.place(r, w, h, state.x, state.y)
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var hot by remember { mutableStateOf(false) }
    // The handle keeps its side once used, so it is where the finger left it; a move picks the side with room again.
    var handleSide by remember { mutableStateOf<Boolean?>(null) }
    // Resizing from the left grip keeps the right corner, from the right grip the left one: the side the drag
    // started on, not one guessed from the placement (which moves as the width grows).
    val topLeft = dragPos ?: resizing?.let { (_, fixed) ->
        Offset(if (handleSide == true) fixed.x - w else fixed.x, fixed.y)
    } ?: Offset(placed.left, placed.top)
    val onLeft = handleSide ?: PipLayout.handleOnLeft(r, PipRect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h))
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
    val closeRadius = with(density) { 24.dp.toPx() }
    val target = Offset(room.center.x, room.bottom - with(density) { 40.dp.toPx() })
    val currentTap by rememberUpdatedState(onTap)
    val currentClose by rememberUpdatedState(onClose)
    val slop = remember { ViewConfiguration.get(view.context).scaledTouchSlop.toFloat() }
    // Read when a gesture starts: keying the gestures on them would restart one as it resizes the window.
    val now by rememberUpdatedState(Geometry(w, h, placed, r, topLeft, onLeft, target))
    val shape = RoundedCornerShape(14.dp)
    /** Remembers where the window is now, at the size it is now. */
    fun keep(left: Float, top: Float, width: Float, height: Float, r: PipRect) {
        val (fx, fy) = PipLayout.fractions(r, width, height, left, top)
        state.x = fx; state.y = fy
        state.fraction = (width / reference).coerceIn(PipLayout.MIN_FRACTION, PipLayout.MAX_FRACTION)
        state.save()
    }
    /** A drag that moves the window (or closes it on ✕); with [pinch], two fingers resize it, and a still tap is a tap. */
    val placeGesture: suspend PointerInputScope.(Boolean) -> Unit = { pinch ->
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val (w, h, placed, r, _, _, target) = now
            var moved = false
            var pinching = false
            var travel = Offset.Zero
            var pos = Offset(placed.left, placed.top)
            var width = w
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (pinch && fingers >= 2) {
                    pinching = true
                    dragPos = null; hot = false
                    width = (width * event.calculateZoom()).coerceIn(reference * PipLayout.MIN_FRACTION, reference * PipLayout.MAX_FRACTION)
                    state.fraction = width / reference
                } else if (!pinching && fingers == 1) {
                    val pan = event.calculatePan()
                    travel += pan
                    if (!moved && travel.getDistance() > slop) moved = true
                    if (moved) {
                        pos += pan
                        dragPos = pos
                        val over = PipLayout.overClose(pos.x + w / 2, pos.y + h / 2, target.x, target.y, closeRadius * 2.4f)
                        if (over && !hot) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        hot = over
                    }
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } while (event.changes.any { it.pressed })
            when {
                pinching -> {
                    val (pw, ph) = PipLayout.size(r, reference, state.fraction, aspect)
                    // Grows and shrinks about its centre, kept inside.
                    keep(placed.centerX - pw / 2, placed.centerY - ph / 2, pw, ph, r)
                }
                moved -> {
                    if (hot) currentClose() else { keep(pos.x, pos.y, w, h, r); handleSide = null }
                    hot = false
                    dragPos = null
                }
                pinch -> currentTap()
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier
        .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
        .graphicsLayer {
            val lift = if (dragPos != null) 1.04f else 1f
            val shrink = if (hot) 0.6f else 1f
            scaleX = lift * shrink * (0.6f + 0.4f * appear.value); scaleY = scaleX
            alpha = appear.value
        }
        .shadow(4.dp, shape).clip(shape)
        .background(Color.Black)
        .testTag(tag)
        .semantics { contentDescription = description; onClick { currentTap(); true } }) {
        content()
        // Focused, the picture takes every touch; otherwise this layer takes them to place the window.
        if (!focused) Box(Modifier.matchParentSize().pointerInput(Unit) { placeGesture(true) }.testTag("$tag-place"))
        Box(Modifier.matchParentSize().border(if (focused) 2.dp else 0.5.dp,
            if (focused) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape))
    }
    // Focused: the bar above the window (below it when there is no room above, over its top edge when there is room for
    // neither), translucent and low, moves it and holds its buttons.
    val barHeight = 32.dp
    val barPx = with(density) { barHeight.toPx() }
    val gap = with(density) { 4.dp.toPx() }
    val barTop = PipLayout.barTop(r, PipRect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h), barPx, gap)
    if (focused) Row(Modifier
        .offset { IntOffset(topLeft.x.roundToInt(), barTop.roundToInt()) }
        .size(with(density) { w.toDp() }, barHeight)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
        .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
        .testTag("$tag-bar"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) { placeGesture(false) }.testTag("$tag-move")
            .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Six dots: a handle to drag.
            Canvas(Modifier.size(8.dp, 13.dp)) {
                val c = Color.White.copy(alpha = 0.5f)
                for (col in 0..1) for (row in 0..2) drawCircle(c, 1.2.dp.toPx(), Offset(size.width * (0.25f + 0.5f * col), size.height * (0.2f + 0.3f * row)))
            }
            status?.let { Box(Modifier.padding(start = 8.dp).size(6.dp).clip(CircleShape).background(it)) }
            // Narrow windows drop the latency first, then the name; the buttons always stay.
            if (w >= with(density) { 160.dp.toPx() }) Text(label, Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty() && w >= with(density) { 220.dp.toPx() }) Text(detail, Modifier.padding(start = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        onKeyboard?.let { BarButton(R.drawable.ic_keyboard, tr("键盘"), "$tag-keyboard", lit = keyboardOpen, onClick = it) }
        onFull?.let { BarButton(R.drawable.ic_fullscreen, tr("打开全屏桌面"), "$tag-full", onClick = it) }
        BarButton(R.drawable.ic_close, tr("关闭桌面小窗"), "$tag-close") { currentClose() }
    }
    if (focused) SideEffect { onBounds(Rect(topLeft.x, minOf(topLeft.y, barTop), topLeft.x + w, maxOf(topLeft.y + h, barTop + barPx))) }
    else SideEffect { onBounds(Rect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h)) }
    // The resize handle, in the bottom corner with room to grow.
    val handle = 32.dp
    val handlePx = with(density) { handle.toPx() }
    if (dragPos == null) Box(Modifier
        .offset { IntOffset((if (onLeft) topLeft.x else topLeft.x + w - handlePx).roundToInt(), (topLeft.y + h - handlePx).roundToInt()) }
        .size(handle)
        .testTag("$tag-resize")
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                val (w, _, _, r, topLeft, onLeft) = now
                val fixed = if (onLeft) Offset(topLeft.x + w, topLeft.y) else topLeft
                handleSide = onLeft
                var width = w
                resizing = width to fixed
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.pressed } ?: break
                    val d = change.positionChange()
                    width = PipLayout.resized(width, d.x, d.y, onLeft, aspect)
                        .coerceIn(reference * PipLayout.MIN_FRACTION, reference * PipLayout.MAX_FRACTION)
                    resizing = width to fixed
                    change.consume()
                } while (event.changes.any { it.pressed })
                val (fw, fh) = PipLayout.size(r, reference, width / reference, aspect)
                keep(if (onLeft) fixed.x - fw else fixed.x, fixed.y, fw, fh, r)
                resizing = null
            }
        }, contentAlignment = if (onLeft) Alignment.BottomStart else Alignment.BottomEnd) {
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
    // The dock's close button, alone, while a drag can end on it.
    if (dragPos != null) Box(Modifier.offset { IntOffset((target.x - closeRadius).roundToInt(), (target.y - closeRadius).roundToInt()) }
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

/** The window's geometry as last composed, for gestures to read when they start. */
private data class Geometry(val w: Float, val h: Float, val placed: PipRect, val room: PipRect, val topLeft: Offset,
    val handleOnLeft: Boolean, val closeTarget: Offset)
