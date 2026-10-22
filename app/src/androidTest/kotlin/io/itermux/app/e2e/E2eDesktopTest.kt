package io.itermux.app.e2e

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.app.data.Host
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.e2e.E2e.app
import io.itermux.app.session.PendingPrompt
import io.itermux.app.session.SessionService
import io.itermux.app.ui.credentialsFor
import io.itermux.app.ui.pageLogin
import io.itermux.desktop.DesktopPhase
import io.itermux.desktop.HidUsage
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The remote desktop end to end (issue #184): the session card's desktop button on a host with an X
 * desktop (the fixture's Xvfb), installing the plugin from nothing, the picture, typing, a still screen
 * sending nothing, leaving and coming back, and another device taking over.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2eDesktopTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    @get:Rule(order = 2) val cleanupAfter = object : org.junit.rules.ExternalResource() {
        override fun after() {
            if (!E2e.enabled) return
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            cleanup()
        }
    }
    @get:Rule(order = 3) val screenshotOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = E2e.capture(compose, "FAIL-${description.methodName}")
    }
    private lateinit var service: SessionService
    private val pluginUrl get() = InstrumentationRegistry.getArguments().getString("e2eDesktopUrl").orEmpty()

    @Before fun setUp() {
        E2e.requireFixture()
        assumeTrue("desktop fixture missing (e2eDesktopUrl)", pluginUrl.isNotEmpty())
        service = E2e.bind(serviceRule)
        cleanup()
        runBlocking { E2e.trustFixture() }
        DesktopSettings(E2e.context).apply { enabled = true; pluginBaseUrl = pluginUrl; keepAliveMinutes = 10 }
        E2e.prepareActivity(compose)
        E2e.waitTag(compose, "home-pager")
    }

    private fun cleanup() {
        runBlocking(Dispatchers.Main) {
            if (::service.isInitialized) service.desktops.connections.value.values.filter { it.host.name.startsWith(E2e.PREFIX) }
                .forEach { service.desktops.close(it.host.id) }
        }
        E2e.cleanup(if (::service.isInitialized) service else null)
    }

    @After fun tearDown() { DesktopSettings(E2e.context).apply { enabled = false; pluginBaseUrl = ""; udpHostOverride = ""; testLoss = 0.0; showStats = false } }

    private fun click(tag: String, timeoutMs: Long = 10_000) {
        E2e.waitTag(compose, tag, timeoutMs)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    /** 显示网络状态 in 操作, then the panel closed with its ✕. */
    private fun showStats(on: Boolean) {
        click("desktop-rail-operations")
        E2e.waitTag(compose, "desktop-operations")
        compose.onNodeWithTag("desktop-show-stats").performScrollTo()
        if (DesktopSettings(E2e.context).showStats != on) click("desktop-show-stats")
        assertEquals(on, DesktopSettings(E2e.context).showStats)
        click("desktop-op-close")
        E2e.waitGone(compose, hasTestTag("desktop-operations"))
        if (on) E2e.waitTag(compose, "desktop-stats") else compose.onNodeWithTag("desktop-stats").assertDoesNotExist()
    }

    /** Runs [script] on the fixture over a login of its own; returns its output. */
    private fun remote(script: String): String = runBlocking {
        val host = app.database.hosts().find(hostId)!!
        val ssh = app.pageLogin(host, app.credentialsFor(host, "", ""), MutableStateFlow<PendingPrompt?>(null))
        val out = StringBuilder()
        withTimeout(30_000) { ssh.administer { it.run(script, 20_000) { line -> out.appendLine(line) } } }
        ssh.disconnect()
        out.toString()
    }

    private var hostId = ""

    private fun desktop(): DesktopConnection = service.desktops[hostId] ?: error("no desktop for the host")

    /** Waits through the compose clock, so the page keeps composing (and its surface exists) meanwhile. */
    private fun waitPhase(timeoutMs: Long, label: String, predicate: (DesktopPhase) -> Boolean): DesktopPhase {
        try { compose.waitUntil(timeoutMs) { service.desktops[hostId]?.phase?.value?.let(predicate) == true } }
        catch (e: Throwable) { throw AssertionError("$label: phase ${service.desktops[hostId]?.phase?.value}; log ${service.desktops[hostId]?.log?.value?.takeLast(8)}", e) }
        return desktop().phase.value
    }

    private fun idle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        runCatching { compose.waitUntil(ms + 1000) { System.currentTimeMillis() >= end } }
    }

    /** The focused editor's imeOptions as the input method service sees them. */
    private fun imeOptions(): Long? = Regex("imeOptions=0x([0-9a-f]+)").findAll(E2e.shell("dumpsys input_method"))
        .lastOrNull()?.groupValues?.get(1)?.toLong(16)

    private fun decodedFrames() = desktop().decoder.decodedFrames

    @Test fun d01_desktopEndToEnd() {
        val host = runBlocking { E2e.save(E2e.host("desktop")) }
        hostId = host.id
        // A terminal session first: its connection runs the read-only desktop probe.
        val session = runBlocking {
            withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, withContext(Dispatchers.IO) { app.credentialsFor(host, "", "") }, preferMosh = false) }
        }
        runBlocking { assertEquals(io.itermux.ssh.Phase.CONNECTED, E2e.connected(session)) }
        compose.waitUntil(15_000) { runBlocking { app.database.hosts().find(host.id)?.desktop } == "yes" }

        // The card shows the button; one tap installs the plugin and opens the picture.
        val button = "home-session-desktop-${session.id}"
        E2e.waitTag(compose, button, 15_000)
        E2e.capture(compose, "desktop-01-card")
        val start = System.nanoTime()
        click(button)
        E2e.waitTag(compose, "desktop-page")
        waitPhase(45_000, "first frame") { it is DesktopPhase.Connected || it is DesktopPhase.Failed }
        val firstFrameMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("desktop failed: ${desktop().phase.value} ${desktop().log.value.takeLast(8)}", desktop().phase.value is DesktopPhase.Connected)
        E2e.metric("desktop_install_first_frame_ms", firstFrameMs, "ms")
        assertTrue(desktop().log.value.any { "主机直接下载" in it })
        compose.waitUntil(5_000) { decodedFrames() > 0 }
        val hello = desktop().hello.value!!
        assertEquals("linux", hello.os)
        assertEquals(1280, hello.displays.first().width)
        E2e.capture(compose, "desktop-02-picture")

        // Tap the xterm (focus follows the pointer on a bare X server), then type through the input method.
        compose.onNodeWithTag("desktop-touch").performTouchInput { click(Offset(width * 0.25f, height * 0.25f)) }
        click("desktop-rail-keyboard")
        E2e.waitTag(compose, "desktop-ime-field")
        // The input method gets a plain ↵ (no Send action, which some greyed out on an empty field).
        compose.waitUntil(5_000) { imeOptions() != null }
        val options = imeOptions()!!
        assertEquals("imeOptions=0x${options.toString(16)}", 0, options and EditorInfo.IME_MASK_ACTION.toLong())
        assertTrue("imeOptions=0x${options.toString(16)}", options and EditorInfo.IME_FLAG_NO_ENTER_ACTION.toLong() != 0L)
        compose.onNodeWithTag("desktop-ime-field").performTextInput("echo E2E_DESKTOP_你好世界 > ~/desktop-typed.txt")
        compose.onNodeWithTag("desktop-ime-field").performTextInput("\n")
        compose.waitUntil(10_000) { "E2E_DESKTOP_你好世界" in remote("cat ~/desktop-typed.txt 2>/dev/null") }
        E2e.capture(compose, "desktop-03-typed")
        // Where a frame's time goes: the host's share (STATE timing), the network and the phone's, all measured.
        runCatching {
            compose.waitUntil(10_000) {
                desktop().input(io.itermux.desktop.DesktopProtocol.text(" ")); idle(300)
                desktop().stats.value.latency?.let { it.totalMs != null && it.captureMs != null && it.networkMs != null } == true
            }
        }
        val latency = desktop().stats.value.latency
        assertTrue("latency not measured: $latency", latency?.totalMs != null && latency.captureMs != null && latency.networkMs != null)
        latency!!
        listOf("capture" to latency.captureMs, "convert" to latency.convertMs, "encode" to latency.encodeMs, "network" to latency.networkMs,
            "decode" to latency.decodeMs, "render" to latency.renderMs, "total" to latency.totalMs).forEach { (k, v) ->
            E2e.metric("desktop_latency_${k}_ms", v?.let { Math.round(it) } ?: -1, "ms")
        }
        assertEquals("openh264", latency.encoder)
        assertTrue("$latency", latency.totalMs!! in 1.0..1000.0)
        // The figures stay off the picture until 操作 switches them on, and the switch is remembered.
        // (The keyboard panel takes the rail's place in portrait: closed for this, open again after.)
        Espresso.pressBack()
        E2e.waitGone(compose, hasTestTag("desktop-keyboard"))
        compose.onNodeWithTag("desktop-stats").assertDoesNotExist()
        showStats(true)
        E2e.capture(compose, "desktop-03b-latency")
        showStats(false)
        click("desktop-rail-keyboard")
        E2e.waitTag(compose, "desktop-ime-field")
        // The shortcut bar is hidden until asked for; ⋯ swaps the input method for the 更多 keys.
        compose.onNodeWithTag("desktop-sk-esc").assertDoesNotExist()
        click("desktop-kb-toggle")
        E2e.waitTag(compose, "desktop-sk-esc")
        click("desktop-kb-more")
        E2e.waitTag(compose, "desktop-key-${0x3A}")
        E2e.capture(compose, "desktop-04-more-keys")
        Espresso.pressBack()
        E2e.waitGone(compose, hasTestTag("desktop-keyboard"))

        // A still screen sends nothing once the refine frame went out.
        idle(2_000)
        val before = decodedFrames()
        idle(3_000)
        val still = decodedFrames() - before
        E2e.metric("desktop_static_frames_3s", still, "count")
        assertTrue("still screen sent $still frames", still <= 1)
        E2e.metric("desktop_rtt_ms", desktop().stats.value.rttMs ?: -1, "ms")
        assertFalse("a loopback link is not poor", (desktop().phase.value as DesktopPhase.Connected).poor)

        // The operations panel and its settings.
        click("desktop-rail-operations")
        E2e.waitTag(compose, "desktop-operations")
        E2e.capture(compose, "desktop-05-operations")
        // Ending the connection is far from closing the panel: the ✕ on top, 断开连接 at the bottom.
        compose.onNodeWithTag("desktop-op-disconnect").performScrollTo()
        idle(600)
        E2e.capture(compose, "desktop-05b-operations-bottom")
        click("desktop-op-close")
        E2e.waitGone(compose, hasTestTag("desktop-operations"))
        assertTrue(desktop().phase.value is DesktopPhase.Connected)
        click("desktop-rail-operations")
        E2e.waitTag(compose, "desktop-operations")
        compose.onNodeWithTag("desktop-quality-clear").performScrollTo()
        click("desktop-mode-mouse")
        click("desktop-quality-clear")
        compose.waitUntil(3_000) { desktop().quality.value == "clear" }
        click("desktop-mode-touch")
        compose.onNodeWithTag("desktop-op-exit").performScrollTo()

        // Leaving keeps it: the card shows it connected and coming back is quick.
        click("desktop-op-exit")
        E2e.waitTag(compose, "home-pager")
        waitPhase(5_000, "paused") { (it as? DesktopPhase.Connected)?.paused == true }
        E2e.waitTag(compose, "$button-badge-connected")
        E2e.capture(compose, "desktop-06-card-connected")
        val framesBefore = decodedFrames()
        val back = System.nanoTime()
        click(button)
        E2e.waitTag(compose, "desktop-page")
        compose.waitUntil(5_000) { decodedFrames() > framesBefore }
        E2e.metric("desktop_resume_first_frame_ms", (System.nanoTime() - back) / 1_000_000, "ms")
        assertEquals(false, (desktop().phase.value as DesktopPhase.Connected).paused)

        // Landscape: the rail moves to the right edge, the picture fills the rest.
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        idle(1_500)
        E2e.waitTag(compose, "desktop-rail-operations")
        E2e.capture(compose, "desktop-08-landscape")
        // The figures on the picture's left, away from the rail on the right; the panel's ✕ still at its top.
        showStats(true)
        click("desktop-rail-operations")
        E2e.waitTag(compose, "desktop-op-close")
        E2e.capture(compose, "desktop-08b-landscape-stats-operations")
        click("desktop-op-close")
        E2e.waitGone(compose, hasTestTag("desktop-operations"))
        showStats(false)
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        idle(1_000)

        // Another device opens the same desktop: this one says so and stops.
        Thread { remote("\"\$HOME/.roamtty/bin/roamtty-desktop\" serve --proto 1 --client 800x600@160 < /dev/null > /dev/null 2>&1 & sleep 3; kill %1 2>/dev/null; exit 0") }.start()
        waitPhase(15_000, "kicked") { it is DesktopPhase.Failed }
        assertEquals("已在另一台设备上打开", (desktop().phase.value as DesktopPhase.Failed).message)
        E2e.waitTag(compose, "desktop-error")
        E2e.capture(compose, "desktop-07-kicked")
    }

    @Test fun d02_buttonHiddenWhileDisabled() {
        val host = runBlocking { E2e.save(E2e.host("desktop-off")) }
        hostId = host.id
        DesktopSettings(E2e.context).enabled = false
        val session = runBlocking {
            withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, withContext(Dispatchers.IO) { app.credentialsFor(host, "", "") }, preferMosh = false) }
        }
        runBlocking { E2e.connected(session) }
        compose.waitUntil(15_000) { runBlocking { app.database.hosts().find(host.id)?.desktop } == "yes" }
        E2e.waitTag(compose, "home-session-${session.id}")
        compose.waitForIdle()
        assertFalse(E2e.exists(compose, hasTestTag("home-session-desktop-${session.id}")))
    }

    /**
     * UDP (plan §7.3 P2) with 5% loss each way: video and typing ride UDP, FEC rebuilds frames,
     * a network change recovers within a second, blocked UDP falls back to SSH and comes back.
     * The emulator reaches this machine's loopback, where the plugin listens, at 10.0.2.2.
     */
    @Test fun d03_udpWithLossRoamingAndFallback() {
        DesktopSettings(E2e.context).apply { udpHostOverride = "10.0.2.2"; testLoss = 0.05 }
        val host = runBlocking { E2e.save(E2e.host("desktop-udp")) }
        hostId = host.id
        val session = runBlocking {
            withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, withContext(Dispatchers.IO) { app.credentialsFor(host, "", "") }, preferMosh = false) }
        }
        runBlocking { E2e.connected(session) }
        compose.waitUntil(15_000) { runBlocking { app.database.hosts().find(host.id)?.desktop } == "yes" }
        click("home-session-desktop-${session.id}", 15_000)
        E2e.waitTag(compose, "desktop-page")
        waitPhase(45_000, "first frame") { it is DesktopPhase.Connected || it is DesktopPhase.Failed }
        assertTrue("${desktop().phase.value}", desktop().phase.value is DesktopPhase.Connected)
        compose.waitUntil(8_000) { desktop().stats.value.link == "UDP" }
        E2e.capture(compose, "desktop-udp-01-connected")

        // Typing under loss: every character arrives (reliable frames are resent), frames keep coming.
        compose.onNodeWithTag("desktop-touch").performTouchInput { click(Offset(width * 0.25f, height * 0.25f)) }
        click("desktop-rail-keyboard")
        E2e.waitTag(compose, "desktop-ime-field")
        val framesBefore = decodedFrames()
        val marker = "E2E_UDP_" + (System.currentTimeMillis() % 100000)
        compose.onNodeWithTag("desktop-ime-field").performTextInput("echo $marker 你好 > ~/desktop-udp.txt")
        compose.onNodeWithTag("desktop-ime-field").performTextInput("\n")
        compose.waitUntil(15_000) { "$marker 你好" in remote("cat ~/desktop-udp.txt 2>/dev/null") }
        repeat(40) { i -> desktop().input(io.itermux.desktop.DesktopProtocol.text("${i % 10}")); idle(60) }
        idle(1_500)
        val stats = desktop().stats.value
        val frames = decodedFrames() - framesBefore
        E2e.metric("desktop_udp_frames_under_loss", frames, "count")
        E2e.metric("desktop_udp_loss_pct", stats.lossPercent ?: -1.0, "%")
        E2e.metric("desktop_udp_fec_recovered", stats.fecRecovered, "count")
        E2e.metric("desktop_udp_lost_frames", stats.lostFrames, "count")
        assertEquals("UDP", stats.link)
        assertFalse("5% loss on a fast link is not a poor network", (desktop().phase.value as DesktopPhase.Connected).poor)
        assertTrue("frames under loss: $frames", frames >= 20)
        assertTrue("lost ${stats.lostFrames} of $frames", stats.lostFrames * 10 <= frames)
        E2e.capture(compose, "desktop-udp-02-typed")
        // The first Back hides the input method, the next closes the panel.
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        E2e.waitGone(compose, hasTestTag("desktop-keyboard"))

        // Network change: a new socket; the host follows and a keyframe arrives within a second.
        val beforeRoam = decodedFrames()
        val t0 = System.nanoTime()
        desktop().networkChanged()
        compose.waitUntil(5_000) { decodedFrames() > beforeRoam }
        val roamMs = (System.nanoTime() - t0) / 1_000_000
        E2e.metric("desktop_udp_roam_ms", roamMs, "ms")
        assertTrue("roam took $roamMs ms", roamMs <= 1_000)
        assertEquals("UDP", desktop().stats.value.link)

        // UDP blocked: the picture carries on over SSH, then returns to UDP.
        desktop().testUdpDrop(1.0)
        val blocked = System.nanoTime()
        compose.waitUntil(10_000) { desktop().stats.value.link == "SSH" }
        val fallbackMs = (System.nanoTime() - blocked) / 1_000_000
        E2e.metric("desktop_udp_fallback_ms", fallbackMs, "ms")
        val onSsh = decodedFrames()
        compose.waitUntil(8_000) { desktop().input(io.itermux.desktop.DesktopProtocol.text("s")); idle(200); decodedFrames() > onSsh }
        desktop().testUdpDrop(0.05)
        compose.waitUntil(10_000) { desktop().stats.value.link == "UDP" }
        val back = decodedFrames()
        compose.waitUntil(8_000) { desktop().input(io.itermux.desktop.DesktopProtocol.text("u")); idle(200); decodedFrames() > back }
        assertTrue(desktop().phase.value is DesktopPhase.Connected)
        showStats(true)
        E2e.capture(compose, "desktop-udp-03-stats")
        showStats(false)
    }

    private fun pointer(): Pair<Int, Int>? = Regex("""x:(\d+) y:(\d+)""").find(remote("DISPLAY=:77 xdotool getmouselocation"))
        ?.groupValues?.let { it[1].toInt() to it[2].toInt() }

    /**
     * Mouse pointer mode over UDP, driven by real input events: one finger moves the cursor by its travel.
     * Touches arrive on the main thread, where a datagram send is refused (NetworkOnMainThreadException).
     */
    @Test fun d05_mouseModeMovesThePointerOverUdp() {
        DesktopSettings(E2e.context).udpHostOverride = "10.0.2.2"
        val host = runBlocking { E2e.save(E2e.host("desktop-mouse")) }
        hostId = host.id
        DesktopSettings(E2e.context).setPointerMode(host.id, io.itermux.desktop.PointerMode.MOUSE)
        val session = runBlocking {
            withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, withContext(Dispatchers.IO) { app.credentialsFor(host, "", "") }, preferMosh = false) }
        }
        runBlocking { E2e.connected(session) }
        compose.waitUntil(15_000) { runBlocking { app.database.hosts().find(host.id)?.desktop } == "yes" }
        click("home-session-desktop-${session.id}", 15_000)
        E2e.waitTag(compose, "desktop-page")
        waitPhase(45_000, "first frame") { it is DesktopPhase.Connected || it is DesktopPhase.Failed }
        assertTrue("${desktop().phase.value}", desktop().phase.value is DesktopPhase.Connected)
        compose.waitUntil(8_000) { desktop().stats.value.link == "UDP" }
        remote("DISPLAY=:77 xdotool mousemove 200 200")
        idle(500)
        val before = pointer()
        val node = compose.onNodeWithTag("desktop-touch").fetchSemanticsNode().boundsInRoot
        val loc = IntArray(2).also { a -> compose.activityRule.scenario.onActivity { it.window.decorView.getLocationOnScreen(a) } }
        val (x0, y0) = (loc[0] + node.left + node.width * 0.3f).toInt() to (loc[1] + node.top + node.height * 0.6f).toInt()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input swipe $x0 $y0 ${x0 + 300} ${y0 - 200} 400").close()
        idle(1_500)
        val after = pointer()
        E2e.capture(compose, "desktop-mouse-01-moved")
        assertNotNull(before); assertNotNull(after)
        assertEquals("UDP", desktop().stats.value.link)
        assertTrue("pointer $before → $after", after!!.first > before!!.first + 50 && after.second < before.second - 30)
    }

    /**
     * A wlroots Wayland desktop (P3): a headless sway of the fixture account, started over SSH. Frames
     * come from wlr-screencopy, typing (with Shift and Chinese) through the virtual keyboard.
     */
    @Test fun d04_waylandDesktop() {
        val host = runBlocking { E2e.save(E2e.host("desktop-wayland")) }
        hostId = host.id
        val sway = "export XDG_RUNTIME_DIR=\"\$HOME/wl\"; mkdir -p -m 700 \"\$XDG_RUNTIME_DIR\"; " +
            "printf 'output HEADLESS-1 resolution 1280x720\\n' > \"\$XDG_RUNTIME_DIR/sway.conf\"; " +
            "WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman setsid sway -c \"\$XDG_RUNTIME_DIR/sway.conf\" " +
            "> /dev/null 2>&1 < /dev/null & sleep 2; ls \"\$XDG_RUNTIME_DIR\""
        val started = remote(sway)
        assumeTrue("sway is not installed on the fixture host ($started)", "wayland-1" in started)
        try {
            val session = runBlocking {
                withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, withContext(Dispatchers.IO) { app.credentialsFor(host, "", "") }, preferMosh = false) }
            }
            runBlocking { E2e.connected(session) }
            compose.waitUntil(15_000) { runBlocking { app.database.hosts().find(host.id)?.desktop } == "yes" }
            click("home-session-desktop-${session.id}", 15_000)
            E2e.waitTag(compose, "desktop-page")
            waitPhase(45_000, "first frame") { it is DesktopPhase.Connected || it is DesktopPhase.Failed }
            assertTrue("${desktop().phase.value} ${desktop().log.value.takeLast(5)}", desktop().phase.value is DesktopPhase.Connected)
            assertEquals("the hello names the wlroots output", "HEADLESS-1", desktop().hello.value!!.displays.first().name)
            // A terminal opened now gets keyboard focus (the headless seat had no keyboard before ours).
            remote("export XDG_RUNTIME_DIR=\"\$HOME/wl\"; SWAYSOCK=\$(ls \"\$XDG_RUNTIME_DIR\"/sway-ipc.*.sock) swaymsg exec foot >/dev/null; sleep 1.5")
            idle(500)
            compose.onNodeWithTag("desktop-touch").performTouchInput { click(Offset(width * 0.25f, height * 0.25f)) }
            click("desktop-rail-keyboard")
            E2e.waitTag(compose, "desktop-ime-field")
            compose.onNodeWithTag("desktop-ime-field").performTextInput("echo E2E_WL_你好 > ~/desktop-wl.txt")
            compose.onNodeWithTag("desktop-ime-field").performTextInput("\n")
            compose.waitUntil(15_000) { "E2E_WL_你好" in remote("cat ~/desktop-wl.txt 2>/dev/null") }
            E2e.capture(compose, "desktop-wl-01-typed")
        } finally {
            remote("pkill -x sway; pkill -x foot; true")
        }
    }

    /**
     * Named keys on a server that does not number its keys like evdev: xrdp's Xorg uses the xfree86
     * codes, where evdev's Left is Right Alt and End is Super. The quick keys must still edit the line.
     */
    @Test fun d06_namedKeysOnXfree86Keycodes() {
        val host = runBlocking { E2e.save(E2e.host("desktop-keys")) }
        hostId = host.id
        remote("DISPLAY=:77 setxkbmap -rules base -model pc104 -layout us -keycodes xfree86")
        try {
            val session = runBlocking {
                withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, withContext(Dispatchers.IO) { app.credentialsFor(host, "", "") }, preferMosh = false) }
            }
            runBlocking { E2e.connected(session) }
            compose.waitUntil(15_000) { runBlocking { app.database.hosts().find(host.id)?.desktop } == "yes" }
            click("home-session-desktop-${session.id}", 15_000)
            E2e.waitTag(compose, "desktop-page")
            waitPhase(45_000, "first frame") { it is DesktopPhase.Connected || it is DesktopPhase.Failed }
            assertTrue("${desktop().phase.value}", desktop().phase.value is DesktopPhase.Connected)
            compose.onNodeWithTag("desktop-touch").performTouchInput { click(Offset(width * 0.25f, height * 0.25f)) }
            click("desktop-rail-keyboard")
            E2e.waitTag(compose, "desktop-ime-field")
            val field = compose.onNodeWithTag("desktop-ime-field")
            // A clean line whatever an earlier test left (Ctrl latched, then a typed c); then "echo AC",
            // back one, "B", and to the end of the line from the 更多 page: the file gets ABC.
            click("desktop-kb-toggle")
            click("desktop-sk-mod-${HidUsage.LEFT_CTRL}")
            field.performTextInput("c")
            field.performTextInput("echo AC")
            click("desktop-sk-←")
            field.performTextInput("B")
            click("desktop-kb-more")
            click("desktop-key-${HidUsage.END}")
            click("desktop-kb-more")
            field.performTextInput(" > ~/desktop-keys.txt")
            field.performTextInput("\n")
            compose.waitUntil(10_000) { remote("cat ~/desktop-keys.txt 2>/dev/null").isNotBlank() }
            E2e.capture(compose, "desktop-keys-01-xfree86")
            assertEquals("ABC", remote("cat ~/desktop-keys.txt").trim())
        } finally {
            remote("DISPLAY=:77 setxkbmap -rules evdev -model pc105 -layout us; rm -f ~/desktop-keys.txt")
        }
    }
}
