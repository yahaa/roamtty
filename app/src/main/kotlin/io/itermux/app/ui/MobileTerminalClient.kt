package io.itermux.app.ui

import android.content.ActivityNotFoundException
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.itermux.app.trf

internal class MobileTerminalClient(
    private val view: TerminalView,
    private val onScale: (Float) -> Unit,
    private val controlArmed: () -> Boolean,
    private val consumeControl: () -> Unit,
    private val shiftArmed: () -> Boolean = { false },
    private val consumeShift: () -> Unit = {},
    private val onDoubleTap: () -> Unit = {},
    private val onTripleTap: () -> Unit = {},
    private val onSwipeLeft: () -> Unit = {},
    private val onSwipeRight: () -> Unit = {},
    private val onSwipeHoldLeft: () -> Unit = {},
    private val onSwipeHoldRight: () -> Unit = {},
    private val onSoftKeyboardEnter: () -> Unit = {},
    /** Opens a tapped path if it names a file, else runs notFound; null leaves paths as plain text. */
    private val onOpenPath: ((path: String, line: Int, notFound: Runnable) -> Boolean)? = null
) : TerminalViewClient {
    override fun onScale(scale: Float): Float { if (scale < 0.9f || scale > 1.1f) onScale(scale); return 1f }
    /** Only a tap on the cursor's line opens the keyboard, so reading taps elsewhere keep the screen clear. */
    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        val minHeight = CURSOR_LINE_MIN_TOUCH_DP * view.resources.displayMetrics.density
        if (e != null && tapHitsCursorLine(e.y, view.cursorRowBounds, minHeight, touchableTop())) SoftwareKeyboardControllerCompat(view).show()
    }

    override fun onOpenUrl(url: String): Boolean {
        try {
            openTerminalUrl(view.context, url)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(view.context, trf("无法打开链接：%s", url), Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(view.context, trf("无法打开链接：%s", url), Toast.LENGTH_SHORT).show()
        }
        return true
    }
    override fun opensPaths() = onOpenPath != null
    override fun onOpenPath(path: String, line: Int, notFound: Runnable) = onOpenPath?.invoke(path, line, notFound) ?: false
    /** The terminal draws under the status bar, which swallows taps: the first view y a finger can reach. */
    private fun touchableTop(): Float {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return 0f
        val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
        val location = IntArray(2).also { view.getLocationInWindow(it) }
        return (top - location[1]).coerceAtLeast(0).toFloat()
    }
    override fun onDoubleTap(e: MotionEvent?) = onDoubleTap()
    override fun onTripleTap(e: MotionEvent?) = onTripleTap()
    override fun onSwipeLeft(e: MotionEvent?) = onSwipeLeft()
    override fun onSwipeRight(e: MotionEvent?) = onSwipeRight()
    override fun onSwipeHoldLeft() { onSwipeHoldLeft.invoke() }
    override fun onSwipeHoldRight() { onSwipeHoldRight.invoke() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = view.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    /** Sticky modifiers apply to one key, then reset. */
    private fun consumeModifiers() {
        if (controlArmed()) consumeControl()
        if (shiftArmed()) consumeShift()
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        val modifierKey = keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
            keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
        if (!modifierKey) consumeModifiers()
        return false
    }
    override fun onInputCleared() = consumeModifiers()
    override fun onSoftKeyboardEnter() = onSoftKeyboardEnter.invoke()
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = controlArmed()
    override fun readAltKey() = false
    override fun readShiftKey() = shiftArmed()
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        consumeModifiers()
        return false
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}

/** Phone rows are thinner than a fingertip: the rows this close to the cursor line count as hitting it. */
private const val CURSOR_LINE_SLACK_ROWS = 2

/** The cursor line's touch band never gets shorter than this, however small the font. */
private const val CURSOR_LINE_MIN_TOUCH_DP = 64f

/**
 * Whether [y] falls on the cursor line [bounds] ({top, bottom}) or within [CURSOR_LINE_SLACK_ROWS] rows of it,
 * the band widened around its centre to [minHeight].
 * A band reaching above [touchableTop] slides down to start there, so a cursor under the status bar stays tappable.
 */
internal fun tapHitsCursorLine(y: Float, bounds: IntArray?, minHeight: Float, touchableTop: Float = 0f): Boolean {
    if (bounds == null) return false
    val centre = (bounds[0] + bounds[1]) / 2f
    val rowHeight = bounds[1] - bounds[0]
    val half = maxOf(rowHeight * (CURSOR_LINE_SLACK_ROWS + 0.5f), minHeight / 2f)
    val top = maxOf(centre - half, touchableTop)
    return y >= top && y < top + 2 * half
}
