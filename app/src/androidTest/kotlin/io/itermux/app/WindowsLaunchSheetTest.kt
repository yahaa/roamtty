package io.itermux.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.LaunchSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real SSH + PowerShell fixture: no POSIX shell, native Windows paths, recoverable listing failures. */
@RunWith(AndroidJUnit4::class)
class WindowsLaunchSheetTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var launched: String? = null

    private fun open(username: String) = runBlocking {
        assumeTrue("Start scripts/windows-launch-fixture.py and reverse port 22248",
            InstrumentationRegistry.getArguments().getString("windowsLaunchFixture") == "true")
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val id = "windows-launch-$username"
        app.database.hosts().forgetPin("[127.0.0.1]:22248")
        app.getSharedPreferences("app-settings", 0).edit().remove("startDirectories:$id").commit()
        val host = Host(id = id, name = "Windows fixture", address = "127.0.0.1", port = 22248,
            username = username, mode = "SHELL", os = "WINDOWS",
            encryptedPassword = app.vault.encrypt("fixture-only".toByteArray(), "$id:password"))
        compose.setContent { MaterialTheme(colorScheme = DarkColors) {
            LaunchSheet(host, false, null, onDismiss = {}) { directory, _ -> launched = directory }
        } }
    }

    private fun awaitText(text: String) = compose.waitUntil(25_000) {
        if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("信任并连接").performClick()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun windowsFoldersNavigateAndLaunchTheResolvedDirectory() {
        open("normal")
        awaitText("My projects")
        compose.onNodeWithTag("launch-folder-My projects").performClick()
        awaitText("中文 [work]")
        compose.onNodeWithTag("launch-crumb-1").assertTextEquals("My projects")
        compose.onNodeWithTag("launch-open").assertIsEnabled()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "windows-launch-folders.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("launch-open").performClick()
        assertEquals("C:/Users/测试/My projects", launched)
    }

    @Test fun failedHomeListingCanRetryWithoutChangingDirectory() {
        open("retry")
        awaitText("无法打开这个文件夹")
        compose.onNodeWithTag("launch-open").assertIsEnabled()
        compose.onNodeWithText("重试").performClick()
        awaitText("My projects")
        compose.onNodeWithTag("launch-folder-Documents").assertExists()
    }

    @Test fun failedFolderListingDoesNotBlockOpeningHome() {
        open("failed")
        awaitText("无法打开这个文件夹")
        compose.onNodeWithTag("launch-open").assertIsEnabled().performClick()
        assertEquals("~", launched)
    }
}
