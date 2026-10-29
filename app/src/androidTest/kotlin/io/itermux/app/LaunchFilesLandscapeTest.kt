package io.itermux.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.session.AgentMode
import io.itermux.app.session.CodingAgent
import io.itermux.app.ui.saveAgentChoice
import io.itermux.app.ui.saveRecentStartDirectories
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * In landscape the launch sheet and the file browser use the whole screen, like the host page there: one flat bar on
 * top; the folders in full height beside a side column with the recent folders, the agent pickers and the button; the
 * files under a bar that carries the path, the summary, the sort, the filter and the menu. Readings come from the
 * loopback OpenSSH fixture.
 */
@RunWith(AndroidJUnit4::class)
class LaunchFilesLandscapeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun landscapeLaunchSheetAndFilesUseTheWholeScreen() = runBlocking<Unit> {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        // Opening files connects a session, whose service asks for notifications; that dialog would cover the app.
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val id = "launch-files-landscape"
        app.database.hosts().save(Host(id = id, name = "Landscape files", address = "127.0.0.1", port = 22228, username = "itermux_test",
            mode = "SHELL", encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password")))
        app.appSettings().saveRecentStartDirectories(id, listOf("/tmp", "/usr", "/etc"))
        // Another test may have left a different agent as the last one used.
        app.appSettings().saveAgentChoice(CodingAgent.CODEX, AgentMode.YOLO)
        fun awaitTag(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) {
            if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("信任并连接").performClick()
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        try {
            compose.onNodeWithTag("home-tab-hosts").performClick()
            awaitTag("host-card-$id", 8000)
            compose.onNodeWithTag("host-card-$id").performClick()
            awaitTag("host-detail-back")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            awaitTag("host-detail-bar")
            val density = compose.activity.resources.displayMetrics.density

            // Files: one bar with the path, the summary, the sort, the filter and the menu; compact rows.
            compose.onNodeWithTag("host-detail-files").performClick()
            // The fixture may refuse a channel while the sheets' logins close; the browser's 重试 asks again.
            compose.waitUntil(40_000) {
                compose.onAllNodesWithText("重试").fetchSemanticsNodes().firstOrNull()?.let { compose.onNodeWithText("重试").performClick() }
                compose.onAllNodesWithTag("files-sort").fetchSemanticsNodes().isNotEmpty()
            }
            val filesBar = bounds("files-bar")
            assertTrue("files bar ${filesBar.height / density} dp", filesBar.height / density <= 48.5f)
            for (tag in listOf("files-back", "files-summary", "files-sort", "files-filter")) {
                assertTrue(tag, abs(bounds(tag).center.y - filesBar.center.y) <= 2 * density)
            }
            screenshot("files-landscape")
            compose.onNodeWithTag("files-filter").performTextInput("e")
            compose.waitForIdle()
            screenshot("files-landscape-filter")
            compose.onNodeWithTag("files-back").performClick()
            // Back leaves the files for where they were opened from; come back to the host page if that was home.
            compose.waitUntil(10_000) {
                listOf("host-detail-agent", "home-tab-hosts").any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
            }
            if (compose.onAllNodesWithTag("host-detail-agent").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("home-tab-hosts").performClick()
                awaitTag("host-card-$id")
                compose.onNodeWithTag("host-card-$id").performClick()
            }
            awaitTag("host-detail-agent")

            // Agent: the folders on the left in full height, the side column with recents, pickers and the button.
            compose.onNodeWithTag("host-detail-agent").performClick()
            awaitTag("launch-folders", 30_000)
            awaitTag("launch-recent-0")
            val bar = bounds("launch-bar")
            assertTrue("bar ${bar.height / density} dp", bar.height / density <= 48.5f)
            val folders = bounds("launch-folders")
            val open = bounds("launch-open")
            assertTrue("button beside the folders", open.left >= folders.right)
            assertTrue("folders under the bar", folders.top >= bar.bottom)
            assertTrue("button on screen", open.bottom <= compose.onAllNodes(isRoot()).fetchSemanticsNodes().last().boundsInRoot.bottom)
            assertTrue("picker above the button", bounds("agent-picker").bottom <= open.top)
            compose.onNodeWithTag("agent-icon-codex", useUnmergedTree = true).assertExists()
            screenshot("launch-landscape-agent")
            compose.onNodeWithTag("launch-close").performClick()

            // Terminal: tmux, when the host has it, shows the selected session on a card above a short button.
            compose.onNodeWithTag("host-detail-terminal").performClick()
            awaitTag("launch-folders", 30_000)
            screenshot("launch-landscape-terminal")
            if (compose.onAllNodesWithTag("launch-source-tmux").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("launch-source-tmux").performClick()
                runCatching { awaitTag("launch-tmux-selected", 15_000) }.onSuccess {
                    compose.onNodeWithTag("launch-attach").assert(hasText("打开会话"))
                    assertTrue(bounds("launch-tmux-selected").bottom <= bounds("launch-attach").top)
                    screenshot("launch-landscape-tmux")
                }
            }
            compose.onNodeWithTag("launch-close").performClick()

        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            app.database.hosts().delete(id)
        }
    }
}
