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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * In landscape, where height is short, the host form, the plugin sheet and the drawer give their height to content: the
 * form as two rows of two sections whose panels share a height, the plugins as cards side by side, and the drawer's
 * Settings and Help as one row. The plugin sheet talks to the loopback OpenSSH fixture.
 */
@RunWith(AndroidJUnit4::class)
class LandscapeSheetsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun awaitTag(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) {
        if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("信任并连接").performClick()
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Before fun wake() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }

    @After fun portrait() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    private fun landscape() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
    }

    @Test fun hostFormIsTwoRowsOfSectionsWhosePanelsShareAHeight() {
        landscape()
        compose.onNodeWithTag("home-tab-hosts").performClick()
        awaitTag("add-host", 8000)
        compose.onNodeWithTag("add-host").performClick()
        awaitTag("host-name", 8000)
        compose.waitForIdle()
        val name = bounds("host-name")
        val password = bounds("host-password")
        assertTrue("authentication beside the connection fields", password.left >= name.right)
        compose.onNodeWithText("tmux · 推荐").assertIsDisplayed()
        val panels = compose.onAllNodesWithTag("section-panel", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(4, panels.size)
        assertEquals("first row panels share a top", panels[0].top, panels[1].top, density)
        assertEquals("first row panels share a height", panels[0].height, panels[1].height, density)
        assertEquals("second row panels share a height", panels[2].height, panels[3].height, density)
        screenshot("landscape-host-form-password")
        // A shorter authentication panel still matches the connection panel beside it.
        compose.onNodeWithText("交互式认证").performClick()
        compose.waitForIdle()
        val after = compose.onAllNodesWithTag("section-panel", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(after[0].height, after[1].height, density)
        screenshot("landscape-host-form-interactive")
    }

    @Test fun drawerKeepsItsHeightForSessions() {
        landscape()
        awaitTag("brand-logo", 8000)
        compose.onNodeWithTag("brand-logo").performClick()
        awaitTag("drawer-settings", 5000)
        compose.waitForIdle()
        val settings = bounds("drawer-settings")
        val guide = bounds("drawer-guide")
        val hosts = bounds("drawer-hosts")
        assertEquals("Settings and Help share one row", settings.center.y, guide.center.y, 2 * density)
        assertTrue("sessions keep ${(settings.top - hosts.bottom) / density} dp", settings.top - hosts.bottom >= 150 * density)
        screenshot("landscape-drawer")
        compose.onNodeWithTag("drawer-settings").performClick()
        awaitTag("settings-theme-dark", 5000)
    }

    @Test fun pluginsAreCardsSideBySide() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val id = "landscape-plugins"
        app.database.hosts().save(Host(id = id, name = "Plugin box", address = "127.0.0.1", port = 22228, username = "itermux_test",
            mode = "SHELL", encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password")))
        try {
            compose.onNodeWithTag("home-tab-hosts").performClick()
            awaitTag("host-card-$id", 8000)
            compose.onNodeWithTag("host-card-$id").performClick()
            awaitTag("host-detail-more")
            landscape()
            awaitTag("host-detail-more")
            compose.onNodeWithTag("host-detail-more").performClick()
            awaitTag("host-detail-plugins", 5000)
            compose.onNodeWithTag("host-detail-plugins").performClick()
            // Probed: each card ends in its state or its install button.
            compose.waitUntil(30_000) {
                listOf("tmux", "mosh").all { plugin ->
                    listOf("plugin-installed-$plugin", "plugin-install-$plugin").any {
                        compose.onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                }
            }
            val tmux = bounds("plugin-tmux")
            val mosh = bounds("plugin-mosh")
            assertEquals("the cards stand side by side", tmux.top, mosh.top, density)
            assertEquals("the cards share a height", tmux.height, mosh.height, density)
            assertTrue(mosh.left > tmux.right)
            compose.onNodeWithTag("host-plugins-refresh").assertIsEnabled()
            screenshot("landscape-plugins")
        } finally {
            app.database.hosts().delete(id)
        }
    }
}
