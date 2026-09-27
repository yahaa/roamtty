package io.itermux.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val measurements = StringBuilder()
    private var previousImeSetting: String? = null
    private var previousImmersiveHint: String? = null
    private var previousDockPinned: Boolean? = null

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
    }

    @Before fun enableEmulatorKeyboard() {
        assumeTrue("Layout fixture runs only on emulators", Build.HARDWARE in listOf("ranchu", "goldfish"))
        previousImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
        previousImmersiveHint = shell("settings get secure immersive_mode_confirmations")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        // Keep Android's first-use education overlay from taking focus during keyboard assertions.
        shell("settings put secure immersive_mode_confirmations confirmed")
        val prefs = instrumentation.targetContext.getSharedPreferences("terminal-settings", android.content.Context.MODE_PRIVATE)
        previousDockPinned = if (prefs.contains("dockPinned")) prefs.getBoolean("dockPinned", false) else null
        prefs.edit().remove("dockPinned").commit()
    }

    @After fun restoreEmulatorKeyboard() {
        previousImeSetting?.let {
            shell(if (it == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $it")
        }
        previousImmersiveHint?.let {
            shell(if (it.isBlank() || it == "null") "settings delete secure immersive_mode_confirmations" else "settings put secure immersive_mode_confirmations $it")
        }
        if (Build.HARDWARE in listOf("ranchu", "goldfish")) {
            instrumentation.targetContext.getSharedPreferences("terminal-settings", android.content.Context.MODE_PRIVATE).edit().apply {
                previousDockPinned?.let { putBoolean("dockPinned", it) } ?: remove("dockPinned")
            }.commit()
        }
    }

    private fun foreground() {
        instrumentation.uiAutomation.executeShellCommand("am start -W --activity-single-top -n io.itermux.app/.MainActivity").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Test fixture output only. Production continues to prohibit secure screenshots.
            it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        compose.waitForIdle()
    }

    private suspend fun rotate(landscape: Boolean) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        foreground()
        compose.waitUntil(10_000) {
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            (bounds.width > bounds.height) == landscape &&
                compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        // AndroidView/remote PTY resizing follows the Compose layout frame.
        delay(500)
    }

    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name-display.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun checkDock() {
        compose.onNodeWithTag("terminal-dock").assertExists()
        compose.onNodeWithTag("restore-dock").assertDoesNotExist()
        compose.onNodeWithTag("terminal-keys").assertDoesNotExist()
        val session = compose.onNodeWithTag("session-switcher").fetchSemanticsNode().boundsInRoot
        val keyboard = compose.onNodeWithTag("keyboard-toggle").fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithTag("terminal-menu").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("terminal-dock").fetchSemanticsNode().boundsInRoot
        assertTrue("Operation rail must occupy visible space", dock.width > 0f && dock.height > 0f)
        if (dock.width > 80 * compose.activity.resources.displayMetrics.density) {
            assertTrue("Wide rail keeps the session above primary controls", session.bottom <= keyboard.top + 1f)
            val transfer = compose.onNodeWithTag("file-transfer").fetchSemanticsNode().boundsInRoot
            assertEquals("Wide rail pairs keyboard and file transfer", keyboard.center.y, transfer.center.y, 2f)
            assertTrue("Keyboard precedes file transfer in the wide rail", keyboard.right <= transfer.left + 1f)
        } else {
            assertEquals("Session and keyboard must align vertically", session.center.x, keyboard.center.x, 2f)
            assertTrue("Keyboard follows the session", session.bottom <= keyboard.top + 1f)
            // The short rail scrolls while the IME is open; the menu may be outside the viewport.
            if (menu.width > 0f && menu.height > 0f && menu.top >= dock.top && menu.bottom <= dock.bottom + 1f) {
                assertEquals("Keyboard and menu must align vertically", keyboard.center.x, menu.center.x, 2f)
                assertTrue("Menu follows the keyboard", keyboard.bottom <= menu.top + 1f)
            }
        }
    }

    private suspend fun checkKeyboardToggle(name: String) {
        checkDock()
        val button = compose.onNodeWithTag("keyboard-toggle").fetchSemanticsNode().boundsInRoot
        val dockWidth = compose.onNodeWithTag("terminal-dock").fetchSemanticsNode().boundsInRoot.width
        repeat(3) { attempt ->
            // Use real touch events to cover native terminal / Compose focus handoff.
            compose.onNodeWithTag("keyboard-toggle").performTouchInput { click() }
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            delay(500)
            compose.onNodeWithContentDescription("收起键盘").assertIsDisplayed()
            checkDock()
            val opened = compose.onNodeWithTag("keyboard-toggle").fetchSemanticsNode().boundsInRoot
            val openedDockWidth = compose.onNodeWithTag("terminal-dock").fetchSemanticsNode().boundsInRoot.width
            if (kotlin.math.abs(dockWidth - openedDockWidth) < 2f) {
                assertEquals("Keyboard button must not move horizontally", button.center.x, opened.center.x, 2f)
                assertEquals("Keyboard button must not move vertically", button.center.y, opened.center.y, 2f)
            }
            val workspace = compose.onNodeWithTag("terminal-workspace").fetchSemanticsNode().boundsInRoot
            val terminal = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
            assertTrue("Keyboard must leave the terminal visible", terminal.height > workspace.height * 0.99f)
            if (attempt == 0) capture("$name-keyboard")
            if (attempt == 1) shell("input keyevent 4")
            else compose.onNodeWithTag("keyboard-toggle").performTouchInput { click() }
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            delay(500)
            compose.onNodeWithContentDescription("键盘", substring = false).assertIsDisplayed()
            compose.onNodeWithTag("terminal").performTouchInput { click(center) }
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            checkDock()
            compose.onNodeWithTag("keyboard-toggle").performTouchInput { click() }
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            compose.waitUntil(8000) {
                compose.onNodeWithTag("terminal-workspace").fetchSemanticsNode().boundsInRoot.height >
                    compose.onRoot().fetchSemanticsNode().boundsInRoot.height * 0.8f
            }
        }
    }

    private fun checkViewport(name: String, reservedWidthDp: Int = 0) {
        compose.waitUntil(8000) {
            val decor = compose.activity.window.decorView
            ViewCompat.getRootWindowInsets(decor)?.let {
                // Before API 30, visibility is inferred from inset sizes and can report stale bars.
                val barsHidden = if (Build.VERSION.SDK_INT >= 30) {
                    !it.isVisible(WindowInsetsCompat.Type.statusBars()) && !it.isVisible(WindowInsetsCompat.Type.navigationBars())
                } else {
                    @Suppress("DEPRECATION")
                    val hiddenFlags = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    @Suppress("DEPRECATION")
                    (decor.systemUiVisibility and hiddenFlags) == hiddenFlags
                }
                barsHidden && !it.isVisible(WindowInsetsCompat.Type.ime())
            } == true
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val terminal = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            .getInsets(WindowInsetsCompat.Type.captionBar())
        val widthRatio = terminal.width / (root.width - insets.left - insets.right)
        val heightRatio = terminal.height / (root.height - insets.top - insets.bottom)
        measurements.appendLine("$name: root=${root.width}x${root.height}, terminal=${terminal.width}x${terminal.height}, width=$widthRatio, height=$heightRatio")
        assertEquals("A camera cutout must not leave a blank band above the terminal", insets.top.toFloat(), terminal.top, 2f)
        assertEquals("A camera cutout must not leave a blank band beside the terminal", insets.left.toFloat(), terminal.left, 2f)
        val dockWidth = reservedWidthDp * compose.activity.resources.displayMetrics.density
        assertEquals("Only a pinned side rail may reserve terminal width", root.width - insets.left - insets.right - dockWidth, terminal.width, 2f)
        assertTrue("Terminal height: $heightRatio", heightRatio > 0.99f)
        compose.activityRule.scenario.onActivity {
            assertEquals("Compose must use full window height", it.window.decorView.height.toFloat(), root.height, 2f)
        }
        capture(name)
    }

    @Test fun fullscreenRotationKeyboardAndDockRecovery() = runBlocking {
        assumeTrue("Layout fixture runs only on emulators", Build.HARDWARE in listOf("ranchu", "goldfish"))
        foreground()
        rotate(false)
        val context = instrumentation.targetContext
        val app = context.applicationContext as ItermuxApp
        val previewHosts = listOf(
            Host(name = "开发工作站", address = "dev.example.com", username = "developer"),
            Host(name = "构建服务器", address = "build.example.com", username = "builder")
        )
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val password = runCatching {
            instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        }.getOrDefault("test-only")
        val host = Host(name = "布局验证工作站", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            withContext(Dispatchers.Main) {
                session.write("printf '\\033[2J\\033[H'; for i in $(seq 1 50); do printf '\\033[32m[%02d]\\033[0m iTermux / workspace / build completed\\n' \"${'$'}i\"; done\r")
            }
            delay(800)
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            checkViewport("layout-portrait-default-hidden")
            compose.onNodeWithTag("restore-dock").performClick()
            checkDock()
            compose.onNodeWithTag("collapse-dock").performClick()
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithTag("restore-dock").performClick()
            checkDock()
            checkViewport("layout-portrait-floating")
            val floating = compose.onNodeWithTag("terminal-dock").fetchSemanticsNode().boundsInRoot
            assertTrue("Floating controls must not span the screen", floating.width < compose.onRoot().fetchSemanticsNode().boundsInRoot.width * 0.7f)
            compose.onNodeWithTag("terminal").performTouchInput { click(center) }
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            checkDock()
            compose.onNodeWithTag("keyboard-toggle").performTouchInput { click() }
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            checkKeyboardToggle("layout-portrait-floating")
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("快捷键", substring = true).assertDoesNotExist()
            compose.onNodeWithText("固定操作栏").performClick()
            checkViewport("layout-portrait-pinned", reservedWidthDp = 48)
            checkKeyboardToggle("layout-portrait-pinned")
            compose.activityRule.scenario.recreate()
            foreground()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal-dock").fetchSemanticsNodes().isNotEmpty() }
            checkViewport("layout-portrait-pinned-recreated", reservedWidthDp = 48)
            val portraitColumns = withContext(Dispatchers.Main) { session.getEmulator().mColumns }
            rotate(true)
            checkViewport("layout-landscape-pinned", reservedWidthDp = 96)
            assertTrue(withContext(Dispatchers.Main) { session.getEmulator().mColumns } > portraitColumns)
            checkKeyboardToggle("layout-landscape-pinned")
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("取消固定操作栏").performClick()
            checkViewport("layout-landscape-floating")
            checkKeyboardToggle("layout-landscape-floating")

            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").performClick()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.onNodeWithTag("prompt-editor").performTextInput("检查横屏布局\n保留终端空间")
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithText("发送文本").assertIsDisplayed()
            val editor = compose.onNodeWithTag("prompt-editor").fetchSemanticsNode().boundsInRoot
            val editingTerminal = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
            assertTrue("Editor must not cover terminal", editor.top >= editingTerminal.bottom - 1f)
            assertTrue("Editor must leave several terminal rows", editingTerminal.height > 40 * compose.activity.resources.displayMetrics.density)
            capture("layout-landscape-editor")
            assertFalse(withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains("检查横屏布局"))
            compose.onNodeWithText("收起", substring = false).performClick()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }

            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("隐藏操作栏").performClick()
            checkViewport("layout-landscape-full")
            rotate(false)
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            checkViewport("layout-portrait-full")
            compose.onNodeWithTag("restore-dock").performClick()
            checkViewport("layout-portrait-restored")
            checkDock()
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").performClick()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.onNodeWithTag("prompt-editor").performTextInput("竖屏草稿")
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithTag("terminal-keys").assertDoesNotExist()
            compose.onNodeWithText("发送文本").assertIsDisplayed()
            capture("layout-portrait-editor")
            compose.onNodeWithText("收起", substring = false).performClick()
            checkViewport("layout-portrait-editor-closed")
            checkDock()
            rotate(true)
            checkViewport("layout-landscape-floating")
            checkDock()
            assertEquals(Phase.CONNECTED, session.state.value.phase)
            compose.onNodeWithTag("session-switcher").performClick()
            compose.onNodeWithText("返回主机列表").performClick()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
            previewHosts.forEach { app.database.hosts().save(it) }
            rotate(true)
            previewHosts.forEach { compose.onNodeWithText(it.name).assertIsDisplayed() }
            capture("layout-hosts-landscape")
            File(context.getExternalFilesDir(null), "layout-measurements.txt").writeText(measurements.toString())
        } catch (failure: Throwable) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "layout-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            File(context.getExternalFilesDir(null), "layout-failure.txt").writeText(compose.onRoot().printToString())
            throw failure
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
            previewHosts.forEach { app.database.hosts().delete(it.id) }
            prompts.cancel()
        }
    }
}
