package io.itermux.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopInputProbe
import io.itermux.desktop.DesktopGestures
import io.itermux.desktop.DesktopProtocol
import io.itermux.desktop.DesktopSwipeShortcut
import io.itermux.desktop.GestureAction
import io.itermux.desktop.GestureFeedback
import io.itermux.desktop.OverlayTouch
import io.itermux.desktop.PointerMode
import io.itermux.desktop.Viewport

/**
 * Every touch on the desktop's picture, turned into input: the gesture engine's actions go to the host, move the
 * view, or buzz the phone. A touch that starts on the network figures waits: a still hold moves them, anything
 * else is replayed to the picture as it came ([OverlayTouch]), so the figures never take a gesture from the desktop.
 */
internal class DesktopTouchInput(private val connection: DesktopConnection, mode: PointerMode, density: Float, touchSlop: Float) {
    val gestures = DesktopGestures(mode, density)
    var viewport by mutableStateOf(Viewport(1f, 1f, 1, 1))
    /** Bumped by every gesture call: the cursor and the ring redraw from the engine's state. */
    var drawn by mutableIntStateOf(0)
        private set
    /** Where the touch area sits in the window. */
    var origin = Offset.Zero

    private var mode = mode
    private var displayIndex = 0
    private var os = ""
    private lateinit var view: View
    private lateinit var stats: StatsBox
    private var onStatsMoved: (Pair<Float, Float>) -> Unit = {}

    /** Called on every composition with what the page shows now. */
    fun update(mode: PointerMode, displayIndex: Int, os: String, view: View, stats: StatsBox, onStatsMoved: (Pair<Float, Float>) -> Unit) {
        this.mode = mode
        gestures.mode = mode
        this.displayIndex = displayIndex
        this.os = os
        this.view = view
        this.stats = stats
        this.onStatsMoved = onStatsMoved
    }

    /** The host's cursor: taken unless the phone moved its own a moment ago. */
    fun hostCursor(x: Int, y: Int) {
        gestures.hostCursor(x, y, SystemClock.uptimeMillis())
        drawn++
    }

    private fun apply(actions: List<GestureAction>) {
        val d = displayIndex
        actions.forEach { a ->
            when (a) {
                is GestureAction.MoveTo -> connection.input(DesktopProtocol.abs(d, a.x, a.y))
                is GestureAction.Button -> connection.input(DesktopProtocol.button(a.button, a.down))
                is GestureAction.Wheel -> connection.input(DesktopProtocol.wheel(a.dx, a.dy))
                is GestureAction.View -> viewport = a.viewport
                is GestureAction.Swipe -> DesktopSwipeShortcut.frames(a.direction, os).forEach(connection::input)
                is GestureAction.Feedback -> view.performHapticFeedback(when (a.kind) {
                    GestureFeedback.HOLD, GestureFeedback.PICKUP -> HapticFeedbackConstants.LONG_PRESS
                    GestureFeedback.CLICK, GestureFeedback.SHORTCUT -> HapticFeedbackConstants.KEYBOARD_TAP
                })
            }
            DesktopInputProbe.record(a)
        }
        drawn++
    }

    // One timer on the main looper for whenever the engine next needs a tick (a hold, a waiting right click,
    // inertia), on the uptime clock touches carry: not a coroutine delay, whose clock a test may hold still.
    private val ticker = Handler(Looper.getMainLooper())
    private val tickToken = Any()
    private var pendingAt: Long? = null

    private fun scheduleTick() {
        val at = gestures.tickAt
        if (at == pendingAt) return
        ticker.removeCallbacksAndMessages(tickToken)
        pendingAt = at ?: return
        ticker.postAtTime({
            pendingAt = null
            apply(gestures.tick(SystemClock.uptimeMillis()))
            scheduleTick()
        }, tickToken, at)
    }

