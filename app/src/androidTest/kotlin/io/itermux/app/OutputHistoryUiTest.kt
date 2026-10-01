package io.itermux.app

import android.content.pm.ActivityInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
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

    @Test fun dragAndAccessibleSeekReachBothEndsWithoutExtraButtons() {
        val records = lines(List(5000) { "OUTPUT_%04d  终端历史记录".format(it) })
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(records, 13) } }
        assertVisible("OUTPUT_4999")
        compose.onNodeWithTag("output-history-live").assertDoesNotExist()
        compose.onNodeWithTag("output-history-top").assertDoesNotExist()
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("output-history-lines").performTouchInput {
            down(Offset(width - 6 * density, height - 24 * density))
            moveTo(Offset(width - 6 * density, 0f), delayMillis = 350)
            up()
        }
        assertVisible("OUTPUT_0000")
        assertEquals(0f, fraction(), 0.001f)
        compose.onNodeWithTag("output-history-lines").performTouchInput {
            down(Offset(width - 6 * density, 24 * density))
            moveTo(Offset(width - 6 * density, height.toFloat()), delayMillis = 350)
            up()
        }
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
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("output-history-lines").performTouchInput {
            down(Offset(width - 6 * density, height - 24 * density))
            moveTo(Offset(width - 6 * density, 0f), delayMillis = 350)
            up()
        }
        assertEquals(0f, fraction(), 0.001f) // The scrollbar stays at the visible edge after horizontal panning.
        compose.runOnIdle { records.value = lines(listOf("短记录", "第二行")) }
        assertVisible("短记录")
        compose.onNodeWithTag("output-history-scrollbar").assertDoesNotExist()
        compose.runOnIdle { records.value = emptyList() }
        compose.onNodeWithTag("output-history").assertIsDisplayed()
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
