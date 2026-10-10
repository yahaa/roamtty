package io.itermux.app.ui

import io.itermux.app.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class HostReachabilityTest {
    @Test fun probeTimesTheSshGreeting() {
        serving("SSH-2.0-OpenSSH_9.9\r\n") { port -> assertNotNull(sshGreetingTime("127.0.0.1", port, 1_000)) }
        serving("Welcome\r\nSSH-2.0-OpenSSH_for_Windows_9.5\r\n") { port -> assertNotNull(sshGreetingTime("127.0.0.1", port, 1_000)) }
    }

    /** A local proxy that accepts the connect for a machine that is off: silent, or hangs up. */
    @Test fun acceptedConnectWithoutGreetingIsOffline() {
        serving(null) { port -> assertNull(sshGreetingTime("127.0.0.1", port, 300)) }
        serving("") { port -> assertNull(sshGreetingTime("127.0.0.1", port, 1_000)) }
        serving("HTTP/1.1 400 Bad Request\r\n") { port -> assertNull(sshGreetingTime("127.0.0.1", port, 1_000)) }
    }

    @Test fun sshPortProbeFailsOnClosedPortOrUnknownHost() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(sshGreetingTime("127.0.0.1", port, 1_000))
        assertNull(sshGreetingTime("host.invalid", 22, 1_000))
    }

    /** Accepts one connection and writes [greeting] then closes it, or holds it open silently when null. */
    private fun serving(greeting: String?, probe: (Int) -> Unit) = ServerSocket(0).use { server ->
        val worker = thread {
            runCatching {
                server.accept().use { client ->
                    if (greeting == null) Thread.sleep(1_000) else client.getOutputStream().write(greeting.toByteArray())
                }
            }
        }
        probe(server.localPort)
        worker.join()
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
        assertEquals(HostReachability.Checking, tracker.statusOf(host))
        assertTrue(tracker.dueAt(host) <= now)

        tracker.record(host, HostReachability.Offline)
        tracker.record(host, HostReachability.Offline)
        assertEquals(HostReachability.Offline, tracker.statusOf(host))
        assertEquals(now + 60_000L, tracker.dueAt(host))

        now += 10_000L
        tracker.record(host, HostReachability.Online(23))
        assertEquals(HostReachability.Online(23), tracker.statusOf(host))
        assertEquals(now + 60_000L, tracker.dueAt(host))

        val edited = host.copy(port = 2222)
        assertEquals(HostReachability.Checking, tracker.statusOf(edited))
        assertTrue(tracker.dueAt(edited) <= now)
    }

    @Test fun latencyLabels() {
        assertEquals("<1 ms", latencyLabel(0))
        assertEquals("42 ms", latencyLabel(42))
    }
}
