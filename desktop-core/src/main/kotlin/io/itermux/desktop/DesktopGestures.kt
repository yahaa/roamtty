package io.itermux.desktop

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/** What the phone should feel or show, never sent to the host. */
enum class GestureFeedback {
    /** A hold was recognised: lift for a right click, or move to drag. */
    HOLD,
    /** A held finger started a drag: the button went down. */
    PICKUP,
    /** A click that needed a hold or a second finger (right, middle). */
    CLICK,
    /** A three-finger swipe, an edge swipe or a smart zoom. */
    SHORTCUT,
}

/** What a gesture asks for: remote input, a local change to the viewport, or feedback. */
sealed interface GestureAction {
    data class MoveTo(val x: Int, val y: Int) : GestureAction
    /** 1 left, 2 right, 3 middle. */
    data class Button(val button: Int, val down: Boolean) : GestureAction
    /** 1/120 of a notch; positive [dy] scrolls up. */
    data class Wheel(val dx: Int, val dy: Int) : GestureAction
    data class View(val viewport: Viewport) : GestureAction
    /** Three fingers swiped: the page turns it into the host's shortcut ([DesktopSwipeShortcut]). */
    data class Swipe(val direction: SwipeDirection) : GestureAction
    data class Feedback(val kind: GestureFeedback) : GestureAction
}

/** A hold in progress, for the ring the page draws under the fingers (view pixels). */
data class HoldIndicator(val x: Float, val y: Float, val fingers: Int,
    /** False while waiting for the hold, true once it fired. */
    val armed: Boolean,
    /** When the hold fires, while waiting for it. */
    val firesAt: Long)

/**
 * Touches to desktop input, after Apple's trackpad (docs/desktop-gestures.md). The number of fingers
 * is the mouse button: one is left, two right, three middle. Taps act at once; holding a finger
 * (two for the right button) until the ring fills picks it up: lift to click with that button, or
 * move to drag. Two fingers scroll the host, with inertia; pinching zooms the picture locally.
 * [PointerMode.MOUSE] is trackpad mode: one finger moves the cursor, with acceleration, and clicks act
 * where it is. [PointerMode.TOUCH] is direct touch, as on a phone: taps and holds act where the finger
 * is, and a slide scrolls what is under it, with inertia (zoomed in, it moves the picture first, then
 * scrolls past its edge). The cursor is the phone's: moves go out as absolute positions, so the host
 * adds no acceleration of its own and the cursor drawn here is where the host's is.
 * Positions are view pixels, sizes scale with [density]; times are milliseconds. The same engine as
 * iOS's `DesktopGestures.swift`, which uses points (density 1).
 */
class DesktopGestures(var mode: PointerMode = PointerMode.TOUCH, private val density: Float = 1f) {
    var viewport = Viewport(1f, 1f, 1, 1)
    /** Movement under this is still a tap or a hold. */
    private val slop = 10f * density
    private val doubleTapRadius = 20f * density
    private val edgeZone = 16f * density

    /** The phone's cursor in native pixels: where the last move put it, or where the host said. */
    var cursorX = 0f; private set
    var cursorY = 0f; private set
    var hasCursor = false; private set
    private var lastLocalMoveAt = Long.MIN_VALUE / 2
    /** The time of the event being handled. */
    private var now = 0L
    var indicator: HoldIndicator? = null; private set

    private class Finger(var x: Float, var y: Float, val startX: Float, val startY: Float)
    private val fingers = HashMap<Int, Finger>()
    /** Finger ids in landing order: multi-finger gestures use the first ones. */
    private val order = ArrayList<Int>()

    private enum class Phase {
        IDLE,
        PENDING, MOVING, HOLD_ARMED, DRAGGING, EDGE_DONE,
        PENDING2, SCROLL, PINCH, HOLD2_ARMED, RIGHT_DRAG,
        PENDING3, SWIPED,
        SPENT,
    }
    private var phase = Phase.IDLE
    private var startedAt = 0L
    private var downX = 0f
    private var downY = 0f
    /** Whether every finger stayed within the slop so far. */
    private var still = true
    private var holdAt: Long? = null
    private var edge: Edge? = null
    /** Multi-finger centroid where the count last changed, and on the last move. */
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorSpan = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastMoveAt = 0L
    /** Smoothed velocity of the finger or centroid, pixels per millisecond. */
    private var vx = 0f
    private var vy = 0f
    private var wheelX = 0f
    private var wheelY = 0f
    /** Touch mode: the last tap, so a second one nearby lands on the same pixel. */
    private var lastTap: Tap? = null
    /** A two-finger tap's right click waits a moment for a second tap, which zooms instead. */
    private var pendingRight: PendingRight? = null
    private var inertia: Inertia? = null
    /** Touch mode: dragging near the edge of a zoomed picture moves it; when the last frame was. */
    private var autoPanAt: Long? = null

