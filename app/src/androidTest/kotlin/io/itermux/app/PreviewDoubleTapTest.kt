package io.itermux.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.code.Languages
import io.itermux.code.SyntaxHighlighter
import io.itermux.app.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The preview's fullscreen toggle must see double taps through selectable, scrollable code. */
@RunWith(AndroidJUnit4::class)
class PreviewDoubleTapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun doubleTapTogglesButTapsAndScrollsDoNot() {
        var toggles = 0
        val document = SyntaxHighlighter.highlight((1..200).joinToString("\n") { "val line$it = $it" }, Languages.detect("a.kt"))
        compose.setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(Modifier.fillMaxSize()) {
                    CodeViewer(document, false, 13f, rememberLazyListState(), rememberScrollState(), emptyList(), 0,
                        Modifier.fillMaxSize().observeDoubleTap { toggles++ })
                }
            }
        }
        val code = compose.onNodeWithTag("file-preview-text")
        code.performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(0, toggles) }
        code.performTouchInput { swipeUp() }
        code.performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, toggles) }
        code.performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.mainClock.advanceTimeBy(1_000)
        code.performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(2, toggles) }
    }
}
