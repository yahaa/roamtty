package io.itermux.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tapping a host asks where the terminal starts: home by default, a typed path, or a recent folder. */
@RunWith(AndroidJUnit4::class)
class StartDirectorySheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = Host(id = "start-dir-test", name = "build-box", address = "10.0.0.8", username = "dev")

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(colors: ColorScheme, recent: String?, onOpen: (String) -> Unit) {
        context.getSharedPreferences("app-settings", 0).edit().putString("startDirectories:${host.id}", recent).commit()
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { StartDirectorySheet(host, onDismiss = {}, onOpen = onOpen) }
            }
        }
    }

    @Test fun defaultOpensHome() {
        var opened: String? = null
        show(LightColors, null) { opened = it }
        compose.onNodeWithTag("start-directory-input").assert(hasText("~"))
        screenshot("start-dir-light-default")
        compose.onNodeWithTag("start-directory-open").performClick()
        compose.waitForIdle()
        assertEquals("~", opened)
    }

    @Test fun typedPathOpensAndIsRemembered() {
        var opened: String? = null
        show(DarkColors, "/srv/web\n~/work/roamtty") { opened = it }
        screenshot("start-dir-dark-recent")
        compose.onNodeWithTag("start-directory-input").performTextReplacement("/opt/app/")
        compose.onNodeWithTag("start-directory-open").performClick()
        compose.waitForIdle()
        assertEquals("/opt/app", opened)
        assertEquals("/opt/app\n/srv/web\n~/work/roamtty",
            context.getSharedPreferences("app-settings", 0).getString("startDirectories:${host.id}", null))
    }

    @Test fun recentFolderOpensInOneTap() {
        var opened: String? = null
        show(LightColors, "/srv/web\n~/work/roamtty") { opened = it }
        compose.onNodeWithTag("start-directory-recent-1").onChildren().onFirst().performClick()
        compose.waitForIdle()
        assertEquals("~/work/roamtty", opened)
    }
}
