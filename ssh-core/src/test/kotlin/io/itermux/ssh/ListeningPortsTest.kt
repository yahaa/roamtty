package io.itermux.ssh

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ListeningPortsTest {
    @Test fun listsOwnProcessesFirstWithTheirFoldersUnderHome() {
        val ports = ListeningPort.parse(listOf(
            "HOME\t/home/v",
            "PORT\t0.0.0.0:22\t\t\t",
            "PORT\t[::]:22\t\t\t",
            "PORT\t127.0.0.1:8888\t42\t/home/v/ml\t/usr/bin/python3 /home/v/.local/bin/jupyter-lab --no-browser",
            "PORT\t[::1]:5173\t7\t/home/v/code/blog\tnode /home/v/code/blog/node_modules/.bin/vite",
            "PORT\t127.0.0.1:5173\t7\t/home/v/code/blog\tnode /home/v/code/blog/node_modules/.bin/vite",
            "PORT\t127.0.0.53%lo:53\t\t\t",
            "PORT\t100.64.0.2:8801\t9\t/home/v\tpython3 -m http.server 8801 --bind 100.64.0.2",
            "noise",
        ))
        assertEquals(listOf(5173, 8801, 8888, 22, 53), ports.map { it.port })
        val vite = ports[0]
        assertEquals(7, vite.pid)
        assertEquals("~/code/blog", vite.directory)
        assertEquals("node vite", vite.shortCommand)
        assertEquals("127.0.0.1", vite.address)
        assertEquals("localhost", ports[3].url.target)
        assertEquals("100.64.0.2", ports[1].url.target)
        assertEquals("~", ports[1].directory)
        assertEquals("python3 jupyter-lab --no-browser", ports[2].shortCommand)
        assertNull(ports[3].pid)
        assertEquals("127.0.0.53", ports[4].address)
    }

    @Test fun macAppPathsWithSpacesShortenToTheProgram() {
        val code = ListeningPort(1, "127.0.0.1", 1, "/Applications/Visual Studio Code.app/Contents/Frameworks/Code Helper " +
            "(Plugin).app/Contents/MacOS/Code Helper (Plugin) /Users/z/.vscode/server.js --node-ipc", "/")
        assertEquals("Code Helper (Plugin) server.js --node-ipc", code.shortCommand)
        assertEquals("node vite /srv/", ListeningPort(1, "", 1, "node /a/b/vite /srv/", "/").shortCommand)
    }

    @Test fun leavesOutEphemeralPorts() {
        val ports = ListeningPort.parse(listOf("PORT\t127.0.0.1:64725\t9\t/\tCode Helper", "PORT\t*:3000\t9\t/\tnode"))
        assertEquals(listOf(3000), ports.map { it.port })
    }

    @Test fun ipv6OnlyListenersAreReachedOverIpv6() {
        val port = ListeningPort.parse(listOf("PORT\t[::1]:3000\t5\t/srv\tnode server.js")).single()
        assertEquals("::1", port.url.target)
        assertEquals("[::1]:3000", port.url.display)
    }

    /** The script on this machine's own shell finds a port this test listens on, with this process's command line. */
    @Test fun scriptFindsAListenerHere() {
        assumeTrue(File("/proc/self/cmdline").exists())
        // Below the ephemeral range, which the list leaves out.
        val server = (20000 until 30000).shuffled().firstNotNullOf { port ->
            runCatching { ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")) }.getOrNull()
        }
        server.use {
            val process = ProcessBuilder("sh", "-c", ListeningPort.SCRIPT).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().readLines()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            assumeTrue("ss not installed", process.exitValue() == 0)
            val found = ListeningPort.parse(lines).firstOrNull { it.port == server.localPort }
            assertNotNull(lines.joinToString("\n"), found)
            assertEquals(ProcessHandle.current().pid().toInt(), found!!.pid)
            assertTrue(found.command, found.command.contains("java"))
        }
    }
}
