package io.itermux.app.ui

import io.itermux.app.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class HostReachabilityTest {
    @Test fun tcpFallbackTimesAListeningPort() {
        ServerSocket(0).use { server -> assertNotNull(tcpRoundTrip("127.0.0.1", server.localPort, 1_000)) }
    }

    @Test fun tcpFallbackFailsOnClosedPortOrUnknownHost() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(tcpRoundTrip("127.0.0.1", port, 1_000))
        assertNull(tcpRoundTrip("host.invalid", 22, 1_000))
    }

    @Test fun sshPortDecidesOnlyWhenPingGetsNoAnswer() {
        var tcpTried = false
        assertEquals(HostReachability.Online(12), probeWithFallback(ping = { 12 }, tcp = { tcpTried = true; 40 }))
        assertFalse(tcpTried)
        assertEquals(HostReachability.Online(40), probeWithFallback(ping = { null }, tcp = { 40 }))
        assertEquals(HostReachability.Offline, probeWithFallback(ping = { null }, tcp = { null }))
    }

    @Test fun echoRequestCarriesTypeAndSequence() {
        val v4 = icmpEchoRequest(v6 = false, sequence = 0x1234)
        assertEquals(8, v4[0].toInt())
        assertEquals(0x12, v4[6].toInt())
        assertEquals(0x34, v4[7].toInt())
        assertEquals(128, icmpEchoRequest(v6 = true, sequence = 1)[0].toInt() and 0xFF)
    }

    @Test fun onlyMatchingEchoRepliesCount() {
        val reply = icmpEchoRequest(v6 = false, sequence = 0xBEEF).also { it[0] = 0 }
        assertTrue(isIcmpEchoReply(reply, reply.size, v6 = false, sequence = 0xBEEF))
        assertFalse(isIcmpEchoReply(reply, reply.size, v6 = false, sequence = 0xBEEE))
        assertFalse(isIcmpEchoReply(reply, 4, v6 = false, sequence = 0xBEEF))
        assertFalse(isIcmpEchoReply(reply, reply.size, v6 = true, sequence = 0xBEEF))
        val reply6 = icmpEchoRequest(v6 = true, sequence = 7).also { it[0] = 129.toByte() }
        assertTrue(isIcmpEchoReply(reply6, reply6.size, v6 = true, sequence = 7))
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
