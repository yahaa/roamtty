package io.itermux.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tapping a host opens its detail page, which logs in over SSH by itself (saved password) and shows live
 * CPU, memory and disk from the loopback OpenSSH fixture. The host menu offers the terminal and AI agent.
 */
@RunWith(AndroidJUnit4::class)
class HostDetailDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun hostPageShowsLiveMetricsAndMenuOffersTerminalAndAgent() = runBlocking {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val id = "host-detail-device"
        app.database.hosts().save(Host(id = id, name = "Fixture box", address = "127.0.0.1", port = 22228, username = "itermux_test",
            mode = "SHELL", encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password")))
        try {
            compose.waitUntil(8000) { compose.onAllNodesWithTag("host-card-$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("host-menu-$id").performClick()
            compose.onNodeWithTag("host-open-terminal-$id").assertExists()
            compose.onNodeWithTag("host-open-agent-$id").performClick()
            compose.onNodeWithTag("start-directory-sheet").assertExists()
            compose.onNodeWithTag("start-directory-input").performTextReplacement("~/work")
            compose.onNodeWithTag("start-directory-open").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("agent-launch-sheet").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("agent-launch-path").assert(hasText("~/work"))
            compose.onAllNodesWithContentDescription("关闭").onLast().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("agent-launch-sheet").fetchSemanticsNodes().isEmpty() }

            compose.onNodeWithTag("host-card-$id").performClick()
            compose.onNodeWithTag("host-detail").assertExists()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(hasAnyAncestor(hasTestTag("host-gauge-cpu")) and hasText("%", substring = true), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("信任并连接").performClick()
            compose.waitUntil(20_000) {
                listOf("host-gauge-cpu", "host-gauge-memory", "host-gauge-disk").all { tag ->
                    compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasText("%", substring = true), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("↓", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Linux", substring = true, useUnmergedTree = true).assertExists()
            screenshot("host-detail-live")
            compose.onNodeWithTag("host-detail").performTouchInput { swipeUp() }
            screenshot("host-detail-info")
        } finally {
            app.database.hosts().delete(id)
        }
        Unit
    }
}
