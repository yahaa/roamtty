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
        assertTrue("default launcher pre-configures default ANTIGRAVITY_MIC 24713", "export ANTIGRAVITY_MIC=127.0.0.1:24713" in plain)
        assertTrue("default launcher honors voice-mic override file", "voice-mic" in plain)

        val withMic = AgentHooks.agyLauncher("127.0.0.1:24713")
        assertTrue(withMic.startsWith("#!/bin/sh\n"))
        assertTrue("export ANTIGRAVITY_MIC=127.0.0.1:24713\nexport ROAMTTY_AGENT=agy\n" in withMic)
        assertTrue("\"voice\":{\"fullDuplex\":true}" in withMic)
        assertTrue("sh ~/.roamtty/agent-hook.sh agy statusline" in withMic)
        assertTrue("agy --settings '" in withMic)
        assertTrue("agy \"\$@\"" in withMic)

        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("192.168.1.1:24713") }
        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("127.0.0.1:0") }
        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("127.0.0.1:70000") }
        assertThrows(IllegalArgumentException::class.java) { AgentHooks.agyLauncher("127.0.0.1:24713;whoami") }
    }

    @Test fun windowsPowerShellLauncherInjectsAntigravityMic() {
        val psLauncher = VoiceEnvironment.windowsLauncherScript("127.0.0.1:24713")
        assertTrue("\$env:ANTIGRAVITY_MIC = '127.0.0.1:24713'" in psLauncher)
        assertTrue("fullDuplex" in psLauncher)
        assertTrue("& agy.cmd @launchArgs" in psLauncher)

        val defaultPs = VoiceEnvironment.windowsLauncherScript(null)
        assertTrue("voice-mic" in defaultPs)
        assertTrue("'127.0.0.1:24713'" in defaultPs)

        // AgentLaunch on Windows injects $env:ANTIGRAVITY_MIC both when explicit and by default
        val cmd = AgentLaunch(CodingAgent.AGY, "C:\\work\\app", AgentMode.STANDARD)
            .commandLine(session = null, antigravityMic = "127.0.0.1:24713")
        val encoded = cmd.substringAfter("-EncodedCommand ").trim()
        val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
        val expectedLiteral = PowerShell.literal("127.0.0.1:24713")
        assertTrue(decoded, "\$env:ANTIGRAVITY_MIC = $expectedLiteral;" in decoded)

        val defaultCmd = AgentLaunch(CodingAgent.AGY, "C:\\work\\app", AgentMode.STANDARD)
            .commandLine(session = null, antigravityMic = null)
        val defaultDecoded = String(Base64.getDecoder().decode(defaultCmd.substringAfter("-EncodedCommand ").trim()), Charsets.UTF_16LE)
        assertTrue(defaultDecoded, "voice-mic" in defaultDecoded)
        assertTrue(defaultDecoded, "\$env:ANTIGRAVITY_MIC = $expectedLiteral" in defaultDecoded)
    }

    @Test fun posixUpdateScriptWritesExecutableAgyLauncherToDisk() {
        val home = Files.createTempDirectory("voice-env-posix").toFile()
        try {
            val script = VoiceEnvironment.posixUpdateLauncherScript("127.0.0.1:34567")
            val proc = ProcessBuilder("/bin/sh", "-s").apply { environment()["HOME"] = home.path }.start()
            proc.outputStream.use { it.write(script.toByteArray()) }
            assertEquals(0, proc.waitFor())
            val agy = File(home, ".roamtty/agy")
            val micFile = File(home, ".roamtty/voice-mic")
            assertTrue(agy.exists())
            assertTrue(agy.canExecute())
            assertEquals("127.0.0.1:34567", micFile.readText().trim())
            val content = agy.readText()
            assertTrue("export ANTIGRAVITY_MIC=127.0.0.1:24713" in content)
            assertTrue("voice-mic" in content)
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
        assertTrue(commands.isEmpty())
        val injectScript = scripts.single()
        assertTrue("printf '%s\\n' '127.0.0.1:24713' > \"\$HOME/.roamtty/voice-mic\"" in injectScript)
        assertTrue("export ANTIGRAVITY_MIC=127.0.0.1:24713" in injectScript)
        assertTrue("tmux set-environment -t roamtty-1 ANTIGRAVITY_MIC 127.0.0.1:24713" in injectScript)
        assertTrue("tmux set-environment -t roamtty-2 ANTIGRAVITY_MIC 127.0.0.1:24713" in injectScript)

        scripts.clear(); commands.clear()
        VoiceEnvironment.clear(fakeRemote, windowsHost = false, tmuxSessions = listOf("roamtty-1"))
        assertEquals(1, scripts.size)
        assertTrue(commands.isEmpty())
        val clearScript = scripts.single()
        assertTrue("rm -f \"\$HOME/.roamtty/voice-mic\"" in clearScript)
        assertTrue("export ANTIGRAVITY_MIC=127.0.0.1:24713" in clearScript)
        assertTrue("tmux set-environment -u -t roamtty-1 ANTIGRAVITY_MIC" in clearScript)

        // Windows path updates agy.ps1 + psmux set-environment in a single PowerShell command
        scripts.clear(); commands.clear()
        VoiceEnvironment.inject(fakeRemote, windowsHost = true, micPort = 25000, tmuxSessions = listOf("roamtty-w"))
        assertTrue(scripts.isEmpty())
        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("powershell"))
        val decodedWin = String(
            Base64.getDecoder().decode(commands.single().substringAfter("-EncodedCommand ").trim()),
            Charsets.UTF_16LE,
        )
        assertTrue(decodedWin, "psmux set-environment -t 'roamtty-w' ANTIGRAVITY_MIC 127.0.0.1:25000" in decodedWin)
    }

    @Test fun injectAndClearRethrowCancellationException() {
        val cancellingRemote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int? {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int? {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { VoiceEnvironment.inject(cancellingRemote, windowsHost = false, micPort = 24713, tmuxSessions = emptyList()) }
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { VoiceEnvironment.clear(cancellingRemote, windowsHost = true, tmuxSessions = emptyList()) }
        }
    }

    @Test fun exitCodeTwoShowsFriendlyOutdatedPluginMessage() {
        val outdated = VoiceConnection.voiceStartExitMessage(VoiceConnection.EXIT_UNSUPPORTED_SUBCOMMAND)
        assertEquals("主机插件版本过旧，不支持语音模式，请更新插件", outdated)
        assertEquals(
            "Host plugin is outdated and does not support voice mode; please update the plugin",
            io.itermux.app.EnglishText.values[outdated],
        )
        assertEquals("语音插件启动失败（退出码 1）", VoiceConnection.voiceStartExitMessage(1))
    }
}
