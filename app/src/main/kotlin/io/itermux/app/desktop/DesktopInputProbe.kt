package io.itermux.app.desktop

import io.itermux.desktop.GestureAction
import java.util.Locale

/**
 * What the desktop's gestures sent, for device tests that compare it with the gesture table: one
 * token per action (m move, b button, w wheel, z zoom, s swipe, f feedback, a rail action), as on iOS.
 * Off unless a test turns it on; it holds the current gesture only (a double tap's second tap and a
 * flick's inertia stay with it).
 */
object DesktopInputProbe {
    @Volatile var enabled = false
    private val tokens = ArrayList<String>()
    private var lastEnded = 0L
    /** Every token recorded, runs included: it keeps growing while inertia scrolls on. */
    @Volatile var count = 0; private set

    val log: List<String> get() = synchronized(tokens) { tokens.toList() }
    val text: String get() = log.joinToString(" ")

    fun clear() = synchronized(tokens) { tokens.clear() }

    fun gestureStarted(time: Long) { if (enabled && time - lastEnded > 500) clear() }
    fun gestureEnded(time: Long) { lastEnded = time }

    fun record(a: GestureAction) {
        if (!enabled) return
        token(when (a) {
            is GestureAction.MoveTo -> "m${a.x},${a.y}"
            is GestureAction.Button -> "b${a.button}${if (a.down) "d" else "u"}"
            is GestureAction.Wheel -> "w${a.dx},${a.dy}"
            is GestureAction.View -> "z" + String.format(Locale.ROOT, "%.2f", a.viewport.zoom)
            is GestureAction.Swipe -> "s" + a.direction.name.lowercase()
            is GestureAction.Feedback -> "f" + a.kind.name.lowercase()
        })
    }

    fun token(t: String) {
        if (!enabled) return
        synchronized(tokens) {
            count++
            // Moves, wheel steps and zooms come many at a time: one token stands for a run of them.
            if (tokens.isNotEmpty() && tokens.last()[0] == t[0] && t[0] in "mwz") tokens[tokens.size - 1] = t else tokens += t
            if (tokens.size > 60) tokens.subList(0, tokens.size - 60).clear()
        }
    }
}
