package io.itermux.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.Prompt
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalExtraKeysTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private var previousImeSetting: String? = null
    private val emulator = Build.HARDWARE in listOf("ranchu", "goldfish")

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }

    @Before fun showEmulatorIme() {
        if (emulator) {
            previousImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
            shell("settings put secure show_ime_with_hard_keyboard 1")
        }
    }

    @After fun restoreEmulatorIme() {
        previousImeSetting?.let { value ->
            shell(if (value == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $value")
        }
    }

    private fun terminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            terminal(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun commitText(text: String) {
        compose.activityRule.scenario.onActivity { activity ->
            requireNotNull(terminal(activity.window.decorView))
                .onCreateInputConnection(EditorInfo()).commitText(text, 1)
        }
    }

    @Test fun extraKeysSendTerminalBytesAndCtrlAppliesOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "隔离按键验证", address = "127.0.0.1", port = 22228,
            username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        val prompts = launch {
            session.pending.collect { request ->
                request?.answer?.complete(if (request.prompt is Prompt.VerifyHost) listOf("trust") else listOf("test-only"))
            }
        }
        suspend fun outputContains(marker: String) {
            val found = withTimeoutOrNull(10_000) {
                while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText.contains(marker) }) delay(40)
                true
            }
            if (found != true) {
                val transcript = withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText.takeLast(500) }
                val focus = shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus") }
                error("Missing $marker; transcript=$transcript; focus=$focus")
            }
        }
        // Keys come from the pet's shortcut ring (a plain shell ring: Tab, Ctrl, Shift, ...).
        fun shortcut(tag: String) = compose.dropOnPetShortcut(tag)
        try {
            assertEquals(Phase.CONNECTED,
                withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            outputContains("KEY_ECHO_READY")
            compose.openSession(session.id)
            shortcut("pet-tab")
            outputContains("KEY_09")
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "extra-keys-compose.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "extra-keys-phone.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }

            shortcut("pet-ctrl")
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "extra-keys-ctrl-armed.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            if (emulator) shell("input text c") else commitText("c")
            outputContains("KEY_03")
            if (emulator) shell("input text d") else commitText("d")
            outputContains("KEY_64")

            shortcut("pet-ctrl")
            commitText("e")
            commitText("f")
            outputContains("KEY_05")
            outputContains("KEY_66")

            if (imeVisible()) {
                shell("input keyevent 4")
                compose.waitUntil(8_000) { !imeVisible() }
            }

            withContext(Dispatchers.Main) { session.write("\u001e") }
            outputContains("HISTORY_180")
            val historyView = requireNotNull(terminal(compose.activity.window.decorView))
            val density = context.resources.displayMetrics.density
            compose.onNodeWithTag("terminal").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width / 2f, 24 * density))
                moveTo(androidx.compose.ui.geometry.Offset(width / 2f, height - 24 * density), delayMillis = 400)
                up()
            }
            compose.waitForIdle()
            assertTrue("Swiping should reach local SSH history", historyView.topRow < -20)
            val readingRow = historyView.topRow
            withContext(Dispatchers.Main) { session.write("x") }
            outputContains("KEY_78")
            assertTrue("New output must not jump out of history", historyView.topRow <= readingRow)
            withContext(Dispatchers.Main) { historyView.scrollToBottom() }
            assertEquals(0, historyView.topRow)
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithTag("restore-dock").performClick()
            compose.onNodeWithTag("collapse-dock").assertIsDisplayed()

            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10_000) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("session-switcher").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("session-switcher").assertIsDisplayed()
            compose.onNodeWithContentDescription(tr("终端设置")).assertIsDisplayed()
            compose.onNodeWithTag("download-file").assertIsDisplayed()
            compose.onNodeWithTag("download-file").performClick()
            compose.onNodeWithTag("download-remote-path").assertDoesNotExist()
            compose.onNodeWithTag("download-confirm").assertIsNotEnabled()
            // Hidden files are disabled by default; wait for a visible fixture directory.
            compose.waitForDownloadEntry("phone-a", 8_000)
            compose.onNodeWithTag("remote-use-directory").assertDoesNotExist()
            compose.waitForIdle()
            delay(250)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "remote-path-menu.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithTag("download-files-list").performScrollToNode(hasTestTag("download-entry-phone-a"))
            compose.onNodeWithTag("download-entry-phone-a").performClick()
            compose.waitForDownloadEntry("assets", 8_000)
            assertTrue("Browsing a directory does not select it as a download", session.transfers.selectedDownloads.isEmpty())
            compose.onNodeWithTag("download-files-list").performScrollToNode(hasTestTag("download-entry-README.md"))
            compose.onNodeWithTag("download-entry-README.md").performClick()
            assertEquals("/phone-a/README.md", session.transfers.selectedDownloads.single().path)
            compose.onNodeWithTag("download-confirm").assertIsEnabled()
            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onNodeWithTag("terminal-menu").assertIsDisplayed()
            compose.waitForIdle()
            delay(500)
            val landscapeTags = listOf("terminal-workspace", "terminal-dock", "session-switcher",
                "upload-file", "download-file", "terminal-menu")
            File(context.getExternalFilesDir(null), "extra-keys-landscape-bounds.txt").writeText(
                landscapeTags.joinToString("\n") { tag ->
                    "$tag: ${compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot}"
                })
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "extra-keys-landscape.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.activityRule.scenario.onActivity { activity ->
                val root = activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "extra-keys-landscape-view.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "extra-keys-landscape-display.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }

            compose.tapCursorLine()
            if (compose.onAllNodesWithTag("restore-dock").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("restore-dock").performClick()
            compose.waitUntil(8_000) { imeVisible() }
            compose.waitUntil(8_000) {
                compose.onNodeWithTag("terminal-dock").fetchSemanticsNode().boundsInRoot.width <=
                    52 * compose.activity.resources.displayMetrics.density
            }
            compose.onNodeWithTag("session-switcher").performScrollTo().assertIsDisplayed()
            val commandsButton = compose.onNodeWithTag("common-commands")
            if (!commandsButton.isDisplayed()) commandsButton.performScrollTo()
            commandsButton.performClick()
            compose.onNodeWithTag("common-commands-list").performScrollToNode(hasTestTag("common-command-git status"))
            withContext(Dispatchers.Main) { session.write("Z") }
            outputContains("KEY_5A") // Flush earlier keystrokes before checking the exact inserted bytes.
            fun receivedBytes() = Regex("KEY_([0-9A-F]{2})").findAll(session.getEmulator().screen.transcriptText)
                .map { it.groupValues[1].toInt(16) }.toList()
            val beforeSelection = withContext(Dispatchers.Main) { receivedBytes().size }
            val savedDraft = "unsent draft"
            withContext(Dispatchers.Main) { session.draft = savedDraft }
            compose.onNodeWithTag("common-command-git status").performClick()
            compose.onNodeWithTag("prompt-editor").assertDoesNotExist()
            compose.onNodeWithTag("common-commands-list").assertDoesNotExist()
            withTimeout(8_000) {
                while (withContext(Dispatchers.Main) { receivedBytes().size } < beforeSelection + "git status\r".length) delay(40)
            }
            delay(250) // Include any accidental duplicate Enter in the comparison.
            assertEquals("History executes immediately with exactly one Enter", "git status\r".map { it.code },
                withContext(Dispatchers.Main) { receivedBytes().drop(beforeSelection) })
            assertEquals("History selection preserves the unsent draft", savedDraft, session.draft)
            assertEquals("git status", (session.commonCommands.value as io.itermux.app.session.CommonCommandsState.Ready).commands.first())
            withContext(Dispatchers.Main) { session.refreshCommonCommands() }
            withTimeout(8_000) { session.commonCommands.first { it is io.itermux.app.session.CommonCommandsState.Ready } }
            assertEquals("Refresh preserves the recently executed command", "git status",
                (session.commonCommands.value as io.itermux.app.session.CommonCommandsState.Ready).commands.first())
            Unit
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            withContext(Dispatchers.Main) { service.close(session.id) }
            prompts.cancel()
        }
    }
}
