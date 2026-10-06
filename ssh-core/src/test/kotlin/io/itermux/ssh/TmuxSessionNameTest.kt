package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        assertTrue(legacy.contains(") exec tmux new-session -A -D -s roamtty;; esac; "))
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
}
