package io.itermux.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The home page opens on its sessions tab; the toolbar's 会话/主机 tabs, a swipe or Back move between the two. */
@RunWith(AndroidJUnit4::class)
class HomePageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ItermuxApp

    @Before fun prepare() {
        compose.runOnUiThread { AppLocale.set(context, AppLanguage.ZH_CN) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun sessionsTabLeadsAndTabsSwitchToHosts() = runBlocking<Unit> {
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        // Port 1 refuses at once, so the session stays listed without a server.
        val host = Host(name = "首页会话主机", address = "127.0.0.1", port = 1, username = "test")
        app.database.hosts().save(host)
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("home-session-${session.id}").fetchSemanticsNodes().isNotEmpty() }
            val root = compose.onRoot().getUnclippedBoundsInRoot()
            val sessionsTab = compose.onNodeWithTag("home-tab-sessions").assertIsSelected().assertTextEquals("会话")
                .getUnclippedBoundsInRoot()
            val hostsTab = compose.onNodeWithTag("home-tab-hosts").assertIsNotSelected().assertTextEquals("主机")
                .getUnclippedBoundsInRoot()
            val tabsCenter = (sessionsTab.left + hostsTab.right) / 2
            assertTrue("tabs sit in the middle: $tabsCenter of ${root.right}", abs((tabsCenter - root.right / 2).value) < 4f)
            compose.onNodeWithTag("home-session-${session.id}").assertIsDisplayed()
                .assertTextContains("首页会话主机", substring = true)
            compose.onNodeWithTag("host-card-${host.id}").assertDoesNotExist()
            capture("home-tab-sessions")

            // Search is the toolbar's last button, flush right; the + sits at the list's top right, beneath it.
            val searchToggle = compose.onNodeWithTag("search-toggle").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("search flush right: ${searchToggle.right} of ${root.right}", root.right - searchToggle.right < 8.dp)
            // The + starts a session on a picked host.
            val add = compose.onNodeWithTag("add-session").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("add at the list's right", abs((add.right - searchToggle.right).value) < 1f)
            assertTrue("add below the toolbar", add.top >= searchToggle.bottom)
            compose.onNodeWithTag("add-session").performClick()
            // What runs comes first, then the host; Back from the hosts returns to that first step.
            compose.onNodeWithTag("new-session-pick-claude").assertIsDisplayed()
            compose.onNodeWithTag("new-session-host-${host.id}").assertDoesNotExist()
            capture("new-session-agents")
            compose.onNodeWithTag("new-session-pick-terminal").performClick()
            compose.onNodeWithTag("new-session-sheet").performScrollToNode(hasTestTag("new-session-host-${host.id}"))
            capture("new-session-hosts")
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("new-session-pick-terminal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("new-session-pick-terminal").performClick()
            compose.onNodeWithTag("new-session-sheet").performScrollToNode(hasTestTag("new-session-host-${host.id}"))
            compose.onNodeWithTag("new-session-host-${host.id}").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("new-session-sheet").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("打开终端").assertIsDisplayed()
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("打开终端").fetchSemanticsNodes().isEmpty() }

            // A tap on 主机 shows the hosts, and the + adds a host there.
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("host-grid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-tab-hosts").assertIsSelected()
            compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${host.id}"))
            compose.onNodeWithTag("host-card-${host.id}").assertIsDisplayed()
            val addHost = compose.onNodeWithTag("add-host").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("add host at the list's right", abs((addHost.right - searchToggle.right).value) < 1f)
            capture("home-tab-hosts")

            // A swipe right goes back to the sessions, and Back on the hosts tab does too.
            compose.onNodeWithTag("home-pager").performTouchInput { swipeRight() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("home-tab-sessions") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-session-${session.id}").assertIsDisplayed()
            compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("home-tab-hosts") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("home-tab-sessions") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-session-${session.id}").assertIsDisplayed()
            // The card's details are one quiet line: its state and the host it runs on.
            compose.onNodeWithTag("home-session-${session.id}").assertTextContains(host.name, substring = true)

            // A session opened from the list returns to the sessions tab, even after the hosts tab was last shown.
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("home-tab-hosts") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-tab-sessions").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("home-tab-sessions") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-session-${session.id}").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-pager").fetchSemanticsNodes().isEmpty() }
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("home-tab-sessions") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-session-${session.id}").assertIsDisplayed()
            capture("home-back-from-terminal")
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
            app.database.hosts().delete(host.id)
        }
    }

    @Test fun newSessionFillsInANewHost() = runBlocking<Unit> {
        // Port 1 and no password: saving skips the system probe, so no server is needed.
        val address = "new-session-${java.util.UUID.randomUUID().toString().take(6)}.invalid"
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("add-session").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("add-session").performClick()
            compose.onNodeWithTag("new-session-pick-claude").performClick()
            compose.onNodeWithTag("new-session-sheet").performScrollToNode(hasTestTag("new-session-new-host"))
            capture("new-session-sheet")
            compose.onNodeWithTag("new-session-new-host").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("host-editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("新建会话") and hasAnyAncestor(hasTestTag("host-editor"))).assertIsDisplayed()
            // A host filled in for a session keeps its password by default.
            compose.onNodeWithTag("save-password").performScrollTo().assertIsOn()
            compose.onNodeWithTag("save-password").performClick()
            // The name may stay blank: the host takes its address.
            compose.onNodeWithTag("host-address").performTextInput(address)
            compose.onNodeWithTag("host-username").performTextInput("test")
            compose.onNodeWithTag("host-port").performTextClearance()
            compose.onNodeWithTag("host-port").performTextInput("1")
            capture("new-session-host-editor")
            compose.onNodeWithTag("save-host").assertTextEquals("下一步").performClick()
            // The saved host continues straight to the launch sheet, for an AI agent.
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("host-editor").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(8_000) { compose.onAllNodesWithText("用 AI 助手打开").fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(800) // let the sheet finish sliding in for the screenshot
            capture("new-session-launch")
            // The agent picked first is the one the launch sheet starts.
            compose.onNodeWithTag("agent-picker").assertTextContains("Claude", substring = true)
            compose.onNodeWithTag("launch-open").assertTextContains("Claude", substring = true)
            val saved = app.database.hosts().observe().first().single { it.address == address }
            assertEquals(address, saved.name)
            Espresso.pressBack()
        } finally {
            app.database.hosts().observe().first().filter { it.address == address }.forEach { app.database.hosts().delete(it.id) }
        }
    }
}
