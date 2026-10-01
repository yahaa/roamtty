package io.itermux.app.session

import org.junit.Assert.*
import org.junit.Test

class OutputHistoryTest {
    @Test fun preservesOverwrittenOutputAndDeduplicatesUnchangedFrames() {
        val history = OutputHistory()
        history.capture("Header\nold reply\nPrompt")
        history.capture("Header\nold reply\nPrompt")
        history.capture("Header\nnew reply\nPrompt")
        assertEquals(listOf("Header", "old reply", "Prompt", "new reply"), history.snapshot())
    }

    @Test fun mergesScrollingFramesAndKeepsFrozenSnapshots() {
        val history = OutputHistory()
        history.capture("one\ntwo\nthree")
        val snapshot = history.snapshot()
        history.capture("two\nthree\nfour")
        assertEquals(listOf("one", "two", "three", "four"), history.snapshot())
        assertEquals(listOf("one", "two", "three"), snapshot)
    }

    @Test fun boundsMemoryAndLines() {
        val history = OutputHistory(maxChars = 64, maxLines = 3)
        repeat(1000) { history.capture("Reply $it") }
        assertEquals(listOf("Reply 997", "Reply 998", "Reply 999"), history.snapshot())
        history.capture("x".repeat(1000))
        assertTrue(history.snapshot().sumOf { it.length + 1 } <= 64)
    }
}
