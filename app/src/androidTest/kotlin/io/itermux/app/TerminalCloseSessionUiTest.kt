package io.itermux.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.ui.TerminalToolbox
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Needs the loopback SSH fixture; pass its password with `-e fixturePassword …` when it is not the default. */
@RunWith(AndroidJUnit4::class)
class TerminalCloseSessionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun closingFromTheSessionMenuEndsTheSessionWithoutAsking() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        TerminalToolbox.autoHideMillis = 0
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val host = Host(name = "Close session", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        app.database.hosts().save(host)
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            withTimeout(15_000) { session.state.first { it.phase == Phase.CONNECTED } }
            compose.openSession(session.id)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("restore-dock").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("restore-dock").performClick()
            compose.onNodeWithTag("session-switcher").performClick()
            // Closing ends the session on the server without asking, and leaves for the sessions tab.
            compose.onNodeWithTag("close-session").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("home-tab-sessions") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("terminal-workspace").assertDoesNotExist()
            compose.waitUntil(5_000) { service.sessions.value.none { it.id == session.id } }
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
            app.database.hosts().delete(host.id)
        }
        Unit
    }
}
