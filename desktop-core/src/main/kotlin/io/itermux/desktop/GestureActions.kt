package io.itermux.desktop

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
