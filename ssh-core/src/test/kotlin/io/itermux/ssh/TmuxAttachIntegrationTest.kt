package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the shipping attach command against an isolated real tmux socket and PTYs. */
class TmuxAttachIntegrationTest {
    private fun run(mode: String) {
        val script = File(System.getProperty("itermux.root"), "ssh-core/src/test/resources/tmux-attach-check.py")
        val process = ProcessBuilder("python3", script.path, mode, Tmux.attachShellCommand("roamtty-review"))
            .redirectErrorStream(true).start()
        try {
            assertTrue("tmux attach check timed out", process.waitFor(15, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assumeFalse(output, output.startsWith("SKIP:"))
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("PASS"))
        } finally { process.destroyForcibly() }
    }

    @Test fun reattachmentExitsCopyModeAndDetachesOnlyPreviousSessionClients() = run("restore")
    @Test fun firstAttachmentStillCreatesTheShell() = run("new")
}
