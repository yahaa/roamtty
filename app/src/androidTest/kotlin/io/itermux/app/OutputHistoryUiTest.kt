package io.itermux.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
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
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.LightColors
import io.itermux.app.ui.OutputHistoryView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OutputHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun fraction() = compose.onNodeWithTag("output-history-scrollbar")
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("output-history").captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun dragAndAccessibleSeekReachBothEndsWithoutExtraButtons() {
        val lines = List(5000) { "OUTPUT_%04d  终端历史记录".format(it) }
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(lines, 13) } }
        compose.onNodeWithText(lines.last()).assertIsDisplayed()
        compose.onNodeWithTag("output-history-live").assertDoesNotExist()
        compose.onNodeWithTag("output-history-top").assertDoesNotExist()
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("output-history-scrollbar").performTouchInput {
            down(Offset(centerX, height - 24 * density))
            moveTo(Offset(centerX, 0f), delayMillis = 350)
            up()
        }
        compose.onNodeWithText(lines.first()).assertIsDisplayed()
        assertEquals(0f, fraction(), 0.001f)
        compose.onNodeWithTag("output-history-scrollbar").performTouchInput {
            down(Offset(centerX, 24 * density))
            moveTo(Offset(centerX, height.toFloat()), delayMillis = 350)
            up()
        }
        compose.onNodeWithText(lines.last()).assertIsDisplayed()
        assertEquals(1f, fraction(), 0.001f)
        compose.onNodeWithTag("output-history").assertIsDisplayed()
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()
        assertEquals(0.5f, fraction(), 0.01f)
    }

    @Test fun wrappedRowsShortAndEmptyRecordsHaveValidScrollBehavior() {
        val lines = mutableStateOf(listOf(List(300) { "很长的一条记录 $it" }.joinToString("\n")))
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { OutputHistoryView(lines.value, 20) } }
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitForIdle()
        assertEquals(1f, fraction(), 0.001f)
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitForIdle()
        assertEquals(0f, fraction(), 0.001f)
        compose.runOnIdle { lines.value = listOf("短记录", "第二行") }
        compose.onNodeWithText("短记录").assertIsDisplayed()
        compose.onNodeWithTag("output-history-scrollbar").assertDoesNotExist()
        compose.runOnIdle { lines.value = emptyList() }
        compose.onNodeWithTag("output-history").assertIsDisplayed()
        compose.onNodeWithTag("output-history-scrollbar").assertDoesNotExist()
    }

    @Test fun historyUsesTerminalTypographyAcrossThemesAndOrientations() {
        val light = mutableStateOf(false)
        val lines = List(120) { row -> when (row % 6) {
            0 -> "$ /workspace/itermux · step ${row / 6 + 1}"
            1 -> "正在检查终端历史交互…"
            2 -> "✓ 保留历史输出与阅读位置"
            3 -> "✓ 统一 Codex / Grok 的浏览方式"
            4 -> "Result: all checks passed."
            else -> ""
        } }
        val content: @Composable () -> Unit = { MaterialTheme(colorScheme = if (light.value) LightColors else DarkColors) {
            OutputHistoryView(lines, 13)
        } }
        compose.setContent(content)
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        capture("history-ui-dark-portrait")
        compose.runOnIdle { light.value = true }
        capture("history-ui-light-portrait")
        val portraitActivity = compose.activity
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8000) {
            var ready = false
            compose.activityRule.scenario.onActivity {
                ready = it !== portraitActivity && it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            ready
        }
        // This bare component host has no onCreate content; MainActivity restoration is tested separately.
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        assertTrue(compose.onNodeWithTag("output-history").fetchSemanticsNode().boundsInRoot.let { it.width > it.height })
        capture("history-ui-light-landscape")
        compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitForIdle()
        assertEquals(1f, fraction(), 0.001f)
    }
}
