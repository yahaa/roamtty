package io.itermux.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
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
class SessionTitleUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun renameFromSessionListAlsoUpdatesTerminalSwitcher() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(Host(name = "测试主机", address = "127.0.0.1", port = 1, username = "test"),
                SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        try {
            compose.waitUntil(8_000) {
                runCatching { compose.onAllNodesWithTag("sessions-tab").fetchSemanticsNodes().isNotEmpty() }
                    .getOrDefault(false)
            }
            compose.onNodeWithText("会话  1").performClick()
            compose.onNodeWithTag("rename-session-${session.id}").performClick()
            compose.onNodeWithTag("session-title-input").performTextInput("修复登录流程")
            compose.onNodeWithText("保存").performClick()
            assertEquals("修复登录流程", session.title.value)
            compose.onNodeWithTag("session-${session.id}").assertIsDisplayed()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-session-${session.id}").assertIsDisplayed().performClick()
            compose.onNodeWithTag("restore-dock").performClick()
            compose.onNodeWithTag("session-switcher").performClick()
            compose.onNodeWithText("✓ 修复登录流程", substring = true).assertIsDisplayed()
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }
}
