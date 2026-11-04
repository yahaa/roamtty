package io.itermux.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class PortTunnelTest {
    /** Stands in for the SSH channel: a plain socket to [host]:[port] on this machine. */
    private val direct: suspend (String, Int) -> RemoteSocket = { host, port ->
        val socket = Socket(if (host == "localhost") "127.0.0.1" else host, port)
        object : RemoteSocket {
            override val input: InputStream = socket.getInputStream()
            override val output: OutputStream = socket.getOutputStream()
            override fun close() = socket.close()
        }
    }

    /** Answers each connection with an HTTP response naming what was asked, then closes it. */
    private fun webServer(): ServerSocket = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            while (true) {
                val client = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    client.use {
                        val request = it.getInputStream().bufferedReader().readLine().orEmpty()
                        val body = "got $request"
                        it.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                    }
                }
            }
        }
    }

    private fun fetch(port: Int, path: String): String = Socket("127.0.0.1", port).use {
        it.getOutputStream().write("GET $path HTTP/1.0\r\n\r\n".toByteArray())
        it.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun carriesEachConnectionToTheServerPort(): Unit = runBlocking {
        webServer().use { server ->
            PortTunnel("localhost", server.localPort, direct, preferredLocalPort = 0).use { tunnel ->
                assertNotEquals(server.localPort, tunnel.localPort)
                assertTrue(tunnel.listening())
                assertTrue(fetch(tunnel.localPort, "/a").endsWith("got GET /a HTTP/1.0"))
                // A second connection gets a channel of its own.
                assertTrue(fetch(tunnel.localPort, "/b?x=1").endsWith("got GET /b?x=1 HTTP/1.0"))
            }
        }
    }

    @Test fun takesTheServerPortNumberWhenItIsFreeOnThePhone(): Unit = runBlocking {
        val free = ServerSocket(0).use { it.localPort }
        PortTunnel("localhost", free, direct).use { assertEquals(free, it.localPort) }
        // Taken: any free port.
        ServerSocket(0).use { taken ->
            PortTunnel("localhost", taken.localPort, direct).use { assertNotEquals(taken.localPort, it.localPort) }
        }
    }

    @Test fun reportsAPortNothingListensOn(): Unit = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        PortTunnel("localhost", closed, direct, preferredLocalPort = 0).use { tunnel ->
            assertFalse(tunnel.listening())
            // The page's connection ends at once instead of hanging.
            assertEquals("", fetch(tunnel.localPort, "/"))
        }
    }

    @Test fun aLostLoginIsNotMistakenForAClosedPort(): Unit = runBlocking {
        val tunnel = PortTunnel("localhost", 5173, { _, _ -> throw SshUnavailableException() }, preferredLocalPort = 0)
        tunnel.use {
            try { it.listening(); fail("expected the lost login") } catch (_: SshUnavailableException) { }
        }
        assertThrows(ConnectException::class.java) { Socket("127.0.0.1", tunnel.localPort).close() }
    }

    /** A page's connection the tunnel could not carry for want of the login is counted; a refused port is not. */
    @Test fun countsConnectionsDroppedForWantOfTheLogin(): Unit = runBlocking {
        val loginUp = java.util.concurrent.atomic.AtomicBoolean(false)
        PortTunnel("localhost", 5173, { h, p -> if (loginUp.get()) throw java.net.ConnectException("$h:$p") else throw SshUnavailableException() },
            preferredLocalPort = 0).use { tunnel ->
            suspend fun ask() = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", tunnel.localPort).use { it.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".toByteArray()); it.getInputStream().read() }
            }
            assertEquals(-1, ask())
            withTimeout(5_000) { while (tunnel.dropped < 1) delay(10) }
            loginUp.set(true)
            assertEquals(-1, ask())
            delay(200)
            assertEquals(1, tunnel.dropped)
        }
    }

    @Test fun parsesLoopbackPagesOnly() {
        assertEquals(LoopbackUrl("localhost", 5173, "/"), LoopbackUrl.parse("http://localhost:5173/"))
        assertEquals(LoopbackUrl("localhost", 5173, "/"), LoopbackUrl.parse("http://LOCALHOST:5173"))
        assertEquals(LoopbackUrl("127.0.0.1", 8000, "/docs?x=1#top"), LoopbackUrl.parse("http://127.0.0.1:8000/docs?x=1#top"))
        assertEquals(LoopbackUrl("0.0.0.0", 80, "/"), LoopbackUrl.parse("http://0.0.0.0"))
        assertEquals("::1", LoopbackUrl.parse("http://[::1]:3000/")?.target)
        assertEquals("localhost", LoopbackUrl.parse("http://0.0.0.0:3000/")?.target)
        assertNull(LoopbackUrl.parse("https://localhost:5173/"))
        assertNull(LoopbackUrl.parse("http://example.com:5173/"))
        assertNull(LoopbackUrl.parse("http://localhost.example.com/"))
        assertNull(LoopbackUrl.parse("http://localhost:99999/"))
    }

    @Test fun mapsPagesThroughTheTunnelAndBack() {
        val url = LoopbackUrl.parse("http://localhost:5173/posts/1")!!
        assertEquals("http://localhost:41000/posts/1", url.through(41000))
        assertEquals("http://127.0.0.1:41000/", LoopbackUrl.parse("http://127.0.0.1:8000")!!.through(41000))
        assertEquals("localhost:5173/posts/1", url.display)
        assertEquals("localhost:5173", LoopbackUrl("localhost", 5173, "/").display)
        assertEquals(LoopbackUrl("localhost", 5173, "/about"), LoopbackUrl.fromTunnel("http://localhost:41000/about", 41000, 5173))
        assertNull(LoopbackUrl.fromTunnel("https://example.com/", 41000, 5173))
    }

    /** The tunnel listens on 127.0.0.1 alone: other loopback addresses are asked for there, and shown as the server's. */
    @Test fun otherLoopbackAddressesGoThroughTheTunnelsOwn() {
        val url = LoopbackUrl.parse("http://127.0.0.2:3000/app")!!
        assertEquals("127.0.0.2", url.target)
        assertEquals("http://127.0.0.1:41000/app", url.through(41000))
        assertEquals(LoopbackUrl("127.0.0.2", 3000, "/app/x"), LoopbackUrl.fromTunnel("http://127.0.0.1:41000/app/x", 41000, 3000, url.host))
        assertEquals(LoopbackUrl("localhost", 3000, "/"), LoopbackUrl.fromTunnel("http://localhost:41000/", 41000, 3000, url.host))
        assertEquals(LoopbackUrl("[::1]", 3000, "/"), LoopbackUrl.fromTunnel("http://localhost:41000/", 41000, 3000, "[::1]"))
        val resolver = ListeningPort(53, "127.0.0.53", null, "", "")
        assertEquals("http://127.0.0.1:41000/", resolver.url.through(41000))
        assertEquals("127.0.0.53", resolver.url.target)
    }
}
