package io.itermux.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** SSH and PowerShell are real; scripts/windows-launch-fixture.py supplies deterministic Windows CIM objects. */
@RunWith(AndroidJUnit4::class)
class WindowsHostMonitorTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun open(username: String, port: Int = 22248, password: String = "fixture-only") = runBlocking {
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val id = "windows-$username"
        app.database.hosts().forgetPin("[127.0.0.1]:$port")
        val host = Host(id = id, name = "Windows monitor", address = "127.0.0.1", port = port,
            username = username, mode = "SHELL", os = "WINDOWS",
            encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password"))
        compose.setContent { MaterialTheme(colorScheme = DarkColors) { Surface(Modifier.fillMaxSize()) {
            HostDetailPage(host, HostReachability.Online(2), emptyList(), onBack = {}, onOpenSession = {},
                onCloseSession = {}, onForgetSession = { _, _ -> }, onLaunch = { _, _ -> },
                onBrowseFiles = {}, onEdit = {}, onManagePlugins = {}, onDelete = {})
        } } }
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(25_000) {
        if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("信任并连接").performClick()
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun nativeWindowsShowsGaugesAndRefreshesNetworkRate() {
        requireFixture()
        open("metrics")
        await(hasAnyAncestor(hasTestTag("host-gauge-cpu")) and hasText("50%"))
        compose.onNode(hasAnyAncestor(hasTestTag("host-gauge-memory")) and hasText("25%"), useUnmergedTree = true).assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag("host-gauge-disk")) and hasText("40%"), useUnmergedTree = true).assertExists()
        await(hasText("↓", substring = true))
        compose.onNodeWithTag("host-monitor-error").assertDoesNotExist()
        screenshot("windows-host-monitor.png")
    }

    @Test fun rejectedSystemQueryShowsErrorInsteadOfHealthyEmptySnapshot() {
        requireFixture()
        open("metrics-failed")
        await(hasTestTag("host-monitor-error"))
        compose.onNodeWithTag("host-monitor-error").assertTextEquals("无法读取监控数据，请检查远端系统查询权限后重试。")
        compose.onNodeWithTag("host-monitor-retry").assertIsEnabled()
        compose.onAllNodes(hasText("每 3 秒刷新")).assertCountEquals(0)
    }

    @Test fun nativeWindowsHostShowsMetrics() {
        requireNativeHost()
        open("roamtty_admin", 22249, "Fixture-only-123!")
        for (tag in listOf("host-gauge-cpu", "host-gauge-memory", "host-gauge-disk")) {
            await(hasAnyAncestor(hasTestTag(tag)) and hasText("%", substring = true))
        }
        await(hasText("↓", substring = true))
        compose.onNodeWithTag("host-monitor-error").assertDoesNotExist()
        screenshot("windows-native-host-monitor.png")
    }

    @Test fun nativeWindowsUserWithoutCimAccessShowsError() {
        requireNativeHost()
        open("roamtty_monitor", 22249, "Fixture-only-123!")
        await(hasTestTag("host-monitor-error"))
        compose.onNodeWithTag("host-monitor-retry").assertIsEnabled()
        compose.onAllNodes(hasText("每 3 秒刷新")).assertCountEquals(0)
    }

    private fun requireNativeHost() = assumeTrue("Requires disposable native Windows host on reversed port 22249",
        InstrumentationRegistry.getArguments().getString("windowsNativeMonitor") == "true")

    private fun requireFixture() = assumeTrue("Requires Windows SSH fixture on reversed port 22248",
        InstrumentationRegistry.getArguments().getString("windowsLaunchFixture") == "true")

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
