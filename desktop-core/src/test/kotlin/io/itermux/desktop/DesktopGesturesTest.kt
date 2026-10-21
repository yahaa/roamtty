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
        assertEquals(500L, g.longPressAt)
        assertTrue(g.tick(499).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), Button(2, true), Button(2, false)), g.tick(500))
        assertTrue(g.up(1000f, 500f, 700).isEmpty())
    }

    @Test fun tapThenHoldDrags() {
        val g = gestures()
        g.down(1000f, 500f, 0); g.up(1000f, 500f, 50)
        assertEquals(listOf(MoveTo(1512, 982), Button(1, true)), g.down(1000f, 500f, 200))
        assertNull("a drag is never a long press", g.longPressAt)
        assertEquals(listOf(MoveTo(1512 + 196, 982)), g.move(1100f, 500f, 260))
        assertEquals(listOf(Button(1, false)), g.up(1100f, 500f, 300))
    }

    @Test fun oneFingerPansOnlyWhenZoomed() {
        val g = gestures()
        g.down(1000f, 500f, 0)
        assertTrue(g.move(1100f, 500f, 50).isEmpty())
        g.up(1100f, 500f, 60)
        g.viewport = view.zoomed(2f, 1000f, 500f)
        g.down(1000f, 500f, 1000)
        val out = g.move(1100f, 500f, 1050)
        assertTrue(out.single() is View)
        assertEquals(100f, (out.single() as View).viewport.panX, 0.01f)
    }

    @Test fun mouseModeMovesRelativelyAndClicksInPlace() {
        val g = gestures(PointerMode.MOUSE)
        g.down(100f, 100f, 0)
        val moves = g.move(150f, 100f, 30)
        assertEquals(1, moves.size)
        // 50 view px / scale × speed.
        assertEquals((50 / view.scale * DesktopGestures.MOUSE_SPEED).toInt(), (moves.single() as MoveBy).dx)
        g.up(150f, 100f, 40)
        g.down(150f, 100f, 1000)
        assertEquals(listOf(Button(1, true), Button(1, false)), g.up(150f, 100f, 1050))
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
