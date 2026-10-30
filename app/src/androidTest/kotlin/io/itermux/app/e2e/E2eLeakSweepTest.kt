package io.itermux.app.e2e

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Debug
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.app.data.Host
import io.itermux.app.e2e.E2e.text
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.lang.ref.WeakReference

/**
 * Repeats whole user journeys (terminal, files and previews, host monitor, settings, rotation) and checks that nothing
 * piles up: closed sessions and old Activities are collected, and the managed heap does not grow cycle after cycle.
 * Keeps a heap dump in files/e2e when something stays, for tracing with hprof-conv and a path finder.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2eLeakSweepTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    @get:Rule(order = 2) val cleanupAfter = object : ExternalResource() {
        override fun after() {
            if (!E2e.enabled) return
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            E2e.cleanup(service)
        }
    }
    private lateinit var service: SessionService

    @Before fun setUp() {
        E2e.requireFixture()
        service = E2e.bind(serviceRule)
        E2e.cleanup(service)
        runBlocking { E2e.trustFixture() }
        E2e.prepareActivity(compose)
        E2e.waitTag(compose, "home-pager")
    }

    private fun click(tag: String) { E2e.waitTag(compose, tag); compose.onNodeWithTag(tag, useUnmergedTree = true).performClick() }

    private fun hostsTab() {
        click("home-tab-hosts")
        compose.waitUntil(5_000) { E2e.exists(compose, hasTestTag("home-tab-hosts") and isSelected()) }
    }

    private fun backToHome() {
        repeat(5) {
            if (E2e.exists(compose, hasTestTag("home-pager"))) return
            Espresso.pressBackUnconditionally()
            compose.waitForIdle()
        }
        E2e.waitTag(compose, "home-pager")
    }

    private fun hostMenu(host: Host, item: String) {
        hostsTab()
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${host.id}"))
        click("host-menu-${host.id}")
        click(item)
    }

    private fun newSession(before: Set<String>): TerminalConnection {
        compose.waitUntil(15_000) { service.sessions.value.any { it.id !in before } }
        val session = service.sessions.value.single { it.id !in before }
        val phase = runBlocking { withTimeout(25_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active }.phase } }
        assertEquals(session.state.value.message, Phase.CONNECTED, phase)
        return session
    }

    private fun closeAll() = runBlocking {
        withContext(Dispatchers.Main) {
            service.sessions.value.filter { it.host.name.startsWith(E2e.PREFIX) }.forEach { service.close(it.id); service.forget(it.id) }
        }
    }

    private fun openEntry(name: String) {
        E2e.waitTag(compose, "files-list", 15_000)
        compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("files-entry-$name"))
        click("files-entry-$name")
    }

    private fun closePreview() {
        Espresso.closeSoftKeyboard()
        click("file-preview-close")
        E2e.waitGone(compose, hasTestTag("file-preview"))
    }

    /** One journey; returns weak references to every connection it opened (all closed again at the end). */
    private fun journey(host: Host, round: Int): List<WeakReference<TerminalConnection>> {
        val opened = mutableSetOf<TerminalConnection>()
        // Terminal opened through the UI, with output and rotation.
        var before = service.sessions.value.map { it.id }.toSet()
        hostMenu(host, "host-open-terminal-${host.id}")
        E2e.waitTag(compose, "launch-sheet")
        click("launch-open")
        val terminal = newSession(before)
        opened += terminal
        E2e.waitTag(compose, "terminal")
        E2e.run(compose, "seq 1 5000; echo SWEEP_${round}_\$((6*7))")
        E2e.waitOutput(compose, terminal, "SWEEP_${round}_42", 30_000)
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        E2e.waitTag(compose, "terminal")
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        E2e.waitTag(compose, "terminal")
        backToHome()
        // Files: a listing, highlighted code with search, an image and a large log.
        before = service.sessions.value.map { it.id }.toSet()
        hostMenu(host, "host-browse-files-${host.id}")
        E2e.waitTag(compose, "files-entry-e2e", 20_000)
        service.sessions.value.filter { it.id !in before }.forEach { opened += it }
        openEntry("e2e")
        openEntry("src")
        openEntry("Main.kt")
        E2e.waitTag(compose, "file-preview-text")
        click("file-preview-search")
        compose.onNodeWithTag("file-preview-search-input").performTextInput("greeting")
        compose.waitUntil(5_000) { E2e.exists(compose, hasTestTag("file-preview-search-count")) }
        Espresso.closeSoftKeyboard()
        click("file-preview-search-close")
        closePreview()
        Espresso.pressBack()
        E2e.waitTag(compose, "files-entry-README.md")
        openEntry("README.md")
        E2e.waitTag(compose, "file-preview-markdown")
        closePreview()
        openEntry("pixel.png")
        E2e.waitTag(compose, "file-preview-image")
        closePreview()
        openEntry("docs")
        openEntry("large.log")
        E2e.waitText(compose, "line 1", substring = true, timeoutMs = 20_000)
        closePreview()
        backToHome()
        // Host page with live monitoring.
        hostsTab()
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${host.id}"))
        click("host-card-${host.id}")
        E2e.waitTag(compose, "host-detail")
        compose.waitUntil(20_000) { "%" in compose.text("host-gauge-cpu") }
        click("host-detail-back")
        E2e.waitTag(compose, "home-pager")
        // Settings and back.
        click("brand-logo")
        click("drawer-settings")
        click("close-settings")
        E2e.waitTag(compose, "home-pager")
        service.sessions.value.filter { it.host.id == host.id }.forEach { opened += it }
        closeAll()
        E2e.waitTag(compose, "home-pager")
        return opened.map { WeakReference(it) }
    }

    private fun heapAfterGc(): Double {
        compose.waitForIdle()
        Thread.sleep(1_500)
        return E2e.javaHeapMb()
    }

    private fun settle(refs: List<WeakReference<*>>): Int {
        val deadline = System.nanoTime() + 15_000_000_000
        var alive: Int
        do {
            compose.waitForIdle()
            E2e.gc()
            alive = refs.count { it.get() != null }
            if (alive == 0) break
            Thread.sleep(250)
        } while (System.nanoTime() < deadline)
        return alive
    }

    /** Five journeys: the first warms caches, the remaining four must not keep growing the heap. */
    @Test fun s01_repeatedJourneysDoNotAccumulate() {
        val host = runBlocking { E2e.save(E2e.host("泄漏巡检")) }
        val refs = mutableListOf<WeakReference<TerminalConnection>>()
        refs += journey(host, 0)
        val warm = heapAfterGc()
        val heaps = mutableListOf(warm)
        for (round in 1..4) {
            refs += journey(host, round)
            heaps += heapAfterGc()
        }
        E2e.log("sweep heaps $heaps")
        val alive = settle(refs)
        val growth = heapAfterGc() - warm
        E2e.metric("sweep_retained_sessions", alive, "count")
        E2e.metric("sweep_heap_growth_mb", growth, "MB")
        E2e.metric("sweep_pss_mb", E2e.pssMb(), "MB")
        if (alive > 0 || growth >= 4.0) Debug.dumpHprofData(File(E2e.outputDir, "sweep.hprof").path)
        assertEquals("closed sessions still reachable after five journeys (heaps $heaps)", 0, alive)
        assertTrue("managed heap grew ${"%.1f".format(growth)} MB over four journeys (heaps $heaps)", growth < 4.0)
    }

    private fun recreatedActivity(): WeakReference<Activity> {
        var old: Activity? = null
        compose.activityRule.scenario.onActivity { old = it }
        val ref = WeakReference<Activity>(old)
        old = null
        compose.activityRule.scenario.recreate()
        E2e.prepareActivity(compose)
        return ref
    }

    /** Recreating the Activity (theme, language, process-kept rotation) must let every old one go, also with a terminal open. */
    @Test fun s02_recreatedActivitiesAreCollected() {
        val host = runBlocking { E2e.save(E2e.host("重建")) }
        val before = service.sessions.value.map { it.id }.toSet()
        hostMenu(host, "host-open-terminal-${host.id}")
        E2e.waitTag(compose, "launch-sheet")
        click("launch-open")
        newSession(before)
        E2e.waitTag(compose, "terminal")
        val refs = List(5) { recreatedActivity().also { E2e.waitTag(compose, "terminal") } }
        backToHome()
        val more = List(3) { recreatedActivity().also { E2e.waitTag(compose, "home-pager") } }
        val alive = settle(refs + more)
        E2e.metric("retained_activities", alive, "count")
        if (alive > 0) Debug.dumpHprofData(File(E2e.outputDir, "activities.hprof").path)
        assertEquals("old Activities still reachable after recreation", 0, alive)
    }
}
