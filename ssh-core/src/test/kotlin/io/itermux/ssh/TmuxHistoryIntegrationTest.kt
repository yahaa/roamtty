package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in test against the isolated local sshd/tmux fixture on port 22230. */
class TmuxHistoryIntegrationTest {
    @Test fun remoteHistoryCanBeEnteredScrolledAndExited() = runBlocking {
        val root = File(System.getProperty("itermux.root"))
        val key = File(root, ".local/tmux-ssh/client_key")
        assumeTrue("Isolated tmux SSH fixture is not configured", key.exists())
        val ssh = JschSession(
            Target("127.0.0.1", 22230, System.getProperty("user.name"), AuthMethod.KEY, SessionMode.TMUX),
            Credentials(privateKey = key.readBytes()),
            object : PinStore {
                private var pin: HostPin? = null
                override suspend fun get(endpoint: String) = pin
                override suspend fun putIfAbsent(pin: HostPin): HostPin = (this.pin ?: pin).also { this.pin = it }
            },
            { if (it is Prompt.VerifyHost) listOf("trust") else null }
        )
        val job = launch { ssh.connect {} }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(15_000) {
                ssh.state.first { it.phase == Phase.CONNECTED || !it.active }
            }.phase)
            assertTrue(ssh.write("i=1; while [ \$i -le 120 ]; do printf 'TMUX_HISTORY_%03d\\n' \$i; i=\$((i+1)); done\r".toByteArray()))
            val initial = withTimeout(10_000) {
                var state: TmuxHistory?
                do { delay(100); state = ssh.tmuxHistory() } while ((state?.rows ?: 0) < 50)
                requireNotNull(state)
            }
            assertFalse(initial.active)
            val entered = requireNotNull(ssh.enterTmuxHistory())
            assertTrue(entered.active)
            val scrolled = requireNotNull(ssh.moveTmuxHistoryTo(20))
            assertEquals(20, scrolled.position)
            val atTop = requireNotNull(ssh.moveTmuxHistoryTo(scrolled.rows))
            assertEquals(atTop.rows, atTop.position)
            val live = requireNotNull(ssh.exitTmuxHistory())
            assertFalse(live.active)
        } finally {
            ssh.disconnect()
            withTimeout(5000) { job.join() }
        }
    }
}
