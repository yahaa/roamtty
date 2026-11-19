package io.itermux.desktop

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
