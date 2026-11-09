package io.itermux.desktop

import io.itermux.desktop.PipAnchor.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipLayoutTest {
    private val room = PipRect(0f, 0f, 1000f, 2000f)

    @Test fun sizeFollowsTheFractionAndAspect() {
        val (w, h) = PipLayout.size(room, 1000f, 0.7f, 1.6f)
        assertEquals(700f, w, 0.01f)
        assertEquals(437.5f, h, 0.01f)
    }

    @Test fun fractionIsClampedToTheAllowedRange() {
        assertEquals(400f, PipLayout.size(room, 1000f, 0.1f, 1.6f).first, 0.01f)
        assertEquals(1000f, PipLayout.size(room, 1000f, 3f, 1.6f).first, 0.01f)
    }

    @Test fun aPictureTooTallForTheRoomShrinksBothWays() {
        val low = PipRect(0f, 0f, 1000f, 300f)
        val (w, h) = PipLayout.size(low, 1000f, 1f, 1.6f)
        assertEquals(300f, h, 0.01f)
        assertEquals(480f, w, 0.01f)
    }

    @Test fun aStretchWindowKeepsItsWidthInALowRoom() {
        val band = PipRect(0f, 100f, 1000f, 400f)
        val (w, h) = PipLayout.size(band, 1000f, 1f, 1.6f, stretch = true)
        assertEquals(1000f, w, 0.01f)
        assertEquals(300f, h, 0.01f)
    }

    @Test fun placeRestsInTheCorner() {
        assertEquals(PipRect(800f, 0f, 1000f, 100f), PipLayout.place(room, 200f, 100f, TOP_END))
        assertEquals(PipRect(0f, 1900f, 200f, 2000f), PipLayout.place(room, 200f, 100f, BOTTOM_START))
    }

    @Test fun nearestCornerFollowsTheCentre() {
        assertEquals(TOP_START, PipLayout.nearest(room, 100f, 100f))
        assertEquals(TOP_END, PipLayout.nearest(room, 900f, 100f))
        assertEquals(BOTTOM_START, PipLayout.nearest(room, 100f, 1900f))
        assertEquals(BOTTOM_END, PipLayout.nearest(room, 600f, 1200f))
    }

    @Test fun aCoveredCursorRowSendsTheWindowDown() {
        val cursorRow = PipRect(0f, 50f, 1000f, 80f)
        assertEquals(BOTTOM_END, PipLayout.clear(room, 300f, 200f, TOP_END, listOf(cursorRow)))
    }

    @Test fun aTallDockOnTheEndSendsTheWindowToTheStart() {
        val dock = PipRect(900f, 200f, 1000f, 1800f)
        assertEquals(TOP_START, PipLayout.clear(room, 300f, 300f, TOP_END, listOf(dock)))
    }

    @Test fun nothingInTheWayKeepsTheCorner() {
        assertEquals(TOP_END, PipLayout.clear(room, 300f, 200f, TOP_END, listOf(PipRect(0f, 1000f, 10f, 1010f))))
        assertEquals(TOP_END, PipLayout.clear(room, 300f, 200f, TOP_END, emptyList()))
    }

    @Test fun everyCornerCoveredKeepsTheCorner() {
        assertEquals(BOTTOM_START, PipLayout.clear(room, 300f, 200f, BOTTOM_START, listOf(room)))
    }

    @Test fun closeTargetIsARadius() {
        assertTrue(PipLayout.overClose(500f, 1900f, 510f, 1910f, 50f))
        assertFalse(PipLayout.overClose(500f, 1700f, 510f, 1910f, 50f))
    }

    @Test fun pinchScalesWithinTheRange() {
        assertEquals(0.84f, PipLayout.pinched(0.7f, 1.2f), 0.001f)
        assertEquals(PipLayout.MAX_FRACTION, PipLayout.pinched(0.9f, 2f), 0.001f)
        assertEquals(PipLayout.MIN_FRACTION, PipLayout.pinched(0.5f, 0.2f), 0.001f)
    }

    @Test fun anchorNamesRoundTrip() {
        PipAnchor.entries.forEach { assertEquals(it, PipAnchor.parse(it.name)) }
        assertEquals(null, PipAnchor.parse("nowhere"))
    }
}
