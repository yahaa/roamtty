package io.itermux.app

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A session row shows its agent and host, and its menu closes the session without asking. */
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
                    SessionRow(session, onClick = {}, onClose = { closed += session })
                }
            }
            // An agent's session shows the agent's mark, its folder as the title and its host beneath; not the transport.
            compose.onNodeWithTag("agent-icon-grok", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true).assertTextEquals("roamtty")
            compose.onNodeWithTag("home-session-location-${session.id}", useUnmergedTree = true).assertTextEquals("详情主机")

            compose.onNodeWithTag("home-session-menu-${session.id}").performClick()
            compose.onNodeWithTag("home-session-rename-${session.id}").assertDoesNotExist()
            compose.onNodeWithTag("home-session-close-${session.id}").performClick()
            assertEquals(listOf(session), closed)
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }

    /** A long title is cut off short of the menu, with room between its … and the menu's dots. */
    @Test fun aLongTitleStopsShortOfTheMenu() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "详情主机", address = "127.0.0.1", port = 1, username = "test")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        withContext(Dispatchers.Main) {
            session.rename("a-very-long-session-title-that-cannot-possibly-fit-on-one-line-of-the-card")
        }
        try {
            compose.setContent {
                MaterialTheme(colorScheme = DarkColors) {
                    androidx.compose.material3.Surface { SessionRow(session, onClick = {}, onClose = {}) }
                }
            }
            val title = compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true).getBoundsInRoot()
            val menu = compose.onNodeWithTag("home-session-menu-${session.id}").getBoundsInRoot()
            assertTrue("title ${title.right} runs into menu ${menu.left}", title.right < menu.left)
            compose.waitForIdle()
            Thread.sleep(600)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "session-long-title.png").outputStream()
                .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }
}
