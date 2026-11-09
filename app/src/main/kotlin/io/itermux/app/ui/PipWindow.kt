package io.itermux.app.ui

import android.content.Context
import android.graphics.Canvas
import androidx.core.graphics.withClip
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.termux.view.TerminalRenderer
import io.itermux.app.R
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.session.TerminalConnection
import io.itermux.desktop.PipAnchor
import io.itermux.desktop.PipLayout
import io.itermux.desktop.PipRect
import kotlin.math.roundToInt

/**
 * Where a small window rests ([anchor]) and how wide it is ([fraction] of the screen's short side), remembered per
 * host and page under [key] once moved or pinched.
 */
@Stable
internal class PipState(private val key: String, private val settings: DesktopSettings, defaultAnchor: PipAnchor, defaultFraction: Float) {
    var anchor by mutableStateOf(PipAnchor.parse(settings.pipAnchor(key)) ?: defaultAnchor)
    var fraction by mutableFloatStateOf(settings.pipFraction(key) ?: defaultFraction)
    fun save() = settings.setPip(key, anchor.name, fraction)
}

@Composable
internal fun rememberPipState(key: String, defaultAnchor: PipAnchor, defaultFraction: Float): PipState {
    val context = LocalContext.current
    return remember(key) { PipState(key, DesktopSettings(context), defaultAnchor, defaultFraction) }
}

private fun Rect.pip() = PipRect(left, top, right, bottom)

/**
 * The small window: the other half of the host (its desktop over the terminal, its terminal over the desktop).
 * It only shows: a tap is [onTap] (the two pages change places), a drag moves it to the nearest corner of [room]
 * (or onto the close target at the bottom, which is [onClose]), a pinch makes it bigger or smaller. It keeps clear
 * of [avoid] (the cursor row, the dock), moving to another corner while one is covered.
 *
 * [room] and [avoid] are in this box's pixels, and so is [stage], the whole area a drag happens in (the close target
 * sits at its bottom; [room] may be a part of it). [reference] is what [PipState.fraction] is a fraction of. A [stretch]
 * window (the terminal) fills a low room at full width instead of shrinking to keep its aspect.
 */
