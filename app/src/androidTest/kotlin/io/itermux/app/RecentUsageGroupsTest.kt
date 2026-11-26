package io.itermux.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.TerminalConnection
import io.itermux.app.ui.HomeSessionsList
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentUsageGroupsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sessionsKeepFixedOrderRegardlessOfAgentStateAndWhenFiltering() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItermuxApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val host = Host(name = "Groups", address = "127.0.0.1", port = 1, username = "test")
        fun status(state: AgentStatus.State) = AgentStatus(state, "claude", "", "", "", 0)
        val sessions = listOf(
            "plain" to null,
            "older" to status(AgentStatus.State.IDLE),
            "waiting" to status(AgentStatus.State.WAITING),
            "newer" to status(AgentStatus.State.IDLE),
        ).map { (id, status) -> TerminalConnection(host, SessionMode.TMUX, null, app.pins, scope, id = id, lastAgentStatus = status) }
        try {
            compose.setContent {
                MaterialTheme {
                    HomeSessionsList(sessions, {}, {})
                }
            }
            fun top(id: String) = compose.onNodeWithTag("home-session-$id").getUnclippedBoundsInRoot().top
            assertTrue("first session stays at top", top("plain") < top("older"))
            assertTrue("waiting stays in its fixed slot", top("older") < top("waiting"))
            assertTrue("later session stays below", top("waiting") < top("newer"))
            compose.onNodeWithTag("home-session-filter-done").performClick()
            compose.onNodeWithTag("home-session-waiting").assertDoesNotExist()
            compose.onNodeWithTag("home-session-plain").assertDoesNotExist()
            assertTrue("filtering preserves fixed order", top("older") < top("newer"))
        } finally { scope.cancel() }
    }
}
