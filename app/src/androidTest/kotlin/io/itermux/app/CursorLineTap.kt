package io.itermux.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView

/** Taps the middle of the terminal's cursor line: the only single tap that opens the keyboard. */
fun AndroidComposeTestRule<*, MainActivity>.tapCursorLine() {
    var bounds: IntArray? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        bounds = findTerminalView(activity.window.decorView)?.cursorRowBounds
    }
    val row = requireNotNull(bounds) { "Cursor line is not on screen" }
    onNodeWithTag("terminal").performTouchInput { click(Offset(centerX, (row[0] + row[1]) / 2f)) }
}

private fun findTerminalView(view: View): TerminalView? {
    if (view is TerminalView) return view
    if (view is ViewGroup) for (i in 0 until view.childCount) findTerminalView(view.getChildAt(i))?.let { return it }
    return null
}
