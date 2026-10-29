package io.itermux.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * In landscape the host page has one flat bar like the home page's (back, 概览 · 进程 · 信息, the actions and the
 * menu) over the whole page in one scroll; the tabs follow the scroll and tapping one scrolls to its section.
 * Readings come from the loopback OpenSSH fixture.
 */
@RunWith(AndroidJUnit4::class)
class HostDetailLandscapeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun selected(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false }

    @Test fun landscapeHostPageIsOneScrollTheTabsFollow() = runBlocking {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val id = "host-detail-landscape"
        app.database.hosts().save(Host(id = id, name = "Landscape box", address = "127.0.0.1", port = 22228, username = "itermux_test",
            mode = "SHELL", encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password")))
        fun awaitTag(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) {
            if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("信任并连接").performClick()
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        try {
            compose.onNodeWithTag("home-tab-hosts").performClick()
            awaitTag("host-card-$id", 8000)
            compose.onNodeWithTag("host-card-$id").performClick()
            awaitTag("host-detail-back")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            awaitTag("host-detail-bar")
            awaitTag("host-monitor-status", 30_000)
            screenshot("host-landscape-overview")

            // One bar no taller than the home page's, with everything on one line and no second toolbar.
            val density = compose.activity.resources.displayMetrics.density
            val bar = compose.onNodeWithTag("host-detail-bar").fetchSemanticsNode().boundsInRoot
            assertTrue("bar ${bar.height / density} dp", bar.height / density <= 48.5f)
            for (tag in listOf("host-detail-back", "host-tab-overview", "host-tab-processes", "host-tab-info", "host-detail-agent",
                "host-detail-terminal", "host-detail-files", "host-detail-more")) {
                val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue(tag, abs(bounds.center.y - bar.center.y) <= 2 * density)
            }
            compose.onNodeWithTag("host-detail-agent").assert(hasContentDescription("Agent"))
            assertTrue(selected("host-tab-overview"))
            listOf("host-gauge-cpu", "host-gauge-memory", "host-gauge-disk", "host-network-rate").forEach {
                compose.onNodeWithTag(it).assertIsDisplayed()
            }

            // Tapping 进程 scrolls to the processes, and the tab stays on it.
            compose.onNodeWithTag("host-tab-processes").performClick()
            compose.waitForIdle()
            assertTrue(selected("host-tab-processes"))
            val section = compose.onNodeWithTag("host-section-processes").fetchSemanticsNode().boundsInRoot
            assertTrue("processes at ${section.top} under bar ${bar.bottom}", abs(section.top - bar.bottom) <= 12 * density)
            screenshot("host-landscape-processes")

            // Tapping 信息 shows the connection, with the user name.
            compose.onNodeWithTag("host-tab-info").performClick()
            compose.waitForIdle()
            assertTrue(selected("host-tab-info"))
            compose.onAllNodesWithText("itermux_test").filterToOne(hasAnyAncestor(hasTestTag("host-section-info"))).assertIsDisplayed()
            screenshot("host-landscape-info")

            // Scrolling back to the top selects 概览; scrolling to the end selects 信息 again.
            repeat(4) { compose.onNodeWithTag("host-detail-scroll").performTouchInput { swipeDown() } }
            compose.waitForIdle()
            assertTrue(selected("host-tab-overview"))
            repeat(4) { compose.onNodeWithTag("host-detail-scroll").performTouchInput { swipeUp() } }
            compose.waitForIdle()
            assertTrue(selected("host-tab-info"))
            assertEquals(false, selected("host-tab-overview"))
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            app.database.hosts().delete(id)
        }
    }
}
