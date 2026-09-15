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
    }

    @After fun restoreEmulatorKeyboard() {
        previousImeSetting?.let {
            shell(if (it == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $it")
        }
        previousImmersiveHint?.let {
            shell(if (it.isBlank() || it == "null") "settings delete secure immersive_mode_confirmations" else "settings put secure immersive_mode_confirmations $it")
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

    private fun checkViewport(name: String, landscape: Boolean, hidden: Boolean = false) {
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
        val widthRatio = terminal.width / root.width
        val heightRatio = terminal.height / root.height
        measurements.appendLine("$name: root=${root.width}x${root.height}, terminal=${terminal.width}x${terminal.height}, width=$widthRatio, height=$heightRatio")
        assertTrue("No blank band above terminal: $terminal", terminal.top < 2f)
        assertTrue("No blank band beside terminal: $terminal", terminal.left < 2f)
        assertTrue("Terminal width: $widthRatio", widthRatio > if (hidden || !landscape) 0.99f else 0.90f)
        assertTrue("Terminal height: $heightRatio", heightRatio > if (hidden || landscape) 0.99f else 0.90f)
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
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        val host = Host(name = "布局验证工作站", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.onNodeWithText("会话", substring = true).performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            withContext(Dispatchers.Main) {
                session.write("printf '\\033[2J\\033[H'; for i in $(seq 1 50); do printf '\\033[32m[%02d]\\033[0m iTermux / workspace / build completed\\n' \"${'$'}i\"; done\r")
            }
            delay(800)
            checkViewport("layout-portrait", false)
            val portraitColumns = withContext(Dispatchers.Main) { session.getEmulator().mColumns }
            rotate(true)
            checkViewport("layout-landscape", true)
            assertTrue(withContext(Dispatchers.Main) { session.getEmulator().mColumns } > portraitColumns)
            val wideRows = withContext(Dispatchers.Main) { session.getEmulator().mRows }

            compose.onNodeWithContentDescription("键盘", substring = false).performClick()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.waitUntil(8000) { session.getEmulator().mRows < wideRows }
            val workspace = compose.onNodeWithTag("terminal-workspace").fetchSemanticsNode().boundsInRoot
            val terminal = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
            assertTrue("Keyboard must leave terminal visible", terminal.height > workspace.height * 0.5f)
            compose.onNodeWithTag("terminal-menu").assertIsDisplayed()
            capture("layout-landscape-keyboard")
            compose.onNodeWithContentDescription("收起键盘").performClick()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }

            compose.onNodeWithContentDescription("语音").performClick()
            compose.waitUntil(8000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.onNodeWithTag("prompt-editor").performTextInput("检查横屏布局\n保留终端空间")
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
            checkViewport("layout-landscape-full", true, hidden = true)
            rotate(false)
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            checkViewport("layout-portrait-full", false, hidden = true)
            compose.onNodeWithTag("restore-dock").performClick()
            checkViewport("layout-portrait-restored", false)
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
