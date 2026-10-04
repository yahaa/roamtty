package io.itermux.app.ui

import io.itermux.app.data.Host
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class HostReachabilityTest {
    @Test fun listeningPortIsOnline() = runBlocking {
        ServerSocket(0).use { server -> assertTrue(probeHost("127.0.0.1", server.localPort, 1_000)) }
    }

    @Test fun closedPortIsOffline() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(probeHost("127.0.0.1", port, 1_000))
    }

    @Test fun unresolvableAddressIsOffline() = runBlocking {
        assertFalse(probeHost("host.invalid", 22, 1_000))
    }

    @Test fun offlineHostsBackOffToFiveMinutes() {
        assertEquals(60_000L, probeInterval(online = true, failures = 0))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
            (1..6).map { probeInterval(online = false, failures = it) })
    }

    @Test fun trackerKeepsResultsUntilDueAndResetsOnEditedTarget() {
        var now = 1_000L
        val tracker = HostReachabilityTracker { now }
        val host = Host(id = "a", name = "a", address = "10.0.0.1", username = "root")
        assertEquals(HostReachability.CHECKING, tracker.statusOf(host))
        assertTrue(tracker.dueAt(host) <= now)

        tracker.record(host, online = false)
        tracker.record(host, online = false)
        assertEquals(HostReachability.OFFLINE, tracker.statusOf(host))
        assertEquals(now + 60_000L, tracker.dueAt(host))

        now += 10_000L
        tracker.record(host, online = true)
        assertEquals(HostReachability.ONLINE, tracker.statusOf(host))
        assertEquals(now + 60_000L, tracker.dueAt(host))

        val edited = host.copy(port = 2222)
        assertEquals(HostReachability.CHECKING, tracker.statusOf(edited))
        assertTrue(tracker.dueAt(edited) <= now)
    }
}
