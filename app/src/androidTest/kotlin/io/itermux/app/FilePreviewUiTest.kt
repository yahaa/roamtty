package io.itermux.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.ui.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class FilePreviewUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun pathBarKeepsTheCurrentFolderVisibleInNarrowWindows() {
        var width by mutableStateOf(160.dp)
        var opened: String? = null
        val path = "/home/developer/projects/a-very-long-folder-name/source"
        compose.setContent { MaterialTheme { Box(Modifier.width(width)) { FileBrowserPathBar(path, "开发服务器", true, { opened = it }) } } }
        compose.onNodeWithTag("files-title").assertTextEquals("source").assertIsDisplayed()
        compose.onNodeWithTag("files-breadcrumbs").assertHeightIsAtLeast(40.dp)
        compose.runOnIdle { width = 600.dp }
        compose.onNodeWithTag("files-crumb-0").assertTextEquals("开发服务器").performClick()
        compose.onNodeWithTag("files-crumb-2").performClick()
        compose.runOnIdle { assertEquals("/home/developer", opened) }
    }

    @Test fun previewsTextImagesEmptyBinaryLargeFilesAndReadErrors() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(Host(name = "dev", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL"),
                SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        val prompts = launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        val prefix = "/preview-${UUID.randomUUID()}"
        val files = linkedMapOf(
            "$prefix.md" to "# Preview 中文\n    indent preserved\n".toByteArray(),
            "$prefix-empty.txt" to byteArrayOf(),
            "$prefix.bin" to byteArrayOf(0, 1, 2, 3),
            "$prefix-large.log" to ("BEGIN\n" + "sample line\n".repeat(30_000) + "END").toByteArray(),
            "$prefix.PNG" to ByteArrayOutputStream().also { out ->
                Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.rgb(220, 120, 60))
                    compress(Bitmap.CompressFormat.PNG, 100, out)
                    recycle()
                }
            }.toByteArray()
        )
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            files.forEach { (path, bytes) -> session.upload(path, bytes.inputStream()) }
            compose.setContent { MaterialTheme(colorScheme = DarkColors) { RemoteFileBrowserPage(session, {}) } }
            fun open(path: String, tag: String) {
                compose.runOnIdle { session.fileBrowser.previewPath = path }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("file-preview-download").assertIsEnabled()
            }
            open("$prefix.md", "file-preview-markdown")
            compose.onNodeWithText("Preview 中文").assertIsDisplayed()
            compose.onNodeWithText("indent preserved").assertIsDisplayed()
            compose.onNodeWithTag("file-preview-markdown-toggle").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("file-preview-text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("# Preview 中文").assertIsDisplayed()
            compose.onNodeWithText("    indent preserved").assertIsDisplayed()
            compose.onNodeWithTag("file-preview-status").assertTextContains("Markdown", substring = true)
            compose.onNodeWithTag("file-preview-search").performClick()
            compose.onNodeWithTag("file-preview-search-input").performTextInput("preview")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("1/1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("file-preview-search-close").performClick()
            compose.onNodeWithTag("file-preview-title").assertIsDisplayed()
            // Double tap hides the header and status line; another double tap brings them back.
            compose.onNodeWithTag("file-preview-text").performTouchInput { doubleClick() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("file-preview-title").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("file-preview-status").assertDoesNotExist()
            compose.onNodeWithTag("file-preview-text").performTouchInput { doubleClick() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("file-preview-title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("file-preview").captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "file-preview-text.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            open("$prefix-large.log", "file-preview-truncated")
            compose.onNodeWithText("BEGIN").assertIsDisplayed()
            compose.onNodeWithText("END").assertDoesNotExist()
            open("$prefix.PNG", "file-preview-image")
            compose.onNodeWithTag("file-preview-image").assertIsDisplayed()
            open("$prefix.bin", "file-preview-unsupported")
            open("$prefix-empty.txt", "file-preview-title")
            compose.waitUntil(10_000) { compose.onAllNodesWithText(tr("此文件为空")).fetchSemanticsNodes().isNotEmpty() }
            open("$prefix-missing.txt", "file-preview-retry")
            compose.onNodeWithTag("file-preview-retry").assertIsEnabled()
            // Retry after the remote file appears must replace the error with real content.
            session.upload("$prefix-missing.txt", "Recovered".byteInputStream())
            compose.onNodeWithTag("file-preview-retry").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Recovered").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("file-preview-close").performClick()
            compose.onNodeWithTag("files-list").assertIsDisplayed()
            assertEquals(Phase.CONNECTED, session.state.value.phase)
        } finally {
            (files.keys + "$prefix-missing.txt").forEach { runCatching { session.deleteRemoteFile(it) } }
            withContext(Dispatchers.Main) { service.close(session.id) }
            prompts.cancel()
        }
    }
}
