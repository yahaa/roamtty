package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class HostSystemIntegrationTest {
    private fun fixture(mode: String, verify: suspend (Int, File) -> Unit) = runBlocking {
        val script = File(System.getProperty("itermux.root"), "ssh-core/src/test/resources/host-system-server.py")
        val events = File.createTempFile("itermux-host-probe", ".log")
        val process = ProcessBuilder("python3", "-u", script.path, mode, events.path)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val line = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readLine() }
            assumeTrue(line.orEmpty(), line?.startsWith("SKIP:") != true)
            val port = requireNotNull(line?.toIntOrNull()) { "Fixture failed: $line" }
            withTimeout(20_000) { verify(port, events) }
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            events.delete()
        }
    }

    private fun session(port: Int, credentials: Credentials = Credentials("fixture-only".toByteArray()),
        trust: Boolean = true, mode: SessionMode = SessionMode.SHELL) =
        JschSession(Target("127.0.0.1", port, "test", AuthMethod.PASSWORD, mode), credentials,
            MemoryPins(), { if (trust) listOf("trust") else null }, connectTimeoutMs = 1000)

    private fun detects(mode: String, expected: HostSystem) = fixture(mode) { port, events ->
        val credentials = Credentials("fixture-only".toByteArray())
        // Even a host configured for tmux must be probed without creating a remote session.
        val ssh = session(port, credentials, mode = SessionMode.TMUX)
        try {
            assertEquals(expected, ssh.detectHostSystem())
            assertFalse(ssh.state.value.active)
            assertTrue(credentials.password!!.all { it == 0.toByte() })
            assertFalse(events.readText().contains("pty"))
            assertFalse(events.readText().contains("shell"))
        } finally { ssh.disconnect() }
    }

    @Test fun detectsUbuntuWithoutStartingTerminal() = detects("ubuntu", HostSystem.UBUNTU)
    @Test fun detectsWindowsWithNonPosixDefaultShell() = detects("windows", HostSystem.WINDOWS)
    @Test fun detectsMacOs() = detects("macos", HostSystem.MACOS)
    @Test fun unknownLinuxKeepsPenguin() = detects("linux", HostSystem.LINUX)
    @Test fun restrictedExecReturnsUnknown() = detects("denied", HostSystem.UNKNOWN)
    @Test fun excessiveOutputIsBounded() = detects("oversized", HostSystem.UNKNOWN)

    private fun detectsLinuxThroughShell(shell: String) = fixture("shell:$shell") { port, _ ->
        val ssh = session(port)
        try { assertTrue("OS detection through $shell", ssh.detectHostSystem().isLinux) }
        finally { ssh.disconnect() }
    }

    @Test fun detectsLinuxWithFishLoginShell() = detectsLinuxThroughShell("fish")
    @Test fun detectsLinuxWithTcshLoginShell() = detectsLinuxThroughShell("tcsh")
    @Test fun detectsLinuxWithNushellLoginShell() = detectsLinuxThroughShell("nu")

    @Test fun untrustedHostCannotRunProbe() = fixture("ubuntu") { port, events ->
        val ssh = session(port, trust = false)
        assertEquals(HostSystem.UNKNOWN, ssh.detectHostSystem())
        assertTrue(events.readText().isEmpty())
        assertEquals(Phase.DISCONNECTED, ssh.state.value.phase)
    }

    @Test fun probeCannotBypassAuthentication() = fixture("ubuntu") { port, events ->
        val ssh = session(port, Credentials("wrong-password".toByteArray()))
        assertEquals(HostSystem.UNKNOWN, ssh.detectHostSystem())
        assertTrue(events.readText().isEmpty())
        assertEquals(Phase.AUTH_FAILED, ssh.state.value.phase)
    }

    @Test fun terminalReceivesOnlyItsOwnOutputWhileMetadataUpdates() = fixture("debian") { port, _ -> coroutineScope {
        val ssh = session(port)
        val output = StringBuffer()
        val worker = launch { ssh.connect { output.append(it.toString(Charsets.UTF_8)) } }
        try {
            assertEquals(HostSystem.DEBIAN, ssh.detectedSystem.filterNotNull().first())
            withTimeout(3000) { while (!output.contains("READY")) delay(10) }
            assertTrue(ssh.write("hello-terminal".toByteArray()))
            withTimeout(3000) { while (!output.contains("hello-terminal")) delay(10) }
            assertFalse(output.contains("ITERMUX"))
            assertFalse(output.contains("ID="))
        } finally { ssh.disconnect(); worker.cancelAndJoin() }
    } }

    @Test fun slowProbeDoesNotBlockTerminalAndCanBeCancelled() = fixture("slow") { port, _ -> coroutineScope {
        val ssh = session(port)
        val output = StringBuffer()
        val worker = launch { ssh.connect { output.append(it.toString(Charsets.UTF_8)) } }
        try {
            withTimeout(3000) { while (!output.contains("READY")) delay(10) }
            assertTrue(ssh.write("still-responsive".toByteArray()))
            withTimeout(3000) { while (!output.contains("still-responsive")) delay(10) }
            assertNull(ssh.detectedSystem.value)
        } finally { ssh.disconnect(); withTimeout(3000) { worker.cancelAndJoin() } }
    } }
}
