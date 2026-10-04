package io.itermux.app.ui

import kotlinx.coroutines.runBlocking
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
}
