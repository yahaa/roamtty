package io.itermux.app.ui

import io.itermux.app.session.AgentStatus
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
}
