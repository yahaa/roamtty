package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/** Opt-in: the port tunnel over a real OpenSSH login, against the e2e fixture (scripts/e2e/fixture.sh start). */
class PortTunnelIntegrationTest {
    private val root = File(System.getProperty("itermux.root"))

    @Test fun pagesComeThroughADirectTcpipChannel(): Unit = runBlocking {
        val password = File(root, ".private/e2e-password")
        assumeTrue("e2e fixture not configured", password.exists())
        assumeTrue("e2e fixture not running", runCatching { Socket("127.0.0.1", 22258).close() }.isSuccess)
        val web = ServerSocket(0)
        val server = Thread {
            while (true) {
                val client = try { web.accept() } catch (_: Exception) { break }
                client.use {
                    it.getInputStream().bufferedReader().readLine()
                    it.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        val ssh = JschSession(Target("127.0.0.1", 22258, "roamtty_e2e", AuthMethod.PASSWORD, SessionMode.SHELL),
            Credentials(password.readText().trim().toByteArray()), MemoryPins(), { listOf("trust") })
        val job = launch(Dispatchers.IO) { ssh.connect { } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { ssh.state.first { it.phase == Phase.CONNECTED || it.phase == Phase.FAILED || it.phase == Phase.AUTH_FAILED } }.phase)
            PortTunnel("localhost", web.localPort, ssh::openSocket, preferredLocalPort = 0).use { tunnel ->
                assertTrue(withTimeout(10_000) { tunnel.listening() })
                val reply = withContext(Dispatchers.IO) { Socket("127.0.0.1", tunnel.localPort).use {
                    it.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".toByteArray())
                    it.getInputStream().readBytes().toString(Charsets.UTF_8)
                } }
                assertTrue(reply, reply.endsWith("\r\n\r\nok"))
            }
            assertTrue("login still up", ssh.state.value.phase == Phase.CONNECTED)
            val closed = ServerSocket(0).use { it.localPort }
            PortTunnel("localhost", closed, ssh::openSocket, preferredLocalPort = 0).use { assertFalse(withTimeout(10_000) { it.listening() }) }
        } finally { ssh.disconnect(); web.close(); server.interrupt(); withTimeout(5000) { job.join() } }
    }
}
