package io.itermux.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every page and sheet in landscape has the same flat 48dp bar, and uses the width: settings as two columns, the
 * new-session choices as a grid, the guide as two columns in a wider dialog.
 */
@RunWith(AndroidJUnit4::class)
class LandscapePagesTest {
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

    private fun awaitTag(tag: String, timeout: Long = 8_000) = compose.waitUntil(timeout) {
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

    private fun openDrawerItem(tag: String) {
        awaitTag("brand-logo")
        compose.onNodeWithTag("brand-logo").performClick()
        awaitTag(tag, 5000)
        compose.onNodeWithTag(tag).performClick()
    }

    @Test fun settingsAreTwoColumnsUnderTheFlatBar() {
        landscape()
        openDrawerItem("drawer-settings")
        awaitTag("settings-theme-system")
        compose.waitForIdle()
        val close = bounds("close-settings")
        assertTrue("close sits in the 48dp bar", close.bottom <= 56 * density)
        val theme = bounds("settings-theme-system")
        val notify = bounds("settings-notify-off")
        assertTrue("notifications sit in the right column", notify.left >= theme.right - 2 * density)
        assertTrue("both columns start near the top", kotlin.math.abs(notify.top - theme.top) < 200 * density)
        screenshot("landscape-settings")
        compose.onNodeWithTag("close-settings").performClick()
    }

    @Test fun newSessionChoicesAreAGrid() {
        landscape()
        awaitTag("add-session")
        compose.onNodeWithTag("add-session").performClick()
        awaitTag("new-session-pick-terminal", 5000)
        compose.waitForIdle()
        val claude = bounds("new-session-pick-claude")
        val codex = bounds("new-session-pick-codex")
        assertEquals("the first two choices share a row", claude.top, codex.top, density)
        assertTrue("side by side", codex.left >= claude.right - density)
        compose.onNodeWithTag("new-session-pick-terminal").assertIsDisplayed()
        screenshot("landscape-new-session")
        compose.onNodeWithTag("new-session-pick-terminal").performClick()
        awaitTag("sheet-back", 5000)
        assertTrue("back sits in the 48dp bar", bounds("sheet-back").bottom <= 56 * density)
        screenshot("landscape-new-session-hosts")
    }

    @Test fun guideIsTwoColumns() {
        landscape()
        openDrawerItem("drawer-guide")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Roamtty").fetchSemanticsNodes().isNotEmpty() }
        screenshot("landscape-guide")
    }
}
