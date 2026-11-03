package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetAddressTest {
    private fun target(address: String): Target {
        requireValidHostAddress(address)
        return Target(address, 22, "dev", AuthMethod.PASSWORD, SessionMode.TMUX)
    }

    @Test fun legacyMalformedSavedHostsCanLoadForRepair() {
        assertEquals("server..example", Target("server..example", 22, "dev", AuthMethod.PASSWORD, SessionMode.TMUX).hostname)
    }

    @Test fun rejectsMalformedHostnamesAndAddressesBeforeConnecting() {
        listOf("server..example", "server..", ".server", "-server.example", "server-.example", "server!", "host:22",
            "[server]", "[::1", "::1]", "1.2.3.999", "1.2.3", "1:2:3", "1::2::3", "fe80::1%",
            "192.0.2.1::", "1:192.0.2.1::", "ssh://server", "user@server", "server/path", "server name").forEach { address ->
            assertThrows(address, IllegalArgumentException::class.java) { target(address) }
        }
    }

    @Test fun acceptsOfflineNamesAndLiteralIpAddressesWithoutDns() {
        listOf("dev", "server.example", "EXAMPLE.COM.", "my-server.local", "bücher.example", "192.168.1.42",
            "::1", "[::1]", "2001:db8::12", "fe80::1%wlan0", "[fe80::1%3]", "::ffff:192.0.2.1",
            "1:2:3:4:5:6:7:8").forEach { address ->
            assertEquals(address.removeSurrounding("[", "]"), target(address).hostname)
        }
    }
}
