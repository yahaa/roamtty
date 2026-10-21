package io.itermux.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every row of the plan's §4.3 button table. */
class DesktopStateTest {
    private val now = 10L * DesktopProbe.CACHE_MS
    private fun derive(enabled: Boolean = true, online: Boolean = true, connected: Boolean = true, os: String = "LINUX",
        supported: Boolean = true, probe: DesktopProbe? = DesktopProbe.YES, at: Long? = now - 1000,
        phase: DesktopPhase = DesktopPhase.Idle) = DesktopButton.derive(enabled, online, connected, os, supported, probe, at, now, phase)

    @Test fun hiddenRows() {
        assertEquals(DesktopButton.HIDDEN, derive(online = false))
        assertEquals(DesktopButton.HIDDEN, derive(connected = false))
        assertEquals(DesktopButton.HIDDEN, derive(probe = DesktopProbe.NO))
        assertEquals(DesktopButton.HIDDEN, derive(enabled = false))
        assertEquals(DesktopButton.HIDDEN, derive(supported = false))
        // Linux waits for a probe; a stale probe counts as none.
        assertEquals(DesktopButton.HIDDEN, derive(probe = null, at = null))
        assertEquals(DesktopButton.HIDDEN, derive(at = now - DesktopProbe.CACHE_MS - 1))
    }

    @Test fun openableRows() {
        assertEquals(DesktopButton.OPENABLE, derive())
        assertEquals(DesktopButton.OPENABLE, derive(os = "MACOS", probe = null, at = null))
        assertEquals(DesktopButton.OPENABLE, derive(os = "WINDOWS", probe = null, at = null))
    }

    @Test fun liveRows() {
        assertEquals(DesktopButton.CONNECTING, derive(phase = DesktopPhase.Connecting(DesktopPhase.Step.START)))
        assertEquals(DesktopButton.NEEDS_ACTION, derive(phase = DesktopPhase.NeedsAction(listOf("not-logged-in"))))
        assertEquals(DesktopButton.NEEDS_ACTION, derive(probe = DesktopProbe.NOLOGIN))
        assertEquals(DesktopButton.CONNECTED, derive(phase = DesktopPhase.Connected()))
        assertEquals(DesktopButton.CONNECTED, derive(phase = DesktopPhase.Connected(paused = true)))
        assertEquals(DesktopButton.POOR, derive(phase = DesktopPhase.Connected(poor = true)))
        assertEquals(DesktopButton.RECOVERING, derive(phase = DesktopPhase.Recovering(0)))
        // An open desktop keeps its button even after its session card's terminal dropped.
        assertEquals(DesktopButton.CONNECTED, derive(connected = false, phase = DesktopPhase.Connected()))
    }
}
