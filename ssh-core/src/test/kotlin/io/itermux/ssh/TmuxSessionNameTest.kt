package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionNameTest {
    @Test fun sanitizeKeepsSafeNamesAndFallsBack() {
        assertEquals("itermux", Tmux.sanitizeSessionName("itermux"))
        assertEquals("itermux-abc123", Tmux.sanitizeSessionName("itermux-abc123"))
        assertEquals("a-b_1drop", Tmux.sanitizeSessionName("a-b_1!; drop"))
        assertEquals(Tmux.LEGACY_SESSION, Tmux.sanitizeSessionName("!!!"))
        assertEquals(64, Tmux.sanitizeSessionName("x".repeat(80)).length)
    }

    @Test fun attachCommandQuotesSanitizedName() {
        assertEquals("exec tmux new-session -A -s 'itermux'", Tmux.attachCommand(Tmux.LEGACY_SESSION))
        assertEquals("exec tmux new-session -A -s 'itermux-deadbeefrm'", Tmux.attachCommand("itermux-deadbeef;rm"))
    }

    @Test fun newSessionNamesAreUniqueAndPrefixed() {
        val a = Tmux.newSessionName()
        val b = Tmux.newSessionName()
        assertTrue(a.startsWith("itermux-"))
        assertTrue(b.startsWith("itermux-"))
        assertNotEquals(a, b)
        assertEquals(a, Tmux.sanitizeSessionName(a))
        assertFalse(a.contains(" "))
    }
}
