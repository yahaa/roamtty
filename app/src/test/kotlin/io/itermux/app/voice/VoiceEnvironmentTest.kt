package io.itermux.app.voice

import io.itermux.app.session.AgentHooks
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.AgentMode
import io.itermux.app.session.CodingAgent
import io.itermux.ssh.HostPlugin
import io.itermux.ssh.PowerShell
import io.itermux.ssh.RemoteHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class VoiceEnvironmentTest {
    @Test fun posixAgyLauncherInjectsAntigravityMicAndRejectsUnsafeEndpoints() {
        val plain = AgentHooks.agyLauncher
        assertFalse("default launcher leaves ANTIGRAVITY_MIC unset", "ANTIGRAVITY_MIC" in plain)

        val withMic = AgentHooks.agyLauncher("127.0.0.1:24713")
        assertTrue(withMic.startsWith("#!/bin/sh\n"))
        assertTrue("export ANTIGRAVITY_MIC=127.0.0.1:24713\nexport ROAMTTY_AGENT=agy\n" in withMic)
        assertTrue("agy \"\$@\"\n" in withMic)

        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("192.168.1.1:24713") }
        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("127.0.0.1:0") }
        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("127.0.0.1:70000") }
        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("127.0.0.1:24713;whoami") }
    }

    @Test fun windowsPowerShellLauncherInjectsAntigravityMic() {
        val psLauncher = VoiceEnvironment.windowsLauncherScript("127.0.0.1:24713")
        assertTrue("\$env:ANTIGRAVITY_MIC = '127.0.0.1:24713'" in psLauncher)
        assertTrue("& agy.cmd @args" in psLauncher)

        val cleared = VoiceEnvironment.windowsLauncherScript(null)
        assertTrue("Remove-Item Env:ANTIGRAVITY_MIC" in cleared)

        // AgentLaunch on Windows injects $env:ANTIGRAVITY_MIC when active
        val cmd = AgentLaunch(CodingAgent.AGY, "C:\\work\\app", AgentMode.STANDARD)
            .commandLine(session = null, antigravityMic = "127.0.0.1:24713")
        val encoded = cmd.substringAfter("-EncodedCommand ").trim()
        val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
        val expectedLiteral = PowerShell.literal("127.0.0.1:24713")
        assertTrue(decoded, "\$env:ANTIGRAVITY_MIC = $expectedLiteral;" in decoded)
    }

    @Test fun posixUpdateScriptWritesExecutableAgyLauncherToDisk() {
        val home = Files.createTempDirectory("voice-env-posix").toFile()
        try {
            val script = VoiceEnvironment.posixUpdateLauncherScript("127.0.0.1:24713")
            val proc = ProcessBuilder("/bin/sh", "-s").apply { environment()["HOME"] = home.path }.start()
            proc.outputStream.use { it.write(script.toByteArray()) }
            assertEquals(0, proc.waitFor())
            val agy = File(home, ".roamtty/agy")
            assertTrue(agy.exists())
            assertTrue(agy.canExecute())
            val content = agy.readText()
            assertTrue("export ANTIGRAVITY_MIC=127.0.0.1:24713" in content)
            assertTrue("export ROAMTTY_AGENT=agy" in content)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun injectAndClearUpdateLauncherAndTmuxSessions() = runBlocking {
        val scripts = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val fakeRemote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int {
                scripts += script; return 0
            }
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int {
                commands += command; return 0
            }
        }

        VoiceEnvironment.inject(fakeRemote, windowsHost = false, micPort = 24713, tmuxSessions = listOf("roamtty-1", "roamtty-2", "roamtty-1"))
        assertEquals(1, scripts.size)
        assertTrue("export ANTIGRAVITY_MIC=127.0.0.1:24713" in scripts.single())
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("tmux set-environment -t roamtty-1 ANTIGRAVITY_MIC 127.0.0.1:24713"))
        assertTrue(commands[1].contains("tmux set-environment -t roamtty-2 ANTIGRAVITY_MIC 127.0.0.1:24713"))

        scripts.clear(); commands.clear()
        VoiceEnvironment.clear(fakeRemote, windowsHost = false, tmuxSessions = listOf("roamtty-1"))
        assertEquals(1, scripts.size)
        assertFalse("ANTIGRAVITY_MIC" in scripts.single())
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("tmux set-environment -u -t roamtty-1 ANTIGRAVITY_MIC"))

        // Windows path updates agy.ps1 + psmux set-environment
        scripts.clear(); commands.clear()
        VoiceEnvironment.inject(fakeRemote, windowsHost = true, micPort = 25000, tmuxSessions = listOf("roamtty-w"))
        assertTrue(scripts.isEmpty())
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.startsWith("powershell") })
    }
}
