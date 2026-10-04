package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the real install script against stub `uname` and package managers to check which one each OS uses. */
class HostPluginInstallerScriptTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun macOsUsesHomebrewWithoutSudo() {
        val (status, output) = install(HostPlugin.TMUX, "Darwin", "brew", "port", "apt-get")
        assertEquals(output, 0, status)
        assertTrue(output, "CALLED brew install tmux" in output)
        assertEquals(HostPluginInstaller.Result.INSTALLED, HostPluginInstaller.classify(status, output))
    }

    @Test fun msys2UsesPacmanWithoutSudo() {
        val (status, output) = install(HostPlugin.MOSH, "MSYS_NT-10.0-22631", "pacman")
        assertTrue(output, "CALLED pacman -Sy --noconfirm --needed mosh" in output)
        assertEquals(HostPluginInstaller.Result.INSTALLED, HostPluginInstaller.classify(status, output))
    }

    @Test fun cygwinUsesAptCyg() {
        val (status, output) = install(HostPlugin.TMUX, "CYGWIN_NT-10.0", "apt-cyg")
        assertTrue(output, "CALLED apt-cyg install tmux" in output)
        assertEquals(HostPluginInstaller.Result.INSTALLED, HostPluginInstaller.classify(status, output))
    }

    @Test fun gitBashHasNoPackageManager() {
        val (status, output) = install(HostPlugin.TMUX, "MINGW64_NT-10.0")
        assertEquals(HostPluginInstaller.Result.NO_PACKAGE_MANAGER, HostPluginInstaller.classify(status, output))
    }

    private fun install(plugin: HostPlugin, kernel: String, vararg managers: String): Pair<Int, String> {
        assumeTrue(File("/bin/sh").canExecute())
        val bin = temp.newFolder("bin")
        stub(bin, "uname", "echo '$kernel'")
        managers.forEach { stub(bin, it, "echo CALLED $it \"\$@\"") }
        // Only the stubs and core utilities: a real brew or sudo on the test machine must not run.
        val process = ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true).apply {
            environment().apply { clear(); put("PATH", "${bin.path}:/usr/bin:/bin"); put("HOME", temp.root.path) }
        }.start()
        process.outputStream.use { it.write(HostPluginInstaller.script(plugin, null).toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    private fun stub(bin: File, name: String, body: String) =
        File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
}
