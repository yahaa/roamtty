package io.itermux.desktop

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The remote screen inside the view: fitted with letterboxing, then zoomed and panned locally.
 * Zoom never reaches the host; coordinates always come out in the display's native pixels.
 */
data class Viewport(val viewWidth: Float, val viewHeight: Float, val remoteWidth: Int, val remoteHeight: Int,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    /** View pixels per native pixel at zoom 1. */
    val baseScale: Float get() = if (remoteWidth <= 0 || remoteHeight <= 0) 1f else min(viewWidth / remoteWidth, viewHeight / remoteHeight)
    val scale: Float get() = baseScale * zoom
    val contentWidth: Float get() = remoteWidth * scale
    val contentHeight: Float get() = remoteHeight * scale
    /** Top-left of the content in the view. */
    val left: Float get() = (viewWidth - contentWidth) / 2 + panX
    val top: Float get() = (viewHeight - contentHeight) / 2 + panY

    fun toRemote(x: Float, y: Float): Pair<Int, Int> =
        ((x - left) / scale).toInt().coerceIn(0, max(0, remoteWidth - 1)) to ((y - top) / scale).toInt().coerceIn(0, max(0, remoteHeight - 1))

    fun contains(x: Float, y: Float) = x >= left && y >= top && x < left + contentWidth && y < top + contentHeight

    fun toView(rx: Float, ry: Float): Pair<Float, Float> = left + rx * scale to top + ry * scale

    /** Keeps the zoomed content covering the view where it can, and centred where it cannot. */
    fun clamped(): Viewport {
        fun limit(content: Float, view: Float, pan: Float): Float {
            if (content <= view) return 0f
            val slack = (content - view) / 2
            return pan.coerceIn(-slack, slack)
        }
        return copy(panX = limit(contentWidth, viewWidth, panX), panY = limit(contentHeight, viewHeight, panY))
    }

    fun panned(dx: Float, dy: Float) = copy(panX = panX + dx, panY = panY + dy).clamped()

    /** Zooms by [factor] keeping the point under ([fx], [fy]) where it is. */
    fun zoomed(factor: Float, fx: Float, fy: Float): Viewport {
        val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val ratio = next / zoom
        // The focus stays put: its offset from the view centre plus pan scales with the zoom.
        val cx = viewWidth / 2
        val cy = viewHeight / 2
        val panX = fx - cx - (fx - cx - panX) * ratio
        val panY = fy - cy - (fy - cy - panY) * ratio
        return copy(zoom = next, panX = panX, panY = panY).clamped()
    }

    companion object { const val MAX_ZOOM = 5f }
}

enum class PointerMode { TOUCH, MOUSE }

/** What a gesture asks for: remote input, or a local change to the viewport. */
sealed interface GestureAction {
    data class MoveTo(val x: Int, val y: Int) : GestureAction
    data class MoveBy(val dx: Int, val dy: Int) : GestureAction
    data class Button(val button: Int, val down: Boolean) : GestureAction
    /** 1/120 of a notch; positive [dy] scrolls up. */
    data class Wheel(val dx: Int, val dy: Int) : GestureAction
    data class View(val viewport: Viewport) : GestureAction
}

/**
 * Turns touches into desktop input (plan §5.2). Touch mode: tap = left click where the finger is,
 * long press = right click, one finger dragging = a left-button drag from where it landed.
 * Mouse mode: one finger moves the cursor relatively, tap and long press click where it is, and a tap
 * then hold and drag drags, as on a laptop's touchpad: a tap's release waits [DOUBLE_TAP_MS] for that
 * second touch, so the host sees one press held through the drag, never a click and then a press
 * (a double-click there maximises a window or opens a file instead of dragging it).
 * Both: two fingers scroll, pinching zooms and pans locally. Times are milliseconds; [tick] fires
 * long presses and delayed releases.
 */
class DesktopGestures(var mode: PointerMode = PointerMode.TOUCH, private val density: Float = 1f) {
    var viewport = Viewport(1f, 1f, 1, 1)
    private val slop = 10f * density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false
    private var longPressed = false
    private var dragging = false
    /** Mouse mode: the left button is still down after a tap, until this time. */
    private var releaseAt: Long? = null
    /** Mouse mode: this touch began while a tap still held the button. */
    private var afterTap = false
    /** A second finger came and went: the rest of this touch does nothing. */
    private var spent = false
    private var twoFinger: TwoFinger? = null
    private var down = false
    private var relRemainderX = 0f
    private var relRemainderY = 0f

    private class TwoFinger(var cx: Float, var cy: Float, var span: Float, val startCx: Float, val startCy: Float, val startSpan: Float) {
        var kind = 0 // 0 undecided, 1 scroll, 2 pinch
        var wheelX = 0f
        var wheelY = 0f
    }

    private val longPressAt: Long? get() =
        if (down && !moved && !longPressed && !dragging && !afterTap && !spent && twoFinger == null) downAt + LONG_PRESS_MS else null

    /** When [tick] next needs calling, for a long press or a tap's release; null when nothing is pending. */
    val tickAt: Long? get() = listOfNotNull(longPressAt, releaseAt).minOrNull()

