package io.itermux.desktop

import io.itermux.desktop.GestureAction.*
import org.junit.Assert.*
import org.junit.Test

/** The gesture table in docs/desktop-gestures.md, row by row (the same cases as iOS's DesktopGesturesTests). */
class DesktopGesturesTest {
    /** A 2000×1000 view showing a 3024×1964 Retina screen: letterboxed left and right. */
    private val view = Viewport(2000f, 1000f, 3024, 1964)

    private fun gestures(mode: PointerMode = PointerMode.TOUCH) = DesktopGestures(mode).also { it.viewport = view }
    private val mouse = PointerMode.MOUSE

    private fun tap(g: DesktopGestures, x: Float, y: Float, at: Long, id: Int = 0) = g.began(id, x, y, at) + g.ended(id, x, y, at + 60)
    private fun buttons(a: List<GestureAction>) = a.filterIsInstance<Button>()
    private fun moves(a: List<GestureAction>) = a.filterIsInstance<MoveTo>()
    private fun wheels(a: List<GestureAction>) = a.filterIsInstance<Wheel>()
    private fun views(a: List<GestureAction>) = a.filterIsInstance<View>().map { it.viewport }
    private fun two(g: DesktopGestures, x0: Float, y0: Float, x1: Float, y1: Float, t: Long) = g.moved(listOf(Triple(0, x0, y0), Triple(1, x1, y1)), t)
    private val click = Feedback(GestureFeedback.CLICK)
    private val hold = Feedback(GestureFeedback.HOLD)
    private val pickup = Feedback(GestureFeedback.PICKUP)
    private val shortcut = Feedback(GestureFeedback.SHORTCUT)

    @Test fun viewportMapsToNativePixels() {
        assertEquals(1000f / 1964, view.scale, 1e-6f)
        assertEquals(1512 to 982, view.toRemote(1000f, 500f))
        // The black bars clamp to the edge.
        assertEquals(0, view.toRemote(10f, 500f).first)
        assertFalse(view.contains(10f, 500f))
        assertEquals(1512 to 982, view.zoomed(2f, 1000f, 500f).toRemote(1000f, 500f))
        assertEquals(1f, view.zoomed(0.5f, 0f, 0f).zoom)
    }

    // Clicks