    private data class Edge(val left: Boolean, val right: Boolean, val top: Boolean, val bottom: Boolean)
    private class Tap(val x: Float, val y: Float, val rx: Int, val ry: Int, val at: Long)
    private class PendingRight(val at: Long, val x: Float, val y: Float)
    /** [panFirst]: a one-finger flick in touch mode, which moves a zoomed picture before scrolling. */
    private class Inertia(var vx: Float, var vy: Float, var at: Long, val panFirst: Boolean)

    /** When [tick] next needs calling; null when nothing is pending. */
    val tickAt: Long? get() = listOfNotNull(holdAt, pendingRight?.at, inertia?.let { it.at + FRAME_MS }, autoPanAt?.let { it + FRAME_MS }).minOrNull()

    /** The host's cursor. While the phone moved it lately, the phone's own position stands. */
    fun hostCursor(x: Int, y: Int, time: Long) {
        if (!hasCursor || time - lastLocalMoveAt > 500) { cursorX = x.toFloat(); cursorY = y.toFloat(); hasCursor = true }
    }

    // Touches

    fun began(id: Int, x: Float, y: Float, time: Long): List<GestureAction> {
        now = time
        val out = mutableListOf<GestureAction>()
        inertia = null
        fingers[id] = Finger(x, y, x, y)
        order.remove(id)
        order += id
        when (fingers.size) {
            1 -> {
                phase = Phase.PENDING; startedAt = time; still = true
                downX = x; downY = y; lastX = x; lastY = y; lastMoveAt = time; vx = 0f; vy = 0f
                holdAt = time + HOLD_MS
                edge = edgeOf(x, y)
                indicator = HoldIndicator(x, y, 1, false, time + HOLD_MS)
            }
            2 -> when (phase) {
                Phase.PENDING, Phase.MOVING -> {
                    phase = Phase.PENDING2
                    val c = centroid(2)
                    anchorX = c[0]; anchorY = c[1]; anchorSpan = c[2]; lastX = c[0]; lastY = c[1]; lastSpan = c[2]
                    lastMoveAt = time; vx = 0f; vy = 0f; wheelX = 0f; wheelY = 0f
                    holdAt = time + HOLD2_MS
                    indicator = HoldIndicator(c[0], c[1], 2, false, time + HOLD2_MS)
                }
                else -> out += endOneFinger()
            }
            3 -> when (phase) {
                Phase.PENDING2 -> {
                    flushRight(out)
                    phase = Phase.PENDING3
                    val c = centroid(3)
                    anchorX = c[0]; anchorY = c[1]; holdAt = null; indicator = null
                }
                Phase.SCROLL, Phase.PINCH -> {} // a third finger brushing the glass: the first two carry on
                else -> { out += endOneFinger(); out += endTwoFinger() }
            }
            else -> { out += endOneFinger(); out += endTwoFinger() }
        }
        return out
    }

    fun moved(id: Int, x: Float, y: Float, time: Long): List<GestureAction> = moved(listOf(Triple(id, x, y)), time)

    /** All the fingers that moved in one event: (id, x, y). */
    fun moved(touches: List<Triple<Int, Float, Float>>, time: Long): List<GestureAction> {
        now = time
        for ((id, x, y) in touches) {
            val f = fingers[id] ?: continue
            f.x = x; f.y = y
            if (hypot(f.x - f.startX, f.y - f.startY) >= slop) still = false
        }
        if (fingers.isEmpty()) return emptyList()
        return when (phase) {
            Phase.PENDING, Phase.MOVING, Phase.HOLD_ARMED, Phase.DRAGGING -> {
                val f = order.firstOrNull()?.let { fingers[it] } ?: return emptyList()
                moveOne(f.x, f.y, time)
            }
            Phase.PENDING2, Phase.SCROLL, Phase.PINCH, Phase.HOLD2_ARMED, Phase.RIGHT_DRAG ->
                if (fingers.size >= 2) moveTwo(time) else emptyList()
            Phase.PENDING3 -> {
                if (fingers.size < 3) return emptyList()
                val c = centroid(3)
                val dx = c[0] - anchorX
                val dy = c[1] - anchorY
                if (hypot(dx, dy) < 40f * density) return emptyList()
                phase = Phase.SWIPED
                val dir = if (abs(dx) > abs(dy)) (if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT)
                    else (if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN)
                listOf(GestureAction.Swipe(dir), GestureAction.Feedback(GestureFeedback.SHORTCUT))
            }
            else -> emptyList()
        }
    }

