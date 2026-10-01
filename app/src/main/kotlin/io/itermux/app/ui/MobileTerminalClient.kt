package io.itermux.app.ui

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.view.SoftwareKeyboardControllerCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

internal class MobileTerminalClient(
    private val view: TerminalView,
    private val onScale: (Float) -> Unit,
    private val controlArmed: () -> Boolean,
    private val consumeControl: () -> Unit,
    private val shiftArmed: () -> Boolean = { false },
    private val consumeShift: () -> Unit = {}
) : TerminalViewClient {
    override fun onScale(scale: Float): Float { if (scale < 0.9f || scale > 1.1f) onScale(scale); return 1f }
    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        SoftwareKeyboardControllerCompat(view).show()
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = view.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        val modifierKey = keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
            keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
        if (!modifierKey) {
            if (controlArmed()) consumeControl()
            if (shiftArmed()) consumeShift()
        }
        return false
    }
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = controlArmed()
    override fun readAltKey() = false
    override fun readShiftKey() = shiftArmed()
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (controlArmed()) consumeControl()
        if (shiftArmed()) consumeShift()
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
