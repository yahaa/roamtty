package io.itermux.app

import android.graphics.Bitmap
import android.app.Instrumentation
import android.content.Intent
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalGesturesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var terminal: TerminalView
    private val input = StringBuilder()
    private var doubleTaps = 0
    private var tripleTaps = 0
    private var swipeLefts = 0
    private var swipeRights = 0
    private val browserIntents = mutableListOf<Intent>()
    private var browserMonitor: Instrumentation.ActivityMonitor? = null

    private fun monitorBrowser() {
        browserMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                browserIntents.add(Intent(intent))
                return Instrumentation.ActivityResult(0, null)
            }
        }.also { InstrumentationRegistry.getInstrumentation().addMonitor(it) }
    }

    @After fun removeBrowserMonitor() {
        browserMonitor?.let { InstrumentationRegistry.getInstrumentation().removeMonitor(it) }
    }
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
                            onDoubleTap = { doubleTaps++ }, onTripleTap = { tripleTaps++ },
                            onSwipeLeft = { swipeLefts++; it.sendInputControlKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT) },
                            onSwipeRight = { swipeRights++; it.sendInputControlKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) },
                            onSwipeHoldLeft = { it.sendInputText("\u0001") }, onSwipeHoldRight = { it.sendInputText("\u0005") }))
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

    @Test fun singleTapOpensTheWholeWrappedUrlInThePhoneBrowser() {
        terminalContent()
        monitorBrowser()
        val url = "https://example.test/" + "a".repeat(emulator.mColumns) + "?x=1&y=2#part"
        compose.runOnIdle { feed("中文 $url\r\n$ ") }
        val point = androidx.compose.ui.geometry.Offset(3.5f * terminal.mRenderer.fontWidth, rowY(1))
        compose.onNodeWithTag("gesture-terminal").performTouchInput { click(point) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        compose.runOnIdle {
            assertEquals(1, browserIntents.size)
            assertEquals(url, browserIntents.single().dataString)
            assertNull(browserIntents.single().selector)
            assertNotNull("The URL is directed to an available browser", browserIntents.single().`package`)
            assertEquals("Opening a link must not send terminal input", "", input.toString())
        }
    }

    @Test fun eitherRowOfATuiWrappedPrLinkOpensTheCompleteAddress() {
        terminalContent()
        monitorBrowser()
        compose.runOnIdle {
            feed("  PR: \u001b[4m#127\u001b[0m (\u001b[4mhttps://github.com/yahaa/\u001b[0m\r\n" +
                "  \u001b[4mroamtty/pull/127\u001b[0m)\r\n$ ")
            assertFalse("The TUI inserts a hard line break", emulator.screen.getLineWrap(0))
        }
        for ((column, row) in listOf(18 to 0, 5 to 1)) {
            val point = androidx.compose.ui.geometry.Offset((column + 0.5f) * terminal.mRenderer.fontWidth, rowY(row))
            compose.onNodeWithTag("gesture-terminal").performTouchInput { click(point) }
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        }
        compose.runOnIdle {
            assertEquals(listOf("https://github.com/yahaa/roamtty/pull/127", "https://github.com/yahaa/roamtty/pull/127"),
                browserIntents.map { it.dataString })
            assertEquals("", input.toString())
        }
    }

    @Test fun eitherRowOfABareHardWrappedUrlOpensTheCompleteAddress() {
        terminalContent()
        monitorBrowser()
        val cols = emulator.mColumns
        val head = "https://github.com/yahaa/roamtty/pu"
        val url = "$head" + "ll/135"
        // Wrap like a TUI whose width is a few cells narrower than the terminal.
        val pad = "x".repeat(cols - head.length - 3 - 6)
        compose.runOnIdle {
            feed("  $pad $head\r\n  ll/135\r\nnext line\r\n$ ")
            assertFalse("The TUI inserts a hard line break", emulator.screen.getLineWrap(0))
        }
        val tapped = listOf((2 + pad.length + 1 + 8) to 0, 3 to 1)
        for ((column, row) in tapped) {
            val point = androidx.compose.ui.geometry.Offset((column + 0.5f) * terminal.mRenderer.fontWidth, rowY(row))
            compose.onNodeWithTag("gesture-terminal").performTouchInput { click(point) }
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        }
        val prose = androidx.compose.ui.geometry.Offset(2.5f * terminal.mRenderer.fontWidth, rowY(2))
        compose.onNodeWithTag("gesture-terminal").performTouchInput { click(prose) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        compose.runOnIdle {
            assertEquals(listOf(url, url), browserIntents.map { it.dataString })
            assertEquals("", input.toString())
        }
    }

    @Test fun agyLoginUrlOpensWithoutTheRuleLineBelowIt() {
        terminalContent()
        monitorBrowser()
        val cols = emulator.mColumns
        val url = "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&state=WB2HSL6uETB0Q72wThjkCQ"
        val rows = url.chunked(cols - 1)
        compose.runOnIdle {
            feed("\r\n" + rows.joinToString("") { "\u001b[4m$it\u001b[0m\r\n" } + "\u2500".repeat(cols - 1) + "\r\n$ ")
        }
        for (row in listOf(1, rows.size)) {
            val point = androidx.compose.ui.geometry.Offset(2.5f * terminal.mRenderer.fontWidth, rowY(row))
            compose.onNodeWithTag("gesture-terminal").performTouchInput { click(point) }
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        }
        compose.runOnIdle {
            assertEquals(listOf(url, url), browserIntents.map { it.dataString })
            assertEquals("", input.toString())
        }
    }

    @Test fun urlApostropheOpensTheWholeAddressButItsEnclosingQuoteDoesNot() {
        terminalContent()
        monitorBrowser()
        val url = "https://example.test/Guns_N'_Roses"
        compose.runOnIdle { feed("  '$url'\r\n$ ") }
        for (column in listOf(3 + url.indexOf('\''), 3 + url.length)) {
            val point = androidx.compose.ui.geometry.Offset((column + 0.5f) * terminal.mRenderer.fontWidth, rowY(0))
            compose.onNodeWithTag("gesture-terminal").performTouchInput { click(point) }
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        }
        compose.runOnIdle {
            assertEquals(listOf(url), browserIntents.map { it.dataString })
            assertEquals("", input.toString())
        }
    }

    @Test fun urlDoubleTripleAndLongPressKeepTheirTerminalGestures() {
        terminalContent()
        monitorBrowser()
        compose.runOnIdle { feed("https://example.test\r\n$ ") }
        val point = androidx.compose.ui.geometry.Offset(5.5f * terminal.mRenderer.fontWidth, rowY(0))
        compose.onNodeWithTag("gesture-terminal").performTouchInput { doubleClick(point) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        compose.runOnIdle { assertEquals(1, doubleTaps); assertTrue(browserIntents.isEmpty()) }
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(point); advanceEventTime(80); click(point); advanceEventTime(80); click(point)
        }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        compose.runOnIdle { assertEquals(1, tripleTaps); assertTrue(browserIntents.isEmpty()) }
        touch(MotionEvent.ACTION_DOWN, point.x, point.y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        touch(MotionEvent.ACTION_UP, point.x, point.y)
        compose.runOnIdle { assertTrue("Long-press still selects the URL", terminal.isSelectingText); assertTrue(browserIntents.isEmpty()) }
        compose.onNodeWithTag("gesture-terminal").performTouchInput { click(point) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        compose.runOnIdle { assertFalse(terminal.isSelectingText); assertTrue(browserIntents.isEmpty()) }
    }

    @Test fun outputDuringSingleTapConfirmationCannotChangeTheOpenedUrl() {
        terminalContent()
        monitorBrowser()
        compose.runOnIdle { feed("https://original.test\r\n$ ") }
        val x = 5.5f * terminal.mRenderer.fontWidth
        // Feed synchronously after touch-up, before GestureDetector's confirmation timer runs.
        compose.runOnIdle {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, x, rowY(0), 0)
                terminal.onTouchEvent(event)
                event.recycle()
            }
            feed("\u001b[H\u001b[2Jhttps://replacement.test\r\n$ ")
        }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        compose.runOnIdle { assertEquals("https://original.test", browserIntents.single().dataString) }
    }

    @Test fun heldThirdTapDoesNotOpenUrlBeforePasting() {
        terminalContent()
        monitorBrowser()
        compose.runOnIdle { feed("https://example.test\r\n$ ") }
        val x = 5.5f * terminal.mRenderer.fontWidth
        repeat(2) {
            touch(MotionEvent.ACTION_DOWN, x, rowY(0))
            touch(MotionEvent.ACTION_UP, x, rowY(0))
            SystemClock.sleep(80)
        }
        touch(MotionEvent.ACTION_DOWN, x, rowY(0))
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 30)
        touch(MotionEvent.ACTION_UP, x, rowY(0))
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        compose.runOnIdle {
            assertEquals(1, tripleTaps)
            assertEquals(0, doubleTaps)
            assertTrue("A third tap held past single-tap confirmation must only paste", browserIntents.isEmpty())
        }
    }

    @Test fun sidewaysSwipesSendArrowsButOtherSwipesDoNot() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput { swipeLeft(startX = centerRight.x - 20, endX = centerLeft.x + 20, durationMillis = 150) }
        compose.runOnIdle { assertEquals(1, swipeLefts); assertEquals("\u001b[D", input.toString()); input.clear() }
        compose.onNodeWithTag("gesture-terminal").performTouchInput { swipe(center, center.copy(x = center.x - 120, y = center.y + 60), 900) }
        compose.runOnIdle { assertEquals("A slow, slightly slanted swipe still counts", 2, swipeLefts); assertEquals("\u001b[D", input.toString()); input.clear() }
        compose.onNodeWithTag("gesture-terminal").performTouchInput { swipeRight(startX = centerLeft.x + 20, endX = centerRight.x - 20, durationMillis = 150) }
        compose.runOnIdle { assertEquals(1, swipeRights); assertEquals("\u001b[C", input.toString()); input.clear() }
        compose.onNodeWithTag("gesture-terminal").performTouchInput { swipeUp(durationMillis = 150) }
        compose.onNodeWithTag("gesture-terminal").performTouchInput { swipe(center, center.copy(x = center.x - 300, y = center.y + 300), 150) }
        compose.onNodeWithTag("gesture-terminal").performTouchInput { swipe(center, center.copy(x = center.x - 20), 150) }
        compose.runOnIdle { assertEquals("Vertical, diagonal and short swipes send nothing", 2 to 1, swipeLefts to swipeRights) }
    }

    @Test fun anyEventMouseSwipesSendWheelsOnMainScreen() = assertAnyEventMouseSwipes(alternateScreen = false)

    @Test fun anyEventMouseSwipesSendWheelsOnAlternateScreen() = assertAnyEventMouseSwipes(alternateScreen = true)

    @Test fun reversingAnyEventMouseFlingStopsThePreviousDirection() {
        terminalContent()
        compose.runOnIdle { feed("\u001b[?1003h\u001b[?1006h"); input.clear() }
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            swipe(center.copy(y = height * 0.25f), center.copy(y = height * 0.65f), 120)
        }
        compose.runOnIdle {
            assertTrue(Regex("(?:\u001b\\[<64;\\d+;\\d+M)+").matches(input))
        }
        // Dispatch directly so an Espresso idle wait cannot turn the reversing finger-down into a long press.
        val (x, height) = compose.runOnIdle { terminal.width / 2f to terminal.height.toFloat() }
        touch(MotionEvent.ACTION_DOWN, x, height * 0.65f)
        compose.runOnIdle { input.clear() }
        for (step in 1..12) {
            SystemClock.sleep(10)
            touch(MotionEvent.ACTION_MOVE, x, height * (0.65f - step / 30f))
        }
        touch(MotionEvent.ACTION_UP, x, height * 0.25f)
        val afterDrag = compose.runOnIdle {
            assertTrue("Reversing must send only WheelDown: $input", Regex("(?:\u001b\\[<65;\\d+;\\d+M)+").matches(input))
            input.length
        }
        compose.waitUntil(timeoutMillis = 2_000) { input.length > afterDrag }
        compose.runOnIdle {
            assertTrue("Inertia must keep the new direction: $input", Regex("(?:\u001b\\[<65;\\d+;\\d+M)+").matches(input))
            assertEquals(0, terminal.topRow)
        }
    }

    private fun assertAnyEventMouseSwipes(alternateScreen: Boolean) {
        terminalContent()
        compose.runOnIdle {
            feed((1..150).joinToString("\r\n") { "history $it" })
            if (alternateScreen) feed("\u001b[?1049h")
            feed("\u001b[?1000l\u001b[?1002l\u001b[?1003h\u001b[?1006h")
            input.clear()
        }
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            down(center.copy(y = height * 0.25f))
            for (step in 1..12) moveTo(center.copy(y = height * (0.25f + step / 30f)), delayMillis = 50)
            advanceEventTime(150)
            up()
        }
        compose.runOnIdle {
            assertTrue("Downward touch must send only WheelUp, not arrows or a click: $input",
                Regex("(?:\u001b\\[<64;\\d+;\\d+M)+").matches(input))
            assertEquals("Remote scrolling must leave the local viewport at the bottom", 0, terminal.topRow)
            input.clear()
        }
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            down(center.copy(y = height * 0.65f))
            for (step in 1..12) moveTo(center.copy(y = height * (0.65f - step / 30f)), delayMillis = 50)
            advanceEventTime(150)
            up()
        }
        compose.runOnIdle {
            assertTrue("Upward touch must send only WheelDown, not arrows or a click: $input",
                Regex("(?:\u001b\\[<65;\\d+;\\d+M)+").matches(input))
            assertEquals(0, terminal.topRow)
            assertEquals(0 to 0, swipeLefts to swipeRights)
        }
    }

    /** Drags from the centre by (dx, dy) in small steps, rests the finger for [holdMs] of real time, then lifts. */
    private fun dragAndHold(dx: Float, dy: Float, holdMs: Long) {
        val (cx, cy) = compose.runOnIdle { terminal.width / 2f to terminal.height / 2f }
        touch(MotionEvent.ACTION_DOWN, cx, cy)
        for (step in 1..10) { touch(MotionEvent.ACTION_MOVE, cx + dx * step / 10, cy + dy * step / 10); SystemClock.sleep(10) }
        SystemClock.sleep(holdMs)
        touch(MotionEvent.ACTION_UP, cx + dx, cy + dy)
        compose.waitForIdle()
    }

    @Test fun swipeAndHoldJumpsToLineStartOrEndInsteadOfArrows() {
        terminalContent()
        val hold = ViewConfiguration.getLongPressTimeout() + 200L
        dragAndHold(-200f, 10f, hold)
        compose.runOnIdle { assertEquals("Left swipe-and-hold sends Ctrl+A only", "\u0001", input.toString()); assertEquals(0, swipeLefts); input.clear() }
        dragAndHold(200f, -10f, hold)
        compose.runOnIdle { assertEquals("Right swipe-and-hold sends Ctrl+E", "\u0005", input.toString()); input.clear() }
        dragAndHold(-200f, 10f, 0)
        compose.runOnIdle { assertEquals("Lifting without resting still sends Left", "\u001b[D", input.toString()); input.clear() }
        dragAndHold(200f, 0f, 0)
        compose.runOnIdle { assertEquals("Lifting without resting still sends Right", "\u001b[C", input.toString()); input.clear() }
        dragAndHold(-30f, 0f, hold)
        compose.runOnIdle { assertEquals("A short hold sends nothing", "", input.toString()) }
        dragAndHold(-20f, 200f, hold)
        compose.runOnIdle {
            assertFalse("A vertical drag-and-hold never jumps", input.contains('\u0001') || input.contains('\u0005'))
        }
    }

    @Test fun completedTripleTapFiresOnce() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput { doubleClick() }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.runOnIdle { assertEquals("A double tap must not paste", 0, tripleTaps) }
        compose.onNodeWithTag("gesture-terminal").performTouchInput { tripleClick() }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.runOnIdle {
            assertEquals(1, tripleTaps)
            assertEquals("A triple tap must not also send the double-tap Tab", 1, doubleTaps)
        }
    }

    @Test fun completedDoubleTapFiresOnceAfterTheTripleTapWindow() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals("Tab waits until a third tap is ruled out", 0, doubleTaps) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.runOnIdle { assertEquals(1, doubleTaps); assertEquals(0, tripleTaps) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.onNodeWithTag("gesture-terminal").performTouchInput { click() }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.runOnIdle { assertEquals("A single tap must not send Tab", 1, doubleTaps) }
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); down(center); moveTo(center.copy(y = center.y + 100)); up()
        }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.runOnIdle { assertEquals("A tap followed by a drag must not send Tab", 1, doubleTaps) }
    }

    @Test fun thirdTapCancellationOrDragDoesNotTriggerPaste() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); click(); advanceEventTime(80); down(center); advanceEventTime(40); cancel()
        }
        compose.runOnIdle { assertEquals(0, tripleTaps) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); click(); advanceEventTime(80); down(center)
            moveTo(center.copy(y = center.y + 100)); up()
        }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.runOnIdle { assertEquals(0, tripleTaps); assertEquals("An aborted third tap sends nothing", 0, doubleTaps) }
    }

    @Test fun thirdTapPinchOrHoldDoesNotTriggerPaste() {
        terminalContent()
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); click(); advanceEventTime(80); down(0, center)
            down(1, center.copy(x = center.x + 100)); up(1); up(0)
        }
        compose.runOnIdle { assertEquals(0, tripleTaps) }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.onNodeWithTag("gesture-terminal").performTouchInput {
            click(); advanceEventTime(80); click(); advanceEventTime(80); down(center)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 50); up()
        }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        compose.runOnIdle { assertEquals(0, tripleTaps); assertEquals("An aborted third tap sends nothing", 0, doubleTaps) }
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
            // Only the blank placeholder, never the remote prompt or padding.
            assertEquals(connection.getTextBeforeCursor(1000, 0)!!.length, info.initialSelStart)
            assertTrue(connection.getTextBeforeCursor(1000, 0)!!.isBlank())
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
            val idle = connection.getExtractedText(ExtractedTextRequest(), 0)
            assertTrue(idle.text.isBlank())
            assertEquals(idle.text.length, idle.selectionStart)
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
