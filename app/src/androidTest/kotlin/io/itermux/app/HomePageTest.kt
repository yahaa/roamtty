package io.itermux.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The home page leads with the sessions, above the hosts, and its + starts a new session on a picked host. */
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
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun sessionsLeadTheHomePageAndPlusStartsOne() = runBlocking<Unit> {
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        // Port 1 refuses at once, so the session stays listed without a server.
        val host = Host(name = "首页会话主机", address = "127.0.0.1", port = 1, username = "test")
        app.database.hosts().save(host)
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("home-session-${session.id}").fetchSemanticsNodes().isNotEmpty() }
            val sessionsTitle = compose.onNodeWithTag("sessions-title").assertIsDisplayed().getUnclippedBoundsInRoot()
            val row = compose.onNodeWithTag("home-session-${session.id}").assertIsDisplayed()
                .assertTextContains("首页会话主机", substring = true).getUnclippedBoundsInRoot()
            val hostsTitle = compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("hosts-title"))
                .let { compose.onNodeWithTag("hosts-title").getUnclippedBoundsInRoot() }
            assertTrue("sessions come before hosts", sessionsTitle.top < row.top && row.bottom <= hostsTitle.top)
            compose.onNodeWithTag("host-grid").performScrollToIndex(0)
            capture("home-sessions-first")

            // + sits at the right end of the sessions title.
            val add = compose.onNodeWithTag("add-session").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("add button on the right", add.left > (sessionsTitle.left + sessionsTitle.right) / 2)
            compose.onNodeWithTag("add-session").performClick()
            compose.onNodeWithTag("new-session-sheet").assertIsDisplayed()
            compose.onNodeWithTag("new-session-sheet").performScrollToNode(hasTestTag("new-session-host-${host.id}"))
            compose.waitForIdle()
            File(context.getExternalFilesDir(null), "home-new-session-sheet.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("new-session-terminal-${host.id}").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("new-session-sheet").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("打开终端").assertIsDisplayed()
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
            app.database.hosts().delete(host.id)
        }
    }
}
