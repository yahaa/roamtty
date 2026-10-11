package io.itermux.app.e2e

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Frame timing under real input. Compose's test rule runs animations on a virtual clock, so these tests use a plain
 * ActivityScenario and drive the screen like a finger does: `input tap` and real-time pointer events through the system, with
 * targets found in the accessibility tree.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2eFramesTest {
    @get:Rule(order = 0) val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    @get:Rule(order = 2) val cleanupAfter = object : ExternalResource() {
        override fun after() { if (E2e.enabled) E2e.cleanup(service) }
    }
    private lateinit var service: SessionService

    @Before fun setUp() {
        E2e.requireFixture()
        service = E2e.bind(serviceRule)
        E2e.cleanup(service)
        runBlocking { E2e.trustFixture() }
        activity.scenario.onActivity {
            io.itermux.app.AppLocale.set(it, io.itermux.app.AppLanguage.ZH_CN)
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        it("会话")
    }

    /** The app's windows, topmost first (a drawer or sheet can sit above the activity). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val automation = E2e.instrumentation.uiAutomation
        val info = automation.serviceInfo
        if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0)
            automation.serviceInfo = info.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        return automation.windows.sortedByDescending { it.layer }.mapNotNull { it.root }
            .filter { it.packageName == E2e.context.packageName }
    }

    private fun find(label: String): Rect? {
        for (root in roots()) {
            val node = findByDescription(root, label) ?: continue
            return Rect().also(node::getBoundsInScreen)
        }
        return null
    }

    private fun findByDescription(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        // Compose exposes virtual nodes that text search does not reach, so walk the tree.
        if ((node.contentDescription?.toString() == label || node.text?.toString() == label) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) findByDescription(node.getChild(i), label)?.let { return it }
        return null
    }

    /** Waits until [label] is on screen; returns its bounds. */
    private fun it(label: String, timeoutMs: Long = 15_000): Rect {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            find(label)?.let { return it }
            Thread.sleep(150)
        }
        error("'$label' not on screen")
    }

    private fun tap(label: String) { val r = it(label); E2e.shell("input tap ${r.centerX()} ${r.centerY()}"); Thread.sleep(600) }

    private val screen get() = E2e.context.resources.displayMetrics

    /** A finger drag in real time: one move per frame through the system's input pipeline. */
    private fun swipe(fromY: Float, toY: Float, ms: Int) {
        val x = screen.widthPixels / 2f
        val y0 = screen.heightPixels * fromY
        val y1 = screen.heightPixels * toY
        val down = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        E2e.instrumentation.sendPointerSync(event(android.view.MotionEvent.ACTION_DOWN, y0))
        val steps = (ms / 16).coerceAtLeast(2)
        for (i in 1..steps) {
            Thread.sleep(16)
            E2e.instrumentation.sendPointerSync(event(android.view.MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / steps))
        }
        E2e.instrumentation.sendPointerSync(event(android.view.MotionEvent.ACTION_UP, y1))
    }

    private fun terminalTopRow(): Int {
        var row = 0
        activity.scenario.onActivity { a ->
            fun walk(v: android.view.View): com.termux.view.TerminalView? = v as? com.termux.view.TerminalView
                ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { walk(g.getChildAt(it)) } }
            row = walk(a.window.decorView)?.topRow ?: 0
        }
        return row
    }

    private fun report(label: String) {
        val frames = E2e.frames()
        E2e.log("$label $frames")
        E2e.metric("${label}_jank_pct", frames.jankPercent, "%")
        E2e.metric("${label}_frame_p90_ms", frames.p90, "ms")
    }

    /** Dragging through 5,000 lines of scrollback in the terminal (a plain View). */
    @Test fun r01_scrollbackScrolling() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("滚动"))
        val session: TerminalConnection = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.SHELL, Credentials(E2e.password.toByteArray()))
        }
        assertEquals(Phase.CONNECTED, E2e.connected(session))
        tap("打开导航")
        tap(session.displayTitle)
        withContext(Dispatchers.Main) { session.write("seq 1 5000; echo SCROLL_\$((2+2))\r") }
        val deadline = System.currentTimeMillis() + 60_000
        while ("SCROLL_4" !in E2e.transcript(session) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        Thread.sleep(1_000)
        E2e.resetFrames()
        E2e.screenshot("r01-before-scroll")
        repeat(4) { swipe(0.2f, 0.8f, 1000) }
        E2e.screenshot("r01-in-scrollback")
        // Dragging must move the view, not just its scroll position: the scrolled screen has to be drawn.
        val drawn = E2e.frames().total
        E2e.metric("scroll_frames_drawn", drawn, "frames")
        val topRow = terminalTopRow()
        E2e.log("scrollback topRow=$topRow frames=$drawn")
        org.junit.Assert.assertTrue("dragging down should reach history (topRow=$topRow)", topRow < -20)
        repeat(2) { swipe(0.8f, 0.2f, 1000) }
        report("scroll")
    }

    /** Flinging a list of 60 host cards (Compose LazyVerticalGrid). */
    @Test fun r02_hostListScrolling() = runBlocking<Unit> {
        repeat(60) { E2e.save(E2e.host("列表 %02d".format(it))) }
        tap("主机")
        it(E2e.PREFIX + "列表 00")
        Thread.sleep(3_000) // reachability probes settle
        E2e.resetFrames()
        repeat(3) {
            swipe(0.8f, 0.3f, 200); Thread.sleep(700)
            swipe(0.3f, 0.8f, 200); Thread.sleep(700)
        }
        report("hostlist")
        E2e.screenshot("r02-host-list")
    }
}
