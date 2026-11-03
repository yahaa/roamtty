package io.itermux.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/** Exercises the actual notice -> home route, without an SSH fixture. */
class ExternalFeedbackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun closingAFailedConnectionNoticeKeepsTheSessionForRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItermuxApp
        val host = Host(name = "Notice close regression", address = "127.0.0.1", port = 1, username = "test", mode = "SHELL")
        app.database.hosts().save(host)
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        try {
            withTimeout(30_000) { session.state.first { it.phase == Phase.FAILED } }
            compose.openSession(session.id)
            compose.onNodeWithTag("terminal-connection-notice").assertIsDisplayed()
            compose.onNodeWithTag("connection-notice-close").performClick()
            compose.onNodeWithTag("terminal-workspace").assertDoesNotExist()
            compose.onNodeWithTag("home-tab-sessions").assertIsSelected()
            compose.runOnIdle { assertSame(session, service.sessions.value.first { it.id == session.id }) }
            compose.onNodeWithTag("home-session-${session.id}").assertExists()
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
            app.database.hosts().delete(host.id)
        }
    }
}
