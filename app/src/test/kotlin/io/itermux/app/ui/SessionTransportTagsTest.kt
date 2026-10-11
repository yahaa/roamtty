package io.itermux.app.ui

import io.itermux.ssh.HostPlugin
import io.itermux.ssh.SessionMode
import io.itermux.ssh.TerminalTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTransportTagsTest {
    @Test fun resumableSessionNamesItsMultiplexerAndMoshWhileMoshCarriesIt() {
        assertEquals(listOf("tmux", "mosh"),
            sessionTransportTags(SessionMode.TMUX, HostPlugin.TMUX, HostPlugin.TMUX, TerminalTransport.MOSH))
        assertEquals(listOf("tmux"), sessionTransportTags(SessionMode.TMUX, HostPlugin.TMUX, HostPlugin.TMUX, TerminalTransport.SSH))
    }

    @Test fun attachedMultiplexerWinsAndTheHostsStandsInBeforeAttach() {
        assertEquals(listOf("psmux"), sessionTransportTags(SessionMode.TMUX, HostPlugin.PSMUX, HostPlugin.TMUX, null))
        assertEquals(listOf("psmux"), sessionTransportTags(SessionMode.TMUX, null, HostPlugin.PSMUX, null))
    }

    @Test fun plainShellHasNoMultiplexer() {
        assertEquals(emptyList<String>(), sessionTransportTags(SessionMode.SHELL, null, HostPlugin.TMUX, TerminalTransport.SSH))
        assertEquals(listOf("mosh"), sessionTransportTags(SessionMode.SHELL, null, HostPlugin.TMUX, TerminalTransport.MOSH))
    }
}
