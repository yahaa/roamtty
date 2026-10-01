package io.itermux.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.*
import com.termux.view.TerminalView
import io.itermux.app.ui.*
import io.itermux.ssh.RemoteEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalGesturesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var terminal: TerminalView
    private val input = StringBuilder()
    private val emulator = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) { input.append(String(data, offset, count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }, 80, 24, 8, 16, 400, SilentTerminalClient())
    private val session = object : TerminalSession {
        override fun getEmulator(): TerminalEmulator = this@TerminalGesturesTest.emulator
        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            this@TerminalGesturesTest.emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
        override fun write(data: String) { input.append(data) }
        override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun reset() = Unit
    }
    private fun feed(text: String) { val bytes = text.toByteArray(); emulator.append(bytes, bytes.size); terminal.onScreenUpdated() }
    private var downTime = 0L
    private fun touch(action: Int, x: Float, y: Float) = compose.runOnIdle {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        terminal.onTouchEvent(event)
        event.recycle()
    }
    private fun terminalContent() {
        compose.setContent {
            MaterialTheme {
                AndroidView(modifier = Modifier.fillMaxSize().testTag("gesture-terminal"), factory = { context ->
                    TerminalView(context, null).also {
                        terminal = it
                        it.setTextSize(36)
                        it.setTypeface(android.graphics.Typeface.MONOSPACE)
                        it.setTerminalViewClient(MobileTerminalClient(it, {}, { false }, {}))
                        it.isFocusableInTouchMode = true
                        it.attachSession(session)
                        it.setCursorDraggingEnabled(true)
                    }
                })
            }
        }
        compose.waitForIdle()
    }
    private fun rowY(row: Int) = (row + 0.8f) * terminal.mRenderer.fontLineSpacing

    @Test fun longPressShowsLensAndDragSendsArrowsWithoutRepeatingDelayedEcho() {
        terminalContent()
        compose.runOnIdle { feed("prior output\r\n$ echo 中文abc") }
        val width = terminal.mRenderer.fontWidth
        val x = emulator.cursorCol * width
        val y = rowY(emulator.cursorRow)
        touch(MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        compose.runOnIdle {
            assertTrue(terminal.isCursorDragging)
            assertFalse(terminal.isSelectingText)
            val bitmap = Bitmap.createBitmap(terminal.width, terminal.height, Bitmap.Config.ARGB_8888)
            terminal.draw(Canvas(bitmap))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "terminal-cursor-magnifier.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        touch(MotionEvent.ACTION_MOVE, x + width * 4, y)
        compose.runOnIdle { assertEquals("", input.toString()) }
        touch(MotionEvent.ACTION_MOVE, x - width * 3, y)
        compose.runOnIdle { assertEquals("\u001b[D".repeat(3), input.toString()) }
        touch(MotionEvent.ACTION_MOVE, x - width * 3, y)
        compose.runOnIdle { assertEquals("\u001b[D".repeat(3), input.toString()) }
        touch(MotionEvent.ACTION_MOVE, x - width * 5, y)
        compose.runOnIdle { assertEquals("\u001b[D".repeat(4), input.toString()) }
        touch(MotionEvent.ACTION_MOVE, x - width * 3, y)
        compose.runOnIdle { assertTrue(input.endsWith("\u001b[C")) }
        touch(MotionEvent.ACTION_UP, x - width * 3, y)
        compose.runOnIdle { assertFalse(terminal.isCursorDragging) }
        touch(MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        touch(MotionEvent.ACTION_CANCEL, x, y)
        compose.runOnIdle { assertFalse(terminal.isCursorDragging) }
    }

    @Test fun longPressOutputStillSelectsText() {
        terminalContent()
        compose.runOnIdle { feed("copy this output\r\n$ ") }
        val x = 5 * terminal.mRenderer.fontWidth
        touch(MotionEvent.ACTION_DOWN, x, rowY(0))
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        compose.runOnIdle { assertFalse(terminal.isCursorDragging); assertTrue(terminal.isSelectingText) }
        touch(MotionEvent.ACTION_UP, x, rowY(0))
    }

    @Test fun fileMenuIsAnchoredToRowAndActionsDismiss() {
        var open by mutableStateOf(false)
        var renamed = false
        val entry = RemoteEntry("notes.txt", false, 123, 0, "-rw-r--r--")
        compose.setContent {
            MaterialTheme { Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(80.dp))
                RemoteFileRow(entry, { open = true }, { open = true }) {
                    FileActionsMenu(entry, open, false, {}, {}, { renamed = true; open = false }, {}, { open = false })
                }
            } }
        }
        val anchor = compose.onNodeWithTag("files-row-menu-notes.txt").fetchSemanticsNode()
        compose.onNodeWithTag("files-row-menu-notes.txt").performClick()
        val menu = compose.onNodeWithTag("files-actions-list").fetchSemanticsNode()
        assertTrue("Menu must stay beside its row", menu.positionOnScreen.y >= anchor.positionOnScreen.y &&
            menu.positionOnScreen.y <= anchor.positionOnScreen.y + anchor.size.height + 40)
        compose.onNodeWithTag("files-action-rename").performClick()
        compose.runOnIdle { assertTrue(renamed); assertFalse(open) }
    }
}
