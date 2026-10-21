package io.itermux.app.e2e

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

    @After fun tearDown() { DesktopSettings(E2e.context).apply { enabled = false; pluginBaseUrl = "" } }

    private fun click(tag: String, timeoutMs: Long = 10_000) {
        E2e.waitTag(compose, tag, timeoutMs)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
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
        compose.onNodeWithTag("desktop-ime-field").performTextInput("echo E2E_DESKTOP_你好世界 > ~/desktop-typed.txt")
        compose.onNodeWithTag("desktop-ime-field").performImeAction()
        compose.waitUntil(10_000) { "E2E_DESKTOP_你好世界" in remote("cat ~/desktop-typed.txt 2>/dev/null") }
        E2e.capture(compose, "desktop-03-typed")
        click("desktop-kb-pc")
        E2e.waitTag(compose, "desktop-key-${0x04}")
        E2e.capture(compose, "desktop-04-pc-keyboard")
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
        click("desktop-mode-mouse")
        click("desktop-quality-clear")
        compose.waitUntil(3_000) { desktop().quality.value == "clear" }
        click("desktop-mode-touch")

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
}
