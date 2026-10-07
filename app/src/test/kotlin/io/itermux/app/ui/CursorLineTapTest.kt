package io.itermux.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorLineTapTest {
    // Cursor row 300..340: with two slack rows each side the band is 220..420.
    private val row = intArrayOf(300, 340)

    @Test fun tapOnCursorLineHits() {
        assertTrue(tapHitsCursorLine(320f, row, 0f))
    }

    @Test fun tapWithinTwoRowsHits() {
        assertTrue(tapHitsCursorLine(225f, row, 0f))
        assertTrue(tapHitsCursorLine(415f, row, 0f))
    }

    @Test fun tapThreeRowsAwayMisses() {
        assertFalse(tapHitsCursorLine(200f, row, 0f))
        assertFalse(tapHitsCursorLine(430f, row, 0f))
    }

    @Test fun tinyFontStillGetsMinimumTouchHeight() {
        // 10px rows give a 50px band; the 300px minimum widens it to 170..470 around centre 320.
        assertTrue(tapHitsCursorLine(180f, intArrayOf(315, 325), 300f))
        assertFalse(tapHitsCursorLine(160f, intArrayOf(315, 325), 300f))
    }

    @Test fun lineUnderStatusBarSlidesBelowIt() {
        // First row 0..40 (band -80..120) under a 100px status bar slides to 100..300.
        assertTrue(tapHitsCursorLine(290f, intArrayOf(0, 40), 0f, touchableTop = 100f))
        assertFalse(tapHitsCursorLine(305f, intArrayOf(0, 40), 0f, touchableTop = 100f))
    }

    @Test fun cursorScrolledOutOfViewNeverHits() {
        assertFalse(tapHitsCursorLine(110f, null, 90f))
    }
}
