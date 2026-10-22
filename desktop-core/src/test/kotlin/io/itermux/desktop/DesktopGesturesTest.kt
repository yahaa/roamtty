package io.itermux.desktop

import io.itermux.desktop.GestureAction.*
import org.junit.Assert.*
import org.junit.Test

class DesktopGesturesTest {
    /** A 2000×1000 view showing a 3024×1964 Retina screen: letterboxed left and right. */
    private val view = Viewport(2000f, 1000f, 3024, 1964)

    private fun gestures(mode: PointerMode = PointerMode.TOUCH) = DesktopGestures(mode).also { it.viewport = view }

    @Test fun viewportMapsToNativePixels() {
        val scale = 1000f / 1964
        assertEquals(scale, view.scale, 1e-6f)
        val (cx, cy) = view.toRemote(1000f, 500f)
        assertEquals(1512, cx)
        assertEquals(982, cy)
        // The black bars clamp to the edge.
        assertEquals(0, view.toRemote(10f, 500f).first)
        assertFalse(view.contains(10f, 500f))
        val zoomed = view.zoomed(2f, 1000f, 500f)
        assertEquals(1512 to 982, zoomed.toRemote(1000f, 500f))
        assertEquals(1f, view.zoomed(0.5f, 0f, 0f).zoom)
    }

    @Test fun touchTapClicksWhereTheFingerIs() {
        val g = gestures()
        assertTrue(g.down(1000f, 500f, 0).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), Button(1, true), Button(1, false)), g.up(1001f, 500f, 80))
    }

    @Test fun touchLongPressRightClicks() {
        val g = gestures()
        g.down(1000f, 500f, 0)
        assertEquals(500L, g.tickAt)
        assertTrue(g.tick(499).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), Button(2, true), Button(2, false)), g.tick(500))
        assertNull(g.tickAt)
        assertTrue("a long press does not drag", g.move(1100f, 500f, 600).isEmpty())
        assertTrue(g.up(1100f, 500f, 700).isEmpty())
    }

    @Test fun touchDragHoldsTheButtonFromWhereTheFingerLanded() {
        val g = gestures()
        g.down(1000f, 500f, 0)
        assertTrue("within the slop nothing happens yet", g.move(1005f, 500f, 20).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), Button(1, true), MoveTo(1512 + 39, 982)), g.move(1020f, 500f, 40))
        assertNull("a drag is never a long press", g.tickAt)
        assertEquals(listOf(MoveTo(1512 + 196, 982)), g.move(1100f, 500f, 80))
        assertEquals(listOf(Button(1, false)), g.up(1100f, 500f, 120))
    }

    @Test fun touchDragsWhenZoomedToo() {
        val g = gestures()
        g.viewport = view.zoomed(2f, 1000f, 500f)
        g.down(1000f, 500f, 0)
        val out = g.move(1100f, 500f, 50)
        assertEquals(Button(1, true), out[1])
        assertTrue(out.none { it is View })
    }

    @Test fun twoTapsAreADoubleClickInTouchMode() {
        val g = gestures()
        g.down(1000f, 500f, 0)
        assertEquals(3, g.up(1000f, 500f, 50).size)
        assertTrue(g.down(1000f, 500f, 150).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), Button(1, true), Button(1, false)), g.up(1000f, 500f, 200))
    }

    @Test fun mouseModeMovesRelativelyAndClicksInPlace() {
        val g = gestures(PointerMode.MOUSE)
        g.down(100f, 100f, 0)
        val moves = g.move(150f, 100f, 30)
        assertEquals(1, moves.size)
        // 50 view px / scale × speed.
        assertEquals((50 / view.scale * DesktopGestures.MOUSE_SPEED).toInt(), (moves.single() as MoveBy).dx)
        assertTrue("moving is no tap", g.up(150f, 100f, 40).isEmpty())
        g.down(150f, 100f, 1000)
        // The press goes out at once; its release waits for a possible second touch.
        assertEquals(listOf(Button(1, true)), g.up(150f, 100f, 1050))
        assertEquals(1050L + DesktopGestures.DOUBLE_TAP_MS, g.tickAt)
        assertTrue(g.tick(1300).isEmpty())
        assertEquals(listOf(Button(1, false)), g.tick(1350))
        assertNull(g.tickAt)
    }

    @Test fun mouseTapThenHoldDragsWithOnePress() {
        val g = gestures(PointerMode.MOUSE)
        g.down(100f, 100f, 0)
        assertEquals(listOf(Button(1, true)), g.up(100f, 100f, 50))
        assertTrue("the tap's press carries on", g.down(300f, 300f, 200).isEmpty())
        assertNull("no release, no long press", g.tickAt)
        assertTrue(g.move(350f, 300f, 250).single() is MoveBy)
        assertEquals(listOf(Button(1, false)), g.up(350f, 300f, 300))
        assertNull(g.tickAt)
    }

    @Test fun mouseTwoTapsDoubleClick() {
        val g = gestures(PointerMode.MOUSE)
        g.down(100f, 100f, 0)
        g.up(100f, 100f, 50)
        g.down(100f, 100f, 150)
        assertEquals(listOf(Button(1, false), Button(1, true), Button(1, false)), g.up(100f, 100f, 200))
        assertNull(g.tickAt)
    }

    @Test fun mouseLateTouchReleasesTheTapFirst() {
        val g = gestures(PointerMode.MOUSE)
        g.down(100f, 100f, 0)
        g.up(100f, 100f, 50)
        // The tick was late: the next touch lets the button go before anything else.
        assertEquals(listOf(Button(1, false)), g.down(100f, 100f, 600))
        assertEquals(1100L, g.tickAt)
    }

    @Test fun cancelLetsEverythingGo() {
        val g = gestures(PointerMode.MOUSE)
        g.down(100f, 100f, 0)
        g.up(100f, 100f, 50)
        assertEquals(listOf(Button(1, false)), g.cancel())
        assertNull(g.tickAt)
        val t = gestures()
        t.down(1000f, 500f, 0)
        t.move(1100f, 500f, 50)
        assertEquals(listOf(Button(1, false)), t.cancel())
    }

    @Test fun theFingerLeftAfterTwoDoesNothing() {
        val g = gestures()
        g.down(900f, 500f, 0)
        g.twoDown(900f, 500f, 1100f, 500f)
        g.twoUp()
        assertTrue(g.move(1300f, 500f, 100).isEmpty())
        assertTrue(g.up(1300f, 500f, 150).isEmpty())
    }

    @Test fun twoFingersScrollOrZoom() {
        val g = gestures()
        g.down(900f, 500f, 0)
        g.twoDown(900f, 500f, 1100f, 500f)
        assertTrue(g.twoMove(900f, 505f, 1100f, 505f).isEmpty())
        val scroll = g.twoMove(900f, 540f, 1100f, 540f)
        assertEquals(Wheel(0, 140), scroll.single())
        g.twoUp()
        assertTrue("lifting after a two-finger gesture is no tap", g.up(900f, 540f, 400).isEmpty())

        val z = gestures()
        z.down(900f, 500f, 0)
        z.twoDown(900f, 500f, 1100f, 500f)
        val pinch = z.twoMove(850f, 500f, 1150f, 500f)
        assertEquals(1.5f, (pinch.single() as View).viewport.zoom, 0.01f)
    }
}
