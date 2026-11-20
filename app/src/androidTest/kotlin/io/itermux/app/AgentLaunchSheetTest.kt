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
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.AgentMode
import io.itermux.app.session.CodingAgent
import io.itermux.app.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The "open with AI agent" sheet: choosing an agent and permission level yields the command to run. */
@RunWith(AndroidJUnit4::class)
class AgentLaunchSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun launch(colors: ColorScheme, name: String): AgentLaunch? {
        context.getSharedPreferences("app-settings", 0).edit().remove("agentLaunchAgent").remove("agentLaunchMode").remove("agentLaunchYolo").commit()
        var launched: AgentLaunch? = null
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    AgentLaunchSheet("/home/dev/projects/roamtty", onDismiss = {}) { launched = it }
                }
            }
        }
        // Nothing saved yet: Codex in YOLO.
        compose.onNodeWithTag("agent-launch-command").onChildren()
            .assertAny(hasText("codex --dangerously-bypass-approvals-and-sandbox"))
        screenshot("$name-yolo")
        compose.onNodeWithTag("agent-option-claude").performClick()
        compose.onNodeWithTag("agent-permission-standard").performClick()
        compose.onNodeWithTag("agent-launch-command").onChildren().assertAny(hasText("claude --permission-mode default"))
        screenshot("$name-standard")
        compose.onNodeWithTag("agent-option-agy").performClick()
        compose.onNodeWithTag("agent-permission-read-only").performClick()
        compose.onNodeWithTag("agent-launch-command").onChildren().assertAny(hasText("agy --mode plan"))
        compose.onNodeWithTag("agent-permission-yolo").performClick()
        compose.onNodeWithTag("agent-launch-command").onChildren().assertAny(hasText("agy --dangerously-skip-permissions"))
        screenshot("$name-agy")
        compose.onNodeWithTag("agent-option-codex").performClick()
        compose.onNodeWithTag("agent-permission-yolo").performClick()
        compose.onNodeWithTag("agent-launch").performClick()
        compose.waitForIdle()
        return launched
    }

    @Test fun darkLaunchesCodexInYolo() {
        assertEquals(AgentLaunch(CodingAgent.CODEX, "/home/dev/projects/roamtty", AgentMode.YOLO), launch(DarkColors, "agent-sheet-dark"))
    }

    @Test fun lightRemembersChoice() {
        launch(LightColors, "agent-sheet-light")
        val prefs = context.getSharedPreferences("app-settings", 0)
        assertEquals("CODEX", prefs.getString("agentLaunchAgent", null))
        assertEquals("YOLO", prefs.getString("agentLaunchMode", null))
    }
}
