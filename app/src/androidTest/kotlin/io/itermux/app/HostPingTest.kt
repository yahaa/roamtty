package io.itermux.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.app.data.Host
import io.itermux.app.ui.HostReachability
import io.itermux.app.ui.probeHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real ICMP ping socket; port 1 is closed, so only ICMP can report these as online. */
@RunWith(AndroidJUnit4::class)
class HostPingTest {
    @Test fun loopbackAnswersPing() = runBlocking {
        val v4 = probeHost(Host(name = "lo", address = "127.0.0.1", port = 1, username = "u"), 2_000)
        assertTrue("$v4", v4 is HostReachability.Online && v4.latencyMs != null)
        val v6 = probeHost(Host(name = "lo6", address = "::1", port = 1, username = "u"), 2_000)
        assertTrue("$v6", v6 is HostReachability.Online)
    }

    @Test fun unknownHostIsOffline() = runBlocking {
        assertEquals(HostReachability.Offline, probeHost(Host(name = "x", address = "host.invalid", port = 1, username = "u"), 1_000))
    }
}
