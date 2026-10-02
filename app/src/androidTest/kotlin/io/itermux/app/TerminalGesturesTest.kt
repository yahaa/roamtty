package io.itermux.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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
    private var doubleTaps = 0
    private var readOnly = false
    private val emulator = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) { input.append(String(data, offset, count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }, 80, 24, 8, 16, 400, SilentTerminalClient())
    private val session = object : TerminalSession {
        override fun isReadOnly() = readOnly
        override fun getEmulator(): TerminalEmulator = this@TerminalGesturesTest.emulator
        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            this@TerminalGesturesTest.emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
        override fun write(data: String) { input.append(data) }
        override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
            if (prependEscape) input.append('\u001b')
            input.append(String(Character.toChars(codePoint)))
        }
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
                        it.setTerminalViewClient(MobileTerminalClient(it, {}, { false }, {},
                            onDoubleTap = { doubleTaps++ }))
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

    @Test fun completedDoubleTapFiresOnceAndReadOnlyHistoryNeverFires() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, doubleTaps); readOnly = true }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.onNodeWithTag("gesture-terminal").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, doubleTaps) }
    }

    @Test fun secondTapCancellationOrDragDoesNotTriggerPaste() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); down(center); advanceEventTime(40); cancel()
        }
        compose.runOnIdle { assertEquals(0, doubleTaps) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); down(center)
            moveTo(center.copy(y = center.y + 100)); up()
        }
        compose.runOnIdle { assertEquals(0, doubleTaps) }
    }

    @Test fun secondTapPinchOrHoldDoesNotTriggerPaste() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); down(0, center)
            down(1, center.copy(x = center.x + 100)); up(1); up(0)
        }
        compose.runOnIdle { assertEquals(0, doubleTaps) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); down(center)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 50); up()
        }
        compose.runOnIdle { assertEquals(0, doubleTaps) }
    }

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

    @Test fun magnifierMovesAcrossHardNewlinesAndBackWithoutRepeatingDelayedArrows() {
        terminalContent()
        compose.runOnIdle { feed("first line\r\nsecond line\r\nthird line\u001b[2;5H") }
        val x = 4 * terminal.mRenderer.fontWidth
        touch(MotionEvent.ACTION_DOWN, x, rowY(1))
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        touch(MotionEvent.ACTION_MOVE, x, rowY(0))
        touch(MotionEvent.ACTION_MOVE, x, rowY(0))
        compose.runOnIdle {
            assertTrue(terminal.isCursorDragging)
            assertEquals("\u001b[A", input.toString())
            feed("\u001b[1;5H")
            assertTrue(terminal.isCursorDragging)
        }
        touch(MotionEvent.ACTION_MOVE, x, rowY(2))
        touch(MotionEvent.ACTION_MOVE, x, rowY(2))
        compose.runOnIdle { assertEquals("\u001b[A\u001b[B\u001b[B", input.toString()) }
        touch(MotionEvent.ACTION_UP, x, rowY(2))
        compose.runOnIdle { assertFalse(terminal.isCursorDragging) }
    }

    @Test fun imeNeverUsesScreenPaddingAsEditableTextAndPreservesIntentionalSpaces() {
        terminalContent()
        compose.runOnIdle {
            feed("prompt>\u001b[60G")
            val info = EditorInfo()
            val connection = terminal.onCreateInputConnection(info)
            assertEquals(0, info.initialSelStart)
            assertEquals("", connection.getTextBeforeCursor(1000, 0).toString())
            assertEquals("", connection.getTextAfterCursor(1000, 0).toString())
            connection.setComposingText("zhong", 1)
            assertEquals("zhong", connection.getTextBeforeCursor(1000, 0).toString())
            feed("\u001b[70G")
            assertEquals("zhong", connection.getExtractedText(ExtractedTextRequest(), 0).text.toString())
            connection.setComposingText("中文", 1)
            connection.commitText("中文", 1)
            repeat(3) { connection.finishComposingText() }
            connection.commitText("  a b  ", 1)
            connection.finishComposingText()
            assertEquals("中文  a b  ", input.toString())
            assertEquals("", connection.getTextBeforeCursor(1000, 0).toString())
            assertEquals(0, connection.getExtractedText(ExtractedTextRequest(), 0).selectionStart)
        }
    }

    @Test fun wrappedInputUsesCharacterArrowsAcrossRows() {
        terminalContent()
        compose.runOnIdle { feed("x".repeat(emulator.mColumns + 6)) }
        val width = terminal.mRenderer.fontWidth
        touch(MotionEvent.ACTION_DOWN, 6 * width, rowY(1))
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        touch(MotionEvent.ACTION_MOVE, (emulator.mColumns - 2) * width, rowY(0))
        touch(MotionEvent.ACTION_MOVE, (emulator.mColumns - 2) * width, rowY(0))
        compose.runOnIdle { assertEquals("\u001b[D".repeat(8), input.toString()) }
        touch(MotionEvent.ACTION_MOVE, 2 * width, rowY(1))
        compose.runOnIdle { assertEquals("\u001b[D".repeat(8) + "\u001b[C".repeat(4), input.toString()) }
        touch(MotionEvent.ACTION_UP, 2 * width, rowY(1))
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
