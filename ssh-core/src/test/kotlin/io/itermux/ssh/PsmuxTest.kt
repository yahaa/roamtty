package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Runs the real psmux scripts under PowerShell 7 (`pwsh`, or `$PWSH`) against a stub `psmux` that
 * logs its arguments. Skipped where no PowerShell is installed.
 */
class PsmuxTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun everyCommandFitsCmdLineLimit() {
        listOf(Psmux.probeCommand, Psmux.installCommand, Psmux.attachCommand(NAME), Psmux.killSessionCommand(NAME),
            Psmux.restartPaneCommand(NAME), Psmux.displayCommand(NAME, "#{pane_current_path}")).forEach {
            assertTrue("Encoded PowerShell command is ${it.length} characters; CMD limit is 8191", it.length < 8191)
        }
    }

    @Test fun parsesProbe() {
        assertEquals(true, Psmux.parseProbe("${Psmux.PROBE}C:\\Users\\me\\AppData\\Local\\Microsoft\\WinGet\\Links\\psmux.exe\r\n"))
        assertEquals(false, Psmux.parseProbe("${Psmux.PROBE}\r\n"))
        assertNull(Psmux.parseProbe("'powershell.exe' is not recognized as an internal or external command"))
        assertNull(Psmux.parseProbe(null))
    }

    @Test fun mapsWindowsPathsToSftp() {
        assertEquals("/C:/Users/me/src", Psmux.sftpPath("C:\\Users\\me\\src\r\n"))
        assertEquals("/D:/", Psmux.sftpPath("D:\\"))
        assertNull(Psmux.sftpPath("\\\\server\\share"))
        assertNull(Psmux.sftpPath("/home/me"))
    }

    @Test fun psmuxIsOfferedOnlyToNativeWindowsShells() {
        assertEquals(listOf(HostPlugin.PSMUX), HostPlugin.offered(nativeWindows = true))
        assertEquals(listOf(HostPlugin.TMUX, HostPlugin.MOSH), HostPlugin.offered(nativeWindows = false))
        assertEquals(setOf(HostPlugin.PSMUX), HostPlugin.decode(HostPlugin.encode(setOf(HostPlugin.PSMUX))))
        assertTrue(HostPlugin.PSMUX.keepsSessions)
        assertFalse(HostPlugin.MOSH.keepsSessions)
    }

    @Test fun attachCreatesAMissingSessionWithoutStatusLine() {
        val (status, _) = run(Psmux.attachCommand(NAME), hasSession = false)
        assertEquals(0, status)
        assertEquals(listOf("has-session -t =$NAME", "new-session -d -s $NAME", "set-option -t $NAME status off",
            "set-option -t $NAME mouse on", "attach-session -t $NAME"), calls())
    }

    @Test fun attachReusesARunningSession() {
        run(Psmux.attachCommand(NAME), hasSession = true)
        val calls = calls()
        assertFalse(calls.any { it.startsWith("new-session") })
        assertEquals("attach-session -t $NAME", calls.last())
    }

    @Test fun killSucceedsWhenTheSessionIsGone() {
        assertEquals(0, run(Psmux.killSessionCommand(NAME), hasSession = false).first)
        assertEquals(listOf("has-session -t =$NAME"), calls())
        run(Psmux.killSessionCommand(NAME), hasSession = true)
        assertEquals("kill-session -t $NAME", calls().last())
    }

    @Test fun restartRespawnsInThePaneFolder() {
        val (status, output) = run(Psmux.restartPaneCommand(NAME), hasSession = true, display = "C:\\work dir")
        assertEquals(0, status)
        assertEquals("", output.trim())
        assertEquals("respawn-pane -k -c C:\\work dir -t $NAME", calls().last())
    }

    @Test fun probeReportsWhetherPsmuxIsOnPath() {
        assertEquals(true, Psmux.parseProbe(run(Psmux.probeCommand, hasSession = false).second))
        assertEquals(false, Psmux.parseProbe(run(Psmux.probeCommand, psmux = false).second))
    }

    @Test fun installUsesWingetAndReportsDone() {
        val (status, output) = run(Psmux.installCommand, psmux = false, winget = true)
        assertEquals(output, 0, status)
        assertTrue(output, "WINGET install --id marlocarlo.psmux -e" in output)
        assertEquals(HostPluginInstaller.Result.INSTALLED, HostPluginInstaller.classify(status, output))
    }

    @Test fun installSkipsWingetWhenPsmuxIsPresent() {
        val (status, output) = run(Psmux.installCommand, winget = true)
        assertFalse(output, "WINGET" in output)
        assertEquals(HostPluginInstaller.Result.INSTALLED, HostPluginInstaller.classify(status, output))
    }

    private val bin by lazy { temp.newFolder("bin") }
    private val log by lazy { File(temp.root, "calls.log") }

    private fun calls(): List<String> = log.takeIf { it.exists() }?.readLines().orEmpty().also { log.delete() }

    private fun run(command: String, hasSession: Boolean = true, display: String = "", psmux: Boolean = true,
        winget: Boolean = false): Pair<Int, String> {
        val pwsh = System.getenv("PWSH") ?: "pwsh"
        assumeTrue("PowerShell is not installed", runCatching {
            ProcessBuilder(pwsh, "-NoProfile", "-Command", "exit 0").start().waitFor(60, TimeUnit.SECONDS)
        }.getOrDefault(false))
        val stub = """
            #!/bin/sh
            printf '%s\n' "${'$'}*" >> '${log.path}'
            case "${'$'}1" in
              has-session) exit ${if (hasSession) 0 else 1} ;;
              display-message) printf '%s\n' '$display' ;;
              -V) echo 'psmux 3.3.8' ;;
            esac
            exit 0
        """.trimIndent() + "\n"
        File(bin, "psmux").delete()
        if (psmux) File(bin, "psmux").apply { writeText(stub); setExecutable(true) }
        if (winget) File(bin, "winget").apply {
            writeText("#!/bin/sh\necho \"WINGET ${'$'}*\"\nprintf '%s' '${stub.replace("'", "'\\''")}' > '${bin.path}/psmux'\nchmod +x '${bin.path}/psmux'\n")
            setExecutable(true)
        }
        val script = command.substringAfter("-EncodedCommand ")
            .let { String(Base64.getDecoder().decode(it), Charsets.UTF_16LE) }
        val file = File(temp.root, "script.ps1").apply { writeText(script) }
        val process = ProcessBuilder(pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", file.path)
            .redirectErrorStream(true).apply {
                environment()["PATH"] = "${bin.path}:/usr/bin:/bin"
                environment()["HOME"] = temp.root.path
            }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("pwsh timed out", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    private companion object { const val NAME = "roamtty-abc123def456" }
}
