package io.itermux.desktop

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
        assertEquals(300f, PipLayout.size(room, 1000f, 0.1f, 1.6f).first, 0.01f)
        assertEquals(1000f, PipLayout.size(room, 1000f, 3f, 1.6f).first, 0.01f)
    }

    @Test fun aRoomTooLowShrinksItKeepingItsAspect() {
        val low = PipRect(0f, 0f, 1000f, 300f)
        val (w, h) = PipLayout.size(low, 1000f, 1f, 1.6f)
        assertEquals(300f, h, 0.01f)
        assertEquals(480f, w, 0.01f)
    }

    @Test fun placeSpansTheFreeRoom() {
        assertEquals(PipRect(0f, 0f, 200f, 100f), PipLayout.place(room, 200f, 100f, 0f, 0f))
        assertEquals(PipRect(800f, 1900f, 1000f, 2000f), PipLayout.place(room, 200f, 100f, 1f, 1f))
        assertEquals(PipRect(400f, 950f, 600f, 1050f), PipLayout.place(room, 200f, 100f, 0.5f, 0.5f))
        // Out-of-range fractions stay inside.
        assertEquals(PipRect(800f, 0f, 1000f, 100f), PipLayout.place(room, 200f, 100f, 3f, -1f))
    }

    @Test fun fractionsRoundTripAndClampADropOutside() {
        val (fx, fy) = PipLayout.fractions(room, 200f, 100f, 400f, 950f)
        assertEquals(0.5f, fx, 0.001f)
        assertEquals(0.5f, fy, 0.001f)
        assertEquals(1f to 0f, PipLayout.fractions(room, 200f, 100f, 5000f, -300f))
        // A window as wide as the room has nowhere to go sideways.
        assertEquals(0f, PipLayout.fractions(room, 1000f, 100f, 50f, 0f).first, 0.001f)
    }

    @Test fun theSamePlaceWhenTheRoomShrinks() {
        val (fx, fy) = PipLayout.fractions(room, 200f, 100f, 800f, 1900f)
        val shorter = PipRect(0f, 0f, 1000f, 1200f)
        assertEquals(PipRect(800f, 1100f, 1000f, 1200f), PipLayout.place(shorter, 200f, 100f, fx, fy))
    }

    @Test fun theHandleIsOnTheSideWithRoomToGrow() {
        assertTrue(PipLayout.handleOnLeft(room, PipRect(700f, 0f, 1000f, 200f)))
        assertFalse(PipLayout.handleOnLeft(room, PipRect(0f, 0f, 300f, 200f)))
        // Full width, or nearly: it stays on the right rather than jumping with a pixel.
        assertFalse(PipLayout.handleOnLeft(room, PipRect(0f, 0f, 1000f, 600f)))
        assertFalse(PipLayout.handleOnLeft(room, PipRect(0.5f, 0f, 1000f, 600f)))
    }

    @Test fun draggingTheHandleOutwardsGrows() {
        assertEquals(500f, PipLayout.resized(400f, 100f, 62.5f, onLeft = false, aspect = 1.6f), 0.01f)
        assertEquals(500f, PipLayout.resized(400f, -100f, 62.5f, onLeft = true, aspect = 1.6f), 0.01f)
        assertEquals(300f, PipLayout.resized(400f, -100f, -62.5f, onLeft = false, aspect = 1.6f), 0.01f)
    }

    @Test fun theBarGoesAboveElseBelowElseOverTheTopEdge() {
        assertEquals(560f, PipLayout.barTop(room, PipRect(0f, 600f, 500f, 900f), 32f, 8f), 0.01f)
        assertEquals(308f, PipLayout.barTop(room, PipRect(0f, 0f, 500f, 300f), 32f, 8f), 0.01f)
        // As tall as the room: over its top edge, still inside.
        assertEquals(8f, PipLayout.barTop(room, PipRect(0f, 0f, 500f, 2000f), 32f, 8f), 0.01f)
        assertEquals(10f, PipLayout.barTop(PipRect(0f, 10f, 1000f, 2000f), PipRect(0f, 10f, 500f, 2000f), 32f, 0f), 0.01f)
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
}
