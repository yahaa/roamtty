package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.net.SocketException
import java.util.concurrent.TimeUnit

/** Real SSH handshakes over loopback. Skips when Python 3/paramiko is unavailable. */
class ReattachIntegrationTest {
    private fun scenario(mode: String, verify: suspend (JschSession, Job, Channel<String>) -> Unit) = runBlocking {
        val script = File(System.getProperty("itermux.root"), "ssh-core/src/test/resources/reattach-server.py")
        val process = try { ProcessBuilder("python3", "-u", script.path, mode).redirectError(ProcessBuilder.Redirect.INHERIT).start() }
        catch (_: java.io.IOException) { assumeTrue("Python 3 is unavailable", false); return@runBlocking }
        try {
            val line = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readLine() }
            assumeTrue(line.orEmpty(), line?.startsWith("SKIP:") != true)
            val port = requireNotNull(line?.toIntOrNull()) { "SSH fixture failed to report its port: $line" }
            val ssh = JschSession(Target("127.0.0.1", port, "test", AuthMethod.PASSWORD, SessionMode.TMUX),
                Credentials("fixture-only".toByteArray()), MemoryPins(), { listOf("trust") },
                connectTimeoutMs = 700, remoteSessionName = "itermux-reattach-regression")
            val output = Channel<String>(Channel.UNLIMITED)
            val worker = launch { ssh.connect { output.send(it.toString(Charsets.UTF_8)) } }
            try {
                awaitReady(output)
                assertEquals(Phase.CONNECTED, ssh.state.value.phase)
                assertTrue(ssh.write("finish\r".toByteArray()))
                verify(ssh, worker, output)
            } finally {
                ssh.disconnect()
                withTimeout(5_000) { worker.join() }
                output.close()
            }
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private suspend fun awaitReady(output: Channel<String>) = withTimeout(15_000) {
        val received = StringBuilder()
        val ready = "READY:${Tmux.attachCommand("itermux-reattach-regression")}\r\n"
        while (!received.contains(ready)) {
            received.append(output.receive())
        }
    }

    private fun exitsWithoutReattachment(mode: String) = scenario(mode) { ssh, worker, output ->
        withTimeout(5_000) { worker.join() }
        assertEquals(Phase.DISCONNECTED, ssh.state.value.phase)
        assertFalse(ssh.write("must-not-send".toByteArray()))
        assertTrue("Normal exit must not start another tmux command", output.tryReceive().isFailure)
    }

    @Test fun successfulRemoteExitEndsConnection() = exitsWithoutReattachment("exit")
    @Test fun failedRemoteCommandEndsConnection() = exitsWithoutReattachment("exit-error")
    @Test fun eofBeforeExitStatusEndsConnection() = exitsWithoutReattachment("exit-delayed")
    // Channel EOF with no exit-status while SSH stays up is TRANSPORT_ENDED (reattachable for TMUX),
    // not REMOTE_EXIT — bare ssh.isConnected must not classify as a clean remote exit.
    @Test fun channelCloseWithoutExitStatusOnLiveTransportReattaches() = recovers("exit-no-status")

    private fun recovers(mode: String) = scenario(mode) { ssh, worker, output ->
        withTimeout(3_000) { ssh.state.first { it.phase == Phase.CONNECTING } }
        assertFalse("Input during an outage must not be queued", ssh.write("must-not-replay".toByteArray()))
        awaitReady(output)
        assertEquals(Phase.CONNECTED, ssh.state.value.phase)
        assertTrue(worker.isActive)
    }

    // A socket that died during sleep looks connected until keepalives run out; the probe on
    // returning to the app must notice at once and reattach tmux.
    @Test fun unansweredProbeReattachesFrozenConnection() = scenario("frozen") { ssh, worker, output ->
        delay(300)
        assertEquals(Phase.CONNECTED, ssh.state.value.phase)
        withTimeout(5_000) { ssh.verifyAlive(timeoutMs = 700) }
        withTimeout(3_000) { ssh.state.first { it.phase == Phase.CONNECTING } }
        awaitReady(output)
        assertEquals(Phase.CONNECTED, ssh.state.value.phase)
        assertTrue(worker.isActive)
        // A live connection passes the probe untouched.
        ssh.verifyAlive(timeoutMs = 2_000)
        assertEquals(Phase.CONNECTED, ssh.state.value.phase)
    }

    // A server that refuses extra channels is still answering; the probe must not tear down its PTY.
    @Test fun refusedProbeKeepsLiveConnection() = scenario("reject-probe") { ssh, worker, output ->
        delay(300)
        withTimeout(5_000) { ssh.verifyAlive(timeoutMs = 2_000) }
        delay(500)
        assertEquals(Phase.CONNECTED, ssh.state.value.phase)
        assertTrue(worker.isActive)
        assertTrue("A refused probe must not reattach", output.tryReceive().isFailure)
    }

    @Test fun delayedRefusalStillProvesTheServerAnswered() = scenario("reject-probe-delayed") { ssh, worker, output ->
        delay(300)
        ssh.verifyAlive(timeoutMs = 2_000)
        assertEquals(Phase.CONNECTED, ssh.state.value.phase)
        assertTrue(worker.isActive)
        assertTrue(output.tryReceive().isFailure)
    }

    @Test fun immediateSocketWriteFailureClosesTheFrozenTransport() = scenario("frozen") { ssh, _, output ->
        delay(300)
        // Fault only the actual JSch socket writer, leaving its reader and connected flag live.
        val sessionField = JschSession::class.java.getDeclaredField("session").apply { isAccessible = true }
        val transport = requireNotNull(sessionField.get(ssh))
        val io = transport.javaClass.getDeclaredField("io").apply { isAccessible = true }.get(transport)
        io.javaClass.getDeclaredField("out").apply { isAccessible = true }.set(io, object : OutputStream() {
            override fun write(byte: Int) { throw SocketException("Network is unreachable") }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { throw SocketException("Network is unreachable") }
        })
        ssh.verifyAlive(timeoutMs = 2_000)
        assertFalse((transport as com.jcraft.jsch.Session).isConnected)
        withTimeout(3_000) { ssh.state.first { it.phase == Phase.CONNECTING } }
        awaitReady(output)
        assertEquals(Phase.CONNECTED, ssh.state.value.phase)
    }

    @Test fun refusedFirstReconnectDoesNotExhaustRetries() = recovers("refused")
    @Test fun handshakeTimeoutDoesNotExhaustRetries() = recovers("timeout")
    @Test fun handshakeEofDoesNotExhaustRetries() = recovers("handshake-eof")

    private fun stops(mode: String, phase: Phase) = scenario(mode) { ssh, worker, _ ->
        withTimeout(5_000) { worker.join() }
        assertEquals(phase, ssh.state.value.phase)
    }

    @Test fun rejectedCredentialsStopReattachment() = stops("auth", Phase.AUTH_FAILED)
    @Test fun changedHostKeyStopsReattachment() = stops("host-key", Phase.HOST_KEY_CHANGED)
    @Test fun missingTmuxStopsReattachment() = stops("missing-tmux", Phase.TMUX_MISSING)

    @Test fun cancelDuringBackoffStopsReattachment() = scenario("refused") { ssh, worker, _ ->
        withTimeout(3_000) { ssh.state.first { it.phase == Phase.CONNECTING } }
        ssh.disconnect()
        worker.cancelAndJoin()
        assertEquals(Phase.DISCONNECTED, ssh.state.value.phase)
    }
}
