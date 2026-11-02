package io.itermux.desktop

import kotlin.math.hypot

/**
 * Who owns a touch that starts on a small box floating over the picture (the network figures, moved by a
 * long press). The box takes it only when the first finger holds still on it for [HOLD_MS]; anything sooner
 * (a lift, a move past [slop], a second finger) makes it the picture's, and the caller replays what it held
 * back, in order and with the touches' own times, so a tap, a swipe or a pinch over the box reaches the desktop
 * exactly as elsewhere. Only a still long press on the box is lost to the picture.
 */
class OverlayTouch(private val slop: Float) {
    enum class Owner { NONE, PENDING, BOX, PICTURE }

    var owner = Owner.NONE
        private set
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** When a still finger becomes the box's: the caller ticks then. */
    val holdAt: Long? get() = if (owner == Owner.PENDING) downAt + HOLD_MS else null

    /** The first finger: on the box it waits, anywhere else it is the picture's at once. */
    fun down(onBox: Boolean, x: Float, y: Float, time: Long): Owner {
        downX = x; downY = y; downAt = time
        owner = if (onBox) Owner.PENDING else Owner.PICTURE
        return owner
    }

    /** The first finger moved to [x], [y]. */
    fun moved(x: Float, y: Float, time: Long): Owner {
        // Still until now (every earlier move stayed within the slop): past the hold it is the box's however far
        // this move goes, even when the timer runs late.
        if (owner == Owner.PENDING) owner = when {
            time >= downAt + HOLD_MS -> Owner.BOX
            hypot(x - downX, y - downY) > slop -> Owner.PICTURE
            else -> Owner.PENDING
        }
        return owner
    }

    /** Another finger came down: a waiting touch was a pinch or a two-finger gesture, the picture's. */
    fun otherFinger(): Owner {
        if (owner == Owner.PENDING) owner = Owner.PICTURE
        return owner
    }

    /** The hold's time came with the finger still: the box's. */
    fun tick(time: Long): Owner {
        if (owner == Owner.PENDING && time >= downAt + HOLD_MS) owner = Owner.BOX
        return owner
    }

    /** The last finger lifted: a waiting touch was a tap. The owner it ended with; the next touch starts afresh. */
    fun up(time: Long): Owner {
        if (owner == Owner.PENDING) owner = if (time >= downAt + HOLD_MS) Owner.BOX else Owner.PICTURE
        return owner.also { owner = Owner.NONE }
    }

    /** The touch was cancelled. */
    fun reset() { owner = Owner.NONE }

    companion object {
        /** How long a finger stays still on the box before it picks the box up. */
        const val HOLD_MS = 400L
    }
}
