package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: a host with mosh installed whose UDP ports are unreachable. ITERMUX_MOSH_BLOCKED_TEST=host:port:user:password. */
class MoshFallbackTest {
    @Test fun fallsBackToSshWhenUdpIsBlocked() = runBlocking {
        val config = System.getenv("ITERMUX_MOSH_BLOCKED_TEST")?.split(':')
        assumeTrue("ITERMUX_MOSH_BLOCKED_TEST not set", config?.size == 4)
        val target = Target(config!![0], config[1].toInt(), config[2], AuthMethod.PASSWORD, SessionMode.SHELL)
        val ssh = JschSession(target, Credentials(config[3].toByteArray()), MemoryPins(),
            { if (it is Prompt.VerifyHost) listOf("trust") else null }, preferMosh = true)
        val output = StringBuffer()
        val job = launch { ssh.connect { output.append(it.toString(Charsets.UTF_8)) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(30_000) { ssh.state.first { !it.active || it.phase == Phase.CONNECTED } }.phase)
            assertEquals(TerminalTransport.SSH, ssh.transport.value)
            assertNotNull(ssh.moshFallback.value)
            assertTrue(ssh.write("echo ssh-\$((40+2))\r".toByteArray()))
            withTimeout(10_000) { while (!output.contains("ssh-42")) delay(30) }
        } finally { ssh.disconnect(); withTimeout(5000) { job.join() } }
    }
}
