package io.itermux.desktop

import kotlin.math.hypot

/** A rectangle in pixels, for [PipLayout]. */
data class PipRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun intersects(o: PipRect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** The four places a small window rests: the corners of the room it floats in. */
enum class PipAnchor(val top: Boolean, val end: Boolean) {
    TOP_START(true, false), TOP_END(true, true), BOTTOM_START(false, false), BOTTOM_END(false, true);

    companion object {
        fun of(top: Boolean, end: Boolean) = entries.first { it.top == top && it.end == end }
        fun parse(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/**
 * Where the small window (the other half of a host shown over the terminal or the desktop) goes: its size from
 * the fraction of the screen the user pinched it to, its corner, the corner it moves to when it would cover
 * something that must stay visible (the cursor row, the dock), and whether a drag ends on the close target.
 */
object PipLayout {
    const val MIN_FRACTION = 0.4f
    const val MAX_FRACTION = 1f

    /**
     * The window's size: [fraction] of [reference] wide (the screen's short side), at [aspect] (width / height),
     * kept inside [room]. A [stretch] window (the terminal, whose text has no aspect) keeps its width when the room
     * is too low and only gets shorter; a picture shrinks both ways so it keeps its aspect.
     */
    fun size(room: PipRect, reference: Float, fraction: Float, aspect: Float, stretch: Boolean = false): Pair<Float, Float> {
        var w = (reference * fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)).coerceAtMost(room.width).coerceAtLeast(1f)
        var h = w / aspect.coerceAtLeast(0.1f)
        if (h > room.height) {
            h = room.height.coerceAtLeast(1f)
            if (!stretch) w = h * aspect
        }
        return w to h
    }

    /** A [w]×[h] window resting in [anchor]'s corner of [room]. */
    fun place(room: PipRect, w: Float, h: Float, anchor: PipAnchor): PipRect {
        val left = if (anchor.end) room.right - w else room.left
        val top = if (anchor.top) room.top else room.bottom - h
        return PipRect(left, top, left + w, top + h)
    }

    /** The corner a window let go of with its centre at [cx], [cy] settles in. */
    fun nearest(room: PipRect, cx: Float, cy: Float): PipAnchor =
        PipAnchor.of(top = cy < room.centerY, end = cx >= room.centerX)

    /**
     * [anchor], unless the window there would cover one of [avoid]: then the first corner that covers none: the other
     * side up or down first (a covered cursor row spans the width, so only that clears it), then across, then
     * diagonally. When every corner covers something, [anchor] stays.
     */
    fun clear(room: PipRect, w: Float, h: Float, anchor: PipAnchor, avoid: List<PipRect>): PipAnchor {
        if (avoid.isEmpty()) return anchor
        val order = listOf(anchor, PipAnchor.of(!anchor.top, anchor.end), PipAnchor.of(anchor.top, !anchor.end), PipAnchor.of(!anchor.top, !anchor.end))
        return order.firstOrNull { a -> place(room, w, h, a).let { r -> avoid.none(r::intersects) } } ?: anchor
    }

    /** Whether a window dragged with its centre at [cx], [cy] is over the close target centred at [tx], [ty]. */
    fun overClose(cx: Float, cy: Float, tx: Float, ty: Float, radius: Float) = hypot(cx - tx, cy - ty) <= radius

    /** The fraction a pinch by [zoom] from [start] gives, within [MIN_FRACTION] and [MAX_FRACTION]. */
    fun pinched(start: Float, zoom: Float) = (start * zoom).coerceIn(MIN_FRACTION, MAX_FRACTION)
}
