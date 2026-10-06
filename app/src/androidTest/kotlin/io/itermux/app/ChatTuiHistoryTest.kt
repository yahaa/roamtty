package io.itermux.app

import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Plain SSH running scripts/chat-tui-fixture.py (pass its remote path as instrumentation argument
 * chatFixture): a full-screen chat that streams 80 lines, repainting header, transcript and spinner.
 */
@RunWith(AndroidJUnit4::class)
class ChatTuiHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private suspend fun awaitScreen(session: TerminalConnection, text: String) = withTimeout(60_000) {
        while (!withContext(Dispatchers.Main) {
                session.emulator.let { it.getSelectedText(0, 0, it.columns, it.rows) }.contains(text) }) delay(100)
    }

    private fun run(mode: String) = runBlocking {
        val fixture = InstrumentationRegistry.getArguments().getString("chatFixture")
        assumeTrue("chatFixture argument required", fixture != null)
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val context = instrumentation.targetContext
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val password = runCatching {
            instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        }.getOrDefault("test-only")
        val host = Host(name = "Chat history QA", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            withContext(Dispatchers.Main) { session.write("exec python3 '$fixture' $mode\r") }
            awaitScreen(session, "Done")
            capture("chat-tui-$mode-live")
            compose.onNodeWithTag("restore-dock").performClick()
            compose.onNodeWithTag("tmux-history").performClick()
            withTimeout(8000) { while (session.tmuxHistory.value?.source != HistorySource.OUTPUT) delay(50) }
            compose.onNodeWithTag("output-history").assertIsDisplayed()
            val text = session.outputHistorySnapshot().map { it.text.trimEnd() }
            for (n in 0 until 80) {
                val line = "ANSWER_%03d the quick brown fox jumps".format(n)
                assertEquals("$line\n${text.joinToString("\n")}", 1, text.count { it == line })
            }
            assertTrue(text.none { it.startsWith("ANSWER_") && !it.endsWith("jumps") })
            compose.onNodeWithTag("output-history-scrollbar").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.3f) }
            delay(300)
            capture("chat-tui-$mode-history")
            compose.onNodeWithTag("tmux-history").performClick()
            withTimeout(8000) { while (session.tmuxHistory.value != null) delay(50) }
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { session.write("\u0003"); service.close(session.id); service.forget(session.id) }
        }
    }

    @Test fun streamedChatIsRecordedOnce() = run("plain")
    @Test fun synchronizedChatIsRecordedOnce() = run("sync")
}
