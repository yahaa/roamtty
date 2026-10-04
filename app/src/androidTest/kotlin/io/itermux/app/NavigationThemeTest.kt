package io.itermux.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.terminal.TextStyle
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationThemeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun sessionListIconsAndLightTerminal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItermuxApp
        val prefs = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val previousTheme = prefs.getString("theme", null)
        val host = Host(name = "Windows 图标验证", address = "127.0.0.1", port = 1,
            username = "test", mode = "SHELL", os = "WINDOWS")
        val linuxHost = Host(name = "Linux 图标验证", address = "linux.example", username = "test", os = "LINUX")
        val macHost = Host(name = "macOS 图标验证", address = "mac.example", username = "test", os = "MACOS")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        compose.activityRule.scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        fun capture(name: String) {
            val path = File(context.getExternalFilesDir(null), "$name.png")
            path.outputStream().use { compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun captureDialog(name: String) {
            val path = File(context.getExternalFilesDir(null), "$name.png")
            path.outputStream().use { compose.onNodeWithTag("host-editor").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun captureWindow(name: String) {
            val path = File(context.getExternalFilesDir(null), "$name.png")
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            app.database.hosts().save(host)
            app.database.hosts().save(linuxHost)
            app.database.hosts().save(macHost)
            compose.waitUntil(8_000) { compose.onAllNodesWithText(host.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Windows").onFirst().assertExists()
            compose.onAllNodesWithContentDescription("Linux").onFirst().assertExists()
            compose.onAllNodesWithContentDescription("macOS").onFirst().assertExists()
            compose.onNodeWithTag("brand-logo").assertIsDisplayed()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("navigation-drawer").assertIsDisplayed()
            compose.onNodeWithTag("drawer-guide").assertIsDisplayed()
            compose.onNodeWithText("帮助").assertIsDisplayed()
            // Hosts show their count like sessions do.
            val hostCount = app.database.hosts().observe().first().size
            compose.onNodeWithTag("drawer-hosts").assert(hasText("$hostCount"))
            capture("navigation-drawer")
            compose.onNodeWithTag("drawer-sessions").performClick()
            compose.onNodeWithText("会话列表").assertIsDisplayed()
            compose.waitUntil(8_000) { !compose.onNodeWithTag("navigation-drawer").isDisplayed() }
            compose.onNodeWithTag("hosts-tab").performClick()
            compose.onNodeWithTag("drawer-guide").assertIsNotDisplayed()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-settings").performClick()
            compose.onNodeWithText("外观").assertIsDisplayed()
            compose.onNodeWithText("深色").performClick()
            compose.waitUntil(8_000) { prefs.getString("theme", null) == "DARK" }
            compose.onNodeWithTag("close-settings").assertIsDisplayed().performClick()
            compose.onNodeWithTag("host-grid").assertIsDisplayed()
            capture("navigation-dark-hosts")
            compose.onNodeWithTag("host-grid").performTouchInput { swipeUp() }
            capture("navigation-dark-os-icons")
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-guide").assertIsDisplayed()
            compose.waitForIdle()
            Thread.sleep(250)
            captureWindow("navigation-settings-drawer")
            compose.onNodeWithTag("drawer-guide").performClick()
            compose.onNodeWithText("iTermux ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithText("会话列表").assertIsDisplayed()
            compose.onNodeWithTag("terminal").assertDoesNotExist()
            compose.onAllNodesWithContentDescription("Windows").onFirst().assertExists()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-settings").performClick()
            compose.onNodeWithText("浅色").performClick()
            compose.waitUntil(8_000) { prefs.getString("theme", null) == "LIGHT" }
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-sessions").performClick()
            capture("navigation-light-sessions")
            compose.onNodeWithTag("hosts-tab").performClick()
            capture("navigation-light-hosts")
            compose.onNodeWithTag("host-grid").performTouchInput { swipeUp() }
            capture("navigation-light-os-icons")
            compose.onNodeWithTag("add-host").performClick()
            compose.onNodeWithTag("host-name").performTextInput("旋转未保存")
            compose.onNodeWithTag("host-address").performTextInput("rotation.example")
            compose.activityRule.scenario.onActivity { activity ->
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.currentFocus?.clearFocus()
            }
            captureDialog("navigation-portrait-editor")
            compose.onNodeWithTag("save-password").performScrollTo().performClick()
            compose.onNodeWithTag("host-password").performScrollTo().performTextInput("rotation-test-only")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText("添加主机").assertIsDisplayed()
            compose.onNodeWithTag("host-name").assertTextContains("旋转未保存")
            compose.onNodeWithTag("host-address").assertTextContains("rotation.example")
            compose.onNodeWithTag("host-password").assertTextContains("rotation-test-only")
            captureDialog("navigation-landscape-editor")
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(8_000) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
            capture("navigation-landscape-hosts")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            compose.waitUntil(8_000) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-settings").performClick()
            compose.onNodeWithText("外观").assertIsDisplayed()
            compose.waitForIdle()
            Thread.sleep(250)
            capture("navigation-light-settings")
            compose.onNodeWithText("浅色").performClick()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-sessions").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.onNodeWithTag("terminal").assertIsDisplayed()
            capture("navigation-light-terminal")
            assertEquals(0xFFF3F0EB.toInt(), session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            assertEquals(true, WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("会话列表").assertIsDisplayed()
            Unit
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            withContext(Dispatchers.Main) { service.close(session.id) }
            app.database.hosts().delete(host.id)
            app.database.hosts().delete(linuxHost.id)
            app.database.hosts().delete(macHost.id)
            prefs.edit().apply { previousTheme?.let { putString("theme", it) } ?: remove("theme") }.commit()
            compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}
