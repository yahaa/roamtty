package io.itermux.app

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.SessionRow
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A session row shows its agent and host, and its menu closes the session. */
@RunWith(AndroidJUnit4::class)
class SessionRowActionsUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun showsTheAgentAndClosesFromTheRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "详情主机", address = "127.0.0.1", port = 1, username = "test")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        withContext(Dispatchers.Main) { session.rename("Grok · roamtty"); session.launchedAgent.value = CodingAgent.GROK }
        val closed = mutableListOf<TerminalConnection>()
        try {
            compose.setContent {
                MaterialTheme(colorScheme = DarkColors) {
                    SessionRow(session, onClick = {}, onClose = { closed += session }, onForget = {})
                }
            }
            // An agent's session shows the agent's mark and its folder; the host stays, the transport does not.
            compose.onNodeWithTag("agent-icon-grok", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true).assertTextEquals("roamtty")
            compose.onNodeWithTag("session-meta", useUnmergedTree = true).assertTextEquals("详情主机")

            compose.onNodeWithTag("home-session-menu-${session.id}").performClick()
            compose.onNodeWithTag("home-session-rename-${session.id}").assertDoesNotExist()
            compose.onNodeWithTag("home-session-close-${session.id}").performClick()
            compose.onNodeWithText("关闭连接").performClick()
            assertEquals(listOf(session), closed)
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }
}
