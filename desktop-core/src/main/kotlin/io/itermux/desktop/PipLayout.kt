package io.itermux.desktop

import kotlin.math.hypot

/** A rectangle in pixels, for [PipLayout]. */
data class PipRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/**
 * Where the small window (a host's desktop over its terminal) goes and how big it is. Its size is a fraction of the
 * screen it was dragged or pinched to; its place is wherever it was dragged, kept as fractions of the room it can
 * move in, so it stays put in the same spot when that room changes (the keyboard opens, the phone turns).
 */
object PipLayout {
    const val MIN_FRACTION = 0.3f
    const val MAX_FRACTION = 1f

    /**
     * The window's size: [fraction] of [reference] wide at [aspect] (width / height), kept inside [room]: a room too
     * low makes it smaller, keeping its aspect.
     */
    fun size(room: PipRect, reference: Float, fraction: Float, aspect: Float): Pair<Float, Float> {
        val a = aspect.coerceAtLeast(0.1f)
        var w = (reference * fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)).coerceAtMost(room.width).coerceAtLeast(1f)
        var h = w / a
        if (h > room.height) { h = room.height.coerceAtLeast(1f); w = h * a }
        return w to h
    }

    /** A [w]×[h] window at [fx], [fy] of the room it can move in: 0, 0 the top left corner, 1, 1 the bottom right. */
    fun place(room: PipRect, w: Float, h: Float, fx: Float, fy: Float): PipRect {
        val left = room.left + (room.width - w).coerceAtLeast(0f) * fx.coerceIn(0f, 1f)
        val top = room.top + (room.height - h).coerceAtLeast(0f) * fy.coerceIn(0f, 1f)
        return PipRect(left, top, left + w, top + h)
    }

    /** The fractions [place] takes for a [w]×[h] window dropped with its top left at [left], [top] (kept inside). */
    fun fractions(room: PipRect, w: Float, h: Float, left: Float, top: Float): Pair<Float, Float> {
        val freeX = room.width - w
        val freeY = room.height - h
        return (if (freeX > 0f) ((left - room.left) / freeX).coerceIn(0f, 1f) else 0f) to
            (if (freeY > 0f) ((top - room.top) / freeY).coerceIn(0f, 1f) else 0f)
    }

    /** Whether the resize handle goes on the window's left: the side with more room to grow into (the right on a tie). */
    fun handleOnLeft(room: PipRect, window: PipRect) = window.left - room.left > room.right - window.right + 1f

    /**
     * The width a drag of the resize handle by [dx], [dy] gives a [width] window at [aspect]: outwards (away from the
     * window, right or left as [onLeft] says, and down) grows it, both directions counting.
     */
    fun resized(width: Float, dx: Float, dy: Float, onLeft: Boolean, aspect: Float): Float =
        width + ((if (onLeft) -dx else dx) + dy * aspect.coerceAtLeast(0.1f)) / 2

    /** Whether a window dragged with its centre at [cx], [cy] is over the close target centred at [tx], [ty]. */
    fun overClose(cx: Float, cy: Float, tx: Float, ty: Float, radius: Float) = hypot(cx - tx, cy - ty) <= radius

    /** The fraction a pinch by [zoom] from [start] gives, within [MIN_FRACTION] and [MAX_FRACTION]. */
    fun pinched(start: Float, zoom: Float) = (start * zoom).coerceIn(MIN_FRACTION, MAX_FRACTION)
}
