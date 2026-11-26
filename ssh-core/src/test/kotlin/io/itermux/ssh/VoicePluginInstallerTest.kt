package io.itermux.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class VoicePluginInstallerTest {
    @Test fun headlessInstallSkipsOpenH264AndRegistration() = runBlocking {
        val home = Files.createTempDirectory("voice-install").toFile()
        try {
            val binary = "#!/bin/sh\necho 'roamtty-desktop 0.9.7 proto 1'\n".toByteArray()
            val packed = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(binary) } }.toByteArray()
            val sha = DesktopPluginInstaller.sha256(binary)
            val ranScripts = mutableListOf<String>()
            val steps = mutableListOf<DesktopPluginInstaller.Step>()

            val remote = object : RemoteHost {
                override suspend fun probePlugins(): Set<HostPlugin>? = null
                override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int {
                    ranScripts += script
                    val p = ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true).apply { environment()["HOME"] = home.path }.start()
                    p.outputStream.use { it.write(script.toByteArray()) }
                    p.inputStream.bufferedReader().forEachLine(onLine)
                    return p.waitFor()
                }
                override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int? = null
                override suspend fun upload(remotePath: String, source: InputStream) {
                    val f = File(home, remotePath)
                    f.parentFile.mkdirs()
                    f.outputStream().use { source.copyTo(it) }
                }
            }

            val installer = DesktopPluginInstaller(remote, fetch = { packed }, onStep = { steps += it })
            val result = installer.install(
                DesktopPluginInstaller.Build(
                    version = "0.9.7",
                    url = "http://127.0.0.1:9/roamtty-desktop.gz",
                    sha256 = sha,
                    openh264Url = "https://ciscobinary.openh264.org/should-be-skipped.bz2",
                    headless = true,
                )
            )
            assertEquals(DesktopPluginInstaller.Result.Installed, result)
            assertFalse("headless must not download OpenH264", DesktopPluginInstaller.Step.ENCODER_HOST in steps)
            assertFalse("headless must not register LaunchAgent/service", DesktopPluginInstaller.Step.REGISTER in steps)
            assertFalse("headless must not invoke `roamtty-desktop install`", ranScripts.any { " install" in it })
            assertArrayEquals(binary, File(home, ".roamtty/bin/roamtty-desktop").readBytes())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun windowsVoiceCommandUsesCmdBinaryPipe() {
        val cmd = WindowsDesktop.voiceCommand("--proto 1 --port 24713")
        assertEquals("cmd /c \"\"%USERPROFILE%\\.roamtty\\bin\\roamtty-desktop.exe\" voice --proto 1 --port 24713\"", cmd)
    }

    @Test fun tmuxAndPsmuxEnvironmentCommandsFormatAndValidate() {
        val setTmux = Tmux.setEnvironmentCommand("roamtty-abc", "ANTIGRAVITY_MIC", "127.0.0.1:24713")
        assertTrue("tmux set-environment -t roamtty-abc ANTIGRAVITY_MIC 127.0.0.1:24713" in setTmux)
        val unsetTmux = Tmux.unsetEnvironmentCommand("roamtty-abc", "ANTIGRAVITY_MIC")
        assertTrue("tmux set-environment -u -t roamtty-abc ANTIGRAVITY_MIC" in unsetTmux)

        val setPsmux = Psmux.setEnvironmentCommand("roamtty-abc", "ANTIGRAVITY_MIC", "127.0.0.1:24713")
        assertTrue(setPsmux.startsWith("powershell"))
        val unsetPsmux = Psmux.unsetEnvironmentCommand("roamtty-abc", "ANTIGRAVITY_MIC")
        assertTrue(unsetPsmux.startsWith("powershell"))

        assertThrows(IllegalArgumentException::class.java) {
            Tmux.setEnvironmentCommand("s", "BAD-NAME", "127.0.0.1:24713")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Tmux.setEnvironmentCommand("s", "ANTIGRAVITY_MIC", "127.0.0.1:24713;id")
        }
    }
}
