package io.itermux.app

import android.app.Activity
import android.app.Instrumentation
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
import java.util.concurrent.atomic.AtomicReference

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
        // Preserve the launch identity so ActivityScenario can still track recreation.
        instrumentation.uiAutomation.executeShellCommand("am start -W --activity-single-top -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${BuildConfig.APPLICATION_ID}/io.itermux.app.MainActivity").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        compose.onNodeWithTag("keyboard-toggle").assertDoesNotExist()
        compose.onNodeWithContentDescription(tr("终端设置")).assertExists()
        val dock = compose.onNodeWithTag("terminal-dock").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("terminal-dock-viewport").fetchSemanticsNode().boundsInRoot
        val margin = 4 * compose.activity.resources.displayMetrics.density
        val expectedTop = (viewport.top + viewport.height * 0.382f - dock.height / 2)
            .coerceIn(viewport.top + margin, (viewport.bottom - margin - dock.height).coerceAtLeast(viewport.top + margin))
        assertEquals("Dock follows the upper golden section and stays on screen", expectedTop, dock.top, 2f)
        assertTrue(dock.bottom <= viewport.bottom && dock.top >= viewport.top)
    }

    private fun checkCollapsedDock() {
        compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
        listOf("upload-file", "download-file", "select-image").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        // Native IME animation can resize the window between two semantics reads.
        // Wait for the button and viewport to report the same layout frame.
        compose.waitUntil(8000) {
            val button = compose.onNodeWithTag("restore-dock").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("terminal-dock-viewport").fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(viewport.top + viewport.height * 0.382f - button.center.y) <= 2f
        }
        val more = compose.onNodeWithTag("restore-dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("Collapsed controls occupy one button", 48 * density, more.width, 1f)
        val viewport = compose.onNodeWithTag("terminal-dock-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals("Collapsed controls hug the screen edge", viewport.right - 8 * density, more.right, 2f)
    }

    private fun checkFileActions() {
        compose.onNodeWithTag("restore-dock").assertDoesNotExist()
        val density = compose.activity.resources.displayMetrics.density
        listOf("upload-file", "download-file", "select-image").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertTrue("File actions have a full touch target", bounds.width >= 48 * density - 1f && bounds.height >= 48 * density - 1f)
        }
    }

    private fun checkOutsideDismiss() {
        compose.onNodeWithTag("restore-dock").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("terminal-dock").assertIsDisplayed()
        compose.onNodeWithTag("terminal-menu").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText("固定操作栏").assertIsDisplayed().performClick()
        compose.tapCursorLine()
        compose.onNodeWithTag("terminal-dock").assertIsDisplayed()
        compose.onNodeWithTag("terminal-menu").performClick()
        compose.onNodeWithText("取消固定操作栏").performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("terminal-dock").assertIsDisplayed()
        compose.tapCursorLine()
        checkCollapsedDock()
        // Close the keyboard opened by the same outside tap, proving the tap was not swallowed.
        compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        shell("input keyevent 4")
        compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        compose.onNodeWithTag("restore-dock").performClick()
        compose.onNodeWithTag("terminal-menu").performClick()
        compose.onNodeWithText("固定操作栏").assertIsDisplayed()
        // A popup owns a separate Android window; exercise an actual outside screen tap.
        shell("input tap 16 200")
        compose.onNodeWithText("固定操作栏").assertDoesNotExist()
        checkCollapsedDock()
    }

    private fun restoreDockAfterOutsideTap() {
        if (compose.onAllNodesWithTag("restore-dock").fetchSemanticsNodes().isNotEmpty()) {
            checkCollapsedDock()
            compose.onNodeWithTag("restore-dock").performTouchInput { click() }
        }
    }

    private fun checkPicker(tag: String, expectedAction: String, expectedType: String) {
        val launched = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                launched.set(intent)
                // Cancel the system picker so the regression test stays within the app.
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag(tag).performTouchInput { click() }
            compose.waitUntil(8000) { launched.get() != null }
            assertEquals(expectedAction, launched.get().action)
            assertEquals(expectedType, launched.get().type)
            compose.waitForIdle()
            checkFileActions()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private suspend fun checkKeyboardLayout(name: String) {
        checkDock()
        val before = compose.onNodeWithTag("terminal-dock-viewport").fetchSemanticsNode().boundsInRoot
        compose.tapCursorLine()
        compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        restoreDockAfterOutsideTap()
        delay(500)
        checkDock()
        val opened = compose.onNodeWithTag("terminal-dock-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Keyboard reduces the available dock viewport", opened.height < before.height)
        capture("$name-keyboard")
        shell("input keyevent 4")
        compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        delay(500)
        compose.onNodeWithTag("collapse-dock").performScrollTo()
        checkDock()
    }

    private fun checkViewport(name: String, reservedWidthDp: Int = 0) {
        // Portrait keeps a transparent status bar over the terminal; landscape hides every system bar.
        val portrait = compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(8000) {
            val decor = compose.activity.window.decorView
            ViewCompat.getRootWindowInsets(decor)?.let {
                // Before API 30, visibility is inferred from inset sizes and can report stale bars.
                val barsExpected = if (Build.VERSION.SDK_INT >= 30) {
                    it.isVisible(WindowInsetsCompat.Type.statusBars()) == portrait && !it.isVisible(WindowInsetsCompat.Type.navigationBars())
                } else {
                    @Suppress("DEPRECATION")
                    val fullscreen = (decor.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
                    @Suppress("DEPRECATION")
                    val navigationHidden = (decor.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0
                    fullscreen != portrait && navigationHidden
                }
                barsExpected && !it.isVisible(WindowInsetsCompat.Type.ime())
            } == true
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        // The text view keeps a small inner padding; bands are measured on the viewport that hosts it.
        val terminal = compose.onNodeWithTag("terminal-viewport").fetchSemanticsNode().boundsInRoot
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            .getInsets(WindowInsetsCompat.Type.captionBar())
        val widthRatio = terminal.width / (root.width - insets.left - insets.right)
        val heightRatio = terminal.height / (root.height - insets.top - insets.bottom)
        measurements.appendLine("$name: root=${root.width}x${root.height}, terminal=${terminal.width}x${terminal.height}, width=$widthRatio, height=$heightRatio")
        assertEquals("Neither a camera cutout nor the status bar may leave a band above the terminal", insets.top.toFloat(), terminal.top, 2f)
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
            compose.openSession(session.id)
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            withContext(Dispatchers.Main) {
                session.write("printf '\\033[2J\\033[H'; for i in $(seq 1 50); do printf '\\033[32m[%02d]\\033[0m iTermux / workspace / build completed\\n' \"${'$'}i\"; done\r")
            }
            delay(800)
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            checkViewport("layout-portrait-default-hidden")
            checkCollapsedDock()
            checkOutsideDismiss()
            compose.onNodeWithTag("restore-dock").performClick()
            checkFileActions()
            compose.tapCursorLine()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            restoreDockAfterOutsideTap()
            checkFileActions()
            checkPicker("upload-file", Intent.ACTION_OPEN_DOCUMENT, "*/*")
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            if (Build.VERSION.SDK_INT >= 33) checkPicker("select-image", "android.provider.action.PICK_IMAGES", "image/*")
            compose.onNodeWithTag("download-file").performTouchInput { click() }
            // The OpenSSH fixture's home, where the sheet opens, has ~/projects.
            compose.waitForDownloadEntry("projects", 8000)
            compose.onNodeWithTag("download-confirm").assertIsNotEnabled()
            // The closed navigation drawer also composes a "关闭" button off screen.
            compose.onNode(hasContentDescription("关闭") and !hasAnyAncestor(hasTestTag("navigation-drawer"))).performClick()
            checkFileActions()
            checkDock()
            compose.onNodeWithTag("collapse-dock").performClick()
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithTag("restore-dock").performClick()
            checkDock()
            checkViewport("layout-portrait-floating")
            val floating = compose.onNodeWithTag("terminal-dock").fetchSemanticsNode().boundsInRoot
            assertTrue("Floating controls must not span the screen", floating.width < compose.onRoot().fetchSemanticsNode().boundsInRoot.width * 0.7f)
            compose.tapCursorLine()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            restoreDockAfterOutsideTap()
            checkDock()
            shell("input keyevent 4")
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            checkKeyboardLayout("layout-portrait-floating")
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("快捷键", substring = true).assertDoesNotExist()
            compose.onNodeWithText("固定操作栏").performClick()
            checkViewport("layout-portrait-pinned", reservedWidthDp = 48)
            checkKeyboardLayout("layout-portrait-pinned")
            compose.activityRule.scenario.recreate()
            foreground()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal-dock").fetchSemanticsNodes().isNotEmpty() }
            checkViewport("layout-portrait-pinned-recreated", reservedWidthDp = 48)
            val portraitColumns = withContext(Dispatchers.Main) { session.getEmulator().mColumns }
            rotate(true)
            checkViewport("layout-landscape-pinned", reservedWidthDp = 96)
            assertTrue(withContext(Dispatchers.Main) { session.getEmulator().mColumns } > portraitColumns)
            checkKeyboardLayout("layout-landscape-pinned")
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("取消固定操作栏").performClick()
            checkViewport("layout-landscape-floating")
            checkKeyboardLayout("layout-landscape-floating")

            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").performTouchInput { click() }
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
            checkCollapsedDock()
            rotate(false)
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            checkViewport("layout-portrait-full")
            compose.onNodeWithTag("restore-dock").performClick()
            checkViewport("layout-portrait-restored")
            checkDock()
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").performTouchInput { click() }
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
            // The host is never saved, so leaving for its detail page falls back to the host list.
            compose.onNodeWithText("返回主机详情").performClick()
            // Landscape pages keep the system bars hidden, so wait for the home page itself, then show its hosts.
            compose.waitUntil(8000) { compose.onAllNodesWithTag("home-tab-hosts").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-tab-hosts").performClick()
            previewHosts.forEach { app.database.hosts().save(it) }
            rotate(true)
            previewHosts.forEach { compose.onNodeWithText(it.name).assertIsDisplayed() }
            capture("layout-hosts-landscape")
            File(context.getExternalFilesDir(null), "layout-measurements.txt").writeText(measurements.toString())
        } catch (failure: Throwable) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "layout-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            // Popups add their own roots; dumping all of them keeps the original failure visible.
            File(context.getExternalFilesDir(null), "layout-failure.txt").writeText(compose.onAllNodes(isRoot()).printToString())
            throw failure
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
            previewHosts.forEach { app.database.hosts().delete(it.id) }
            prompts.cancel()
        }
    }
}