    /** Each pointer keeps its id while it touches: the engine follows fingers, not indices. */
    private fun feed(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                gestures.mode = mode
                gestures.viewport = viewport
                if (e.actionMasked == MotionEvent.ACTION_DOWN) DesktopInputProbe.gestureStarted(e.eventTime)
                val i = e.actionIndex
                apply(gestures.began(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime))
            }
            MotionEvent.ACTION_MOVE -> apply(gestures.moved((0 until e.pointerCount).map { Triple(e.getPointerId(it), e.getX(it), e.getY(it)) }, e.eventTime))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                apply(gestures.ended(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime))
                if (e.actionMasked == MotionEvent.ACTION_UP) DesktopInputProbe.gestureEnded(e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> apply(gestures.cancel())
        }
        // The next tick follows whatever the touch changed (a hold, a waiting right click, inertia).
        scheduleTick()
    }

    private val overlay = OverlayTouch(touchSlop)
    /** The events of a touch that may yet be the figures' or the picture's. */
    private val held = ArrayList<MotionEvent>()
    private val holdToken = Any()
    // The first finger and where it is: plain fields, read per event.
    private var firstId = 0
    private var last = Offset.Zero

    private fun release() {
        ticker.removeCallbacksAndMessages(holdToken)
        held.forEach { it.recycle() }
        held.clear()
    }

    private fun pickUp() {
        release()
        stats.startDrag(last.x, last.y)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun drop() {
        if (stats.dragging) { stats.dragging = false; onStatsMoved(stats.fx to stats.fy) }
    }

    /** Takes a touch event on the picture: to the figures while they are held, else to the desktop. */
    fun route(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return routeDown(e)
        val before = overlay.owner
        val first = e.findPointerIndex(firstId).coerceAtLeast(0)
        val x = e.getX(first)
        val y = e.getY(first)
        val after = when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> overlay.moved(x, y, e.eventTime)
            MotionEvent.ACTION_POINTER_DOWN -> overlay.otherFinger()
            MotionEvent.ACTION_UP -> overlay.up(e.eventTime)
            MotionEvent.ACTION_CANCEL -> before.also { overlay.reset() }
            else -> before
        }
        if (before == OverlayTouch.Owner.PENDING) last = Offset(x, y)
        when (after) {
            OverlayTouch.Owner.PENDING -> held += MotionEvent.obtain(e)
            OverlayTouch.Owner.PICTURE -> {
                if (before == OverlayTouch.Owner.PENDING) { held.forEach(::feed); release() }
                feed(e)
            }
            OverlayTouch.Owner.BOX -> {
                if (before == OverlayTouch.Owner.PENDING) pickUp()
                if (e.actionMasked == MotionEvent.ACTION_MOVE) stats.dragTo(x, y)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) drop()
            }
            OverlayTouch.Owner.NONE -> {}
        }
        if (e.actionMasked == MotionEvent.ACTION_CANCEL && before == OverlayTouch.Owner.PENDING) release()
    }

    /** The first finger comes down: on the figures it waits to see whether it holds still, elsewhere it is the picture's. */
    private fun routeDown(e: MotionEvent) {
        release(); drop()
        firstId = e.getPointerId(0)
        last = Offset(e.x, e.y)
        val onBox = stats.bounds?.contains(Offset(e.x + origin.x, e.y + origin.y)) == true
        if (overlay.down(onBox, e.x, e.y, e.eventTime) != OverlayTouch.Owner.PENDING) return feed(e)
        held += MotionEvent.obtain(e)
        ticker.postAtTime({ if (overlay.tick(SystemClock.uptimeMillis()) == OverlayTouch.Owner.BOX) pickUp() },
            holdToken, overlay.holdAt ?: return)
    }

    /** Leaving the page mid-drag or right after a tap must not leave the host's button down. */
    fun dispose() {
        ticker.removeCallbacksAndMessages(tickToken)
        apply(gestures.cancel())
        release()
        stats.dragging = false
    }
}
