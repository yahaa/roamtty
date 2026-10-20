package io.itermux.app.ui

import io.itermux.app.data.Host
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.StartDirectory
import io.itermux.ssh.SessionMode
import org.junit.Assert.*
import org.junit.Test

class SessionRequestsTest {
    private val host = Host(name = "dev", address = "10.0.0.2", username = "me", mode = SessionMode.TMUX.name)

    @Test fun terminalLaunchChangesIntoTheDirectory() {
        val request = launchRequest(host, "~/work", agent = null)
        assertEquals(SessionMode.TMUX, request.mode)
        assertEquals("", request.title)
        assertEquals(StartDirectory.command("~/work"), request.startupCommand)
        assertFalse(request.agentHooks)
        assertNull(request.agent)
    }

    @Test fun agentLaunchStartsTheAgentAndNamesTheSession() {
        val agent = AgentLaunch(CodingAgent.entries.first(), "~/work")
        val request = launchRequest(host, "~/work", agent)
        assertEquals(agent.title, request.title)
        assertEquals(agent.commandLine, request.startupCommand)
        assertEquals(agent.reportsStatus, request.agentHooks)
        assertEquals(agent.agent, request.agent)
        assertNull(request.reuseSessionId)
    }

    @Test fun endpointKeyFollowsAddressEdits() {
        assertEquals(host.endpointKey, host.copy(name = "renamed").endpointKey)
        assertNotEquals(host.endpointKey, host.copy(address = "10.0.0.3").endpointKey)
        assertNotEquals(host.endpointKey, host.copy(port = 2222).endpointKey)
    }
}
