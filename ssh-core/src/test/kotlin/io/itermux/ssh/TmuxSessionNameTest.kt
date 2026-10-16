package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TmuxSessionNameTest {
    @Test fun sanitizeKeepsSafeNamesAndFallsBack() {
        assertEquals("roamtty", Tmux.sanitizeSessionName("roamtty"))
        assertEquals("roamtty-abc123", Tmux.sanitizeSessionName("roamtty-abc123"))
        assertEquals("a-b_1drop", Tmux.sanitizeSessionName("a-b_1!; drop"))
        assertEquals(Tmux.LEGACY_SESSION, Tmux.sanitizeSessionName("!!!"))
        assertEquals(64, Tmux.sanitizeSessionName("x".repeat(80)).length)
    }

    @Test fun attachCommandQuotesSanitizedName() {
        val legacy = Tmux.attachCommand(Tmux.LEGACY_SESSION)
        assertTrue(legacy.startsWith("exec /bin/sh -c '${Tmux.PATH_SETUP}; "))
        val options = "\\; set-option -t roamtty mouse on \\; set-option -t roamtty status off"
        assertTrue(legacy.contains(") exec tmux new-session -A -D -s roamtty $options;; esac; "))
        assertTrue(legacy.endsWith(" $options'"))
        assertTrue(legacy.contains("exec tmux new-session -A -D -s roamtty /bin/sh -c "))
        assertTrue(legacy.contains("#{pane_mode}"))
        assertTrue(legacy.indexOf("send-keys -t roamtty -X cancel") < legacy.indexOf("new-session -A -D"))
        // Only the outer quotes are single quotes, so any login shell passes the script through intact.
        assertEquals(2, legacy.count { it == '\'' })
        assertTrue(legacy.endsWith("'"))
        assertTrue(Tmux.attachCommand("roamtty-deadbeef;rm").contains("new-session -A -D -s roamtty-deadbeefrm /bin/sh"))
        assertTrue(Tmux.PATH_SETUP.contains("/opt/homebrew/bin"))
    }

    @Test fun newSessionNamesAreUniqueAndPrefixed() {
        val a = Tmux.newSessionName()
        val b = Tmux.newSessionName()
        assertTrue(a.startsWith("roamtty-"))
        assertTrue(b.startsWith("roamtty-"))
        assertNotEquals(a, b)
        assertEquals(a, Tmux.sanitizeSessionName(a))
        assertFalse(a.contains(" "))
    }

    @Test fun killScriptTargetsTheExactSanitizedName() {
        val script = Tmux.killSessionScript("roamtty-deadbeef;rm")
        assertTrue(script.startsWith(Tmux.PATH_SETUP))
        assertTrue(script.contains("has-session -t =roamtty-deadbeefrm 2>/dev/null || exit 0"))
        assertTrue(script.contains("exec tmux kill-session -t =roamtty-deadbeefrm"))
    }

    /** Runs the script against a private tmux server: only the named session and its task end. */
    @Test fun killScriptEndsOnlyTheNamedSession() {
        assumeTrue(runCatching { ProcessBuilder("tmux", "-V").start().waitFor() == 0 }.getOrDefault(false))
        val dir = java.nio.file.Files.createTempDirectory("tmux-kill").toFile()
        fun sh(script: String): Int = ProcessBuilder("/bin/sh", "-c", script).also {
            it.environment().remove("TMUX")
            it.environment()["TMUX_TMPDIR"] = dir.path
        }.redirectErrorStream(true).start().waitFor()
        try {
            assertEquals(0, sh("tmux new-session -d -s roamtty-keep 'sleep 600' && tmux new-session -d -s roamtty 'sleep 600'"))
            assertEquals(0, sh(Tmux.killSessionScript("roamtty")))
            assertTrue(sh("tmux has-session -t =roamtty") != 0)
            assertEquals(0, sh("tmux has-session -t =roamtty-keep"))
            // Already gone: still a success, and the prefix never reaches roamtty-keep.
            assertEquals(0, sh(Tmux.killSessionScript("roamtty")))
            assertEquals(0, sh("tmux has-session -t =roamtty-keep"))
        } finally {
            sh("tmux kill-server")
            dir.deleteRecursively()
        }
    }

    @Test fun restartPaneEndsTheForegroundProgramAndReportsIt() {
        assumeTrue(runCatching { ProcessBuilder("tmux", "-V").start().waitFor() == 0 }.getOrDefault(false))
        val dir = java.nio.file.Files.createTempDirectory("tmux-restart").toFile()
        val work = java.io.File(dir, "work").apply { mkdir() }.canonicalPath
        fun sh(script: String): Pair<Int, String> = ProcessBuilder("/bin/sh", "-c", script).also {
            it.environment().remove("TMUX")
            it.environment()["TMUX_TMPDIR"] = dir.path
        }.redirectErrorStream(true).start().let { val out = it.inputStream.bufferedReader().readText(); it.waitFor() to out }
        try {
            assertEquals(0, sh("tmux new-session -d -s roamtty-r -x 80 -y 24 -c '$work' sh").first)
            sh("tmux send-keys -t roamtty-r 'tail -f /dev/null --retry' Enter")
            var running = ""
            repeat(50) { if (running != "tail") { Thread.sleep(100); running = sh("tmux display -p -t roamtty-r '#{pane_current_command}'").second.trim() } }
            assertEquals("tail", running)
            val pid = sh("tmux display -p -t roamtty-r '#{pane_pid}'").second.trim()
            assertEquals(0 to "tail -f /dev/null --retry", sh(Tmux.restartPaneCommand("roamtty-r")).let { it.first to it.second.trim() })
            // A fresh pane process in the same folder; a bare shell reports no program.
            assertTrue(sh("tmux display -p -t roamtty-r '#{pane_pid}'").second.trim() != pid)
            assertEquals(work, sh("tmux display -p -t roamtty-r '#{pane_current_path}'").second.trim())
            assertEquals(0 to "", sh(Tmux.restartPaneCommand("roamtty-r")).let { it.first to it.second.trim() })
            assertTrue(sh(Tmux.restartPaneCommand("roamtty-missing")).first != 0)
        } finally {
            sh("tmux kill-server")
            dir.deleteRecursively()
        }
    }

    @Test fun agentStatusKeepsNonAsciiWithoutAUtf8Locale() {
        assumeTrue(runCatching { ProcessBuilder("tmux", "-V").start().waitFor() == 0 }.getOrDefault(false))
        val dir = java.nio.file.Files.createTempDirectory("tmux-status").toFile()
        val work = java.io.File(dir, "工作").apply { mkdir() }.canonicalPath
        // As sshd on macOS runs a command: no LANG, LC_CTYPE or LC_ALL.
        fun sh(script: String): String = ProcessBuilder("/bin/sh", "-c", script).also {
            it.environment().keys.removeAll { key -> key == "TMUX" || key == "LANG" || key.startsWith("LC_") }
            it.environment()["TMUX_TMPDIR"] = dir.path
        }.redirectErrorStream(true).start().let { val out = it.inputStream.bufferedReader().readText(); it.waitFor(); out }
        try {
            sh("tmux -u new-session -d -s roamtty-s -c '$work' sh && tmux -u set-option -t roamtty-s @roamtty_task '帮我编译 App'")
            val lines = sh(Tmux.agentStatusCommand("roamtty-s")).lines()
            assertEquals("sh|||帮我编译 App|", lines[0])
            assertEquals(work, lines[1])
        } finally {
            sh("tmux kill-server")
            dir.deleteRecursively()
        }
    }
}
