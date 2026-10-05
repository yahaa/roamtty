package io.itermux.app.session

import io.itermux.app.session.AgentAlerts.Action
import io.itermux.app.session.AgentAlerts.Candidate
import io.itermux.ssh.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentAlertsTest {
    private var clock = 1_000_000_000L
    private val alerts = AgentAlerts { clock }
    private val sessions = listOf(Candidate("a", "roamtty-a"), Candidate("b", "roamtty-b"))

    private fun event(id: String, kind: String = AgentEvent.ATTENTION, tmux: String = "roamtty-b", key: String = tmux,
        ts: Long = clock / 1000) = AgentEvent(id, ts, "claude", kind, "permission", tmux, key, "/w", "Bash")

    @Test fun attentionGoesToItsTmuxSessionAndResolvedWithdrawsIt() {
        assertEquals(Action.Show("b", event("1"), alert = true), alerts.onEvent("h", event("1"), sessions, viewing = null))
        assertEquals(Action.Cancel("b"), alerts.onEvent("h", event("2", AgentEvent.RESOLVED), sessions, viewing = null))
        assertNull(alerts.onEvent("h", event("3", AgentEvent.RESOLVED), sessions, viewing = null))
    }

    @Test fun eventsOutsideTmuxGoToThePreferredSession() {
        val action = alerts.onEvent("h", event("1", tmux = "", key = "sid"), sessions, viewing = null) as Action.Show
        assertEquals("a", action.sessionId)
        assertEquals(Action.Cancel("a"), alerts.onEvent("h", event("2", AgentEvent.RESOLVED, tmux = "", key = "sid"), sessions, null))
    }

    @Test fun replayedEventsAreIgnored() {
        alerts.onEvent("h", event("1"), sessions, viewing = null)
        assertNull(alerts.onEvent("h", event("1"), sessions, viewing = null))
    }

    @Test fun staleAttentionAfterReconnectIsDropped() {
        val old = event("1", ts = (clock - AgentAlerts.REPLAY_WINDOW_MS - 1_000) / 1000)
        assertNull(alerts.onEvent("h", old, sessions, viewing = null))
    }

    @Test fun repeatedRequestsAlertOnlyOnceInFiveSeconds() {
        assertEquals(true, (alerts.onEvent("h", event("1"), sessions, null) as Action.Show).alert)
        clock += 2_000
        assertEquals(false, (alerts.onEvent("h", event("2"), sessions, null) as Action.Show).alert)
        clock += AgentAlerts.THROTTLE_MS
        assertEquals(true, (alerts.onEvent("h", event("3"), sessions, null) as Action.Show).alert)
    }

    @Test fun theSessionOnScreenGetsNoReminder() {
        assertNull(alerts.onEvent("h", event("1"), sessions, viewing = "b"))
        assertNull(alerts.onEvent("h", event("2", AgentEvent.RESOLVED), sessions, viewing = "b"))
    }

    @Test fun openingTheSessionWithdrawsItsReminder() {
        alerts.onEvent("h", event("1"), sessions, viewing = null)
        assertEquals(Action.Cancel("b"), alerts.onViewed("b"))
        assertNull(alerts.onViewed("b"))
        assertNull(alerts.onEvent("h", event("2", AgentEvent.RESOLVED), sessions, viewing = null))
    }

    @Test fun parsesEventLines() {
        val line = """{"v":1,"id":"17-42","ts":1791100000,"agent":"codex","kind":"attention","reason":"permission",""" +
            """"tmux":"","key":"s1","cwd":"/home/u","msg":"Run \"ls\"\né"}"""
        assertEquals(AgentEvent("17-42", 1791100000, "codex", "attention", "permission", "", "s1", "/home/u", "Run \"ls\"\né"),
            AgentEvent.parse(line))
        assertNull(AgentEvent.parse("{\"id\":\"1\",\"kind\":\"other\"}"))
        assertNull(AgentEvent.parse("not json"))
        assertNull(AgentEvent.parse("{\"id\":\"1\",\"kind\":\"attention\""))
        assertEquals(200, AgentEvent.parse("""{"id":"1","kind":"attention","msg":"${"x".repeat(300)}"}""")!!.msg.length)
    }
}