    fun ended(id: Int, x: Float, y: Float, time: Long): List<GestureAction> {
        val f = fingers[id] ?: return emptyList()
        now = time
        if (hypot(x - f.startX, y - f.startY) >= slop) still = false
        fingers.remove(id)
        order.remove(id)
        val n = fingers.size
        val out = mutableListOf<GestureAction>()
        if (n < 2) when (phase) {
            Phase.SCROLL -> {
                // A pause before lifting leaves nothing to carry on.
                val speed = if (time - lastMoveAt > 60) 0f else hypot(vx, vy)
                if (speed > 0.05f * density) inertia = Inertia(vx, vy, time, false)
                phase = Phase.SPENT
            }
            Phase.PINCH -> phase = Phase.SPENT
            Phase.HOLD2_ARMED -> { out += rightClick(anchorX, anchorY); phase = Phase.SPENT }
            Phase.RIGHT_DRAG -> { out += GestureAction.Button(2, false); phase = Phase.SPENT }
            else -> {}
        }
        if (n == 0) out += finish(time)
        return out
    }

    /** The touches were taken away, or the screen is going: nothing may stay pressed. */
    fun cancel(): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        if (phase == Phase.DRAGGING) out += GestureAction.Button(1, false)
        if (phase == Phase.RIGHT_DRAG) out += GestureAction.Button(2, false)
        fingers.clear(); order.clear()
        phase = Phase.IDLE; holdAt = null; indicator = null; pendingRight = null; inertia = null; autoPanAt = null
        return out
    }

    fun tick(time: Long): List<GestureAction> {
        now = time
        val out = mutableListOf<GestureAction>()
        pendingRight?.let { if (time >= it.at) flushRight(out) }
        val at = holdAt
        if (at != null && time >= at) {
            holdAt = null
            if (phase == Phase.PENDING && fingers.size == 1) {
                flushRight(out)
                phase = Phase.HOLD_ARMED
                indicator = HoldIndicator(downX, downY, 1, true, at)
                if (mode == PointerMode.TOUCH) out += moveTo(downX, downY)
                out += GestureAction.Feedback(GestureFeedback.HOLD)
            } else if (phase == Phase.PENDING2 && fingers.size >= 2) {
                flushRight(out)
                phase = Phase.HOLD2_ARMED
                indicator = HoldIndicator(anchorX, anchorY, 2, true, at)
                if (mode == PointerMode.TOUCH) out += moveTo(anchorX, anchorY)
                out += GestureAction.Feedback(GestureFeedback.HOLD)
            }
        }
        inertia?.let { i ->
            if (time < i.at + FRAME_MS) return@let
            val dt = (time - i.at).toFloat()
            val decay = DECELERATION.toDouble().pow(dt.toDouble()).toFloat()
            i.vx *= decay; i.vy *= decay; i.at = time
            out += if (i.panFirst) swipe(i.vx * dt, i.vy * dt) else scroll(i.vx * dt, i.vy * dt)
            if (hypot(i.vx, i.vy) < 0.02f * density) inertia = null
        }
        autoPanAt?.let { if (time >= it + FRAME_MS) out += autoPan(time) }
        return out
    }

    // One finger

    private fun moveOne(x: Float, y: Float, time: Long): List<GestureAction> {
        val dx = x - lastX
        val dy = y - lastY
        val dt = max(1L, time - lastMoveAt).toFloat()
        track(dx / dt, dy / dt)
        lastX = x; lastY = y; lastMoveAt = time
        val far = hypot(x - downX, y - downY) >= slop
        val out = mutableListOf<GestureAction>()
        when (phase) {
            Phase.PENDING -> {
                if (!far) return emptyList()
                flushRight(out)
                holdAt = null; indicator = null
                val e = edge
                if (e != null && inward(e, x - downX, y - downY)) {
                    phase = Phase.EDGE_DONE
                    return out + toEdge(e) + GestureAction.Feedback(GestureFeedback.SHORTCUT)
                }
                phase = Phase.MOVING
                // Touch mode scrolls what is under the finger: the host scrolls what is under its cursor.
                if (mode == PointerMode.TOUCH) return out + moveTo(downX, downY) + swipe(dx, dy)
                return out + follow(x, y, dx, dy)
            }
            Phase.MOVING -> return if (mode == PointerMode.TOUCH) swipe(dx, dy) else follow(x, y, dx, dy)
            Phase.HOLD_ARMED -> {
                if (!far) return emptyList()
                phase = Phase.DRAGGING
                indicator = null
                if (mode == PointerMode.TOUCH) {
                    // The press goes where the finger landed, so the drag picks up what was under it.
                    val r = listOf(moveTo(downX, downY), GestureAction.Button(1, true), GestureAction.Feedback(GestureFeedback.PICKUP), moveTo(x, y))
                    armAutoPan(time)
                    return r
                }
                return listOf(GestureAction.Button(1, true), GestureAction.Feedback(GestureFeedback.PICKUP)) + follow(x, y, dx, dy)
            }
            Phase.DRAGGING -> {
                if (mode == PointerMode.TOUCH) armAutoPan(time)
                return follow(x, y, dx, dy)
            }
            else -> return emptyList()
        }
    }

    /** Trackpad: the cursor travels the finger's way, faster the faster it goes. Touch: it is under the finger. */
    private fun follow(x: Float, y: Float, dx: Float, dy: Float): List<GestureAction> {
        if (mode == PointerMode.TOUCH) return listOf(moveTo(x, y))
        val gain = pointerGain(hypot(vx, vy) * 1000 / density)
        val s = viewport.scale
        setCursor(cursorX + dx * gain / s, cursorY + dy * gain / s)
        return listOf(GestureAction.MoveTo(cursorX.toInt(), cursorY.toInt())) + keepCursorInView()
    }

    /** Trackpad, zoomed in: the picture follows a cursor that reaches its outer 15%. */
    private fun keepCursorInView(): List<GestureAction> {
        if (viewport.zoom <= 1f) return emptyList()
        val (px, py) = viewport.toView(cursorX, cursorY)
        val mx = viewport.viewWidth * 0.15f
        val my = viewport.viewHeight * 0.15f
        var dx = 0f
        var dy = 0f
        if (px < mx) dx = mx - px else if (px > viewport.viewWidth - mx) dx = viewport.viewWidth - mx - px
        if (py < my) dy = my - py else if (py > viewport.viewHeight - my) dy = viewport.viewHeight - my - py
        if (dx == 0f && dy == 0f) return emptyList()
        val next = viewport.panned(dx, dy)
        if (next == viewport) return emptyList()
        viewport = next
        return listOf(GestureAction.View(next))
    }

    private fun armAutoPan(time: Long) {
        if (autoPanAt == null && viewport.zoom > 1f && edgeDepth(lastX, lastY) != (0f to 0f)) autoPanAt = time
    }

    /** How far into the edge band a point is, -1…1 on each axis (negative: left or top). */
    private fun edgeDepth(x: Float, y: Float): Pair<Float, Float> {
        val bx = min(60f * density, viewport.viewWidth * 0.15f)
        val by = min(60f * density, viewport.viewHeight * 0.15f)
        fun depth(v: Float, size: Float, band: Float): Float = when {
            v < band -> -min(1f, (band - v) / band)
            v > size - band -> min(1f, (v - (size - band)) / band)
            else -> 0f
        }
        return depth(x, viewport.viewWidth, bx) to depth(y, viewport.viewHeight, by)
    }

    /** Touch mode: a drag held near the edge of a zoomed picture moves the picture, up to 600 dp/s. */
    private fun autoPan(time: Long): List<GestureAction> {
        val at = autoPanAt
        if (at == null || (phase != Phase.DRAGGING && phase != Phase.RIGHT_DRAG) || mode != PointerMode.TOUCH || viewport.zoom <= 1f) {
            autoPanAt = null; return emptyList()
        }
        val (ex, ey) = edgeDepth(lastX, lastY)
        val dt = (time - at).toFloat()
        val next = viewport.panned(-ex * 0.6f * density * dt, -ey * 0.6f * density * dt)
        autoPanAt = time
        if (next == viewport) { autoPanAt = null; return emptyList() }
        viewport = next
        return listOf(GestureAction.View(next), moveTo(lastX, lastY))
    }

    /**
     * The picture's edges, where they show in the view: a touch within the edge zone of one (on either
     * side of it, along the picture) may be an edge swipe. The black bars beside the picture are not
     * the picture's: the system's back gesture starts there.
     */
    private fun edgeOf(x: Float, y: Float): Edge? {
        val v = viewport
        val pl = max(0f, v.left)
        val pr = min(v.viewWidth, v.left + v.contentWidth)
        val pt = max(0f, v.top)
        val pb = min(v.viewHeight, v.top + v.contentHeight)
        val alongY = y >= pt - edgeZone && y <= pb + edgeZone
        val alongX = x >= pl - edgeZone && x <= pr + edgeZone
        val e = Edge(alongY && abs(x - pl) < edgeZone, alongY && abs(x - pr) < edgeZone,
            alongX && abs(y - pt) < edgeZone, alongX && abs(y - pb) < edgeZone)
        return if (e.left || e.right || e.top || e.bottom) e else null
    }

    private fun inward(e: Edge, dx: Float, dy: Float) =
        (e.left && dx > abs(dy)) || (e.right && -dx > abs(dy)) || (e.top && dy > abs(dx)) || (e.bottom && -dy > abs(dx))

    /** An edge swipe puts the cursor on the host's edge there (the Dock, the taskbar), or in its corner. */
    private fun toEdge(e: Edge): GestureAction {
        val (rx, ry) = viewport.toRemote(downX, downY)
        var x = if (mode == PointerMode.TOUCH) rx.toFloat() else cursorX
        var y = if (mode == PointerMode.TOUCH) ry.toFloat() else cursorY
        if (e.left) x = 0f else if (e.right) x = (viewport.remoteWidth - 1).toFloat()
        if (e.top) y = 0f else if (e.bottom) y = (viewport.remoteHeight - 1).toFloat()
        setCursor(x, y)
        return GestureAction.MoveTo(cursorX.toInt(), cursorY.toInt())
    }

    /** A second finger came while one was holding or dragging: that ends, and so does the gesture. */
    private fun endOneFinger(): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        if (phase == Phase.DRAGGING) out += GestureAction.Button(1, false)
        if (phase in ONE_FINGER) phase = Phase.SPENT
        holdAt = null; indicator = null; autoPanAt = null
        return out
    }

    private fun endTwoFinger(): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        if (phase == Phase.RIGHT_DRAG) out += GestureAction.Button(2, false)
        if (phase in MULTI_FINGER) phase = Phase.SPENT
        holdAt = null; indicator = null; autoPanAt = null
        return out
    }

    // Two fingers

    /** x, y and the span between the first two fingers. */
    private fun centroid(count: Int): FloatArray {
        val pts = order.take(count).mapNotNull { fingers[it] }
        if (pts.isEmpty()) return floatArrayOf(lastX, lastY, lastSpan)
        val x = pts.map { it.x }.sum() / pts.size
        val y = pts.map { it.y }.sum() / pts.size
        val span = if (pts.size >= 2) hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y) else 0f
        return floatArrayOf(x, y, span)
    }

    private fun moveTwo(time: Long): List<GestureAction> {
        val c = centroid(2)
        val (cx, cy, span) = Triple(c[0], c[1], c[2])
        val dx = cx - lastX
        val dy = cy - lastY
        val dt = max(1L, time - lastMoveAt).toFloat()
        track(dx / dt, dy / dt)
        val out = mutableListOf<GestureAction>()
        if (phase == Phase.PENDING2) {
            if (abs(span - anchorSpan) > 24f * density) phase = Phase.PINCH
            else if (hypot(cx - anchorX, cy - anchorY) > 12f * density) phase = Phase.SCROLL
            if (phase != Phase.PENDING2) { flushRight(out); holdAt = null; indicator = null }
            if (phase == Phase.SCROLL && mode == PointerMode.TOUCH) out += moveTo(anchorX, anchorY)
        }
        when (phase) {
            Phase.SCROLL -> out += scroll(dx, dy)
            Phase.PINCH -> if (lastSpan > 0f) {
                viewport = viewport.zoomed(span / lastSpan, cx, cy).panned(dx, dy)
                out += GestureAction.View(viewport)
            }
            Phase.HOLD2_ARMED -> if (hypot(cx - anchorX, cy - anchorY) >= slop) {
                phase = Phase.RIGHT_DRAG
                indicator = null
                if (mode == PointerMode.TOUCH) {
                    out += listOf(moveTo(anchorX, anchorY), GestureAction.Button(2, true), GestureAction.Feedback(GestureFeedback.PICKUP), moveTo(cx, cy))
                    lastX = cx; lastY = cy
                    armAutoPan(time)
                } else {
                    out += GestureAction.Button(2, true)
                    out += GestureAction.Feedback(GestureFeedback.PICKUP)
                    out += follow(cx, cy, dx, dy)
                }
            }
            Phase.RIGHT_DRAG -> {
                if (mode == PointerMode.TOUCH) { lastX = cx; lastY = cy; armAutoPan(time) }
                out += follow(cx, cy, dx, dy)
            }
            else -> {}
        }
        lastX = cx; lastY = cy; lastSpan = span; lastMoveAt = time
        return out
    }

    /** Wheel units per pixel of finger travel: about what moves the content with the fingers. */
    private val wheelPerPixel: Float get() = (2.5f * density / viewport.scale).coerceIn(2f, 12f) / density

    /**
     * Touch mode, one finger: zoomed in, the picture follows the finger, and only what is left past
     * its edge scrolls the host, as a zoomed photo on a phone; at full size it all scrolls.
     */
    private fun swipe(dx: Float, dy: Float): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        var rx = dx
        var ry = dy
        if (viewport.zoom > 1f) {
            val next = viewport.panned(dx, dy)
            rx -= next.panX - viewport.panX
            ry -= next.panY - viewport.panY
            if (next != viewport) { viewport = next; out += GestureAction.View(next) }
        }
        return out + scroll(rx, ry)
    }

    /** Fingers moving down pull the page down, as on a phone: the wheel scrolls up. */
    private fun scroll(dx: Float, dy: Float): List<GestureAction> {
        wheelX += dx * wheelPerPixel
        wheelY += dy * wheelPerPixel
        val wx = wheelX.toInt()
        val wy = wheelY.toInt()
        // A few units at a time: macOS turns three of them into one pixel and drops the rest.
        if (abs(wx) < 3 && abs(wy) < 3) return emptyList()
        wheelX -= wx; wheelY -= wy
        return listOf(GestureAction.Wheel(-wx, wy))
    }

    private fun track(nvx: Float, nvy: Float) {
        vx = vx * 0.4f + nvx * 0.6f
        vy = vy * 0.4f + nvy * 0.6f
    }

    // Ends

    private fun finish(time: Long): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        when (phase) {
            Phase.PENDING -> {
                flushRight(out)
                if (mode == PointerMode.TOUCH) {
                    // Where the finger landed, or a moment ago nearby: a double-click lands on one pixel.
                    var r = viewport.toRemote(downX, downY)
                    val t = lastTap
                    if (t != null && startedAt - t.at <= DOUBLE_TAP_MS && hypot(downX - t.x, downY - t.y) <= doubleTapRadius) r = t.rx to t.ry
                    lastTap = Tap(downX, downY, r.first, r.second, time)
                    setCursor(r.first.toFloat(), r.second.toFloat())
                    out += listOf(GestureAction.MoveTo(r.first, r.second), GestureAction.Button(1, true), GestureAction.Button(1, false))
                } else {
                    out += listOf(GestureAction.Button(1, true), GestureAction.Button(1, false))
                }
            }
            Phase.MOVING -> if (mode == PointerMode.TOUCH) {
                val speed = if (time - lastMoveAt > 60) 0f else hypot(vx, vy)
                if (speed > 0.05f * density) inertia = Inertia(vx, vy, time, true)
            }
            Phase.HOLD_ARMED -> out += rightClick(downX, downY)
            Phase.DRAGGING -> out += GestureAction.Button(1, false)
            Phase.PENDING2 -> if (still && time - startedAt <= TAP_MS) {
                if (pendingRight != null) { pendingRight = null; out += smartZoom(anchorX, anchorY) }
                else pendingRight = PendingRight(time + DOUBLE_TAP_MS, anchorX, anchorY)
            }
            Phase.PENDING3 -> if (still && time - startedAt <= TAP_MS) {
                if (mode == PointerMode.TOUCH) out += moveTo(anchorX, anchorY)
                out += listOf(GestureAction.Button(3, true), GestureAction.Button(3, false), GestureAction.Feedback(GestureFeedback.CLICK))
            }
            else -> {}
        }
        phase = Phase.IDLE; holdAt = null; indicator = null; autoPanAt = null
        return out
    }

    private fun rightClick(x: Float, y: Float): List<GestureAction> =
        (if (mode == PointerMode.TOUCH) listOf(moveTo(x, y)) else emptyList()) +
            listOf(GestureAction.Button(2, true), GestureAction.Button(2, false), GestureAction.Feedback(GestureFeedback.CLICK))

    private fun flushRight(out: MutableList<GestureAction>) {
        val p = pendingRight ?: return
        pendingRight = null
        out += rightClick(p.x, p.y)
    }

    /** Two-finger double tap, as on a Mac: zoom in on that spot, or back out to the whole screen. */
    private fun smartZoom(x: Float, y: Float): List<GestureAction> {
        viewport = if (viewport.zoom > 1.01f) Viewport(viewport.viewWidth, viewport.viewHeight, viewport.remoteWidth, viewport.remoteHeight)
            else viewport.zoomed(2.5f / viewport.zoom, x, y)
        return listOf(GestureAction.View(viewport), GestureAction.Feedback(GestureFeedback.SHORTCUT))
    }

    // Helpers

    private fun setCursor(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, max(0, viewport.remoteWidth - 1).toFloat())
        cursorY = y.coerceIn(0f, max(0, viewport.remoteHeight - 1).toFloat())
        hasCursor = true
        lastLocalMoveAt = now
    }

    private fun moveTo(x: Float, y: Float): GestureAction {
        val (rx, ry) = viewport.toRemote(x, y)
        setCursor(rx.toFloat(), ry.toFloat())
        return GestureAction.MoveTo(rx, ry)
    }

    companion object {
        const val HOLD_MS = 350L
        /** Two fingers rest a moment before scrolling, so their hold is longer. */
        const val HOLD2_MS = 500L
        const val DOUBLE_TAP_MS = 300L
        /** A multi-finger tap lifts within this. */
        const val TAP_MS = 350L
        /** Inertia frames. */
        const val FRAME_MS = 16L
        /** A scroll view's normal deceleration, per millisecond. */
        const val DECELERATION = 0.998f
        private val ONE_FINGER = setOf(Phase.PENDING, Phase.MOVING, Phase.HOLD_ARMED, Phase.DRAGGING, Phase.EDGE_DONE)
        private val MULTI_FINGER = setOf(Phase.PENDING2, Phase.SCROLL, Phase.PINCH, Phase.HOLD2_ARMED, Phase.RIGHT_DRAG, Phase.PENDING3, Phase.SWIPED)

        /** Pointer acceleration, after macOS: slow for precision, up to 4× for a flick across the screen. [dpPerSecond] of finger speed. */
        fun pointerGain(dpPerSecond: Float): Float = (0.8f * (dpPerSecond / 150f).toDouble().pow(0.6).toFloat()).coerceIn(0.5f, 4f)
    }
}

/**
 * Three-finger swipes as the host's own shortcuts: up shows every window, down the desktop (the
 * plugin's actions, which know each system), left and right switch to the desktop on that side.
 */
object DesktopSwipeShortcut {
    fun frames(direction: SwipeDirection, os: String): List<ByteArray> {
        fun combo(keys: List<Int>) = keys.map { DesktopProtocol.key(it, true) } + keys.reversed().map { DesktopProtocol.key(it, false) }
        val modifiers = when (os.lowercase()) {
            "macos" -> listOf(HidUsage.LEFT_CTRL)
            "windows" -> listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_GUI)
            else -> listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_ALT)
        }
        return when (direction) {
            SwipeDirection.UP -> listOf(DesktopProtocol.action(DesktopProtocol.ACTION_ALL_WINDOWS))
            SwipeDirection.DOWN -> listOf(DesktopProtocol.action(DesktopProtocol.ACTION_SHOW_DESKTOP))
            // Fingers going left bring in the desktop on the right, as on a trackpad.
            SwipeDirection.LEFT -> combo(modifiers + HidUsage.RIGHT)
            SwipeDirection.RIGHT -> combo(modifiers + HidUsage.LEFT)
        }
    }
}
