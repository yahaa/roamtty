package io.itermux.app.ui

import io.itermux.app.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class HostReachabilityTest {
    @Test fun probeAcceptsSshIdentificationWithOptionalPreambleAndComments() {
        serving("SSH-2.0-OpenSSH_9.9\r\n") { port -> assertNotNull(sshTcpConnectTime("127.0.0.1", port, 1_000)) }
        serving("Welcome\r\nSSH-2.0-OpenSSH_for_Windows_9.5\r\n") { port -> assertNotNull(sshTcpConnectTime("127.0.0.1", port, 1_000)) }
        serving("SSH-1.99-fixture compatibility mode\n") { port -> assertNotNull(sshTcpConnectTime("127.0.0.1", port, 1_000)) }
    }

    @Test fun probeSpeaksFirstForProxiesThatWaitForClientBytes() {
        // A sniffing transparent proxy dials sshd only after the client's first bytes arrive.
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            var sent = ""
            val worker = thread {
                runCatching {
                    server.accept().use { client ->
                        sent = client.getInputStream().bufferedReader().readLine()
                        client.getOutputStream().write("SSH-2.0-OpenSSH_9.3\r\n".toByteArray())
                    }
                }
            }
            try { assertNotNull(sshTcpConnectTime("127.0.0.1", server.localPort, 1_000)) } finally { worker.join() }
            assertTrue(sent, sent.startsWith("SSH-2.0-"))
        }
    }

    @Test fun tcpLatencyExcludesDelayedSshGreeting() {
        serving("SSH-2.0-fixture\r\n", greetingDelayMs = 600) { port ->
            val start = System.nanoTime()
            val latency = sshTcpConnectTime("127.0.0.1", port, 2_000)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertNotNull(latency)
            assertTrue("Online status must wait for the greeting: $elapsedMs ms", elapsedMs >= 600)
            assertTrue("TCP latency must exclude the delayed greeting: $latency ms", latency!! < 300)
        }
    }

    @Test fun truncatedGreetingIsOfflineEvenWhenTheConnectionStaysOpen() {
        serving("SSH-") { port -> assertNull(sshTcpConnectTime("127.0.0.1", port, 1_000)) }
        serving("SSH-2.0-fixture", holdOpenMs = 500) { port ->
            assertNull(sshTcpConnectTime("127.0.0.1", port, 200))
        }
    }

    @Test fun malformedIdentificationIsOffline() {
        listOf("SSH-\r\n", "SSH-2.0-\r\n", "SSH-2.0- fixture\r\n", "SSH-2.0-fixture\u0000\r\n",
            "SSH-1.5-fixture\r\n", "SSH-2.0-fixture\rbroken\n").forEach { greeting ->
            serving(greeting) { port -> assertNull(greeting, sshTcpConnectTime("127.0.0.1", port, 1_000)) }
        }
    }

    @Test fun identificationLengthIsBounded() {
        serving("SSH-2.0-" + "a".repeat(245) + "\r\n") { port ->
            assertNotNull(sshTcpConnectTime("127.0.0.1", port, 1_000))
        }
        serving("SSH-2.0-" + "a".repeat(246) + "\r\n") { port ->
            assertNull(sshTcpConnectTime("127.0.0.1", port, 1_000))
        }
    }

    @Test fun greetingBeyondTheDeadlineIsOffline() {
        serving("SSH-2.0-fixture\r\n", greetingDelayMs = 500) { port ->
            assertNull(sshTcpConnectTime("127.0.0.1", port, 150))
        }
    }

    /** A local proxy that accepts the connect for a machine that is off: silent, or hangs up. */
    @Test fun acceptedConnectWithoutGreetingIsOffline() {
        serving(null) { port -> assertNull(sshTcpConnectTime("127.0.0.1", port, 300)) }
        serving("") { port -> assertNull(sshTcpConnectTime("127.0.0.1", port, 1_000)) }
        serving("HTTP/1.1 400 Bad Request\r\n") { port -> assertNull(sshTcpConnectTime("127.0.0.1", port, 1_000)) }
    }

    @Test fun sshPortProbeFailsOnClosedPortOrUnknownHost() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(sshTcpConnectTime("127.0.0.1", port, 1_000))
        assertNull(sshTcpConnectTime("host.invalid", 22, 1_000))
    }

    /** Accepts one connection and writes [greeting] then closes it, or holds it open silently when null. */
    private fun serving(greeting: String?, greetingDelayMs: Long = 0, holdOpenMs: Long = 0, probe: (Int) -> Unit) = ServerSocket(0).use { server ->
        server.soTimeout = 3_000
        val worker = thread {
            runCatching {
                server.accept().use { client ->
                    Thread.sleep(greetingDelayMs)
                    if (greeting == null) Thread.sleep(1_000) else client.getOutputStream().write(greeting.toByteArray())
                    Thread.sleep(holdOpenMs)
                }
            }
        }
        try { probe(server.localPort) } finally { worker.join() }
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

    @Test fun requestedProbeIsDueAtOnceAndKeepsTheLastResult() {
        var now = 1_000L
        val tracker = HostReachabilityTracker { now }
        val host = Host(id = "a", name = "a", address = "10.0.0.1", username = "root")
        tracker.requestProbe(host)
        assertEquals(null, tracker.checkedAt(host))

        tracker.record(host, HostReachability.Offline)
        tracker.record(host, HostReachability.Offline)
        assertEquals(1_000L, tracker.checkedAt(host))
        now += 5_000L
        tracker.requestProbe(host)
        assertEquals(now, tracker.dueAt(host))
        assertEquals(HostReachability.Offline, tracker.statusOf(host))
        assertEquals(1_000L, tracker.checkedAt(host))
        // The back-off count survives the request: a third failure waits two minutes.
        tracker.record(host, HostReachability.Offline)
        assertEquals(now + 120_000L, tracker.dueAt(host))
    }

    @Test fun listWaitsForAProbeAfterTheDropThatFindsTheHost() {
        assertFalse(hostBackSince(HostReachability.Offline, 2_000L, since = 1_000L))
        assertFalse(hostBackSince(HostReachability.Checking, null, since = 1_000L))
        // An online result from before the drop says nothing about now.
        assertFalse(hostBackSince(HostReachability.Online(12), 500L, since = 1_000L))
        assertTrue(hostBackSince(HostReachability.Online(12), 1_000L, since = 1_000L))
        assertTrue(hostBackSince(HostReachability.Online(null), 3_000L, since = 1_000L))
    }

    @Test fun latencyLabels() {
        assertEquals("<1 ms", latencyLabel(0))
        assertEquals("42 ms", latencyLabel(42))
    }
}
