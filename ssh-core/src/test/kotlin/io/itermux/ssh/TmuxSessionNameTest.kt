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
        assertTrue(legacy.contains(") exec tmux new-session -A -D -s roamtty \\; set-option -t roamtty mouse on;; esac; "))
        assertTrue(legacy.endsWith(" \\; set-option -t roamtty mouse on'"))
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
}
