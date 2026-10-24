package io.itermux.ssh

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WindowsDesktopTest {
    private fun decode(command: String): String =
        String(Base64.getDecoder().decode(command.substringAfter("-EncodedCommand ")), Charsets.UTF_16LE)

    @Test fun scriptsFitCmdExe() {
        // cmd.exe caps a command line at 8191 characters.
        val scripts = listOf(WindowsDesktop.probeCommand,
            PowerShell.command(WindowsDesktop.hostDownloadScript("https://github.com/yahaa/roamtty-desktop/releases/download/v1.2.3/windows-x64.exe", "a".repeat(64))),
            PowerShell.command(WindowsDesktop.installScript(".roamtty\\desktop\\openh264-x.bz2")))
        scripts.forEach { assertTrue("${it.length}", it.length < 8000) }
    }

    @Test fun probeReportsTheSameMarkers() {
        val probe = decode(WindowsDesktop.probeCommand)
        assertTrue(HostPluginProbe.DESKTOP in probe && HostPluginProbe.UNAME in probe && HostPluginProbe.DESKTOP_PLUGIN in probe)
        assertTrue("Win32_ComputerSystem" in probe)
        val out = "${HostPluginProbe.DESKTOP}yes\r\n${HostPluginProbe.UNAME}Windows AMD64\r\n${HostPluginProbe.DESKTOP_PLUGIN}roamtty-desktop 0.5.0 proto 1\r\n"
        assertEquals(HostDesktop("yes", "Windows AMD64", "roamtty-desktop 0.5.0 proto 1"), HostPluginProbe.parseDesktop(out))
    }

    @Test fun downloadChecksAndPlaces() {
        val s = WindowsDesktop.hostDownloadScript("https://x/it's.exe", "ab".repeat(32))
        assertTrue("curl.exe -fsSL" in s && "'https://x/it''s.exe'" in s)
        assertTrue("Get-FileHash" in s && "'${"ab".repeat(32)}'" in s && DesktopPluginInstaller.SHA_MISMATCH in s)
        assertTrue("schtasks /End" in s && "Move-Item -Force" in s)
        assertTrue(DesktopPluginInstaller.Build("1", "https://x/windows-x64.exe", "a", null).windows)
    }

    @Test fun serveGoesThroughCmd() {
        val c = WindowsDesktop.serveCommand("--proto 1 --client 2400x1080@420")
        assertEquals("cmd /c \"\"%USERPROFILE%\\.roamtty\\bin\\roamtty-desktop.exe\" serve --proto 1 --client 2400x1080@420\"", c)
    }
}
