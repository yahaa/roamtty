package io.itermux.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Environment
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.session.*
import io.itermux.app.ui.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadDestinationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val resolver get() = context.contentResolver
    private val preferences get() = context.getSharedPreferences("download-settings", Context.MODE_PRIVATE)
    private val folderName = "000-itermux-${UUID.randomUUID()}"
    private val localPath get() = "/sdcard/Documents/$folderName"
    private var previousPreferences: Map<String, *> = emptyMap<String, String>()
    private var previousGrants = emptySet<Uri>()
    private var previousFlags = 0

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Before fun prepare() {
        previousPreferences = preferences.all
        previousGrants = resolver.persistedUriPermissions.map { it.uri }.toSet()
        preferences.edit().clear().commit()
        previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        shell("mkdir -p $localPath/alternate")
    }

    @After fun cleanup() {
        // Only this test's UUID-named directory is removed.
        shell("rm -rf $localPath")
        resolver.persistedUriPermissions.filter { it.uri !in previousGrants }.forEach {
            resolver.releasePersistableUriPermission(it.uri,
                (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        }
        preferences.edit().clear().apply { previousPreferences.forEach { (key, value) -> putString(key, value as String) } }.commit()
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }

    private fun waitForPicker() {
        compose.waitForIdle()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) return
            SystemClock.sleep(50)
        }
        fail("Expected the system file picker")
    }

    private fun acceptPicker(child: String? = null) {
        waitForPicker()
        if (child != null) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var opened = false
            while (!opened && SystemClock.uptimeMillis() < deadline) {
                var node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(child)?.firstOrNull { it.text?.toString() == child }
                while (node != null && !node.isClickable) node = node.parent
                opened = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!opened) SystemClock.sleep(50)
            }
            assertTrue("Folder $child should be selectable", opened)
            SystemClock.sleep(250)
        }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && !root.packageName.toString().contains("documentsui") &&
                !root.packageName.toString().contains("permissioncontroller")) return
            root?.findAccessibilityNodeInfosByViewId("android:id/button1")
                ?.firstOrNull { it.isEnabled && it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(100)
        }
        fail("System picker did not close")
    }

    private fun cancelPicker() {
        waitForPicker()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val packageName = automation.rootInActiveWindow?.packageName?.toString()
            if (packageName == context.packageName) return
            // Back first navigates out of a nested folder before dismissing DocumentsUI.
            if (packageName?.contains("documentsui") == true) {
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                // Global back is asynchronous. Wait for the window transition before another back,
                // otherwise a stale DocumentsUI root can cause us to close the test Activity too.
                automation.waitForIdle(500, 3_000)
            }
            SystemClock.sleep(250)
        }
        fail("System picker was not canceled")
    }

    private suspend fun withSession(action: suspend (TerminalConnection, List<DownloadItem>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pins = object : PinStore {
            private var pin: HostPin? = null
            override suspend fun get(endpoint: String) = pin
            override suspend fun putIfAbsent(pin: HostPin) = this.pin ?: pin.also { this.pin = it }
        }
        val session = TerminalConnection(Host(name = "Destinations", address = "127.0.0.1", port = 22228,
            username = "itermux_test", mode = "SHELL"), SessionMode.SHELL, Credentials("test-only".toByteArray()), pins, scope)
        val prompts = scope.launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        val files = listOf("$folderName-report.pdf", "$folderName-README", "$folderName-中文 文件.txt").map { DownloadItem(it) }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            files.forEach { session.upload(it.path, it.name.byteInputStream()) }
            action(session, files)
        } finally {
            files.forEach { runCatching { session.deleteRemoteFile(it.path) } }
            prompts.cancel()
            session.disconnect()
            scope.cancel()
        }
    }

    private fun content(session: TerminalConnection, files: State<List<DownloadItem>>): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                val actions = rememberDownloadActions(session)
                Column {
                    Button(onClick = { actions.start(files.value, false) }, enabled = !actions.busy,
                        modifier = Modifier.testTag("default-download")) { Text("Download") }
                    Button(onClick = { actions.start(files.value, true) }, enabled = !actions.busy,
                        modifier = Modifier.testTag("save-as-download")) { Text("Save as") }
                    DownloadDirectorySetting()
                }
                DownloadFilesUi(session, actions)
            }
        }
        return restoration
    }

    private suspend fun completed(session: TerminalConnection, expected: Int): List<DownloadItem> {
        compose.waitForIdle()
        val result = withTimeout(15_000) { session.transfers.status.first { !it.running && it.result != null } }
        assertEquals(expected, session.transfers.status.value.downloads.size)
        assertEquals(true, session.transfers.status.value.succeeded)
        withContext(Dispatchers.Main) { session.transfers.dismiss() }
        compose.waitForIdle()
        return result.downloads
    }

    private fun contents(tree: Uri): Map<String, String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)!!.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    put(cursor.getString(1), resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
                }
            }
        }
    }

    @Test fun defaultPersistsAcrossRecreationAndSingleBatchDownloadsWhileSaveAsStaysTemporary() = runBlocking {
        withSession { session, files ->
            val selected = mutableStateOf(files.take(1))
            val restoration = content(session, selected)
            compose.onNodeWithTag("default-download").performClick()
            acceptPicker(folderName)
            val first = completed(session, 1).single()
            val tree = requireNotNull(DownloadDirectory(context).uri)
            val expectedDirectory = "${Environment.getExternalStorageDirectory()}/Documents/$folderName"
            assertEquals("$expectedDirectory/${files.first().name}", first.localPath)
            assertTrue(DownloadDirectory(context).isAvailable(tree))
            assertEquals(files.first().name, contents(tree)[files.first().name])

            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { selected.value = files.drop(1) }
            compose.onNodeWithTag("default-download").performClick()
            val batch = completed(session, 2) // Completing without accepting a picker proves the default was reused.
            assertEquals(files.drop(1).map { "$expectedDirectory/${it.name}" }, batch.map { it.localPath })
            assertEquals(files.map { it.name }.toSet(), contents(tree).keys)

            compose.runOnIdle { selected.value = files.take(1) }
            compose.onNodeWithTag("save-as-download").performClick()
            acceptPicker()
            val saveAs = completed(session, 1).single()
            assertEquals(tree, DownloadDirectory(context).uri)
            assertEquals(2, contents(tree).values.count { it == files.first().name }) // No overwrite.
            assertNotEquals(first.localPath, saveAs.localPath)
            assertEquals(files.first().name, contents(tree)[saveAs.localPath!!.substringAfterLast('/')])

            compose.runOnIdle { selected.value = files.drop(1) }
            compose.onNodeWithTag("save-as-download").performClick()
            acceptPicker("alternate")
            val alternateFiles = completed(session, 2)
            assertEquals(files.drop(1).map { "$expectedDirectory/alternate/${it.name}" }, alternateFiles.map { it.localPath })
            assertEquals(tree, DownloadDirectory(context).uri)
            val alternate = DocumentsContract.buildTreeDocumentUri(tree.authority, DocumentsContract.getTreeDocumentId(tree) + "/alternate")
            assertEquals(files.drop(1).map { it.name }.toSet(), contents(alternate).keys)
        }
    }

    @Test fun revokedGrantReopensPickerAndCancelDoesNotStartADownload() = runBlocking {
        withSession { session, files ->
            content(session, mutableStateOf(files.take(1)))
            compose.onNodeWithTag("default-download").performClick()
            acceptPicker(folderName)
            completed(session, 1)
            val tree = requireNotNull(DownloadDirectory(context).uri)
            resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertFalse(DownloadDirectory(context).isAvailable(tree))
            compose.onNodeWithTag("default-download").performClick()
            cancelPicker()
            compose.waitUntil(10_000) {
                runCatching { compose.onAllNodesWithTag("download-confirm").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            assertFalse(session.transfers.status.value.running)
            assertTrue(session.transfers.pendingDownloads.isEmpty())
            assertEquals(tree, DownloadDirectory(context).uri)
            compose.onNodeWithTag("download-confirm").performClick()
            acceptPicker()
            completed(session, 1)
            assertTrue(DownloadDirectory(context).isAvailable(tree))
        }
    }

    @Test fun settingsChangeDefaultAndDeletedDirectoryIsDetected() = runBlocking {
        withSession { session, files ->
            content(session, mutableStateOf(files.take(1)))
            compose.onNodeWithTag("settings-download-change").performClick()
            acceptPicker(folderName)
            compose.waitUntil(10_000) { DownloadDirectory(context).uri != null }
            val original = requireNotNull(DownloadDirectory(context).uri)
            compose.onNodeWithTag("settings-download-change").performClick()
            acceptPicker("alternate")
            compose.waitUntil(10_000) { DownloadDirectory(context).uri != original }
            val changed = requireNotNull(DownloadDirectory(context).uri)
            compose.onNodeWithTag("default-download").performClick()
            completed(session, 1)
            assertEquals(files.first().name, contents(changed)[files.first().name])
            DocumentsContract.deleteDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(changed, DocumentsContract.getTreeDocumentId(changed)))
            assertFalse(DownloadDirectory(context).isAvailable(changed))
            compose.onNodeWithTag("default-download").performClick()
            cancelPicker()
            compose.waitUntil(10_000) {
                runCatching { compose.onAllNodesWithTag("download-confirm").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            assertFalse(session.transfers.status.value.running)
        }
    }

    @Test fun fileBrowserAndDownloadPanelUseTheSameDefault() = runBlocking {
        withSession { session, files ->
            compose.setContent { MaterialTheme { RemoteFileBrowserPage(session, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("files-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("files-entry-${files.first().name}"))
            compose.onNodeWithTag("files-row-menu-${files.first().name}").performClick()
            compose.onNodeWithTag("files-action-download").performClick()
            acceptPicker(folderName)
            compose.waitForIdle()
            withTimeout(15_000) { session.transfers.status.first { !it.running && it.result != null } }
            val displayedPath = "${Environment.getExternalStorageDirectory()}/Documents/$folderName/${files.first().name}"
            val remotePath = session.transfers.status.value.downloads.single().path
            compose.onNodeWithTag("download-result-details").performClick()
            compose.onNodeWithTag("download-local-path-$remotePath")
                .assertTextEquals(trf("下载路径：%s", displayedPath))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { getLayout ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(getLayout(layouts))
                    assertTrue("Long download paths wrap instead of being truncated", layouts.single().lineCount > 1)
                    assertFalse(layouts.single().hasVisualOverflow)
                }
            java.io.File(context.getExternalFilesDir(null), "download-result-local-path.png").outputStream().use {
                compose.onNodeWithTag("transfer-dialog").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            completed(session, 1)
            val tree = requireNotNull(DownloadDirectory(context).uri)
            withContext(Dispatchers.Main) {
                session.transfers.selectedDownloads = files.drop(1)
                session.transfers.downloadDialog = true
            }
            compose.onNodeWithTag("download-confirm").performClick()
            completed(session, 2)
            assertEquals(files.map { it.name }.toSet(), contents(tree).keys)
        }
    }
}
