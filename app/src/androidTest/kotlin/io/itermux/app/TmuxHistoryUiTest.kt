package io.itermux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the isolated loopback OpenSSH/tmux fixture, never a saved host. */
@RunWith(AndroidJUnit4::class)
class TmuxHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    private fun terminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            terminal(view.getChildAt(index))?.let { return it }
        return null
    }

    @Test fun thumbDragsRemoteHistoryAndReturnsToLive() = runBlocking {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = File(context.filesDir, "tmux_client_key")
        assumeTrue("Push the isolated test key into the test app first", key.exists())
        val username = InstrumentationRegistry.getArguments().getString("tmuxUser")
        assumeTrue("Pass the isolated SSH test account as tmuxUser", !username.isNullOrBlank())
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22230")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "隔离 tmux 历史", address = "127.0.0.1", port = 22230,
            username = requireNotNull(username), auth = AuthMethod.KEY.name, mode = SessionMode.TMUX.name)
        val session = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.TMUX, Credentials(privateKey = key.readBytes()))
        }
        val prompts = launch {
            session.pending.collect { request ->
                request?.answer?.complete(if (request.prompt is Prompt.VerifyHost) listOf("trust") else null)
            }
        }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) {
                session.state.first { it.phase == Phase.CONNECTED || !it.active }
            }.phase)
            compose.waitUntil(8000) { compose.onAllNodesWithText("会话  1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("会话  1").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            withContext(Dispatchers.Main) {
                session.write("i=1; while [ \$i -le 180 ]; do printf 'TMUX_UI_%03d\\n' \$i; i=\$((i+1)); done\r")
            }
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) {
                    session.getEmulator().screen.transcriptText.contains("TMUX_UI_180")
                }) delay(40)
            }
            compose.onNodeWithTag("restore-dock").performClick()
            compose.onNodeWithTag("tmux-history").assertIsDisplayed().performClick()
            withTimeout(8000) { while (session.tmuxHistory.value?.active != true) delay(40) }
            val rows = requireNotNull(session.tmuxHistory.value).rows
            assertTrue("tmux must own older lines", rows > 80)
            compose.onNodeWithTag("terminal").performTouchInput { swipeDown() }
            withTimeout(8000) { while ((session.tmuxHistory.value?.position ?: 0) == 0) delay(40) }
            val density = context.resources.displayMetrics.density
            compose.onNodeWithTag("terminal").performTouchInput {
                down(Offset(width - 6 * density, height - 12 * density))
                moveTo(Offset(width - 6 * density, 9 * density), delayMillis = 500)
                up()
            }
            withTimeout(8000) { while ((session.tmuxHistory.value?.position ?: 0) < rows / 2) delay(40) }
            val view = requireNotNull(terminal(compose.activity.window.decorView))
            withTimeout(8000) {
                while (!withContext(Dispatchers.Main) {
                    val emulator = session.getEmulator()
                    emulator.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
                        .contains("[${rows}/${rows}]")
                }) delay(40)
            }
            withTimeout(8000) { while (session.tmuxHistory.value?.position != rows) delay(40) }
            compose.waitForIdle()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), "tmux-history-phone.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            compose.activityRule.scenario.onActivity {
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            compose.activityRule.scenario.onActivity { activity ->
                val root = activity.window.decorView
                val screen = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(screen))
                File(context.getExternalFilesDir(null), "tmux-history-ui-phone.png").outputStream().use {
                    screen.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screen.recycle()
            }
            compose.onNodeWithTag("terminal").performTouchInput {
                down(Offset(width - 6 * density, 9 * density))
                moveTo(Offset(width - 6 * density, height - 12 * density), delayMillis = 500)
                up()
            }
            withTimeout(8000) { while (session.tmuxHistory.value?.position != 0) delay(40) }
            withTimeout(8000) {
                while (!withContext(Dispatchers.Main) {
                    val emulator = session.getEmulator()
                    emulator.getSelectedText(0, 0, emulator.mColumns, emulator.mRows).contains("TMUX_UI_180")
                }) delay(40)
            }
            compose.onNodeWithTag("tmux-history").performClick()
            withTimeout(8000) { while (session.tmuxHistory.value != null) delay(40) }
            withContext(Dispatchers.Main) { session.draft = "unsent draft"; session.enterTmuxHistory() }
            withTimeout(8000) { while (session.tmuxHistory.value?.active != true) delay(40) }
            withContext(Dispatchers.Main) { session.executeHistoryCommand("printf 'EXEC_%s\\n' OK") }.join()
            withTimeout(8000) {
                while (!withContext(Dispatchers.Main) {
                    val emulator = session.getEmulator()
                    emulator.getSelectedText(0, 0, emulator.mColumns, emulator.mRows).contains("EXEC_OK")
                }) delay(40)
            }
            assertNull(session.tmuxHistory.value)
            assertEquals("unsent draft", session.draft)

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previousClip = withContext(Dispatchers.Main) { clipboard.primaryClip }
            try {
                withContext(Dispatchers.Main) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Fixture", "printf 'PASTE_%s\\n' OK"))
                    session.enterTmuxHistory()
                }
                withTimeout(8000) { while (session.tmuxHistory.value?.active != true) delay(40) }
                compose.onNodeWithTag("terminal").performTouchInput { doubleClick() }
                withTimeout(8000) {
                    while (!withContext(Dispatchers.Main) {
                        session.tmuxHistory.value == null && session.emulator.screen.transcriptText.contains("printf 'PASTE_%s")
                    }) delay(40)
                }
                withContext(Dispatchers.Main) {
                    assertFalse("Paste must not submit the shell command", session.emulator.screen.transcriptText.contains("PASTE_OK"))
                    assertEquals("unsent draft", session.draft)
                    session.write("\r")
                }
                withTimeout(8000) {
                    while (!withContext(Dispatchers.Main) { session.emulator.screen.transcriptText.contains("PASTE_OK") }) delay(40)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    clipboard.setPrimaryClip(previousClip ?: ClipData.newPlainText("", ""))
                }
            }
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { session.write("exit\r"); service.close(session.id); service.forget(session.id) }
        }
    }
}