@Composable
internal fun BoxScope.PipWindow(state: PipState, room: Rect, reference: Float, aspect: Float, avoid: List<Rect>,
    tag: String, description: String, onTap: () -> Unit, onClose: () -> Unit, stretch: Boolean = false, dimmed: Boolean = false,
    stage: Rect = room, content: @Composable BoxScope.() -> Unit) {
    if (room.width <= 0f || room.height <= 0f) return
    val density = LocalDensity.current
    val view = LocalView.current
    val r = room.pip()
    val (w, h) = PipLayout.size(r, reference, state.fraction, aspect, stretch)
    val rest = PipLayout.place(r, w, h, PipLayout.clear(r, w, h, state.anchor, avoid.map { it.pip() }))
    var dragging by remember { mutableStateOf(false) }
    /** Where the finger has the window while dragging; the gesture cannot drive an animation itself. */
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var hot by remember { mutableStateOf(false) }
    val position = remember { Animatable(Offset(rest.left, rest.top), Offset.VectorConverter) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
    LaunchedEffect(rest.left, rest.top, dragging) {
        if (dragging) return@LaunchedEffect
        dragPos?.let { position.snapTo(it); dragPos = null }
        position.animateTo(Offset(rest.left, rest.top), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
    }
    val closeRadius = with(density) { 24.dp.toPx() }
    val target = Offset(stage.center.x, stage.bottom - with(density) { 40.dp.toPx() })
    val currentTap by rememberUpdatedState(onTap)
    val currentClose by rememberUpdatedState(onClose)
    val slop = remember { ViewConfiguration.get(view.context).scaledTouchSlop.toFloat() }
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier
        .offset { (dragPos ?: position.value).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
        .graphicsLayer {
            val lift = if (dragging) 1.04f else 1f
            val shrink = if (hot) 0.6f else 1f
            scaleX = lift * shrink * (0.6f + 0.4f * appear.value); scaleY = scaleX
            alpha = appear.value * (if (dimmed) 0.45f else 1f)
        }
        .shadow(4.dp, shape).clip(shape)
        .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
        .background(MaterialTheme.colorScheme.background)
        .testTag(tag)
        .semantics { contentDescription = description; onClick { currentTap(); true } }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var moved = false
                var pinching = false
                var travel = Offset.Zero
                var startFraction = state.fraction
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    if (fingers >= 2) {
                        if (!pinching) { pinching = true; startFraction = state.fraction }
                        state.fraction = PipLayout.pinched(state.fraction, event.calculateZoom())
                    } else if (!pinching && fingers == 1) {
                        val pan = event.calculatePan()
                        travel += pan
                        if (!moved && travel.getDistance() > slop) { moved = true; dragging = true }
                        if (moved) {
                            val p = (dragPos ?: position.value) + pan
                            dragPos = p
                            val over = PipLayout.overClose(p.x + w / 2, p.y + h / 2, target.x, target.y, closeRadius * 2.4f)
                            if (over && !hot) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            hot = over
                        }
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
                when {
                    pinching -> if (state.fraction != startFraction) state.save()
                    moved -> {
                        val c = (dragPos ?: position.value) + Offset(w / 2, h / 2)
                        if (hot) currentClose() else {
                            state.anchor = PipLayout.nearest(stage.pip(), c.x, c.y)
                            state.save()
                        }
                        hot = false
                        dragging = false
                    }
                    else -> currentTap()
                }
            }
        }, content = content)
    // The dock's close button, alone, while a drag can end on it.
    if (dragging) Box(Modifier.offset { IntOffset((target.x - closeRadius).roundToInt(), (target.y - closeRadius).roundToInt()) }
        .size(48.dp).shadow(4.dp, CircleShape).clip(CircleShape)
        .background(if (hot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .then(if (hot) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
        .testTag("pip-close-target"), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_close), null, Modifier.size(20.dp),
            tint = if (hot) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A session's screen, read-only and small: the rows up to the cursor at a small font, at the emulator's own columns
 * and rows. It never resizes the session (as a [com.termux.view.TerminalView] would to fit itself), so the remote
 * program does not reflow when the small window comes or goes; columns past the window's width are cut off.
 */
internal class TerminalMiniView(context: Context) : View(context) {
    var session: TerminalConnection? = null
        set(value) { field = value; invalidate() }
    private var renderer: TerminalRenderer? = null
    private var rendererKey: Pair<Int, Typeface>? = null
    /** Where the renderer starts its first row: row r spans [rowOffset + r × line spacing, one line spacing). */
    private var rowOffset = 0
    private val padding = (6 * resources.displayMetrics.density).roundToInt()

    fun setFont(sizePx: Int, typeface: Typeface) {
        if (rendererKey == sizePx to typeface) return
        rendererKey = sizePx to typeface
        renderer = TerminalRenderer(sizePx, typeface)
        val paint = android.graphics.Paint().apply { this.typeface = typeface; textSize = sizePx.toFloat() }
        rowOffset = kotlin.math.ceil(paint.fontSpacing).toInt() + kotlin.math.ceil(paint.ascent()).toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = session ?: return
        val r = renderer ?: return
        val emulator = s.getEmulator()
        val line = r.fontLineSpacing.coerceAtLeast(1)
        // Whole rows up to the cursor's, as a terminal of this height would show them; fewer rows start at the top.
        val shown = ((height - padding) / line).coerceAtLeast(1)
        val first = (emulator.cursorRow + 1 - shown).coerceAtLeast(0)
        canvas.withClip(0, padding / 2, width, height) {
            translate(padding.toFloat(), padding / 2f - rowOffset - first * line.toFloat())
            r.render(emulator, this, 0, -1, -1, -1, -1)
        }
    }
}
