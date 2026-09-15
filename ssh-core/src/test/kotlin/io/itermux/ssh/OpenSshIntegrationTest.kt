package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/** Opt-in real Linux OpenSSH tests using scripts/prepare-openssh-test.sh. */
class OpenSshIntegrationTest {
    private val root = File(System.getProperty("itermux.root"))
    private fun password(): String {
        val file = File(root, ".private/test-password")
        assumeTrue("Local OpenSSH test fixture not configured", file.exists())
        return file.readText().trim()
    }
    private fun target(auth: AuthMethod = AuthMethod.PASSWORD, mode: SessionMode = SessionMode.SHELL) = Target("127.0.0.1", 22228, "itermux_test", auth, mode)
    private fun credentials(auth: AuthMethod = AuthMethod.PASSWORD, key: String = "ed25519") = if (auth == AuthMethod.KEY) Credentials(privateKey = File(root, "ssh-core/src/test/resources/keys/$key").readBytes(), passphrase = "itermux-test-passphrase".toByteArray()) else Credentials(password().toByteArray())
    private suspend fun waitState(ssh: SshSession, vararg phases: Phase) = withTimeout(20_000) { ssh.state.first { it.phase in phases } }
    private fun answer(prompt: Prompt): List<String> = when (prompt) { is Prompt.VerifyHost -> listOf("trust"); is Prompt.Keyboard -> prompt.labels.map { password() } }
    private suspend fun waitText(output: StringBuffer, text: String) = withTimeout(10_000) { while (!output.toString().contains(text)) delay(30) }
    private fun authenticated(auth: AuthMethod, key: String = "ed25519") = runBlocking {
        password()
        val ssh = JschSession(target(auth), credentials(auth, key), MemoryPins(), { answer(it) })
        val output = StringBuffer()
        val job = launch { ssh.connect { output.append(it.toString(Charsets.UTF_8)) } }
        try {
            assertEquals(Phase.CONNECTED, waitState(ssh, Phase.CONNECTED, Phase.FAILED, Phase.AUTH_FAILED).phase)
            assertTrue(ssh.write("printf '\\n%s\\n' '你好，iTermux'\r".toByteArray()))
            waitText(output, "你好，iTermux")
            ssh.resize(101, 37)
            delay(200)
            ssh.write("stty size\r".toByteArray())
            waitText(output, "37 101")
        } finally { ssh.disconnect(); withTimeout(5000) { job.join() } }
        assertFalse(ssh.write("do-not-replay".toByteArray()))
    }
    @Test fun passwordAuthenticationAndPtyResize() = authenticated(AuthMethod.PASSWORD)
    @Test fun keyboardInteractiveAuthentication() = authenticated(AuthMethod.INTERACTIVE)
    @Test fun encryptedEd25519Authentication() = authenticated(AuthMethod.KEY)
    @Test fun encryptedRsaAuthentication() = authenticated(AuthMethod.KEY, "rsa")
    @Test fun wrongPasswordIsClassified() = runBlocking {
        password()
        val ssh = JschSession(target(), Credentials("wrong-password".toByteArray()), MemoryPins(), { if (it is Prompt.VerifyHost) listOf("trust") else null })
        withTimeout(20_000) { ssh.connect {} }
        assertEquals(Phase.AUTH_FAILED, ssh.state.value.phase)
    }
    @Test fun failedTmuxProbeOffersPlainSshWithoutInstallingAnything() = runBlocking {
        password()
        val ssh = JschSession(target(mode = SessionMode.TMUX).copy(port = 22229), credentials(), MemoryPins(), { answer(it) })
        withTimeout(15_000) { ssh.connect {} }
        assertEquals(Phase.TMUX_MISSING, ssh.state.value.phase)
        assertFalse(ssh.write("do-not-send".toByteArray()))
    }
    @Test fun changedFingerprintBlocksBeforeAuthentication() = runBlocking {
        password()
        val pins = MemoryPins()
        pins.putIfAbsent(HostPin(target().endpoint, "ssh-ed25519", Base64.getEncoder().encodeToString("different-key".toByteArray())))
        val ssh = JschSession(target(), credentials(), pins, { error("Changed key must not reach authentication") })
        withTimeout(10_000) { ssh.connect {} }
        assertEquals(Phase.HOST_KEY_CHANGED, ssh.state.value.phase)
        assertNotNull(ssh.state.value.newFingerprint)
    }
    @Test fun cancelDuringTrustDialogTerminatesAndDoesNotPin() = runBlocking {
        password()
        val pins = MemoryPins(); val requested = CompletableDeferred<Unit>()
        val ssh = JschSession(target(), credentials(), pins, { requested.complete(Unit); awaitCancellation() })
        val job = launch { ssh.connect {} }
        withTimeout(10_000) { requested.await() }
        ssh.disconnect()
        withTimeout(5000) { job.join() }
        assertEquals(Phase.DISCONNECTED, ssh.state.value.phase)
        assertNull(pins.get(target().endpoint))
    }
    @Test fun silentSocketTimesOut() = runBlocking {
        ServerSocket(0).use { server ->
            val socket = async(Dispatchers.IO) { server.accept() }
            val ssh = JschSession(Target("127.0.0.1", server.localPort, "test", AuthMethod.PASSWORD, SessionMode.SHELL), Credentials(), MemoryPins(), { null }, connectTimeoutMs = 500)
            withTimeout(4000) { ssh.connect {} }
            socket.await().close()
            assertEquals(Phase.FAILED, ssh.state.value.phase)
            assertTrue(ssh.state.value.message.contains("超时"))
        }
    }
    @Test fun brokenEstablishedNetworkDisconnectsAndDropsInput() = runBlocking {
        password()
        ServerSocket(0).use { listener ->
            val accepted = async(Dispatchers.IO) { listener.accept() }
            val bridge = launch(Dispatchers.IO) {
                accepted.await().use { local -> Socket("127.0.0.1", 22228).use { remote ->
                    coroutineScope {
                        val upload = launch(Dispatchers.IO) { try { runCatching { local.getInputStream().copyTo(remote.getOutputStream()) } } finally { remote.close() } }
                        runCatching { remote.getInputStream().copyTo(local.getOutputStream()) }
                        upload.cancel()
                    }
                } }
            }
            val ssh = JschSession(target().copy(port = listener.localPort), credentials(), MemoryPins(), { answer(it) })
            val job = launch { ssh.connect {} }
            try {
                assertEquals(Phase.CONNECTED, waitState(ssh, Phase.CONNECTED, Phase.FAILED).phase)
                accepted.await().close()
                withTimeout(5000) { ssh.state.first { !it.active } }
                assertFalse(ssh.write("never replay".toByteArray()))
            } finally {
                ssh.disconnect(); accepted.await().close(); bridge.cancel(); withTimeout(5000) { job.join() }
            }
        }
    }
    @Test fun tmuxReattachesAndCtrlCStopsForegroundTask() = runBlocking {
        password()
        val pins = MemoryPins()
        suspend fun connection(action: suspend (JschSession, StringBuffer) -> Unit) {
            val ssh = JschSession(target(mode = SessionMode.TMUX), credentials(), pins, { answer(it) })
            val output = StringBuffer()
            val job = launch { ssh.connect { output.append(it.toString(Charsets.UTF_8)) } }
            try {
                assertEquals(Phase.CONNECTED, waitState(ssh, Phase.CONNECTED, Phase.FAILED, Phase.TMUX_MISSING).phase)
                delay(400); action(ssh, output)
            } finally { ssh.disconnect(); withTimeout(5000) { job.join() } }
        }
        connection { ssh, output ->
            ssh.write("export ITERMUX_PERSIST=restored_123; sleep 60\r".toByteArray())
            delay(300); ssh.write(byteArrayOf(3)); delay(200)
            ssh.write("printf '\\ninterrupt_%s\\n' 'ok'\r".toByteArray()); waitText(output, "interrupt_ok")
        }
        connection { ssh, output ->
            ssh.write("printf '\\n%s\\n' \"\$ITERMUX_PERSIST\"\r".toByteArray())
            waitText(output, "restored_123")
            ssh.write("vim -Nu NONE -n\r".toByteArray()); delay(500)
            ssh.write("\u001b:q!\r".toByteArray()); delay(300)
            ssh.write("printf '\\nvim_exit_%s\\n' 'ok'\r".toByteArray()); waitText(output, "vim_exit_ok")
        }
    }
}
