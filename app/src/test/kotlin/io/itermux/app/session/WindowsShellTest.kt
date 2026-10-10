package io.itermux.app.session

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Executes generated commands in PowerShell; set ROAMTTY_PWSH to a local pwsh executable. */
class WindowsShellTest {
    private fun run(script: String): Pair<Int, List<String>> {
        val pwsh = System.getenv("ROAMTTY_PWSH")
        assumeTrue("Requires ROAMTTY_PWSH", !pwsh.isNullOrBlank())
        val process = ProcessBuilder(pwsh!!, "-NoLogo", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
            .redirectErrorStream(true).start()
        try {
            assertTrue("PowerShell timed out", process.waitFor(20, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readLines()
        } finally { process.destroyForcibly() }
    }

    private fun decode(command: String) = String(Base64.getDecoder().decode(command.substringAfter("-EncodedCommand ")), Charsets.UTF_16LE)

    @Test fun listingExecutesWithLiteralUnicodeAndWildcardNames() {
        val root = Files.createTempDirectory("windows-folders-").toFile()
        try {
            val folder = File(root, "it's 中文 [work] \$(throw 'injected') %TEMP% O’Brien").apply { mkdir() }
            listOf("Api", "中文", ".hidden", "bad\nname").forEach { File(folder, it).mkdir() }
            File(folder, "plain-file").writeText("text")
            val (status, lines) = run(RemoteFolders.windowsScript(folder.path))
            assertEquals(lines.toString(), 0, status)
            val listing = RemoteFolders.parse(lines)!!
            assertEquals(folder.path, listing.path)
            assertEquals(listOf("Api", "中文"), listing.folders)
            assertEquals(3, run(RemoteFolders.windowsScript(File(root, "missing").path)).first)
        } finally { root.deleteRecursively() }
    }

    @Test fun selectedWindowsDirectoryIsEnteredAndFailedCdDoesNotRunAgent() {
        val root = Files.createTempDirectory("windows-launch-").toFile()
        try {
            val folder = File(root, "it's 中文 [work] \$(throw 'injected') %TEMP% O’Brien").apply { mkdir() }
            val setup = "New-PSDrive -Name X -PSProvider FileSystem -Root ${WindowsShell.literal(root.path)} | Out-Null; "
            val command = StartDirectory.command("X:/${folder.name}")!!
            val (status, output) = run(setup + decode(command) + "; (Get-Location).ProviderPath")
            assertEquals(output.toString(), 0, status)
            assertEquals(folder.path, output.last())
            val failed = run(setup + "function codex { 'AGENT_RAN' }; " + decode(AgentLaunch(CodingAgent.CODEX, "X:/missing").commandLine))
            assertFalse(failed.second.toString(), failed.second.any { it == "AGENT_RAN" })
        } finally { root.deleteRecursively() }
    }

    @Test fun windowsAgentPrefersCmdShimToPolicyRestrictedPs1() {
        val root = Files.createTempDirectory("windows-agent-").toFile()
        try {
            File(root, "codex.cmd").apply { writeText("#!/bin/sh\nprintf 'CMD_SHIM:%s\\n' \"\u0024*\"\n"); setExecutable(true) }
            File(root, "codex.ps1").writeText("throw 'PS1_SHIM_MUST_NOT_RUN'")
            val setup = "New-PSDrive -Name X -PSProvider FileSystem -Root ${WindowsShell.literal(root.path)} | Out-Null; " +
                "\u0024env:PATH = ${WindowsShell.literal(root.path)} + [IO.Path]::PathSeparator + \u0024env:PATH; function codex { throw 'PS1_SHIM_MUST_NOT_RUN' }; "
            val (status, output) = run(setup + decode(AgentLaunch(CodingAgent.CODEX, "X:/", AgentMode.READ_ONLY).commandLine))
            assertEquals(output.toString(), 0, status)
            assertTrue(output.toString(), output.any { it == "CMD_SHIM:--sandbox read-only" })
            // Linux cannot mount a native Windows UNC provider; supply only that provider result.
            val unc = run(setup + "function Get-Location { [pscustomobject]@{ ProviderPath = '\\\\server\\share' } }; " +
                decode(AgentLaunch(CodingAgent.CODEX, "X:/").commandLine))
            assertFalse(unc.second.toString(), unc.second.any { it.startsWith("CMD_SHIM:") })
            assertTrue(unc.second.toString(), unc.second.any { it.contains("map this network folder") })
        } finally { root.deleteRecursively() }
    }
}