    @Test fun touchTapClicksAtOnceWhereTheFingerLanded() {
        val g = gestures()
        assertTrue(g.began(0, 1000f, 500f, 0).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), Button(1, true), Button(1, false)), g.ended(0, 1003f, 500f, 80))
        assertNull("nothing waits after a tap", g.tickAt)
    }

    @Test fun trackpadTapClicksAtOnceWhereTheCursorIs() {
        val g = gestures(mouse)
        g.hostCursor(100, 200, 0)
        assertEquals(listOf(Button(1, true), Button(1, false)), tap(g, 900f, 500f, 1000))
        assertNull("the button is not held for a possible drag", g.tickAt)
        // Moving straight after the tap only moves: no accidental drag.
        g.began(0, 900f, 500f, 1100)
        val out = g.moved(0, 960f, 500f, 1130)
        assertTrue("$out", buttons(out).isEmpty())
        assertEquals(1, moves(out).size)
    }

    @Test fun touchDoubleTapLandsOnOnePixelDespiteJitter() {
        val g = gestures()
        tap(g, 1000f, 500f, 0)
        assertEquals(listOf(MoveTo(1512, 982), Button(1, true), Button(1, false)), tap(g, 1007f, 504f, 160))
        // Too late, or too far: a tap of its own.
        assertNotEquals(1512, moves(tap(g, 1007f, 504f, 900)).first().x)
        tap(g, 1000f, 500f, 2000)
        assertNotEquals(1512, moves(tap(g, 1030f, 500f, 2150)).first().x)
    }

    @Test fun trackpadDoubleTapIsTwoClicksInPlace() {
        val g = gestures(mouse)
        g.hostCursor(300, 300, 0)
        assertEquals(listOf(Button(1, true), Button(1, false), Button(1, true), Button(1, false)), tap(g, 900f, 500f, 1000) + tap(g, 906f, 503f, 1150))
    }

    @Test fun twoFingerTapRightClicks() {
        val g = gestures(mouse)
        g.began(0, 900f, 500f, 0); g.began(1, 1100f, 500f, 20)
        g.ended(0, 900f, 500f, 90)
        assertTrue("a second two-finger tap would zoom instead", g.ended(1, 1100f, 500f, 100).isEmpty())
        assertEquals(400L, g.tickAt)
        assertEquals(listOf(Button(2, true), Button(2, false), click), g.tick(400))
        assertNull(g.tickAt)

        // Touch mode: at the middle of the two fingers.
        val t = gestures()
        t.began(0, 900f, 500f, 0); t.began(1, 1100f, 500f, 10)
        t.ended(0, 900f, 500f, 80); t.ended(1, 1100f, 500f, 80)
        assertEquals(listOf(MoveTo(1512, 982), Button(2, true), Button(2, false), click), t.tick(380))
    }

    @Test fun anotherTouchSendsAWaitingRightClickFirst() {
        val g = gestures(mouse)
        g.began(0, 900f, 500f, 0); g.began(1, 1100f, 500f, 0)
        g.ended(0, 900f, 500f, 80); g.ended(1, 1100f, 500f, 80)
        assertEquals(listOf(Button(2, true), Button(2, false), Button(1, true), Button(1, false)), buttons(tap(g, 900f, 500f, 200)))
    }

    @Test fun threeFingerTapMiddleClicks() {
        val g = gestures(mouse)
        g.began(0, 900f, 500f, 0); g.began(1, 1000f, 500f, 10); g.began(2, 1100f, 500f, 20)
        g.ended(0, 900f, 500f, 90); g.ended(1, 1000f, 500f, 95)
        assertEquals(listOf(Button(3, true), Button(3, false), click), g.ended(2, 1100f, 500f, 100))
        assertNull(g.tickAt)
    }

    // Holds

    @Test fun holdThenLiftRightClicks() {
        val g = gestures()
        g.began(0, 1000f, 500f, 0)
        assertEquals(DesktopGestures.HOLD_MS, g.tickAt)
        assertEquals("the ring fills while waiting", false, g.indicator?.armed)
        assertTrue(g.tick(349).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), hold), g.tick(350))
        assertEquals(true, g.indicator?.armed)
        assertTrue("within the slop it is still a click", g.moved(0, 1004f, 500f, 400).isEmpty())
        assertEquals(listOf(MoveTo(1512, 982), Button(2, true), Button(2, false), click), g.ended(0, 1004f, 500f, 600))
        assertNull(g.indicator)
    }

    @Test fun touchHoldThenMoveDragsFromWhereTheFingerLanded() {
        val g = gestures()
        g.began(0, 1000f, 500f, 0)
        g.tick(350)
        assertEquals(listOf(MoveTo(1512, 982), Button(1, true), pickup, MoveTo(1512 + 39, 982)), g.moved(0, 1020f, 500f, 400))
        assertEquals(listOf(MoveTo(1512 + 196, 982)), g.moved(0, 1100f, 500f, 450))
        assertEquals(listOf(Button(1, false)), g.ended(0, 1100f, 500f, 500))
    }

    @Test fun trackpadHoldThenMoveDragsTheCursor() {
        val g = gestures(mouse)
        g.hostCursor(500, 500, 0)
        g.began(0, 900f, 500f, 1000)
        assertEquals("the cursor stays where it is", listOf(hold), g.tick(1350))
        val out = g.moved(0, 930f, 500f, 1400)
        assertEquals(listOf(Button(1, true), pickup), out.take(2))
        assertTrue(moves(out).first().x > 500)
        assertEquals(listOf(Button(1, false)), g.ended(0, 930f, 500f, 1500))
    }

    @Test fun twoFingerHoldThenMoveRightDrags() {
        val g = gestures()
        g.began(0, 900f, 500f, 0); g.began(1, 1100f, 500f, 0)
        assertEquals(DesktopGestures.HOLD2_MS, g.tickAt)
        assertEquals(listOf(MoveTo(1512, 982), hold), g.tick(500))
        assertEquals(2, g.indicator?.fingers)
        assertEquals(listOf(MoveTo(1512, 982), Button(2, true), pickup, MoveTo(1512, 982 + 58)), two(g, 900f, 530f, 1100f, 530f, 600))
        assertEquals(listOf(Button(2, false)), g.ended(0, 900f, 530f, 700))
        assertTrue(g.ended(1, 1100f, 530f, 710).isEmpty())
    }

    // Moving and scrolling

    @Test fun touchSlideScrollsWhatIsUnderTheFinger() {
        val g = gestures()
        g.began(0, 1000f, 500f, 0)
        assertTrue("within the slop nothing happens yet", g.moved(0, 1000f, 505f, 16).isEmpty())
        val first = g.moved(0, 1000f, 530f, 32)
        assertEquals("the host scrolls what is under its cursor: put it under the finger", MoveTo(1512, 982), first.first())
        assertNull("a slide is never a hold", g.tickAt)
        val out = first.toMutableList()
        var y = 530f
        var t = 32L
        while (y < 800) { y += 25; t += 16; out += g.moved(0, 1000f, y, t) }
        assertTrue("a slide presses nothing: $out", buttons(out).isEmpty())
        assertEquals("the cursor stays put while scrolling", 1, moves(out).size)
        assertTrue(wheels(out).isNotEmpty())
        assertTrue("finger down pulls the page down: ${wheels(out)}", wheels(out).all { it.dy > 0 && it.dx == 0 })
        assertTrue("at full size the picture stays put", views(out).isEmpty())
        // A flick carries on after lifting, slowing down.
        assertTrue("no click after a slide", buttons(g.ended(0, 1000f, y, t + 5)).isEmpty())
        assertEquals(t + 5 + DesktopGestures.FRAME_MS, g.tickAt)
        val coast = mutableListOf<Wheel>()
        while (coast.size < 1000) { val at = g.tickAt ?: break; coast += wheels(g.tick(at)) }
        assertTrue(coast.size > 5)
        assertTrue(coast.all { it.dy > 0 })
        assertNull(g.tickAt)
    }

    @Test fun touchSlideStoppedBeforeLiftingDoesNotCoast() {
        val g = gestures()
        g.began(0, 1000f, 300f, 0)
        for (i in 1..10) g.moved(0, 1000f, 300f + i * 20, i * 16L)
        g.ended(0, 1000f, 500f, 400)
        assertNull(g.tickAt)
    }

    @Test fun zoomedInTouchSlideMovesThePictureThenScrolls() {
        val g = gestures()
        g.viewport = view.zoomed(2f, 1000f, 500f)
        val before = g.viewport
        g.began(0, 1000f, 500f, 0)
        val pan = g.moved(0, 980f, 480f, 30) + g.moved(0, 960f, 460f, 60)
        assertTrue("the picture follows the finger, as a zoomed photo", views(pan).isNotEmpty())
        assertTrue("inside the picture's slack nothing scrolls: $pan", wheels(pan).isEmpty())
        assertTrue(g.viewport.panY < before.panY)
        // Far past the top edge: the picture stops there and the rest scrolls the host.
        val past = g.moved(0, 960f, -3000f, 90)
        val edge = (past.first() as View).viewport
        assertEquals(edge, edge.clamped())
        assertTrue("past the edge the host scrolls: $past", (wheels(past).lastOrNull()?.dy ?: 0) < 0)
        assertTrue(buttons(past).isEmpty())
    }

    @Test fun touchTwoFingerScrollPutsTheCursorBetweenTheFingers() {
        val g = gestures()
        g.began(0, 900f, 300f, 0); g.began(1, 1100f, 300f, 0)
        val out = two(g, 900f, 340f, 1100f, 340f, 30)
        assertEquals(MoveTo(1512, 589), out.first())
        assertTrue(wheels(out).isNotEmpty())
    }

    @Test fun trackpadMovesTheCursorFasterTheFasterTheFinger() {
        fun travel(step: Float, every: Long): Int {
            val g = gestures(mouse)
            g.hostCursor(1000, 1000, 0)
            g.began(0, 500f, 500f, 1000)
            var x = 500f
            var t = 1000L
            var last = 1000
            while (x < 800) { x += step; t += every; moves(g.moved(0, x, 500f, t)).lastOrNull()?.let { last = it.x } }
            return last - 1000
        }
        val slow = travel(2f, 16)
        val fast = travel(30f, 16)
        assertTrue("slow $slow, fast $fast", fast > slow * 3)
        assertEquals(0.5f, DesktopGestures.pointerGain(10f))
        assertEquals(4f, DesktopGestures.pointerGain(100_000f))
    }

    @Test fun trackpadSpeedIsInDpNotPixels() {
        // The same finger speed in dp gives the same travel in picture points at any density.
        fun travel(density: Float): Float {
            val g = DesktopGestures(mouse, density).also { it.viewport = Viewport(2000f * density, 1000f * density, 3024, 1964) }
            g.hostCursor(1000, 1000, 0)
            g.began(0, 500f * density, 500f * density, 1000)
            var x = 500f
            var t = 1000L
            while (x < 800) { x += 10; t += 16; g.moved(0, x * density, 500f * density, t) }
            return g.cursorX
        }
        assertEquals(travel(1f), travel(3f), 2f)
    }

    @Test fun twoFingersScrollWithInertia() {
        val g = gestures()
        g.began(0, 900f, 300f, 0); g.began(1, 1100f, 300f, 0)
        assertTrue(two(g, 900f, 305f, 1100f, 305f, 16).isEmpty())
        val out = mutableListOf<GestureAction>()
        var y = 305f
        var t = 16L
        while (y < 500) { y += 20; t += 16; out += two(g, 900f, y, 1100f, y, t) }
        assertTrue(wheels(out).isNotEmpty())
        assertTrue("fingers moving down scroll up: ${wheels(out)}", wheels(out).all { it.dy > 0 && it.dx == 0 })
        assertTrue(buttons(out).isEmpty())
        g.ended(0, 900f, y, t + 5); g.ended(1, 1100f, y, t + 5)
        assertEquals("a flick carries on", t + 5 + DesktopGestures.FRAME_MS, g.tickAt)
        val coast = mutableListOf<Wheel>()
        var now = t + 5
        while (now < t + 20_000) { val at = g.tickAt ?: break; now = at; coast += wheels(g.tick(at)) }
        assertTrue(coast.size > 5)
        assertTrue(coast.all { it.dy > 0 })
        assertTrue("it slows down", kotlin.math.abs(coast.last().dy) < kotlin.math.abs(coast.first().dy))
        assertNull("and stops", g.tickAt)
    }

    @Test fun pauseBeforeLiftingLeavesNoInertia() {
        val g = gestures()
        g.began(0, 900f, 300f, 0); g.began(1, 1100f, 300f, 0)
        for (i in 1..10) two(g, 900f, 300f + i * 20, 1100f, 300f + i * 20, i * 16L)
        g.ended(0, 900f, 500f, 400); g.ended(1, 1100f, 500f, 400)
        assertNull(g.tickAt)
    }

    @Test fun aNewTouchStopsInertia() {
        val g = gestures()
        g.began(0, 900f, 300f, 0); g.began(1, 1100f, 300f, 0)
        for (i in 1..10) two(g, 900f, 300f + i * 20, 1100f, 300f + i * 20, i * 16L)
        g.ended(0, 900f, 500f, 165); g.ended(1, 1100f, 500f, 165)
        assertNotNull(g.tickAt)
        g.began(0, 900f, 500f, 200)
        assertEquals("only the new touch's hold is pending", 200 + DesktopGestures.HOLD_MS, g.tickAt)
    }

    @Test fun zoomedInTwoFingersStillScrollTheHost() {
        val g = gestures()
        g.viewport = view.zoomed(2f, 1000f, 500f)
        val before = g.viewport
        g.began(0, 900f, 500f, 0); g.began(1, 1100f, 500f, 0)
        val out = two(g, 860f, 460f, 1060f, 460f, 30) + two(g, 820f, 420f, 1020f, 420f, 60)
        assertTrue("the picture stays put: $out", views(out).isEmpty())
        assertTrue(wheels(out).isNotEmpty())
        assertEquals(before, g.viewport)
    }

    // The picture

    @Test fun pinchZoomsAndMovesThePicture() {
        val g = gestures()
        g.began(0, 900f, 500f, 0); g.began(1, 1100f, 500f, 0)
        val pinch = two(g, 850f, 500f, 1150f, 500f, 30)
        assertEquals(1, pinch.size)
        assertEquals(1.5f, views(pinch).first().zoom, 0.01f)
        assertTrue("zoom never reaches the host", wheels(pinch).isEmpty() && buttons(pinch).isEmpty())
        val zoomed = g.viewport
        val pan = two(g, 800f, 500f, 1100f, 500f, 60)
        assertEquals(zoomed.zoom, views(pan).first().zoom, 0.01f)
        assertTrue("the picture follows the fingers while pinching", views(pan).first().panX < zoomed.panX)
    }

    @Test fun twoFingerDoubleTapZoomsInAndOut() {
        val g = gestures(mouse)
        fun twoTap(t: Long) = g.began(0, 1400f, 300f, t) + g.began(1, 1600f, 300f, t) + g.ended(0, 1400f, 300f, t + 60) + g.ended(1, 1600f, 300f, t + 60)
        assertTrue(twoTap(0).isEmpty())
        val zoomIn = twoTap(200)
        assertEquals(2.5f, views(zoomIn).first().zoom, 0.01f)
        assertTrue("no right click", buttons(zoomIn).isEmpty())
        assertNull(g.tickAt)
        // The spot under the fingers stays put.
        assertEquals(view.toRemote(1500f, 300f).first.toFloat(), g.viewport.toRemote(1500f, 300f).first.toFloat(), 2f)
        twoTap(2000)
        val zoomOut = twoTap(2200)
        assertEquals(1f, views(zoomOut).first().zoom)
        assertEquals(0f, views(zoomOut).first().panX)
    }

    @Test fun trackpadZoomedInThePictureFollowsTheCursor() {
        val g = gestures(mouse)
        g.viewport = view.zoomed(2.5f, 1000f, 500f)
        g.hostCursor(1512, 982, 0)
        g.began(0, 500f, 500f, 1000)
        val out = mutableListOf<GestureAction>()
        var x = 500f
        var t = 1000L
        while (x < 900) { x += 20; t += 16; out += g.moved(0, x, 500f, t) }
        assertTrue("the picture moved to keep the cursor in view", views(out).isNotEmpty())
        assertTrue(g.viewport.toView(g.cursorX, g.cursorY).first <= 2000 * 0.85f + 1)
    }

    @Test fun touchDragNearTheEdgeOfAZoomedPictureMovesIt() {
        val g = gestures()
        g.viewport = view.zoomed(2.5f, 1000f, 500f)
        g.began(0, 1000f, 500f, 0)
        g.tick(350)
        g.moved(0, 1500f, 500f, 400)
        g.moved(0, 1990f, 500f, 450)
        assertEquals(450 + DesktopGestures.FRAME_MS, g.tickAt)
        val out = g.tick(466)
        assertTrue("the picture slides left", views(out).first().panX < view.zoomed(2.5f, 1000f, 500f).panX)
        assertEquals("the drag follows to what is now under the finger", 1, moves(out).size)
        g.ended(0, 1990f, 500f, 500)
        assertNull(g.tickAt)
    }

    // Shortcuts

    @Test fun edgeSwipesPutTheCursorOnTheHostsEdge() {
        // The picture spans x 230…1770 of the 2000-wide view (black bars left and right) and all its height.
        val g = gestures()
        g.began(0, 235f, 500f, 0)
        assertEquals(listOf(MoveTo(0, 982), shortcut), g.moved(0, 270f, 502f, 30))
        assertTrue("the rest of the swipe does nothing", g.moved(0, 500f, 502f, 60).isEmpty())
        assertTrue(g.ended(0, 500f, 502f, 90).isEmpty())

        g.began(0, 1000f, 995f, 1000)
        assertEquals(listOf(MoveTo(1512, 1963), shortcut), g.moved(0, 1000f, 960f, 1030))
        g.ended(0, 1000f, 960f, 1060)

        g.began(0, 1765f, 5f, 2000)
        assertEquals("a corner: the hot corner", listOf(MoveTo(3023, 0), shortcut), g.moved(0, 1740f, 20f, 2030))
        g.ended(0, 1740f, 20f, 2060)

        // Along the edge is not a swipe in: it scrolls.
        g.began(0, 235f, 500f, 3000)
        assertEquals(1, moves(g.moved(0, 236f, 540f, 3030)).size)
        assertFalse(g.moved(0, 236f, 560f, 3060).contains(shortcut))
        g.ended(0, 236f, 560f, 3090)
    }

    @Test fun theScreenEdgeOutsideThePictureIsNoEdgeSwipe() {
        // The screen's edge, on the black bar: the system's back gesture starts there, not an edge swipe.
        val g = gestures()
        g.began(0, 5f, 500f, 0)
        val out = g.moved(0, 60f, 502f, 30)
        assertFalse("$out", out.contains(shortcut))
    }

    @Test fun threeFingerSwipes() {
        for ((d, dir) in listOf((-80f to 0f) to SwipeDirection.LEFT, (80f to 0f) to SwipeDirection.RIGHT,
            (0f to -80f) to SwipeDirection.UP, (0f to 80f) to SwipeDirection.DOWN)) {
            val (dx, dy) = d
            val g = gestures(mouse)
            g.began(0, 900f, 500f, 0); g.began(1, 1000f, 500f, 0); g.began(2, 1100f, 500f, 0)
            val out = g.moved(listOf(Triple(0, 900 + dx, 500 + dy), Triple(1, 1000 + dx, 500 + dy), Triple(2, 1100 + dx, 500 + dy)), 50)
            assertEquals(listOf(Swipe(dir), shortcut), out)
            assertTrue("once per swipe", g.moved(0, 900 + 2 * dx, 500 + 2 * dy, 80).isEmpty())
            g.ended(0, 0f, 0f, 100); g.ended(1, 0f, 0f, 100)
            assertTrue("a swipe is no tap", g.ended(2, 0f, 0f, 100).isEmpty())
        }
    }

    @Test fun fourFingersAreLeftToTheSystem() {
        val g = gestures()
        g.began(0, 1000f, 500f, 0)
        g.tick(350)
        g.moved(0, 1100f, 500f, 400)
        g.began(1, 900f, 600f, 450); g.began(2, 1000f, 600f, 450); g.began(3, 1100f, 600f, 450)
        val out = (0..3).flatMap { g.moved(it, 500f, 500f, 500) + g.ended(it, 500f, 500f, 600) }
        assertTrue("$out", out.isEmpty())
    }

    // Odds and ends

    @Test fun aSecondFingerEndsADrag() {
        val g = gestures()
        g.began(0, 1000f, 500f, 0)
        g.tick(350)
        g.moved(0, 1100f, 500f, 400)
        assertEquals(listOf(Button(1, false)), g.began(1, 900f, 500f, 450))
        assertTrue(g.moved(0, 1200f, 500f, 500).isEmpty())
        assertTrue((g.ended(1, 900f, 500f, 550) + g.ended(0, 1200f, 500f, 600)).isEmpty())
    }

    @Test fun theFingerLeftAfterTwoDoesNothing() {
        val g = gestures()
        g.began(0, 900f, 500f, 0); g.began(1, 1100f, 500f, 0)
        two(g, 900f, 540f, 1100f, 540f, 30)
        g.ended(1, 1100f, 540f, 60)
        assertTrue(g.moved(0, 1300f, 500f, 100).isEmpty())
        assertTrue(g.ended(0, 1300f, 500f, 150).isEmpty())
    }

    @Test fun cancelLetsEverythingGo() {
        val g = gestures()
        g.began(0, 1000f, 500f, 0)
        g.tick(350)
        g.moved(0, 1100f, 500f, 400)
        assertEquals(listOf(Button(1, false)), g.cancel())
        assertNull(g.tickAt)
        assertNull(g.indicator)
    }

    @Test fun thePhonesCursorStandsWhileItMovesIt() {
        val g = gestures(mouse)
        g.hostCursor(100, 100, 0)
        assertEquals(100f, g.cursorX)
        g.began(0, 500f, 500f, 1000)
        g.moved(0, 600f, 500f, 1050)
        val local = g.cursorX
        assertTrue(local > 100)
        g.hostCursor(100, 100, 1200)
        assertEquals("a late echo does not pull the cursor back", local, g.cursorX)
        g.hostCursor(700, 800, 2000)
        assertEquals("the host moved it itself", 700f, g.cursorX)
        assertEquals(800f, g.cursorY)
    }

    @Test fun swipesBecomeEachSystemsShortcut() {
        fun eq(a: List<ByteArray>, b: List<ByteArray>) = assertEquals(a.map { it.toList() }, b.map { it.toList() })
        eq(listOf(DesktopProtocol.action(DesktopProtocol.ACTION_ALL_WINDOWS)), DesktopSwipeShortcut.frames(SwipeDirection.UP, "windows"))
        eq(listOf(DesktopProtocol.action(DesktopProtocol.ACTION_SHOW_DESKTOP)), DesktopSwipeShortcut.frames(SwipeDirection.DOWN, "macos"))
        eq(listOf(DesktopProtocol.key(HidUsage.LEFT_CTRL, true), DesktopProtocol.key(HidUsage.RIGHT, true),
            DesktopProtocol.key(HidUsage.RIGHT, false), DesktopProtocol.key(HidUsage.LEFT_CTRL, false)), DesktopSwipeShortcut.frames(SwipeDirection.LEFT, "macOS"))
        eq(listOf(DesktopProtocol.key(HidUsage.LEFT_CTRL, true), DesktopProtocol.key(HidUsage.LEFT_GUI, true),
            DesktopProtocol.key(HidUsage.LEFT, true), DesktopProtocol.key(HidUsage.LEFT, false),
            DesktopProtocol.key(HidUsage.LEFT_GUI, false), DesktopProtocol.key(HidUsage.LEFT_CTRL, false)), DesktopSwipeShortcut.frames(SwipeDirection.RIGHT, "windows"))
        assertEquals(DesktopProtocol.key(HidUsage.LEFT_ALT, true).toList(), DesktopSwipeShortcut.frames(SwipeDirection.LEFT, "linux")[1].toList())
    }
}
