package io.itermux.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileBrowserLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun browserAndRenameSurviveRecreationAndBackRetracesFolders() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItermuxApp
        // The loopback fixture creates a fresh host key on each run.
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val host = Host(name = "Design Studio", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL", os = "LINUX")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        app.database.hosts().save(host)
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            withContext(Dispatchers.Main) { session.rename("File browser task") }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(trf("主机选项：%s", host.name)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(trf("主机选项：%s", host.name)).performClick()
            compose.onNodeWithTag("host-browse-files-${host.id}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("files-entry-phone-a").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("files-title").assertTextEquals("/")
            compose.onNodeWithTag("files-subtitle").assertTextEquals(host.name)
            compose.onNodeWithText(tr("服务器文件")).assertDoesNotExist()
            compose.onNode(hasText("File browser task", substring = true) and
                hasAnyAncestor(hasTestTag("remote-file-browser"))).assertDoesNotExist()
            compose.onNodeWithTag("files-entry-phone-a").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("files-list").fetchSemanticsNodes().isNotEmpty() }
            // Other SFTP tests leave files in this directory; the target may be outside the lazy viewport.
            compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("files-entry-design-notes.md"))
            compose.onNodeWithTag("files-title").assertTextEquals("phone-a")
            compose.onNodeWithTag("files-crumb-1").assertTextEquals("phone-a")
            compose.onNodeWithTag("files-entry-design-notes.md").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("file-preview-text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("# Design notes").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("file-preview-text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("# Design notes").assertIsDisplayed()
            compose.onNodeWithTag("file-preview-close").performClick()
            compose.onNodeWithTag("files-row-menu-design-notes.md").performClick()
            compose.onNodeWithTag("files-action-rename").performClick()
            compose.onNodeWithTag("files-rename-input").performTextReplacement("Revised notes.md")
            compose.runOnIdle { session.transfers.pendingDownloads = listOf(io.itermux.app.session.DownloadItem("/phone-a/design-notes.md")) }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("files-rename-input").assertTextContains("Revised notes.md")
            compose.runOnIdle {
                assertEquals("/phone-a", session.fileBrowser.directory)
                assertEquals("/phone-a/design-notes.md", session.transfers.pendingDownloads.single().path)
            }
            compose.onNodeWithText(tr("取消")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("files-entry-design-notes.md").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(context.getExternalFilesDir(null), "design-files-dark.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithTag("files-back").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("files-entry-phone-a").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("files-back").performClick()
            compose.onNodeWithTag("host-grid").assertIsDisplayed()
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
            app.database.hosts().delete(host.id)
        }
        Unit
    }
}
