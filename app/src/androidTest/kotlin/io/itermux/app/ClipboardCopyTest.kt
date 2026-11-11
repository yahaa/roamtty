package io.itermux.app

import android.content.ClipboardManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.code.MarkdownParser
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.MarkdownView
import io.itermux.app.ui.clipboardText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Copy buttons put plain text on the system clipboard, where the terminal and desktop paste read it back. */
@RunWith(AndroidJUnit4::class)
class ClipboardCopyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun codeBlockCopyReachesTheSystemClipboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(Modifier.fillMaxSize()) { MarkdownView(MarkdownParser.parse("```sh\necho copied\n```"), null) }
            }
        }
        compose.onNodeWithContentDescription("复制代码").performClick()
        compose.runOnIdle {
            assertEquals("echo copied", context.clipboardText())
            assertEquals("Roamtty", context.getSystemService(ClipboardManager::class.java).primaryClip?.description?.label)
        }
    }
}
