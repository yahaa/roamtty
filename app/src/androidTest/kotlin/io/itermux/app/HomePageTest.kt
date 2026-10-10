package io.itermux.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.AgentMode
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.SessionService
import io.itermux.app.ui.OPEN_LAST_SESSION_KEY
import io.itermux.app.ui.saveLastLaunch
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The home page: sessions first, then hosts ordered pinned → recent → name, each with a one-tap launch. */
@RunWith(AndroidJUnit4::class)
class HomePageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ItermuxApp
    private val prefs = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)

    private fun capture(name: String) {
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun cardTop(host: Host) = compose.onNodeWithTag("host-card-${host.id}").fetchSemanticsNode().boundsInRoot.top

    @Test fun sessionsLeadAndHostsOrderByPinAndUse() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val alpha = Host(name = "Home Alpha", address = "127.0.0.1", port = 1, username = "test", mode = "SHELL", os = "LINUX")
        val recent = Host(name = "Home Zulu recent", address = "127.0.0.1", port = 1, username = "test", mode = "SHELL",
            os = "UBUNTU", lastUsedAt = now - 210 * 60_000, plugins = "")
        val pinned = Host(name = "Home Yankee pinned", address = "127.0.0.1", port = 1, username = "test", mode = "SHELL",
            os = "MACOS", pinned = true)
        val hosts = listOf(alpha, recent, pinned)
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(recent, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        try {
            // Cards read their last launch when they first show.
            prefs.saveLastLaunch(recent.id, "~/proj", AgentLaunch(CodingAgent.CLAUDE, "~/proj", AgentMode.STANDARD))
            hosts.forEach { app.database.hosts().save(it) }
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("host-card-${alpha.id}").fetchSemanticsNodes().isNotEmpty() }

            // The sidebar stays; the session also sits above the hosts.
            compose.onNodeWithTag("brand-logo").assertIsDisplayed()
            compose.onNodeWithTag("home-session-${session.id}").assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("home-sessions").fetchSemanticsNode().boundsInRoot.bottom <= cardTop(pinned))
            // Pinned first, then the recently used host, then by name.
            assertTrue(cardTop(pinned) < cardTop(recent))
            compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${alpha.id}"))
            assertTrue(cardTop(recent) < cardTop(alpha))
            compose.onNodeWithTag("host-pinned-${pinned.id}", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("host-sessions-${recent.id}", useUnmergedTree = true).assertTextContains("1 个会话", substring = true)
            compose.onNodeWithTag("host-last-used-${recent.id}", useUnmergedTree = true).assertTextEquals("3 小时前使用")
            // Probed without tmux: the card offers to install it.
            compose.onNodeWithTag("host-install-mux-${recent.id}", useUnmergedTree = true).assertExists()
            // The last launch is one tap away; a host without one offers the agent sheet.
            compose.onNodeWithTag("host-quick-launch-${recent.id}").assertTextContains("启动 Claude · proj")
            compose.onNodeWithTag("host-quick-launch-${alpha.id}").assertTextContains("启动 AI 助手")
            compose.onNodeWithTag("host-grid").performScrollToIndex(0)
            capture("home-sessions-and-hosts")

            // A bell while the terminal is elsewhere flags the session until it is opened.
            withContext(Dispatchers.Main) { session.getEmulator().append(byteArrayOf(7), 1) }
            assertTrue(session.attention.value)
            compose.onNodeWithTag("home-session-attention-${session.id}", useUnmergedTree = true).assertIsDisplayed()
            capture("home-session-attention")
            compose.openSession(session.id)
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            withTimeout(5_000) { session.attention.first { !it } }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } // terminal → host page
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("host-detail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("host-detail-install-mux").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("host-detail-back").performClick()
            compose.onNodeWithTag("home-session-attention-${session.id}", useUnmergedTree = true).assertDoesNotExist()

            // Long-press opens the host menu; pinning reorders the list.
            compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${alpha.id}"))
            compose.onNodeWithTag("host-card-${alpha.id}").performTouchInput { longClick() }
            compose.onNodeWithTag("host-pin-${alpha.id}").performClick()
            withTimeout(5_000) { app.database.hosts().observe().first { list -> list.any { it.id == alpha.id && it.pinned } } }
            compose.waitUntil(5_000) { cardTop(alpha) < cardTop(recent) }

            // Three hosts need no search; more than six do.
            compose.onNodeWithTag("search-toggle").assertDoesNotExist()
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
            hosts.forEach { app.database.hosts().delete(it.id) }
            prefs.edit { remove("lastLaunch:${recent.id}") }
        }
    }

    @Test fun searchAppearsOnlyForLongHostLists() = runBlocking<Unit> {
        val hosts = (1..7).map { Host(name = "Search host $it", address = "search$it.example", username = "test", os = "LINUX") }
        try {
            hosts.take(1).forEach { app.database.hosts().save(it) }
            compose.waitUntil(8_000) { compose.onAllNodesWithText("Search host 1").fetchSemanticsNodes().isNotEmpty() }
            val existing = app.database.hosts().observe().first().size
            if (existing <= 6) compose.onNodeWithTag("search-toggle").assertDoesNotExist()
            hosts.drop(1).forEach { app.database.hosts().save(it) }
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("search-toggle").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("search-toggle").performClick()
            compose.onNodeWithTag("host-search").performTextInput("Search host 7")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Search host 1").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("host-card-${hosts.last().id}").assertIsDisplayed()
        } finally {
            hosts.forEach { app.database.hosts().delete(it.id) }
        }
    }

    @Test fun pastedSshCommandFillsTheNewHostForm() {
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("add-host").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("add-host").onFirst().performClick()
        compose.onNodeWithTag("host-import").performTextInput("ssh -p 2222 -i ~/.ssh/id_ed25519 me@dev.example.com")
        compose.onNodeWithTag("host-import-apply").performClick()
        compose.onNodeWithTag("host-name").assertTextContains("dev.example.com")
        compose.onNodeWithTag("host-address").assertTextContains("dev.example.com")
        compose.onNodeWithTag("host-port").assertTextContains("2222")
        compose.onNodeWithTag("host-username").assertTextContains("me")
        compose.onNodeWithText("导入 Ed25519 / RSA 私钥").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已填入 dev.example.com。该主机使用私钥 ~/.ssh/id_ed25519，请在下方导入。").assertExists()
        File(context.getExternalFilesDir(null), "home-import-command.png").outputStream().use {
            compose.onNodeWithTag("host-editor").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // A config with several entries asks which one.
        compose.onNodeWithTag("host-import").performTextClearance()
        compose.onNodeWithTag("host-import").performTextInput("Host a b\n  HostName box.example\n  User ann")
        compose.onNodeWithTag("host-import-apply").performClick()
        compose.onNodeWithText("找到 2 台主机").assertIsDisplayed()
        compose.onNodeWithTag("host-import-choice-b").performClick()
        compose.onNodeWithTag("host-name").assertTextContains("b")
        compose.onNodeWithTag("host-address").assertTextContains("box.example")
        compose.onNodeWithTag("host-username").assertTextContains("ann")
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun settingOpensTheLastSessionOnLaunch() = runBlocking<Unit> {
        val host = Host(name = "Home relaunch", address = "127.0.0.1", port = 1, username = "test", mode = "SHELL", os = "LINUX")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        try {
            app.database.hosts().save(host)
            compose.openSession(session.id)
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("host-detail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("host-detail-back").performClick()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-settings").performClick()
            compose.onNodeWithTag("settings-open-last-session").performScrollTo().performClick()
            assertTrue(prefs.getBoolean(OPEN_LAST_SESSION_KEY, false))
            compose.onNodeWithTag("close-settings").performClick()

            // A fresh activity (as after the app was closed) goes straight to that terminal.
            compose.activityRule.scenario.onActivity { it.finish() }
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            prefs.edit { remove(OPEN_LAST_SESSION_KEY) }
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
            app.database.hosts().delete(host.id)
        }
        assertFalse(prefs.getBoolean(OPEN_LAST_SESSION_KEY, false))
        assertEquals(null, app.database.hosts().find(host.id))
    }
}
