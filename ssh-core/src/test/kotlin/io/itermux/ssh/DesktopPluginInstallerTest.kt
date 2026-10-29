package io.itermux.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Runs the installer's scripts in a local shell whose HOME is a temporary directory. */
class DesktopPluginInstallerTest {
    private val home = Files.createTempDirectory("desktop-install").toFile()
    private val binary = "#!/bin/sh\necho 'roamtty-desktop 0.1.0 proto 1'\n".toByteArray()
    private val packed = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(binary) } }.toByteArray()
    private val sha = DesktopPluginInstaller.sha256(binary)

    private val local = object : RemoteHost {
        override suspend fun probePlugins(): Set<HostPlugin>? = null
        override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int? {
            val p = ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true).apply { environment()["HOME"] = home.path }.start()
            p.outputStream.use { it.write(script.toByteArray()) }
            p.inputStream.bufferedReader().forEachLine(onLine)
            return p.waitFor()
        }
        override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int? = null
        override suspend fun upload(remotePath: String, source: InputStream) {
            val f = File(home, remotePath)
            check(!f.exists())
            f.outputStream().use { source.copyTo(it) }
        }
    }

    private fun installer(fetched: MutableList<String>, steps: MutableList<DesktopPluginInstaller.Step>, body: ByteArray = packed) =
        DesktopPluginInstaller(local, fetch = { fetched += it; body }, onStep = { steps += it })

    private val installed get() = File(home, ".roamtty/bin/roamtty-desktop")

    @Test fun hostDownloadsItself() = runBlocking {
        assumeTrue(File("/usr/bin/curl").exists())
        val release = File(home, "release.gz").apply { writeBytes(packed) }
        val fetched = mutableListOf<String>()
        val steps = mutableListOf<DesktopPluginInstaller.Step>()
        val result = installer(fetched, steps).install(DesktopPluginInstaller.Build("0.1.0", "file://${release.path}", sha, null))
        assertEquals(DesktopPluginInstaller.Result.Installed, result)
        assertTrue("phone never downloads", fetched.isEmpty())
        assertArrayEquals(binary, installed.readBytes())
        assertTrue(installed.canExecute())
    }

    @Test fun phoneDownloadsWhenTheHostCannot() = runBlocking {
        val fetched = mutableListOf<String>()
        val steps = mutableListOf<DesktopPluginInstaller.Step>()
        val url = "http://127.0.0.1:9/unreachable.gz"
        val result = installer(fetched, steps).install(DesktopPluginInstaller.Build("0.1.0", url, sha, null))
        assertEquals(DesktopPluginInstaller.Result.Installed, result)
        assertEquals(listOf(url), fetched)
        assertTrue(DesktopPluginInstaller.Step.PHONE_DOWNLOAD in steps && DesktopPluginInstaller.Step.UPLOAD in steps)
        assertArrayEquals(binary, installed.readBytes())
        assertTrue("no part files left", File(home, ".roamtty/bin").list()!!.all { it == "roamtty-desktop" })
    }

    @Test fun wrongChecksumIsRejected() = runBlocking {
        val result = installer(mutableListOf(), mutableListOf())
            .install(DesktopPluginInstaller.Build("0.1.0", "http://127.0.0.1:9/x.gz", "00".repeat(32), null))
        assertEquals(DesktopPluginInstaller.Reason.CHECKSUM, (result as DesktopPluginInstaller.Result.Failed).reason)
        assertFalse(installed.exists())
    }

    @Test fun placeScriptRejectsATamperedUpload() = runBlocking {
        File(home, ".roamtty/bin").mkdirs()
        val part = File(home, ".roamtty/bin/x.part").apply { writeBytes(binary + 1) }
        val lines = mutableListOf<String>()
        assertEquals(4, local.run(DesktopPluginInstaller.placeScript(part.path, sha), 5000) { lines += it })
        assertTrue(lines.any { DesktopPluginInstaller.SHA_MISMATCH in it })
        assertFalse(part.exists())
        assertFalse(installed.exists())
    }

    @Test fun probeParsesDesktop() {
        val out = "__ITERMUX_TMUX__=/usr/bin/tmux\n__ITERMUX_MOSH__=\n__ITERMUX_DESKTOP__=yes\n__ITERMUX_UNAME__=Linux x86_64\n" +
            "__ITERMUX_DESKTOP_PLUGIN__=roamtty-desktop 0.1.0 proto 1\n"
        assertEquals(HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.1.0 proto 1"), HostPluginProbe.parseDesktop(out))
        assertEquals("0.1.0", HostPluginProbe.parseDesktop(out)!!.version)
        assertEquals(setOf(HostPlugin.TMUX, HostPlugin.DESKTOP), HostPluginProbe.parse(out))
        val none = "__ITERMUX_TMUX__=\n__ITERMUX_MOSH__=\n__ITERMUX_DESKTOP__=no\n__ITERMUX_UNAME__=Linux x86_64\n__ITERMUX_DESKTOP_PLUGIN__=\n"
        assertNull(HostPluginProbe.parseDesktop(none)!!.pluginVersion)
        assertEquals(emptySet<HostPlugin>(), HostPluginProbe.parse(none))
        assertNull(HostPluginProbe.parseDesktop("__ITERMUX_TMUX__=\n"))
        assertFalse(HostPlugin.DESKTOP in HostPlugin.offered(false))
    }

    @Test fun probeScriptRunsInAPosixShell() = runBlocking {
        val lines = mutableListOf<String>()
        assertEquals(0, local.run(HostPluginProbe.script, 10_000) { lines += it })
        val desktop = HostPluginProbe.parseDesktop(lines.joinToString("\n"))
        assertNotNull(lines.joinToString("\n"), desktop)
        // Probe must work on the CI/dev host OS; do not require Linux specifically (macOS reports Darwin).
        assertTrue(desktop!!.uname.isNotBlank())
        assertTrue(desktop.uname.startsWith("Linux") || desktop.uname.startsWith("Darwin") || "Windows" in desktop.uname)
    }

    /** The macOS app scripts, dumped for running on a Mac (ROAMTTY_DUMP_MAC_SCRIPTS=<dir>). */
    @Test fun dumpsMacScripts() {
        val dir = System.getenv("ROAMTTY_DUMP_MAC_SCRIPTS") ?: return
        val m = DesktopPluginInstaller.Build("0.4.0", System.getenv("ROAMTTY_MAC_URL")!!, System.getenv("ROAMTTY_MAC_SHA")!!, null)
        assertTrue(m.app)
        File(dir, "host-app.sh").writeText(DesktopPluginInstaller.hostAppScript(m))
    }

    @Test fun zipBuildsAreApps() {
        assertTrue(DesktopPluginInstaller.Build("1", "https://x/macos-universal.zip", "a", null).app)
        assertFalse(DesktopPluginInstaller.Build("1", "https://x/linux-x86_64.gz", "a", null).app)
        val script = DesktopPluginInstaller.placeAppScript("/tmp/x.zip", "ab")
        assertTrue("ditto -x -k" in script && "ln -sf" in script && "Roamtty Desktop.app" in script)
    }
}
