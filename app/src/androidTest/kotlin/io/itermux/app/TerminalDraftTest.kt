package io.itermux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalDraftTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun pasteKeepsExistingDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "草稿验证", address = "127.0.0.1", port = 1, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        val previousClip = clipboard.primaryClip
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithText("会话  1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("会话  1").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.onNodeWithTag("restore-dock").performClick()
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").performTextInput("保留草稿")
            assertEquals("保留草稿", session.draft)
            compose.onNodeWithTag("draft-left").performClick()
            compose.onNodeWithText("收起", substring = false).performClick()

            clipboard.setPrimaryClip(ClipData.newPlainText("测试剪贴板", "加上粘贴"))
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("粘贴").performClick()
            compose.onNodeWithTag("prompt-editor").assertTextContains("保留草加上粘贴稿")
            assertEquals("保留草加上粘贴稿", session.draft)
        } finally {
            if (previousClip != null) clipboard.setPrimaryClip(previousClip)
            else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
    }
}
