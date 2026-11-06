package io.itermux.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.ui.DesktopPanel
import io.itermux.app.ui.DesktopRail
import io.itermux.app.ui.SessionRowTrailing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** English labels are longer than the Chinese ones the layouts were drawn around. */
@RunWith(AndroidJUnit4::class)
class EnglishLabelsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val saved = AppLocale.language

    @Before fun english() { AppLocale.set(context, AppLanguage.ENGLISH) }
    @After fun restore() { AppLocale.set(context, saved) }

    @Test fun landscapeRailShowsWholeLabels() {
        compose.setContent { Box(Modifier.fillMaxHeight()) { DesktopRail(true, DesktopPanel.NONE, {}, {}, {}) } }
        for (label in listOf("All windows", "Show desktop")) {
            val results = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
            assertFalse("$label is cut off", results.single().didOverflowHeight)
            assertEquals(label, 2, results.single().lineCount)
        }
    }

    @Test fun actionsFitAndTagsGiveWayWhenTheRowIsNarrow() {
        compose.setContent {
            Column {
                Row(Modifier.width(150.dp).height(40.dp).testTag("row")) {
                    SessionRowTrailing(listOf("tmux", "mosh"), "t", "narrow", Modifier.weight(1f)) {
                        Box(Modifier.size(100.dp, 28.dp).testTag("action"))
                    }
                }
                Row(Modifier.width(320.dp).height(40.dp)) {
                    SessionRowTrailing(listOf("tmux", "mosh"), "t", "wide", Modifier.weight(1f)) {
                        Box(Modifier.size(100.dp, 28.dp))
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("action").getUnclippedBoundsInRoot()
        assertTrue("The action keeps its width", action.width >= 99.dp)
        assertTrue("The action stays inside the row", action.right <= row.right)
        compose.onNodeWithTag("t-tag-mosh-narrow").assertIsNotDisplayed()
        // With room, both tags show; what stays in the narrow row is as wide as there, never squeezed to "tm…".
        compose.onNodeWithTag("t-tag-mosh-wide").assertIsDisplayed()
        val narrow = compose.onNodeWithTag("t-tag-tmux-narrow").assertIsDisplayed().getUnclippedBoundsInRoot()
        val wide = compose.onNodeWithTag("t-tag-tmux-wide").getUnclippedBoundsInRoot()
        assertEquals(wide.width.value, narrow.width.value, 0.5f)
    }
}
