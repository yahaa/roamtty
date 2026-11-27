package io.itermux.app.ui

import io.itermux.app.data.Host
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.RESTORED_MESSAGE
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.HostPin
import io.itermux.ssh.Phase
import io.itermux.ssh.PinStore
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupsTest {
    private fun status(state: AgentStatus.State) = AgentStatus(state, "claude", "", "", "", 0)

    @Test fun groupsByWhatTheAgentDoesWhileConnected() {
        assertEquals(SessionGroup.WAITING, sessionGroup(true, status(AgentStatus.State.WAITING)))
        assertEquals(SessionGroup.WORKING, sessionGroup(true, status(AgentStatus.State.WORKING)))
        assertEquals(SessionGroup.DONE, sessionGroup(true, status(AgentStatus.State.IDLE)))
        // No agent, or a status left from before the connection dropped: with the rest.
        assertEquals(SessionGroup.OTHER, sessionGroup(true, null))
        assertEquals(SessionGroup.OTHER, sessionGroup(false, status(AgentStatus.State.WAITING)))
    }

    @Test fun sessionsComingBackKeepWhatTheirAgentReportedLast() {
        val live = status(AgentStatus.State.IDLE)
        val last = status(AgentStatus.State.WORKING)
        val connected = ConnectionState(Phase.CONNECTED)
        // Live once the host has answered, even with no agent.
        assertEquals(live to true, listedAgentStatus(connected, true, live, last, hostOffline = false))
        assertEquals(null to true, listedAgentStatus(connected, true, null, last, hostOffline = false))
        // Connected, connecting or restored from the journal before the host answers: the last report.
        assertEquals(last to false, listedAgentStatus(connected, false, null, last, hostOffline = false))
        assertEquals(last to false, listedAgentStatus(ConnectionState(Phase.CONNECTING), false, null, last, hostOffline = false))
        assertEquals(last to false, listedAgentStatus(ConnectionState(Phase.DISCONNECTED, RESTORED_MESSAGE), false, null, last,
            hostOffline = false))
        // While its host is offline too, so the card keeps its group and what it showed; not once it failed.
        assertEquals(last to false, listedAgentStatus(ConnectionState(Phase.DISCONNECTED, RESTORED_MESSAGE), false, null, last,
            hostOffline = true))
        assertEquals(last to false, listedAgentStatus(ConnectionState(Phase.DISCONNECTED), false, null, last, hostOffline = true))
        assertEquals(null to false, listedAgentStatus(ConnectionState(Phase.FAILED), false, null, last, hostOffline = false))
    }

    @Test fun restoredSessionsSortWithTheLiveOnes() {
        assertEquals(0, activityRank(ConnectionState(Phase.CONNECTED)))
        assertEquals(0, activityRank(ConnectionState(Phase.CONNECTING)))
        assertEquals(0, activityRank(ConnectionState(Phase.DISCONNECTED, RESTORED_MESSAGE)))
        assertEquals(3, activityRank(ConnectionState(Phase.DISCONNECTED)))
    }

    @Test fun durationsStayShortAndUnbroken() {
        assertEquals("12s", shortDuration(12))
        assertEquals("2m54s", shortDuration(174))
        assertEquals("4m05s", shortDuration(245))
        assertEquals("1h05m", shortDuration(3900))
        assertEquals("刚刚", ago(30))
        assertEquals("3分钟前", ago(200))
        assertEquals("2小时前", ago(7300))
        assertEquals("1天前", ago(90_000))
    }

    @Test fun filterSessionsPreservingOrderKeepsFixedSessionOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val host = Host(name = "Order", address = "127.0.0.1", port = 22, username = "tester")
        val pins = object : PinStore {
            override suspend fun get(endpoint: String): HostPin? = null
            override suspend fun putIfAbsent(pin: HostPin): HostPin = pin
        }
        fun conn(id: String) = TerminalConnection(host, SessionMode.TMUX, null, pins, scope, id = id)
        val sessions = listOf(conn("s-1"), conn("s-2"), conn("s-3"), conn("s-4"))
        val groupOf = mapOf(
            "s-1" to SessionGroup.DONE,
            "s-2" to SessionGroup.WAITING,
            "s-3" to SessionGroup.OTHER,
            "s-4" to SessionGroup.DONE,
        )
        try {
            val ordered = sessionsNewestFirst(sessions)
            // Newest created sits at the top and never reorders when agent states differ.
            assertEquals(listOf("s-4", "s-3", "s-2", "s-1"),
                filterSessionsPreservingOrder(ordered, groupOf, null).map { it.id })
            // Filtering to a status preserves fixed newest-first relative order.
            assertEquals(listOf("s-4", "s-1"),
                filterSessionsPreservingOrder(ordered, groupOf, SessionGroup.DONE).map { it.id })
            assertEquals(listOf("s-2"),
                filterSessionsPreservingOrder(ordered, groupOf, SessionGroup.WAITING).map { it.id })
            assertEquals(listOf("s-3"),
                filterSessionsPreservingOrder(ordered, groupOf, SessionGroup.OTHER).map { it.id })
        } finally {
            scope.cancel()
        }
    }
}
