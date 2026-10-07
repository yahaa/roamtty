package io.itermux.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorLineTapTest {
    @Test fun tapInsideCursorLineHits() {
        assertTrue(tapHitsCursorLine(110f, intArrayOf(100, 160), 0f))
    }

    @Test fun tapOnOtherLineMisses() {
        assertFalse(tapHitsCursorLine(40f, intArrayOf(100, 160), 0f))
        assertFalse(tapHitsCursorLine(160f, intArrayOf(100, 160), 0f))
    }

    @Test fun thinLineGrowsToMinimumTouchHeight() {
        // 30px line centred at 115 widened to 90px: 70..160.
        assertTrue(tapHitsCursorLine(75f, intArrayOf(100, 130), 90f))
        assertTrue(tapHitsCursorLine(155f, intArrayOf(100, 130), 90f))
        assertFalse(tapHitsCursorLine(65f, intArrayOf(100, 130), 90f))
    }

    @Test fun cursorScrolledOutOfViewNeverHits() {
        assertFalse(tapHitsCursorLine(110f, null, 90f))
    }
}
