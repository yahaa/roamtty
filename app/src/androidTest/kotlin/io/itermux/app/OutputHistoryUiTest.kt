package io.itermux.app

import android.content.pm.ActivityInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.hamcrest.Matchers.equalTo
import com.termux.terminal.*
import com.termux.view.TerminalView
import io.itermux.app.session.OutputHistoryLine
import io.itermux.app.session.OutputHistorySession
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.HistoryGrid
import io.itermux.app.ui.LightColors
import io.itermux.app.ui.OutputHistoryView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OutputHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun immersiveTerminalHost() {
        compose.activityRule.scenario.onActivity {
            WindowInsetsControllerCompat(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun emulator(columns: Int, rows: Int = 12) = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }, columns, rows, 8, 16, 400, SilentTerminalClient())

    private fun TerminalEmulator.feed(text: String) { val bytes = text.toByteArray(); append(bytes, bytes.size) }
    private fun lines(text: List<String>, columns: Int = 40): List<OutputHistoryLine> {
        val source = emulator(columns)
        return text.map {
            source.feed("\u001b[0m\u001b[H\u001b[2K$it")
            OutputHistoryLine.capture(source, 0)
        }
    }
    private fun terminal(): TerminalView = compose.activity.window.decorView.findViewWithTag("output-history-terminal")
    private fun fraction() = compose.onNodeWithTag("output-history-scrollbar")
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun assertVisible(text: String) = compose.onNodeWithTag("output-history-lines").assertTextContains(text, substring = true)
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("output-history").captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun HistoryGrid.touch(action: Int, x: Float, y: Float, start: Long, elapsed: Long = 0) {
        val event = MotionEvent.obtain(start, start + elapsed, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun HistoryGrid.drag(downwards: Boolean, driftX: Float = 0f, fling: Boolean = true) {
        val start = SystemClock.uptimeMillis()
        val x = width * 0.65f
        val y = height * if (downwards) 0.2f else 0.8f
        val distance = height * if (downwards) 0.6f else -0.6f
        touch(MotionEvent.ACTION_DOWN, x, y, start)
        for (step in 1..8) touch(MotionEvent.ACTION_MOVE,
            x + driftX * step / 8, y + distance * step / 8, start, step * 12L)
        touch(if (fling) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL,
            x + driftX, y + distance, start, 100)
    }

    @Test fun diagonalVerticalDragsKeepFollowingTheFingerOnWideHistory() {
        val records = lines(List(5000) { "ROW_$it " + "─".repeat(125) }, 140)
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records, 13) } }
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle {
            val view = terminal()
            val grid = view.parent as HistoryGrid
            val history = view.currentSession as OutputHistorySession
            for (downwards in listOf(true, false)) {
                val before = history.firstLine
                grid.drag(downwards, driftX = -grid.width * 0.25f, fling = false)
                val moved = if (downwards) before - history.firstLine else history.firstLine - before
                val expected = grid.height * 0.6f / view.mRenderer.fontLineSpacing
                assertTrue("Vertical drag lost motion: moved=$moved, expected=$expected", moved >= expected - 2)
                assertEquals("Sideways drift must not pan a vertical gesture", 0, grid.scrollX)
            }
        }
    }

    @Test fun capturedHistoryFlingsBeyondHalfScreenInBothDirections() {
        val records = lines(List(5000) { "ROW_$it" })
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records, 13) } }
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        for (downwards in listOf(true, false)) {
            var releasedAt = 0
            compose.runOnIdle {
                (terminal().parent as HistoryGrid).drag(downwards)
                releasedAt = (terminal().currentSession as OutputHistorySession).firstLine
            }
            SystemClock.sleep(450) // Native Scroller advances on Android frames, independently of the Compose clock.
            compose.runOnIdle {
                val history = terminal().currentSession as OutputHistorySession
                val moved = if (downwards) releasedAt - history.firstLine else history.firstLine - releasedAt
                assertTrue("Fling stopped too soon: moved=$moved, screen=${history.emulator.mRows}",
                    moved > history.emulator.mRows / 2)
            }
        }
    }

    @Test fun touchingDuringFlingStopsItAndAllowsImmediateReverseDrag() {
        val records = lines(List(5000) { "ROW_$it" })
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records, 13) } }
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        var heldAt = 0
        var downTime = 0L
        compose.runOnIdle {
            val grid = terminal().parent as HistoryGrid
            grid.drag(downwards = true)
            downTime = SystemClock.uptimeMillis()
            grid.touch(MotionEvent.ACTION_DOWN, grid.width / 2f, grid.height * 0.8f, downTime)
            heldAt = (terminal().currentSession as OutputHistorySession).firstLine
        }
        SystemClock.sleep(120)
        compose.runOnIdle {
            val view = terminal()
            val grid = view.parent as HistoryGrid
            val history = view.currentSession as OutputHistorySession
            assertEquals("Old fling must stop as soon as the finger touches", heldAt, history.firstLine)
            grid.touch(MotionEvent.ACTION_MOVE, grid.width / 2f, grid.height * 0.5f, downTime, 140)
            assertTrue("Reverse drag should take effect immediately", history.firstLine > heldAt)
            grid.touch(MotionEvent.ACTION_CANCEL, grid.width / 2f, grid.height * 0.5f, downTime, 160)
        }
    }

    @Test fun accessibleSeekDuringFlingKeepsTheRequestedPosition() {
        val records = lines(List(5000) { "ROW_$it" })
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records, 13) } }
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { (terminal().parent as HistoryGrid).drag(downwards = true) }
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        SystemClock.sleep(120)
        assertEquals(1f, fraction(), 0.001f)
        assertVisible("ROW_4999")
    }

    @Test fun dragAndAccessibleSeekReachBothEndsWithoutExtraButtons() {
        val records = lines(List(5000) { "OUTPUT_%04d  终端历史记录".format(it) })
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records, 13) } }
        assertVisible("OUTPUT_4999")
        compose.onNodeWithTag("output-history-live").assertDoesNotExist()
        compose.onNodeWithTag("output-history-top").assertDoesNotExist()
        // The right edge is no longer a thumb: a swipe there scrolls about one screen, like anywhere else.
        compose.onNodeWithTag("output-history-lines").performTouchInput { swipe(Offset(right - 6f, top + 24f), Offset(right - 6f, bottom - 24f), 200) }
        compose.waitForIdle()
        assertTrue(fraction() in 0.001f..0.999f)
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertVisible("OUTPUT_0000")
        assertEquals(0f, fraction(), 0.001f)
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertVisible("OUTPUT_4999")
        assertEquals(1f, fraction(), 0.001f)
        compose.onNodeWithTag("output-history").assertIsDisplayed()
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(0.5f, fraction(), 0.001f)
    }

    @Test fun wideGridPansWithoutReflowAndShortRecordsHideScrollbar() {
        val records = mutableStateOf(lines(List(100) { "╭" + "─".repeat(125) + "RIGHT_EDGE───╮" }, 140))
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records.value, 13) } }
        compose.onNodeWithTag("output-history-lines").performTouchInput { swipeLeft(startX = centerX) }
        compose.runOnIdle {
            val grid = terminal().parent as HistoryGrid
            assertTrue("Pan: viewport=${grid.width}, grid=${terminal().width}, x=${grid.scrollX}", grid.scrollX > 0)
            assertEquals(140, terminal().currentSession.emulator.mColumns)
            assertFalse(terminal().onCheckIsTextEditor())
            assertNull(terminal().onCreateInputConnection(EditorInfo()))
        }
        compose.onNodeWithTag("output-history-lines").performTouchInput { swipe(Offset(right - 6f, bottom - 24f), Offset(right - 6f, top + 24f), 200) }
        compose.waitForIdle()
        // A vertical swipe still scrolls after horizontal panning (output starts at the bottom; swipe up stays there).
        assertEquals(1f, fraction(), 0.001f)
        compose.onNodeWithTag("output-history-lines").performTouchInput { swipe(Offset(right - 6f, top + 24f), Offset(right - 6f, bottom - 24f), 200) }
        compose.waitForIdle()
        assertTrue(fraction() < 1f)
        compose.runOnIdle { records.value = lines(listOf("短记录", "第二行")) }
        assertVisible("短记录")
        compose.onNodeWithTag("output-history-scrollbar").assertDoesNotExist()
        compose.runOnIdle { records.value = emptyList() }
        compose.onNodeWithTag("output-history").assertIsDisplayed()
        compose.onNodeWithTag("output-history-scrollbar").assertDoesNotExist()
    }

    /** A reattached tmux pane records about one screen: taller than the initial 24-row viewport, not taller than the view. */
    @Test fun oneScreenOfRecordsAfterReattachDoesNotCrash() {
        val records = lines(List(30) { "REATTACH_%02d".format(it) })
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records, 13) } }
        compose.runOnIdle { assertTrue(terminal().currentSession.emulator.mRows >= records.size) }
        assertVisible("REATTACH_29")
        compose.onNodeWithTag("output-history-scrollbar").assertDoesNotExist()
    }

    @Test fun capturedBoxDrawingMatchesLiveRendererPixelForPixelAcrossThemes() {
        val source = emulator(40)
        source.feed("\u001b[?25l\u001b[1;38;5;2m╭" + "─".repeat(38) + "╮\u001b[0m\r\n" +
            "│ 中文 e\u0301 🙂   Codex / Grok\u001b[40G│\r\n" +
            "\u001b[38;2;170;100;80m│ colored prompt\u001b[0m\u001b[40G│\r\n" +
            "╰" + "─".repeat(38) + "╯\r\n" + "\u001b[7m inverse \u001b[0m  \u001b[2mdim\u001b[0m")
        val records = (0 until source.mRows).map { OutputHistoryLine.capture(source, it) }
        val light = mutableStateOf(false)
        compose.setContent { MaterialTheme(colorScheme = if (light.value) LightColors else DarkColors) {
            OutputHistoryView(records, 13, light.value)
        } }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { light.value = theme }
            compose.runOnIdle {
                val view = terminal()
                source.mColors.setLightTheme(theme)
                val expected = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.mRenderer.render(source, Canvas(expected), 0, -1, -1, -1, -1)
                view.draw(Canvas(actual))
                for ((label, bitmap) in listOf("live" to expected, "history" to actual)) {
                    File(compose.activity.getExternalFilesDir(null), "grid-$label-$theme.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                assertTrue("Captured glyph positions, borders and ANSI colors must match live output (light=$theme, cols=${view.currentSession.emulator.mColumns}, rows=${view.currentSession.emulator.mRows})", expected.sameAs(actual))
                expected.recycle(); actual.recycle()
            }
        }
        capture("history-grid-box-light")
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = clipboard.primaryClip
        try {
            val renderer = terminal().mRenderer
            compose.runOnIdle {
                val view = terminal()
                assertTrue("History view must accept selection focus", view.isFocusableInTouchMode && view.requestFocus())
            }
            // Native GestureDetector schedules its long press on the Android looper, not the Compose clock.
            onView(withTagValue(equalTo("output-history-terminal"))).perform(GeneralClickAction(Tap.LONG, { view ->
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                floatArrayOf(position[0] + 16 * renderer.fontWidth, position[1] + 1.75f * renderer.fontLineSpacing)
            }, Press.FINGER))
            compose.runOnIdle { assertTrue(terminal().isSelectingText) }
            // TerminalView restores the floating toolbar after the 300 ms gesture cooldown.
            android.os.SystemClock.sleep(400)
            onView(withText("Copy")).inRoot(isPlatformPopup()).perform(click())
            compose.runOnIdle { assertEquals("Codex", clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
        } finally {
            if (oldClip != null) clipboard.setPrimaryClip(oldClip)
            else if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
        }
    }

    @Test fun historyUsesTerminalTypographyAcrossThemesAndOrientations() {
        val light = mutableStateOf(false)
        val records = lines(List(120) { row -> when (row % 6) {
            0 -> "$ /workspace/itermux · step ${row / 6 + 1}"
            1 -> "正在检查终端历史交互…"
            2 -> "\u001b[32m✓ 保留历史输出与阅读位置"
            3 -> "✓ 统一 Codex / Grok 的浏览方式"
            4 -> "Result: all checks passed."
            else -> ""
        } })
        val content: @Composable () -> Unit = { MaterialTheme(colorScheme = if (light.value) LightColors else DarkColors) {
            OutputHistoryView(records, 13, light.value)
        } }
        compose.setContent(content)
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        capture("history-grid-dark-portrait")
        compose.runOnIdle { light.value = true }
        capture("history-grid-light-portrait")
        val portraitActivity = compose.activity
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8000) {
            var ready = false
            compose.activityRule.scenario.onActivity {
                ready = it !== portraitActivity && it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            ready
        }
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        assertTrue(compose.onNodeWithTag("output-history").fetchSemanticsNode().boundsInRoot.let { it.width > it.height })
        capture("history-grid-light-landscape")
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(1f, fraction(), 0.001f)
    }
}
