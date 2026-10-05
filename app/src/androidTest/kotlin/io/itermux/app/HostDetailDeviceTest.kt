package io.itermux.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun hostPageShowsLiveMetricsAndMenuOffersTerminalAndAgent() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val id = "host-detail-device"
        app.getSharedPreferences("app-settings", 0).edit().remove("startDirectories:$id")
            .putString("agentLaunchAgent", "CLAUDE").putBoolean("agentLaunchYolo", false).commit()
        app.database.hosts().save(Host(id = id, name = "Fixture box", address = "127.0.0.1", port = 22228, username = "itermux_test",
            mode = "SHELL", encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password")))
        try {
            compose.waitUntil(8000) { compose.onAllNodesWithTag("host-card-$id").fetchSemanticsNodes().isNotEmpty() }
            fun trustIfAsked() {
                if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("信任并连接").performClick()
            }
            fun awaitTag(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) {
                trustIfAsked(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }

            // Host menu → AI agent: one sheet; folders are tapped, not typed, and the agent is chosen in place.
            compose.onNodeWithTag("host-menu-$id").performClick()
            compose.onNodeWithTag("host-open-terminal-$id").assertExists()
            compose.onNodeWithTag("host-open-agent-$id").performClick()
            compose.onNodeWithTag("launch-path").assert(hasText("~"))
            awaitTag("launch-folder-projects")
            compose.onNodeWithTag("launch-folder-notes").assertExists()
            compose.onNodeWithTag("launch-folder-projects").performClick()
            awaitTag("launch-folder-app")
            compose.onNodeWithTag("launch-path").assert(hasText("~/projects"))
            compose.onNodeWithTag("launch-parent").assertExists()
            compose.onNodeWithTag("agent-option-codex").performClick()
            compose.onNodeWithTag("launch-open").assert(hasText("在 projects 启动 Codex"))
            screenshot("launch-sheet-agent")
            compose.onNodeWithTag("launch-open").performClick()
            compose.waitUntil(20_000) { trustIfAsked(); compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } // terminal → sessions
            compose.waitUntil(5000) { compose.onAllNodesWithText("Codex · projects", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("hosts-tab").performClick()

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
            // Terminal from the host page reuses the page's login, so folders show at once; untouched, it is home.
            compose.onNodeWithTag("host-detail-terminal").performClick()
            awaitTag("launch-folder-projects", 8000)
            compose.onNodeWithTag("launch-open").assert(hasText("在主目录打开终端"))
            screenshot("launch-sheet-terminal")
        } finally {
            val service = (serviceRule.bindService(Intent(instrumentation.targetContext, SessionService::class.java)) as SessionService.LocalBinder).service
            withContext(Dispatchers.Main) {
                service.sessions.value.filter { it.host.id == id }.forEach { service.close(it.id); service.forget(it.id) }
            }
            app.database.hosts().delete(id)
        }
        Unit
    }
}
