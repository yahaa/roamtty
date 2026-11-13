package io.itermux.app.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import io.itermux.desktop.PipLayout
import io.itermux.desktop.PipRect

/** The window's geometry as last composed, for gestures to read when they start. */
internal data class PipGeometry(val w: Float, val h: Float, val placed: PipRect, val room: PipRect, val topLeft: Offset,
    val handleOnLeft: Boolean, val closeTarget: Offset)

/** What a gesture is doing to the small window right now. */
@Stable
internal class PipGestureState {
    /** Set while the handle is dragged: the width so far, and the top corner that stays where it is. */
    var resizing by mutableStateOf<Pair<Float, Offset>?>(null)
    /** Where a drag has the window's top left; null while it rests. */
    var dragPos by mutableStateOf<Offset?>(null)
    /** A drag is over the close target. */
    var hot by mutableStateOf(false)
    /** The handle keeps its side once used, so it is where the finger left it; a move picks the side with room again. */
    var handleSide by mutableStateOf<Boolean?>(null)
}

/**
 * The gestures that place the small window. [reference] (what [PipState.fraction] is a fraction of), [aspect] and
 * [now] are read as a gesture runs: a turn of the phone or a new display changes them, and keying the gestures on
 * them would restart one as it resizes the window.
 */
internal class PipGestures(
    private val state: PipState,
    private val live: PipGestureState,
    private val view: View,
    private val slop: Float,
    private val closeRadius: Float,
    private val reference: () -> Float,
    private val aspect: () -> Float,
    private val now: () -> PipGeometry,
    private val onTap: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val widths get() = reference() * PipLayout.MIN_FRACTION..reference() * PipLayout.MAX_FRACTION

    /** Remembers where the window is now, at the size it is now. */
    private fun keep(left: Float, top: Float, width: Float, height: Float, r: PipRect) {
        val (fx, fy) = PipLayout.fractions(r, width, height, left, top)
        state.x = fx; state.y = fy
        state.fraction = (width / reference()).coerceIn(PipLayout.MIN_FRACTION, PipLayout.MAX_FRACTION)
        state.save()
    }

    /** A drag that moves the window (or closes it on ✕); with [pinch], two fingers resize it, and a still tap is a tap. */
    suspend fun PointerInputScope.place(pinch: Boolean) = awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val (w, h, placed, r, _, _, target) = now()
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
                live.dragPos = null; live.hot = false
                width = (width * event.calculateZoom()).coerceIn(widths)
                state.fraction = width / reference()
            } else if (!pinching && fingers == 1) {
                val pan = event.calculatePan()
                travel += pan
                if (!moved && travel.getDistance() > slop) moved = true
                if (moved) {
                    pos += pan
                    live.dragPos = pos
                    val over = PipLayout.overClose(pos.x + w / 2, pos.y + h / 2, target.x, target.y, closeRadius * 2.4f)
                    if (over && !live.hot) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    live.hot = over
                }
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
        when {
            pinching -> {
                val (pw, ph) = PipLayout.size(r, reference(), state.fraction, aspect())
                // Grows and shrinks about its centre, kept inside.
                keep(placed.centerX - pw / 2, placed.centerY - ph / 2, pw, ph, r)
            }
            moved -> {
                if (live.hot) onClose() else { keep(pos.x, pos.y, w, h, r); live.handleSide = null }
                live.hot = false
                live.dragPos = null
            }
            pinch -> onTap()
        }
    }

    /**
     * A drag on the corner handle. Resizing from the left grip keeps the right corner, from the right grip the left
     * one: the side the drag started on, not one guessed from the placement (which moves as the width grows).
     */
    suspend fun PointerInputScope.resize() = awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        val (w, _, _, r, topLeft, onLeft) = now()
        val fixed = if (onLeft) Offset(topLeft.x + w, topLeft.y) else topLeft
        live.handleSide = onLeft
        var width = w
        live.resizing = width to fixed
        do {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.pressed } ?: break
            val d = change.positionChange()
            width = PipLayout.resized(width, d.x, d.y, onLeft, aspect()).coerceIn(widths)
            live.resizing = width to fixed
            change.consume()
        } while (event.changes.any { it.pressed })
        val (fw, fh) = PipLayout.size(r, reference(), width / reference(), aspect())
        keep(if (onLeft) fixed.x - fw else fixed.x, fixed.y, fw, fh, r)
        live.resizing = null
    }
}
