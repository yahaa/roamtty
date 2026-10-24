package io.itermux.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.data.recentFirst
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

    @Test fun recentUseOrdersEachGroupWithoutMovingPastWaitingSessions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItermuxApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val host = Host(name = "Groups", address = "127.0.0.1", port = 1, username = "test")
        fun status(state: AgentStatus.State) = AgentStatus(state, "claude", "", "", "", 0)
        val sessions = listOf(
            "waiting" to status(AgentStatus.State.WAITING),
            "older" to status(AgentStatus.State.IDLE),
            "newer" to status(AgentStatus.State.IDLE),
            "plain" to null,
        ).map { (id, status) -> TerminalConnection(host, SessionMode.TMUX, null, app.pins, scope, id = id, lastAgentStatus = status) }
        try {
            compose.setContent {
                MaterialTheme {
                    HomeSessionsList(sessions.recentFirst(listOf("plain", "newer", "older", "waiting")) { it.id }, {}, {})
                }
            }
            fun top(id: String) = compose.onNodeWithTag("home-session-$id").getUnclippedBoundsInRoot().top
            assertTrue("waiting still leads", top("waiting") < top("newer"))
            assertTrue("most recently used completed session leads its group", top("newer") < top("older"))
            assertTrue("plain terminals keep their group", top("older") < top("plain"))
            compose.onNodeWithTag("home-session-filter-done").performClick()
            compose.onNodeWithTag("home-session-waiting").assertDoesNotExist()
            compose.onNodeWithTag("home-session-plain").assertDoesNotExist()
            assertTrue("filtering preserves recency", top("newer") < top("older"))
        } finally { scope.cancel() }
    }
}
