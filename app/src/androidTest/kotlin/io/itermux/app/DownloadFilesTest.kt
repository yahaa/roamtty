package io.itermux.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.session.*
import io.itermux.app.ui.DownloadFilesUi
import io.itermux.app.ui.DarkColors
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DownloadFilesTest {
    @get:Rule val compose = createComposeRule()

    private suspend fun withSession(action: suspend (TerminalConnection) -> Unit) {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("app-settings", android.content.Context.MODE_PRIVATE)
        val previousShowHidden = if (preferences.contains("showHiddenFiles")) preferences.getBoolean("showHiddenFiles", false) else null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pins = object : PinStore {
            private var accepted: HostPin? = null
            override suspend fun get(endpoint: String) = accepted
            override suspend fun putIfAbsent(pin: HostPin) = accepted ?: pin.also { accepted = it }
        }
        val session = TerminalConnection(Host(name = "Downloads", address = "127.0.0.1", port = 22228,
            username = "itermux_test", mode = "SHELL"), SessionMode.SHELL, Credentials("test-only".toByteArray()), pins, scope)
        val prompts = scope.launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            action(session)
        } catch (error: Throwable) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            File(instrumentation.targetContext.getExternalFilesDir(null), "download-test-failure.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot()?.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw error
        } finally {
            preferences.edit().apply {
                if (previousShowHidden == null) remove("showHiddenFiles") else putBoolean("showHiddenFiles", previousShowHidden)
            }.commit()
            prompts.cancel()
            session.disconnect()
            scope.cancel()
        }
    }

    @Test fun selectionCanSpanDirectoriesAndShowsMetadata() = runBlocking {
        withSession { session ->
            withContext(Dispatchers.Main) {
                session.transfers.downloadDirectory = "/phone-a"
                session.transfers.downloadDialog = true
            }
            compose.setContent { MaterialTheme(colorScheme = DarkColors) { DownloadFilesUi(session) } }
            compose.waitForDownloadEntry("assets")
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val orientationBeforeSelection = instrumentation.targetContext.resources.configuration.orientation
            File(instrumentation.targetContext.getExternalFilesDir(null), "download-picker-$orientationBeforeSelection.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("download-files-list").performScrollToNode(hasTestTag("download-entry-design-notes.md"))
            compose.onNodeWithTag("download-entry-design-notes.md").performClick().assertIsOn()
            compose.onNodeWithTag("download-files-list").performScrollToNode(hasTestTag("download-entry-package.json"))
            compose.onNodeWithTag("download-entry-package.json").performClick().assertIsOn()
            compose.onNodeWithTag("download-confirm").assertIsEnabled()
            compose.onNodeWithTag("download-search-toggle").performClick()
            compose.onNodeWithTag("download-search").performTextReplacement("README")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.onNodeWithTag("download-entry-README.md").assertIsDisplayed()
            compose.onNodeWithTag("download-entry-design-notes.md").assertDoesNotExist()
            compose.onNodeWithTag("download-options").performClick()
            compose.onNodeWithTag("download-select-all").performClick()
            compose.onNodeWithTag("download-search").performTextClearance()
            compose.onNodeWithTag("download-search").performImeAction()
            compose.onNodeWithTag("download-parent").performClick()
            compose.waitForDownloadEntry("phone-a")
            compose.onNodeWithTag("download-options").performClick()
            if (compose.onNodeWithTag("download-show-hidden").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.Off) {
                compose.onNodeWithTag("download-show-hidden").performClick()
            }
            compose.onNodeWithTag("download-refresh").performClick()
            compose.waitForDownloadEntry("phone-a")
            compose.onNodeWithTag("download-search").performTextReplacement(".bash_history")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.onNodeWithTag("download-entry-.bash_history").performClick()
            compose.onNodeWithTag("download-search").performTextClearance()
            compose.onNodeWithTag("download-search").performImeAction()
            compose.onNodeWithTag("download-selected").performClick()
            compose.onNodeWithTag("download-files-list").performScrollToNode(hasTestTag("download-entry-design-notes.md"))
            compose.onNodeWithTag("download-entry-design-notes.md").assertIsOn()
            compose.onNodeWithTag("download-files-list").performScrollToNode(hasTestTag("download-entry-.bash_history"))
            compose.onNodeWithTag("download-entry-.bash_history").assertIsOn()
            assertEquals(4, session.transfers.selectedDownloads.size)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.waitForIdle()
            delay(250)
            val orientation = context.resources.configuration.orientation
            File(context.getExternalFilesDir(null), "download-multiple-$orientation.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun terminalDownloadsFollowCurrentDirectoryAndSearchAcceptsNestedPaths() = runBlocking {
        withSession { session ->
            withContext(Dispatchers.Main) {
                val osc = "\u001b]7;file://fixture/phone-a\u0007".toByteArray()
                session.emulator.append(osc, osc.size)
                session.openTerminalDownloads()
            }
            compose.setContent { MaterialTheme { DownloadFilesUi(session) } }
            compose.waitForDownloadEntry("assets")
            compose.onNodeWithTag("download-directory").assertTextEquals("/phone-a")
            compose.onNodeWithTag("download-search-toggle").performClick()
            compose.onNodeWithTag("download-search").performTextReplacement("../phone-a/README")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("download-entry-README.md").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("download-entry-README.md").performClick()
            assertEquals("/phone-a/README.md", session.transfers.selectedDownloads.single().path)
            compose.onNodeWithTag("download-search").performTextReplacement("/phone-b/")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("download-directory").assertTextEquals("/phone-b") }.isSuccess
            }
            compose.onNodeWithTag("download-search").performTextReplacement("./assets/")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("download-directory").assertTextEquals("/phone-a/assets") }.isSuccess
            }
            withContext(Dispatchers.Main) { session.transfers.downloadDialog = false }
            compose.waitForIdle()
            withContext(Dispatchers.Main) {
                session.onWorkingDirectoryChanged("file://fixture/phone-b")
                session.openTerminalDownloads()
            }
            compose.waitUntil(10_000) {
                !session.transfers.resolvingDownloadDirectory && session.transfers.downloadDirectory == "/phone-b"
            }
            compose.onNodeWithTag("download-directory").assertTextEquals("/phone-b")
        }
    }

    @Test fun fuzzySearchMatchesAbbreviatedDirectoriesAndNamesWithoutMixingDownloadPaths() = runBlocking {
        withSession { session ->
            withContext(Dispatchers.Main) {
                session.transfers.downloadDirectory = "/phone-a"
                session.transfers.downloadDialog = true
            }
            compose.setContent { MaterialTheme { DownloadFilesUi(session) } }
            compose.waitForDownloadEntry("assets")
            compose.onNodeWithTag("download-search-toggle").performClick()
            compose.onNodeWithTag("download-search").performTextReplacement("RDME")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.onNodeWithTag("download-entry-README.md").assertIsDisplayed()
            compose.onNodeWithTag("download-search").performTextReplacement("DST/ITMDBGAPK")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("download-entry-iTermux-debug.apk").fetchSemanticsNodes().size == 2 }
            compose.onAllNodesWithTag("download-entry-iTermux-debug.apk")[0].performClick()
            compose.onAllNodesWithTag("download-entry-iTermux-debug.apk")[1].performClick()
            assertEquals(setOf("/phone-a/dist/iTermux-debug.apk", "/phone-a/dist-backup/iTermux-debug.apk"),
                session.transfers.selectedDownloads.map { it.path }.toSet())
            compose.onNodeWithTag("download-selected").performClick()
            compose.onAllNodesWithTag("download-entry-iTermux-debug.apk").assertCountEquals(2)
            compose.onNodeWithTag("download-search").performTextReplacement("does-not-exist")
            compose.onNodeWithTag("download-search").performImeAction()
            compose.onNodeWithText(tr("无匹配文件")).assertIsDisplayed()
        }
    }

    @Test fun newestFilesComeFirstAndLongNamesStaySearchable() = runBlocking {
        withSession { session ->
            val id = UUID.randomUUID().toString().take(8)
            val older = "/phone-b/older-$id.txt"
            val longName = "release-candidate-$id-build-arm64-v8a-signed-final-with-a-very-long-descriptive-name.apk"
            try {
                session.upload(older, "old".byteInputStream())
                delay(1_100) // SFTP mtimes have one-second resolution.
                session.upload("/phone-b/$longName", "new".byteInputStream())
                withContext(Dispatchers.Main) {
                    session.transfers.downloadDirectory = "/phone-b"
                    session.transfers.downloadDialog = true
                }
                compose.setContent { MaterialTheme { DownloadFilesUi(session) } }
                compose.waitForDownloadEntry(longName)
                val rows = compose.onNodeWithTag("download-files-list").onChildren()
                rows[0].assert(hasTestTag("download-entry-$longName"))
                compose.waitForDownloadEntry("older-$id.txt")
                compose.onNodeWithTag("download-search-toggle").performClick()
                // An autocomplete space and a wrapped terminal copy must not hide the file.
                compose.onNodeWithTag("download-search").performTextReplacement("candidate-$id-build-arm64-v8a-signed ")
                compose.onNodeWithTag("download-entry-$longName").assertIsDisplayed()
                compose.onNodeWithTag("download-search").performTextReplacement("release-candidate-$id-build-arm64-v8a-sig\nned-final")
                compose.onNodeWithTag("download-entry-$longName").assertIsDisplayed()
                compose.onNodeWithTag("download-entry-older-$id.txt").assertDoesNotExist()
            } finally {
                withContext(NonCancellable) {
                    runCatching { session.deleteRemoteFile(older) }
                    runCatching { session.deleteRemoteFile("/phone-b/$longName") }
                }
            }
        }
    }

    @Test fun batchDownloadsUseDocumentTreeKeepNamesAndContinueAfterFailure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val tree = AtomicReference<Uri?>()
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
        compose.setContent {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree.set(it) }
            Button(onClick = { picker.launch(initial) }, modifier = Modifier.testTag("choose-tree")) { Text("Choose folder") }
        }
        val created = mutableListOf<Uri>()
        try {
            compose.onNodeWithTag("choose-tree").performClick()
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (tree.get() == null && SystemClock.uptimeMillis() < deadline) {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")
                    ?.firstOrNull { it.isClickable && it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(100)
            }
            val destination = requireNotNull(tree.get()) { "System folder picker did not return a tree" }
            withSession { session ->
                val prefix = "batch-${UUID.randomUUID()}"
                val good = "phone-a/$prefix-report.pdf"
                val missing = "$prefix/missing"
                val duplicate = "phone-b/$prefix-report.pdf"
                val extensionless = "$prefix-README"
                session.upload(good, "first".byteInputStream())
                session.upload(duplicate, "second".byteInputStream())
                session.upload(extensionless, "plain".byteInputStream())
                val isolatedTree = destination
                withContext(Dispatchers.Main) {
                    session.transfers.downloadFiles(context, session, isolatedTree,
                        listOf(good, missing, duplicate, extensionless).map { DownloadItem(it) })
                }
                val result = withTimeout(30_000) { session.transfers.status.first { !it.running } }
                assertEquals(listOf(DownloadPhase.COMPLETE, DownloadPhase.FAILED, DownloadPhase.COMPLETE, DownloadPhase.COMPLETE), result.downloads.map { it.phase })
                assertEquals(false, result.succeeded)
                assertNull("A deleted partial file must not be presented as a saved download", result.downloads[1].localPath)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(isolatedTree, DocumentsContract.getTreeDocumentId(isolatedTree))
                val names = mutableListOf<String>()
                val contents = mutableListOf<String>()
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (!cursor.getString(1).startsWith(prefix)) continue
                        names += cursor.getString(1)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(isolatedTree, cursor.getString(0))
                        created += uri
                        contents += resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    }
                }
                assertEquals(3, names.size)
                assertTrue(names.contains("$prefix-report.pdf"))
                assertTrue(names.contains("$prefix-report (2).pdf"))
                assertTrue(names.contains("$prefix-README"))
                assertEquals(setOf("first", "second", "plain"), contents.toSet())
                assertEquals(names.toSet(), result.downloads.filter { it.phase == DownloadPhase.COMPLETE }
                    .map { requireNotNull(it.localPath).substringAfterLast('/') }.toSet())
            }
        } finally {
            created.forEach { DocumentsContract.deleteDocument(resolver, it) }
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
