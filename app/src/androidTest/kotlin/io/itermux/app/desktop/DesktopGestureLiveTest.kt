package io.itermux.app.desktop

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.ssh.AuthMethod
import io.itermux.app.data.Host
import io.itermux.app.data.StoredPin
import io.itermux.app.e2e.E2e
import io.itermux.app.session.SessionService
import io.itermux.desktop.DesktopPhase
import io.itermux.desktop.DesktopProtocol
import io.itermux.desktop.HidUsage
import io.itermux.desktop.PointerMode
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * The gesture table (docs/desktop-gestures.md) against a real desktop host, the same flows as iOS's
 * DesktopGestureUITests: every gesture is made with real multi-touch injected at real times, the input
 * the page sent is read back from [DesktopInputProbe], and the host's picture shows the effect.
 * Opt-in: instrumentation arguments gHost, gPort, gUser, gPassword, gKeyType and gKey (its host key)
 * of a disposable desktop host.
 */
@RunWith(AndroidJUnit4::class)
class DesktopGestureLiveTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    @get:Rule(order = 2) val shotOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = capture("FAIL-${description.methodName}")
    }
    private val args get() = InstrumentationRegistry.getArguments()
    private val address get() = args.getString("gHost").orEmpty()
    private val port get() = args.getString("gPort")?.toIntOrNull() ?: 22
    private lateinit var service: SessionService
    private lateinit var host: Host
    private val failures = mutableListOf<String>()
    /** The picture's rectangle on the screen, in screen pixels. */
    private var picLeft = 0f; private var picTop = 0f; private var picW = 0f; private var picH = 0f
    /** The touch view's rectangle on the screen. */
    private var viewLeft = 0f; private var viewTop = 0f; private var viewW = 0f; private var viewH = 0f

    @Before fun setUp() {
        assumeTrue("Set gHost, gUser, gPassword, gKeyType and gKey to a disposable desktop host", address.isNotEmpty())
        service = E2e.bind(serviceRule)
        cleanup()
        DesktopInputProbe.enabled = true
        E2e.prepareActivity(compose)
    }

    @After fun tearDown() {
        DesktopInputProbe.enabled = false
        if (::service.isInitialized && args.getString("gKeep") == null) cleanup()
    }

    /** Saves the host and leaves it (with gKeep=1), for checks by hand in a normal launch. */
    @Test fun saveHostOnly() {
        assumeTrue(args.getString("gKeep") != null)
        val id = UUID.randomUUID().toString()
        val h = Host(id, PREFIX + address, address, port, args.getString("gUser") ?: "desktop", AuthMethod.PASSWORD.name, SessionMode.SHELL.name,
            encryptedPassword = E2e.app.vault.encrypt(args.getString("gPassword").orEmpty().toByteArray(), "$id:password"),
            os = args.getString("gOs") ?: "LINUX", desktop = "yes")
        runBlocking {
            val endpoint = "[$address]:$port"
            E2e.app.database.hosts().forgetPin(endpoint)
            E2e.app.database.hosts().insertPin(StoredPin(endpoint, args.getString("gKeyType").orEmpty(), args.getString("gKey").orEmpty()))
            E2e.app.database.hosts().save(h)
        }
    }

    private fun cleanup() = runBlocking {
        val hosts = E2e.app.database.hosts().observe().first().filter { it.name.startsWith(PREFIX) }
        withContext(Dispatchers.Main) { hosts.forEach { service.desktops.close(it.id) } }
        hosts.forEach { E2e.app.database.hosts().delete(it.id) }
    }

    private fun open(mode: PointerMode) {
        val id = UUID.randomUUID().toString()
        host = Host(id, PREFIX + address, address, port, args.getString("gUser") ?: "desktop", AuthMethod.PASSWORD.name, SessionMode.SHELL.name,
            encryptedPassword = E2e.app.vault.encrypt(args.getString("gPassword").orEmpty().toByteArray(), "$id:password"),
            os = args.getString("gOs") ?: "LINUX", desktop = "yes")
        runBlocking {
            val endpoint = "[$address]:$port"
            E2e.app.database.hosts().forgetPin(endpoint)
            E2e.app.database.hosts().insertPin(StoredPin(endpoint, args.getString("gKeyType").orEmpty(), args.getString("gKey").orEmpty()))
            E2e.app.database.hosts().save(host)
        }
        DesktopSettings(E2e.context).setPointerMode(id, mode)
        E2e.waitTag(compose, "home-tab-hosts", 15_000)
        compose.onNodeWithTag("home-tab-hosts").performClick()
        E2e.waitTag(compose, "host-screen-$id", 15_000)
        // The card opens the desktop once the host shows reachable; the detail page's 桌面 does at once.
        compose.onNodeWithTag("host-card-$id").performClick()
        E2e.waitTag(compose, "host-detail-desktop", 15_000)
        compose.onNodeWithTag("host-detail-desktop").performClick()
        E2e.waitTag(compose, "desktop-page", 15_000)
        try { compose.waitUntil(90_000) { service.desktops[id]?.phase?.value.let { it is DesktopPhase.Connected || it is DesktopPhase.Failed } } }
        catch (e: Throwable) { throw AssertionError("not connected: ${service.desktops[id]?.phase?.value} ${service.desktops[id]?.log?.value?.takeLast(8)}", e) }
        assertTrue("${desktop().phase.value} ${desktop().log.value.takeLast(8)}", desktop().phase.value is DesktopPhase.Connected)
        E2e.waitTag(compose, "desktop-touch", 15_000)
        compose.waitUntil(15_000) { desktop().hello.value != null }
        pause(2.0)
        locate()
    }

    private fun desktop() = service.desktops[host.id] ?: error("no desktop")

    private fun locate() {
        val node = compose.onNodeWithTag("desktop-touch").fetchSemanticsNode().boundsInRoot
        val loc = IntArray(2)
        compose.activityRule.scenario.onActivity { it.window.decorView.getLocationOnScreen(loc) }
        viewLeft = loc[0] + node.left; viewTop = loc[1] + node.top; viewW = node.width; viewH = node.height
        val d = desktop().hello.value!!.displays[desktop().display.value]
        val aspect = d.height.toFloat() / d.width
        picW = min(viewW, viewH / aspect); picH = picW * aspect
        picLeft = viewLeft + (viewW - picW) / 2; picTop = viewTop + (viewH - picH) / 2
    }

    // Points, in screen pixels

    private fun at(fx: Float, fy: Float) = P(viewLeft + viewW * fx, viewTop + viewH * fy)
    /** A point inside the host's picture, as fractions of it (the view letterboxes it). */
    private fun pic(fx: Float, fy: Float) = P(picLeft + picW * fx, picTop + picH * fy)

    // Probe

    private fun tokens() = DesktopInputProbe.log

    private fun ordered(expected: List<String>, seen: List<String>): Boolean {
        var i = 0
        for (t in seen) if (i < expected.size && (t == expected[i] || (expected[i].endsWith("*") && t.startsWith(expected[i].dropLast(1))))) i++
        return i == expected.size
    }

    /** Waits until the tokens hold [expected] in order ("m*" for any move); records a failure otherwise and carries on. */
    private fun expect(expected: List<String>, step: String, within: Double = 3.0): List<String> {
        val end = SystemClock.uptimeMillis() + (within * 1000).toLong()
        var seen: List<String>
        do {
            seen = tokens()
            if (ordered(expected, seen)) { E2e.log("ok  $step: $seen"); return seen }
            pause(0.1)
        } while (SystemClock.uptimeMillis() < end)
        fail("$step: expected $expected in order, got $seen")
        return seen
    }

    private fun none(prefix: String, step: String) {
        val seen = tokens()
        if (seen.any { it.startsWith(prefix) }) fail("$step: no $prefix expected, got $seen")
    }

    private fun fail(message: String) { E2e.log("FAIL $message"); failures += message }
    private fun check(ok: Boolean, message: String) { if (!ok) fail(message) }

    private fun pause(s: Double = 0.8) = Thread.sleep((s * 1000).toLong())

    private fun capture(name: String) { E2e.screenshot("gesture-${host.os.lowercase()}-$name") }

    private fun escape() {
        desktop().input(DesktopProtocol.key(HidUsage.ESCAPE, true)); desktop().input(DesktopProtocol.key(HidUsage.ESCAPE, false))
        pause(1.0)
    }

    // The picture

    /** The middle of the host's picture, small and grey, to tell whether the host's screen changed. */
    private fun picture(): IntArray {
        val shot = E2e.instrumentation.uiAutomation.takeScreenshot() ?: return IntArray(0)
        val x = (picLeft + picW * 0.1f).toInt(); val y = (picTop + picH * 0.2f).toInt()
        val crop = Bitmap.createBitmap(shot, x, y, (picW * 0.8f).toInt(), (picH * 0.6f).toInt())
        val small = Bitmap.createScaledBitmap(crop, 96, 48, true)
        val px = IntArray(96 * 48)
        small.getPixels(px, 0, 96, 0, 0, 96, 48)
        return IntArray(px.size) { (AColor.red(px[it]) * 30 + AColor.green(px[it]) * 59 + AColor.blue(px[it]) * 11) / 100 }
    }

    private fun share(a: IntArray, b: IntArray) = a.indices.count { it < b.size && abs(a[it] - b[it]) > 16 }.toDouble() / a.size.coerceAtLeast(1)

    private fun assertChanged(before: IntArray, what: String) {
        val end = SystemClock.uptimeMillis() + 3000
        var s: Double
        do { s = share(before, picture()); if (s > 0.004) return; pause(0.2) } while (SystemClock.uptimeMillis() < end)
        fail("$what: the host's picture did not change (share $s)")
    }

    private fun wheelDy(seen: List<String>) = seen.firstOrNull { it.startsWith("w") }?.substringAfter(",")?.toIntOrNull() ?: 0

    private fun finish() {
        assertTrue(failures.joinToString("\n", "${failures.size} row(s) failed:\n"), failures.isEmpty())
    }

    // Touch mode

    @Test fun touchModeGestures() {
        open(PointerMode.TOUCH)
        val mid = at(0.5f, 0.5f)

        Touches.tap(mid)
        val tap = expect(listOf("m*", "b1d", "b1u"), "tap clicks where the finger is")
        pause()

        // A second tap a few pixels off lands on the same pixel.
        val jitter = P(mid.x + 10, mid.y + 8)
        Touches.perform(listOf(listOf(mid to 0.0, mid to 0.06)), then = listOf(listOf(jitter to 0.18, jitter to 0.24)))
        val double = expect(listOf("m*", "b1d", "b1u", "m*", "b1d", "b1u"), "double tap")
        val moves = double.filter { it.startsWith("m") }
        check(moves.toSet().size == 1, "both clicks on one pixel: $double")
        check(moves.firstOrNull() == tap.firstOrNull { it.startsWith("m") }, "the double tap lands where the tap did: $double vs $tap")
        pause()

        // One finger flicks the document under it, as on a phone.
        val page = picture()
        Touches.perform(listOf(listOf(pic(0.5f, 0.75f) to 0.0, pic(0.5f, 0.25f) to 0.15)))
        val slide = expect(listOf("m*", "w*"), "one finger scrolls what is under it")
        none("b", "a slide presses nothing")
        none("z", "at full size a slide leaves the picture alone")
        check(wheelDy(slide) < 0, "finger going up scrolls down: $slide")
        val lifted = DesktopInputProbe.count
        pause(0.6)
        check(DesktopInputProbe.count > lifted + 3, "a flick carries on after lifting")
        pause(1.0)
        if (share(page, picture()) <= 0.004) {
            val end = picture()
            Touches.perform(listOf(listOf(pic(0.5f, 0.25f) to 0.0, pic(0.5f, 0.75f) to 0.15)))
            assertChanged(end, "the document under the finger scrolled (back from its end)")
        } else {
            capture("touch-scrolled")
            pause(2.0)
            Touches.perform(listOf(listOf(pic(0.5f, 0.25f) to 0.0, pic(0.5f, 0.75f) to 0.15)))
        }
        pause(2.5)

        var before = picture()
        Touches.perform(listOf(listOf(mid to 0.0, mid to 0.6)))
        expect(listOf("m*", "fhold", "b2d", "b2u", "fclick"), "hold and lift right clicks")
        pause(0.5)
        assertChanged(before, "hold: a menu opened on the host")
        capture("touch-hold-menu")
        escape()

        Touches.perform(listOf(listOf(at(0.4f, 0.4f) to 0.0, at(0.4f, 0.4f) to 0.5, at(0.55f, 0.5f) to 1.0)))
        expect(listOf("fhold", "m*", "b1d", "fpickup", "m*", "b1u"), "hold and move drags from where the finger landed")
        pause()

        before = picture()
        Touches.tap(at(0.45f, 0.5f), at(0.55f, 0.5f))
        expect(listOf("m*", "b2d", "b2u", "fclick"), "two-finger tap right clicks at the middle", 2.0)
        pause(0.5)
        assertChanged(before, "two-finger tap: a menu opened on the host")
        capture("touch-right-click-menu")
        escape()

        Touches.tap(at(0.4f, 0.5f), at(0.5f, 0.5f), at(0.6f, 0.5f))
        expect(listOf("m*", "b3d", "b3u", "fclick"), "three-finger tap middle clicks")
        pause()

        twoFingerScroll()
        pinchAndSmartZoom(touch = true)
        edgeSwipes()
        finish()
    }

    // Trackpad mode

    @Test fun trackpadModeGestures() {
        open(PointerMode.MOUSE)
        val mid = at(0.5f, 0.5f)
        centerCursor()

        Touches.tap(mid)
        expect(listOf("b1d", "b1u"), "one-finger tap clicks at once")
        none("m", "a tap does not move the cursor")
        pause()

        Touches.perform(listOf(listOf(mid to 0.0, mid to 0.06)), then = listOf(listOf(mid to 0.2, mid to 0.26)))
        expect(listOf("b1d", "b1u", "b1d", "b1u"), "double tap is a double click")
        pause()

        Touches.perform(listOf(listOf(at(0.3f, 0.5f) to 0.0, at(0.6f, 0.45f) to 0.35)))
        expect(listOf("m*"), "one finger moves the cursor")
        none("b", "moving presses nothing")
        pause()

        centerCursor()
        var before = picture()
        Touches.tap(at(0.45f, 0.5f), at(0.55f, 0.5f))
        expect(listOf("b2d", "b2u", "fclick"), "two-finger tap right clicks", 2.0)
        pause(0.5)
        assertChanged(before, "two-finger tap: a menu opened on the host")
        capture("trackpad-right-click-menu")
        escape()

        Touches.tap(at(0.4f, 0.5f), at(0.5f, 0.5f), at(0.6f, 0.5f))
        expect(listOf("b3d", "b3u", "fclick"), "three-finger tap middle clicks")
        pause()

        before = picture()
        Touches.perform(listOf(listOf(mid to 0.0, mid to 0.6)))
        expect(listOf("fhold", "b2d", "b2u", "fclick"), "hold and lift right clicks")
        pause(0.5)
        assertChanged(before, "hold: a menu opened on the host")
        capture("trackpad-hold-menu")
        escape()

        Touches.perform(listOf(listOf(at(0.4f, 0.4f) to 0.0, at(0.4f, 0.4f) to 0.5, at(0.55f, 0.5f) to 1.0)))
        expect(listOf("fhold", "b1d", "fpickup", "m*", "b1u"), "hold and move drags")
        pause()

        Touches.perform(listOf(listOf(at(0.45f, 0.4f) to 0.0, at(0.45f, 0.4f) to 0.7, at(0.45f, 0.55f) to 1.2),
            listOf(at(0.55f, 0.4f) to 0.0, at(0.55f, 0.4f) to 0.7, at(0.55f, 0.55f) to 1.2)))
        expect(listOf("fhold", "b2d", "fpickup", "m*", "b2u"), "two-finger hold and move right drags")
        pause(1.0)
        escape()

        centerCursor()
        twoFingerScroll()
        pinchAndSmartZoom(touch = false)
        edgeSwipes()
        threeFingerSwipes()
        finish()
    }

    /** 显示桌面 tapped again and again: each tap must reach the host at once and change its picture. */
    @Test fun showDesktopButtonEveryTap() {
        open(PointerMode.TOUCH)
        val report = mutableListOf<String>()
        for (i in 1..6) {
            val before = picture()
            val sent = DesktopInputProbe.count
            val tapped = SystemClock.uptimeMillis()
            compose.onNodeWithTag("desktop-rail-show-desktop").performClick()
            var sentMs = -1L
            while (SystemClock.uptimeMillis() - tapped < 3000) {
                if (DesktopInputProbe.count > sent && tokens().lastOrNull() == "a1") { sentMs = SystemClock.uptimeMillis() - tapped; break }
                pause(0.05)
            }
            var changedMs = -1L
            while (SystemClock.uptimeMillis() - tapped < 5000) {
                if (share(before, picture()) > 0.02) { changedMs = SystemClock.uptimeMillis() - tapped; break }
                pause(0.05)
            }
            report += "tap $i: sent $sentMs ms, picture changed $changedMs ms"
            capture("show-desktop-$i")
            pause(2.0)
        }
        E2e.log("show desktop: " + report.joinToString(" | "))
        report.forEach { check(!it.contains("sent -1") && !it.contains("changed -1"), it) }
        finish()
    }

    /**
     * The system's back gesture works from the black bars beside the picture, while a swipe in from the
     * screen's edge on the picture stays the desktop's.
     */
    @Test fun swipeBackFromOutsideThePicture() {
        open(PointerMode.TOUCH)
        val screenW = compose.activity.window.decorView.width.toFloat()
        // On the picture: an edge swipe, the desktop keeps it.
        val onPic = pic(0f, 0.5f)
        edgeSwipe(P(2f, onPic.y), P(screenW * 0.6f, onPic.y))
        pause(1.5)
        check(compose.onAllNodes(hasTestTag("desktop-page")).fetchSemanticsNodes().isNotEmpty(), "an edge swipe on the picture does not leave the page")
        expect(listOf("m0,*", "fshortcut"), "the edge swipe from the screen's edge on the picture went to the host")
        // On the black bar above the picture: back to the hosts page.
        val bar = P(1f, (viewTop + picTop) / 2)
        check(picTop - viewTop > 40, "portrait shows a black bar above the picture")
        edgeSwipe(bar, P(screenW * 0.6f, bar.y))
        try { compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("desktop-page")).fetchSemanticsNodes().isEmpty() } }
        catch (e: Throwable) { fail("swiping back from the black bar leaves the desktop") }
        capture("back-from-bar")

        // Landscape: the bars are left and right of the picture, and the system's bars are hidden.
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        pause(2.0)
        try {
            open(PointerMode.TOUCH)
            check(picLeft - viewLeft > 40, "landscape shows a black bar left of the picture")
            val side = P(2f, viewTop + viewH / 2)
            edgeSwipe(side, P(compose.activity.window.decorView.width * 0.5f, side.y))
            try { compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("desktop-page")).fetchSemanticsNodes().isEmpty() } }
            catch (e: Throwable) { fail("landscape: swiping back from the black bar leaves the desktop") }
            capture("landscape-back-from-bar")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        finish()
    }

    /**
     * A swipe in from the screen's edge through the system's own `input swipe`: the system's back gesture
     * ignores touches injected through UiAutomation, as iOS's ignore synthesized ones.
     */
    private fun edgeSwipe(from: P, to: P) {
        E2e.shell("input swipe ${from.x.toInt()} ${from.y.toInt()} ${to.x.toInt()} ${to.y.toInt()} 200")
    }

    // Shared

    /** Trackpad mode: the cursor to the middle of the host's screen, from its bottom-left corner. */
    private fun centerCursor() {
        val corner = pic(0f, 1f)
        Touches.perform(listOf(listOf(P(corner.x + 8, corner.y - 8) to 0.0, P(corner.x + 70, corner.y - 50) to 0.2)))
        expect(listOf("m0,*", "fshortcut"), "a swipe in from the picture's bottom-left corner")
        pause(0.3)
        // Slow enough for the lowest gain, 0.5: the picture's width moves the cursor half the host's.
        val from = P(viewLeft + viewW * 0.05f, viewTop + viewH / 2 + picH * 0.45f)
        val to = P(from.x + picW * 0.9f, from.y - picH * 0.9f)
        Touches.perform(listOf((0..40).map { i -> val f = i / 40f; P(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f) to 0.2 * i }))
        pause(0.5)
    }

    private fun twoFingerScroll() {
        val before = picture()
        Touches.perform(listOf(listOf(pic(0.45f, 0.75f) to 0.0, pic(0.45f, 0.25f) to 0.15), listOf(pic(0.55f, 0.75f) to 0.0, pic(0.55f, 0.25f) to 0.15)))
        val during = expect(listOf("w*"), "two fingers scroll")
        none("b", "scrolling presses nothing")
        none("z", "scrolling leaves the picture alone")
        check(wheelDy(during) < 0, "fingers going up scroll down: $during")
        val atLift = DesktopInputProbe.count
        pause(0.6)
        check(DesktopInputProbe.count > atLift + 3, "inertia carries the scroll on after lifting")
        assertChanged(before, "the document under the cursor scrolled")
        capture("scrolled")
        pause(2.0)
        Touches.perform(listOf(listOf(pic(0.45f, 0.25f) to 0.0, pic(0.45f, 0.75f) to 0.15), listOf(pic(0.55f, 0.25f) to 0.0, pic(0.55f, 0.75f) to 0.15)))
        pause(2.5)
    }

    private fun pinchAndSmartZoom(touch: Boolean) {
        Touches.perform(listOf(listOf(at(0.45f, 0.5f) to 0.0, at(0.3f, 0.5f) to 0.4), listOf(at(0.55f, 0.5f) to 0.0, at(0.7f, 0.5f) to 0.4)))
        val pinch = expect(listOf("z*"), "pinch zooms")
        none("w", "pinching does not scroll")
        none("b", "pinching does not click")
        check((pinch.lastOrNull { it.startsWith("z") }?.drop(1)?.toDoubleOrNull() ?: 0.0) > 1.5, "pinched past 1.5×: $pinch")
        capture("pinched")
        pause()
        Touches.perform(listOf(listOf(at(0.45f, 0.6f) to 0.0, at(0.45f, 0.45f) to 0.4), listOf(at(0.55f, 0.6f) to 0.0, at(0.55f, 0.45f) to 0.4)))
        expect(listOf("w*"), "zoomed in, two fingers scroll")
        none("z", "zoomed in, scrolling leaves the picture alone")
        pause(1.5)
        if (touch) {
            Touches.perform(listOf(listOf(at(0.5f, 0.5f) to 0.0, at(0.4f, 0.5f) to 0.5)))
            expect(listOf("m*", "z*"), "zoomed in, one finger moves the picture")
            none("w", "inside the zoomed picture, a slide does not scroll the host")
            none("b", "moving the picture presses nothing")
            capture("touch-zoomed-moved")
            pause()
        }
        val a = at(0.45f, 0.4f); val b = at(0.55f, 0.4f)
        for ((step, zoom) in listOf("smart zoom out" to "z1.00", "smart zoom in" to "z2.50", "smart zoom back out" to "z1.00")) {
            Touches.perform(listOf(listOf(a to 0.0, a to 0.06), listOf(b to 0.0, b to 0.06)), then = listOf(listOf(a to 0.2, a to 0.26), listOf(b to 0.2, b to 0.26)))
            expect(listOf(zoom, "fshortcut"), step)
            none("b", "$step does not right click")
            pause()
        }
    }

    private fun edgeSwipes() {
        val left = pic(0f, 0.5f)
        Touches.perform(listOf(listOf(P(left.x + 8, left.y) to 0.0, P(left.x + 200, left.y) to 0.2)))
        expect(listOf("m0,*", "fshortcut"), "a swipe in from the left edge puts the cursor on the host's left edge")
        pause()
        val bottom = pic(0.5f, 1f)
        Touches.perform(listOf(listOf(P(bottom.x, bottom.y - 8) to 0.0, P(bottom.x, bottom.y - 200) to 0.2)))
        expect(listOf("m*", "fshortcut"), "a swipe up from the bottom edge reaches the host's bottom edge")
        capture("cursor-bottom-edge")
        pause()
    }

    private fun threeFingerSwipes() {
        for ((d, token) in listOf((0f to -0.25f) to "sup", (0f to 0.25f) to "sdown", (-0.3f to 0f) to "sleft", (0.3f to 0f) to "sright")) {
            val fingers = listOf(0.4f, 0.5f, 0.6f).map { fx -> listOf(at(fx, 0.5f) to 0.0, at(fx + d.first, 0.5f + d.second) to 0.3) }
            Touches.perform(fingers)
            expect(listOf(token, "fshortcut"), "three-finger swipe $token")
            capture("three-finger-$token")
            pause(1.5)
            if (token == "sup") escape()
            if (token == "sdown") { Touches.perform(fingers); pause(1.5) }
        }
    }

    data class P(val x: Float, val y: Float)

    /** Multi-touch injected through UiAutomation at real times, every 8 ms between the given points. */
    object Touches {
        private const val STEP_MS = 8L

        fun tap(vararg points: P) = perform(points.map { listOf(it to 0.0, it to 0.06) })

        /**
         * Each finger lands at its first point, moves through the rest at their offsets (seconds) and lifts
         * at the last. [then]: more fingers in the same run, offsets from the same start.
         */
        fun perform(fingers: List<List<Pair<P, Double>>>, then: List<List<Pair<P, Double>>> = emptyList()) {
            val all = fingers + then
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            val start = SystemClock.uptimeMillis() + 20
            val ends = all.map { (it.last().second * 1000).toLong() }
            val begins = all.map { (it.first().second * 1000).toLong() }
            val last = ends.max()
            fun where(f: List<Pair<P, Double>>, ms: Long): P {
                val t = ms / 1000.0
                val i = f.indexOfFirst { it.second >= t }
                if (i < 0) return f.last().first
                if (i == 0) return f[0].first
                val (a, ta) = f[i - 1]; val (b, tb) = f[i]
                val k = ((t - ta) / (tb - ta).coerceAtLeast(1e-6)).toFloat()
                return P(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
            }
            val down = mutableListOf<Int>() // finger indices touching, in landing order
            var downTime = 0L
            // Stamped when sent: injecting can fall behind the plan, and the page's timers run on the real clock.
            fun send(action: Int, @Suppress("UNUSED_PARAMETER") planned: Long, ms: Long) {
                val time = SystemClock.uptimeMillis()
                val props = down.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                val coords = down.map { i -> val p = where(all[i], ms); MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 0.1f } }.toTypedArray()
                val e = MotionEvent.obtain(downTime, time, action, down.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                ui.injectInputEvent(e, true)
                e.recycle()
            }
            var ms = 0L
            while (true) {
                val time = start + ms
                val wait = time - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
                // Fingers landing now.
                for (i in all.indices) if (i !in down && i !in lifted && begins[i] <= ms) {
                    down += i
                    if (down.size == 1) { downTime = SystemClock.uptimeMillis(); send(MotionEvent.ACTION_DOWN, time, ms) }
                    else send(MotionEvent.ACTION_POINTER_DOWN or ((down.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), time, ms)
                }
                if (down.isNotEmpty()) send(MotionEvent.ACTION_MOVE, time, ms)
                // Fingers lifting now.
                for (i in down.toList()) if (ends[i] <= ms) {
                    val index = down.indexOf(i)
                    if (down.size == 1) send(MotionEvent.ACTION_UP, time, ms)
                    else send(MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), time, ms)
                    down.remove(i); lifted += i
                }
                if (ms >= last && down.isEmpty()) break
                ms += STEP_MS
            }
            lifted.clear()
        }
        private val lifted = mutableSetOf<Int>()
    }

    companion object { const val PREFIX = "GESTURE " }
}
