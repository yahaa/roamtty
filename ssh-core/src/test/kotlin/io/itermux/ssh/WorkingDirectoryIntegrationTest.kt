package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional local OpenSSH fixture on port 22231 with PS1=CWD_READY and an isolated tmux socket. */
class WorkingDirectoryIntegrationTest {
    @Test fun shellDirectoryTracksCdAndIsIsolatedFromAnotherSshConnection() = verify(SessionMode.SHELL)
    @Test fun tmuxDirectoryTracksCdAndTheActivePane() = verify(SessionMode.TMUX)

    private fun verify(mode: SessionMode) = runBlocking {
        val root = File(System.getProperty("itermux.root"))
        assumeTrue(File(root, ".local/cwd-ssh/sshd_config").exists())
        val key = File(root, ".local/tmux-ssh/client_key")
        fun connection() = JschSession(
            Target("127.0.0.1", 22231, System.getProperty("user.name"), AuthMethod.KEY, mode),
            Credentials(privateKey = key.readBytes()),
            object : PinStore {
                private var pin: HostPin? = null
                override suspend fun get(endpoint: String) = pin
                override suspend fun putIfAbsent(pin: HostPin) = (this.pin ?: pin).also { this.pin = it }
            }, { if (it is Prompt.VerifyHost) listOf("trust") else null }, remoteSessionName = Tmux.newSessionName())
        val ssh = connection()
        val other = connection()
        val output = listOf(StringBuffer(), StringBuffer())
        val jobs = listOf(ssh, other).mapIndexed { index, session ->
            launch { session.connect { output[index].append(it.toString(Charsets.UTF_8)) } }
        }
        suspend fun awaitDirectory(session: JschSession, path: String) = withTimeout(10_000) {
            while (session.currentWorkingDirectory() != path) delay(100)
        }
        try {
            listOf(ssh, other).forEach {
                assertEquals(Phase.CONNECTED, withTimeout(15_000) {
                    it.state.first { state -> state.phase == Phase.CONNECTED || !state.active }
                }.phase)
            }
            // CONNECTED means the PTY is open; tmux may still be starting its interactive shell.
            withTimeout(10_000) { while (output.any { !it.contains("CWD_READY") }) delay(20) }
            assertTrue(ssh.write("cd /tmp\r".toByteArray()))
            assertTrue(other.write("cd /usr\r".toByteArray()))
            awaitDirectory(ssh, "/tmp")
            awaitDirectory(other, "/usr")
            assertTrue(ssh.write("cd /var\r".toByteArray()))
            awaitDirectory(ssh, "/var")
            assertEquals("/usr", other.currentWorkingDirectory())
            if (mode == SessionMode.TMUX) {
                assertTrue(ssh.write("tmux split-window -h -c /tmp\r".toByteArray()))
                awaitDirectory(ssh, "/tmp")
                ssh.write("exit\r".toByteArray())
                awaitDirectory(ssh, "/var")
            }
        } finally {
            listOf(ssh, other).forEach { it.write("exit\r".toByteArray()) }
            delay(200)
            listOf(ssh, other).forEach { it.disconnect() }
            jobs.forEach { withTimeout(5000) { it.join() } }
        }
    }
}
