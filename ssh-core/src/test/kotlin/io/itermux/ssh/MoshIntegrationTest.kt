package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Opt-in: a disposable Linux host with sshd, sudo and UDP 60001+ published, e.g. an Ubuntu container
 * with a sudo user. Set ITERMUX_MOSH_TEST=host:port:user:password. The first test installs the plugins.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MoshIntegrationTest {
    private val config = System.getenv("ITERMUX_MOSH_TEST")?.split(':')
    private fun target(mode: SessionMode): Target {
        assumeTrue("ITERMUX_MOSH_TEST not set", config?.size == 4)
        return Target(config!![0], config[1].toInt(), config[2], AuthMethod.PASSWORD, mode)
    }
    private val password get() = config!![3]
    private fun credentials() = Credentials(password.toByteArray())
    private val pins = MemoryPins()
    private val trust: suspend (Prompt) -> List<String>? = { if (it is Prompt.VerifyHost) listOf("trust") else null }

    @Test fun a_installsPluginsWithSudoPassword() = runBlocking {
        val target = target(SessionMode.SHELL)
        val log = StringBuffer()
        var before: Set<HostPlugin>? = null
        var after: Set<HostPlugin>? = null
        val results = mutableListOf<HostPluginInstaller.Result>()
        JschSession(target, credentials(), pins, trust).administer { host ->
            before = host.probePlugins()
            // A wrong sudo password is reported as such, without running the package manager.
            val wrong = StringBuilder()
            results += HostPluginInstaller.classify(host.run(HostPluginInstaller.script(HostPlugin.TMUX, "wrong"), 60_000) { wrong.appendLine(it) }, wrong.toString())
            for (plugin in HostPlugin.entries) {
                val out = StringBuilder()
                val status = host.run(HostPluginInstaller.script(plugin, password), 600_000) { out.appendLine(it); log.appendLine(it) }
                results += HostPluginInstaller.classify(status, out.toString())
            }
            after = host.probePlugins()
        }
        assertNotNull(before)
        assertEquals(log.toString(), listOf(HostPluginInstaller.Result.SUDO_PASSWORD, HostPluginInstaller.Result.INSTALLED,
            HostPluginInstaller.Result.INSTALLED), results)
        assertEquals(setOf(HostPlugin.TMUX, HostPlugin.MOSH), after)
    }

    private suspend fun waitText(output: StringBuffer, text: String) =
        try { withTimeout(15_000) { while (!output.toString().contains(text)) delay(30) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Missing '$text' in: $output") }

    private fun moshSession(mode: SessionMode) = runBlocking {
        val ssh = JschSession(target(mode), credentials(), pins, trust, remoteSessionName = Tmux.newSessionName(), preferMosh = true)
        ssh.resize(100, 30)
        val output = StringBuffer()
        val job = launch { ssh.connect { output.append(it.toString(Charsets.UTF_8)) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(30_000) { ssh.state.first { !it.active || it.phase == Phase.CONNECTED } }.phase)
            assertEquals(TerminalTransport.MOSH, ssh.transport.value)
            delay(500)
            assertTrue(ssh.write("echo mosh-\$((40+2))\r".toByteArray()))
            waitText(output, "mosh-42")
            ssh.resize(91, 33)
            delay(500)
            assertTrue(ssh.write("stty size\r".toByteArray()))
            // tmux keeps one row for its status line.
            waitText(output, if (mode == SessionMode.TMUX) "32 91" else "33 91")
            if (mode == SessionMode.TMUX) {
                assertTrue(ssh.write("echo \$TMUX | cut -c1-5\r".toByteArray()))
                waitText(output, "/tmp/")
            }
            assertEquals(setOf(HostPlugin.TMUX, HostPlugin.MOSH), withTimeout(10_000) { ssh.detectedPlugins.first { it != null } })
            // Exiting the remote program ends mosh cleanly as a remote exit.
            if (mode == SessionMode.SHELL) {
                ssh.write("exit\r".toByteArray())
                withTimeout(15_000) { job.join() }
                assertEquals(Phase.DISCONNECTED, ssh.state.value.phase)
            }
        } finally { ssh.disconnect(); withTimeout(5000) { job.join() } }
    }

    @Test fun b_moshShell() = moshSession(SessionMode.SHELL)
    @Test fun c_moshTmux() = moshSession(SessionMode.TMUX)
}
