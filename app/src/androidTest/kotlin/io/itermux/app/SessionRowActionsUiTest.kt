package io.itermux.app

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A session row shows its agent and host, and its long-press menu closes the session without asking. */
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
            // An agent's session shows the agent's mark and badge, its folder as the title and its host's chip beside it;
            // not the transport, and no folder line while the pane has not said where it is.
            compose.onNodeWithTag("agent-icon-grok", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true).assertTextEquals("roamtty")
            compose.onNodeWithTag("home-session-host-${session.id}", useUnmergedTree = true).assertTextEquals("详情主机")
            compose.onNodeWithTag("home-session-location-${session.id}", useUnmergedTree = true).assertDoesNotExist()

            compose.onNodeWithTag("home-session-${session.id}").performTouchInput { longClick() }
            compose.onNodeWithTag("home-session-rename-${session.id}").assertDoesNotExist()
            compose.onNodeWithTag("home-session-close-${session.id}").performClick()
            assertEquals(listOf(session), closed)
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }

    /**
     * A tmux session whose host cannot be reached cannot be closed (closing logs in to end it), so its menu also
     * offers to force delete it: after a confirmation the card goes without a login.
     */
    @Test fun forceDeletesATmuxSessionThatCannotBeReached() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "离线主机", address = "127.0.0.1", port = 1, username = "test")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.TMUX, Credentials("test-only".toByteArray())) }
        try {
            withTimeout(20_000) { session.state.first { !it.active } }
            assertTrue(service.sessions.value.any { it.id == session.id })
            compose.setContent {
                MaterialTheme(colorScheme = DarkColors) {
                    SessionRow(session, onClick = {}, onClose = {}, onForget = { service.forget(session.id) }, hostOffline = true)
                }
            }
            compose.onNodeWithTag("home-session-${session.id}").performTouchInput { longClick() }
            compose.onNodeWithTag("home-session-close-${session.id}").assertExists()
            compose.onNodeWithTag("home-session-forget-${session.id}").performClick()
            assertTrue(service.sessions.value.any { it.id == session.id })
            compose.onNodeWithTag("home-session-forget-confirm-${session.id}").performClick()
            compose.waitForIdle()
            assertTrue(service.sessions.value.none { it.id == session.id })
        } finally {
            withContext(Dispatchers.Main) { if (service.sessions.value.any { it.id == session.id }) service.close(session.id); service.forget(session.id) }
        }
        Unit
    }

    /** A connected-looking plain session offers no force delete. */
    @Test fun aShellSessionHasNoForceDelete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "详情主机", address = "127.0.0.1", port = 1, username = "test")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        try {
            compose.setContent { MaterialTheme(colorScheme = DarkColors) { SessionRow(session, onClick = {}, onClose = {}) } }
            compose.onNodeWithTag("home-session-${session.id}").performTouchInput { longClick() }
            compose.onNodeWithTag("home-session-close-${session.id}").assertExists()
            compose.onNodeWithTag("home-session-forget-${session.id}").assertDoesNotExist()
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }

    /** A long title stays inside its row, short of the row's edge. */
    @Test fun aLongTitleStaysInsideTheRow() = runBlocking {
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
            val row = compose.onNodeWithTag("home-session-${session.id}").getBoundsInRoot()
            assertTrue("title ${title.right} runs past the row's padding ${row.right}", title.right < row.right - 8.dp)
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