    fun down(x: Float, y: Float, time: Long): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        val held = releaseAt
        releaseAt = null
        down = true
        downX = x; downY = y; lastX = x; lastY = y; downAt = time
        moved = false; longPressed = false; dragging = false; afterTap = false; spent = false
        // Mouse mode, a tap a moment ago: its press carries on into whatever this touch does.
        if (held != null && time < held && mode == PointerMode.MOUSE) afterTap = true
        else if (held != null) out += GestureAction.Button(1, false)
        return out
    }

    fun move(x: Float, y: Float, time: Long): List<GestureAction> {
        if (!down || twoFinger != null || spent) return emptyList()
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        if (!moved && hypot(x - downX, y - downY) < slop) return emptyList()
        moved = true
        return when {
            longPressed -> emptyList()
            mode == PointerMode.MOUSE -> { if (afterTap) dragging = true; rel(dx, dy) }
            dragging -> listOf(moveTo(x, y))
            else -> {
                // The press goes where the finger landed, so the drag picks up what was under it.
                dragging = true
                listOf(moveTo(downX, downY), GestureAction.Button(1, true), moveTo(x, y))
            }
        }
    }

    fun up(x: Float, y: Float, time: Long): List<GestureAction> {
        if (!down) return emptyList()
        down = false
        if (twoFinger != null) return emptyList()
        if (dragging) {
            dragging = false
            return listOf(GestureAction.Button(1, false))
        }
        if (afterTap) {
            afterTap = false
            // A second tap without a drag: a double-click.
            return listOf(GestureAction.Button(1, false), GestureAction.Button(1, true), GestureAction.Button(1, false))
        }
        if (longPressed || moved || spent) return emptyList()
        if (mode == PointerMode.MOUSE) {
            releaseAt = time + DOUBLE_TAP_MS
            return listOf(GestureAction.Button(1, true))
        }
        // Where the finger landed: lifting drifts a few pixels.
        return listOf(moveTo(downX, downY), GestureAction.Button(1, true), GestureAction.Button(1, false))
    }

    fun tick(time: Long): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        releaseAt?.let { if (time >= it) { releaseAt = null; out += GestureAction.Button(1, false) } }
        val at = longPressAt ?: return out
        if (time < at) return out
        longPressed = true
        if (mode == PointerMode.TOUCH) out += moveTo(downX, downY)
        out += GestureAction.Button(2, true)
        out += GestureAction.Button(2, false)
        return out
    }

    /** A second finger landed: whatever one finger was doing ends. */
    fun twoDown(x0: Float, y0: Float, x1: Float, y1: Float): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        if (dragging || afterTap) { out += GestureAction.Button(1, false); dragging = false; afterTap = false }
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val span = hypot(x1 - x0, y1 - y0)
        twoFinger = TwoFinger(cx, cy, span, cx, cy, span)
        return out
    }

    fun twoMove(x0: Float, y0: Float, x1: Float, y1: Float): List<GestureAction> {
        val t = twoFinger ?: return emptyList()
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val span = hypot(x1 - x0, y1 - y0)
        if (t.kind == 0) {
            if (abs(span - t.startSpan) > 24f * density) t.kind = 2
            else if (hypot(cx - t.startCx, cy - t.startCy) > 12f * density) t.kind = 1
        }
        val out = mutableListOf<GestureAction>()
        when (t.kind) {
            1 -> {
                // Fingers moving down pull the page down, as on a phone: the wheel scrolls up.
                t.wheelX += (cx - t.cx) * WHEEL_PER_PX / density
                t.wheelY += (cy - t.cy) * WHEEL_PER_PX / density
                val wx = t.wheelX.toInt()
                val wy = t.wheelY.toInt()
                if (wx != 0 || wy != 0) {
                    t.wheelX -= wx; t.wheelY -= wy
                    out += GestureAction.Wheel(-wx, wy)
                }
            }
            2 -> if (t.span > 0f) {
                viewport = viewport.zoomed(span / t.span, cx, cy).panned(cx - t.cx, cy - t.cy)
                out += GestureAction.View(viewport)
            }
        }
        t.cx = cx; t.cy = cy; t.span = span
        return out
    }

    /** Fewer than two fingers again: the gesture is over until every finger lifts. */
    fun twoUp() { twoFinger = null; spent = true }

    /** The touch was taken away, or the screen is going: nothing may stay pressed. */
    fun cancel(): List<GestureAction> {
        val out = if (dragging || afterTap || releaseAt != null) listOf(GestureAction.Button(1, false)) else emptyList()
        down = false; dragging = false; afterTap = false; releaseAt = null; twoFinger = null
        return out
    }

    private fun moveTo(x: Float, y: Float): GestureAction {
        val (rx, ry) = viewport.toRemote(x, y)
        return GestureAction.MoveTo(rx, ry)
    }

    /** Finger travel in view pixels to cursor travel in native pixels, as if dragging on the picture. */
    private fun rel(dx: Float, dy: Float): List<GestureAction> {
        relRemainderX += dx / viewport.scale * MOUSE_SPEED
        relRemainderY += dy / viewport.scale * MOUSE_SPEED
        val ix = relRemainderX.toInt()
        val iy = relRemainderY.toInt()
        if (ix == 0 && iy == 0) return emptyList()
        relRemainderX -= ix; relRemainderY -= iy
        return listOf(GestureAction.MoveBy(ix, iy))
    }

    companion object {
        const val LONG_PRESS_MS = 500L
        const val DOUBLE_TAP_MS = 300L
        /** Wheel units (1/120 notch) per dp of two-finger travel. */
        const val WHEEL_PER_PX = 4f
        const val MOUSE_SPEED = 1.5f
    }
}
