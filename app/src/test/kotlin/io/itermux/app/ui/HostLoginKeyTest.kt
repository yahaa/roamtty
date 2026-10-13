package io.itermux.app.ui

import io.itermux.app.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HostLoginKeyTest {
    private val host = Host(id = "h1", name = "Ubuntu", address = "43.163.113.102", username = "ubuntu", auth = "KEY",
        encryptedKey = byteArrayOf(1, 2, 3), keyLabel = "dragon_node_secret.pem")

    @Test fun editingTheLoginChangesTheKey() {
        assertNotEquals(host.loginKey, host.copy(username = "root").loginKey)
        assertNotEquals(host.loginKey, host.copy(address = "10.0.0.1").loginKey)
        assertNotEquals(host.loginKey, host.copy(port = 2222).loginKey)
        assertNotEquals(host.loginKey, host.copy(encryptedKey = byteArrayOf(4, 5, 6)).loginKey)
        assertNotEquals(host.loginKey, host.copy(auth = "PASSWORD", encryptedPassword = byteArrayOf(7)).loginKey)
    }

    @Test fun savingProbeResultsKeepsTheKey() {
        // A fresh copy of the same bytes (as Room reads back) must not drop the login.
        assertEquals(host.loginKey, host.copy(encryptedKey = byteArrayOf(1, 2, 3), os = "LINUX", plugins = "TMUX").loginKey)
        assertEquals(host.loginKey, host.copy(name = "TencentOS").loginKey)
    }
}
