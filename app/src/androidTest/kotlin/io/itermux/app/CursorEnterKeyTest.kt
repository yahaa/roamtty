package io.itermux.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.ui.KeyboardHideAfterEnterMillis
import io.itermux.app.ui.TerminalToolbox
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.Prompt
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Needs the key-echo fixture: `scripts/ssh-key-echo.py` and `adb reverse tcp:22228 tcp:22228`. */
@RunWith(AndroidJUnit4::class)
class CursorEnterKeyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @After fun restoreTimeout() {
        TerminalToolbox.enterKeyHideMillis = 5_000L
        TerminalToolbox.enterKeyTypingPauseMillis = 700L
        TerminalToolbox.petArrowsHideMillis = 4_000L
    }

    private fun terminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            terminal(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Test fun enterKeyFollowsCursorRowAndHidesWhenQuiet() = runBlocking {
        TerminalToolbox.enterKeyHideMillis = 1_500L
        TerminalToolbox.enterKeyTypingPauseMillis = 1_000L
        TerminalToolbox.petArrowsHideMillis = 2_000L
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "回车键验证", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
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
            assertEquals("missing $marker", true, found)
        }
        fun enterKeyShown() = compose.onAllNodesWithTag("cursor-enter").fetchSemanticsNodes().isNotEmpty()
        fun escapeKeyShown() = compose.onAllNodesWithTag("cursor-escape").fetchSemanticsNodes().isNotEmpty()
        fun arrowKeysShown() = compose.onAllNodesWithTag("pet-arrow-up").fetchSemanticsNodes().isNotEmpty()
        fun type(text: String) = compose.activityRule.scenario.onActivity { activity ->
            requireNotNull(terminal(activity.window.decorView)).onCreateInputConnection(EditorInfo()).commitText(text, 1)
        }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            outputContains("KEY_ECHO_READY")
            compose.openSession(session.id)
            compose.waitForIdle()
            assertTrue("hidden until input", !enterKeyShown())
            // Esc holds its place on the cursor row meanwhile.
            compose.waitUntil(3_000) { escapeKeyShown() }
            compose.onNodeWithTag("cursor-escape").performTouchInput { click() }
            outputContains("KEY_1B")

            // It stays hidden while typing and appears on the cursor row once input pauses.
            type("a")
            compose.waitForIdle()
            assertTrue("hidden while typing", !enterKeyShown())
            assertTrue("esc stays while typing", escapeKeyShown())
            assertTrue("no arrows before a double tap", !arrowKeysShown())
            outputContains("KEY_61")
            compose.waitUntil(3_000) { enterKeyShown() }
            val view = requireNotNull(terminal(compose.activity.window.decorView))
            val viewTop = IntArray(2).also { view.getLocationInWindow(it) }[1]
            val rowCentre = withContext(Dispatchers.Main) { viewTop + view.cursorRowTop + view.lineHeight / 2f }
            val key = compose.onNodeWithTag("cursor-enter").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("key centre ${key.center.y} on cursor row $rowCentre", abs(key.center.y - rowCentre) <= 2f)
            assertTrue("key at the right end", key.right >= view.width * 0.9f)
            compose.waitUntil(3_000) { !escapeKeyShown() }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "cursor-enter.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }

            // With the keyboard open, the Enter key also puts it away after a short pause.
            suspend fun imeShown() = withContext(Dispatchers.Main) {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            withContext(Dispatchers.Main) {
                view.requestFocus()
                view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
            }
            withTimeout(5_000) { while (!imeShown()) delay(50) }
            compose.waitUntil(3_000) { enterKeyShown() }
            compose.onNodeWithTag("cursor-enter").performTouchInput { click() }
            outputContains("KEY_0D")
            assertTrue("The keyboard stays open right after Enter", imeShown())
            // The hide waits on the compose clock, which only waitUntil advances.
            compose.waitUntil(KeyboardHideAfterEnterMillis + 3_000) { runBlocking { !imeShown() } }

            // After a quiet spell it hides; a double tap brings it back.
            compose.waitUntil(5_000) { !enterKeyShown() }
            // Esc takes the same spot back.
            compose.waitUntil(3_000) { escapeKeyShown() }
            val escape = compose.onNodeWithTag("cursor-escape").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            val escRow = withContext(Dispatchers.Main) { viewTop + view.cursorRowTop + view.lineHeight / 2f }
            assertTrue("esc on the cursor row", abs(escape.center.y - escRow) <= 2f)
            assertTrue("esc in the enter key's place", abs(escape.center.x - key.center.x) <= 1f)
            // A double tap sends Tab and brings Up above the pet and Down below it; the cursor row keeps its key.
            compose.onNodeWithTag("terminal").performTouchInput { doubleClick() }
            compose.waitUntil(3_000) { enterKeyShown() }
            outputContains("KEY_09")
            compose.waitUntil(3_000) { arrowKeysShown() }
            val pet = compose.onNodeWithTag("restore-dock").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            val up = compose.onNodeWithTag("pet-arrow-up").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            val down = compose.onNodeWithTag("pet-arrow-down").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("up just above the pet", abs(up.bottom - pet.top) <= 2f && abs(up.center.x - pet.center.x) <= 1f)
            assertTrue("down just below the pet", abs(down.top - pet.bottom) <= 2f && abs(down.center.x - pet.center.x) <= 1f)
            compose.onNodeWithTag("pet-arrow-up").performTouchInput { click() }
            outputContains("KEY_41")
            compose.onNodeWithTag("pet-arrow-down").performTouchInput { click() }
            outputContains("KEY_42")
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "pet-arrows.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            // The double-tap arrows fade after a quiet spell.
            compose.waitUntil(5_000) { !arrowKeysShown() }
            // A leading slash brings the same arrows around the pet, and they outlast that spell.
            type("/")
            outputContains("KEY_2F")
            compose.waitUntil(3_000) { arrowKeysShown() }
            delay(TerminalToolbox.petArrowsHideMillis + 500L)
            assertTrue("arrows stay on a slash line", arrowKeysShown())
            suspend fun downs() = withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText.split("KEY_42").size - 1 }
            val before = downs()
            compose.onNodeWithTag("pet-arrow-down").performTouchInput { click() }
            assertEquals("down sent again", true, withTimeoutOrNull(10_000) { while (downs() <= before) delay(40); true })
            // Ending the line (the IME Enter commits "\n") lets them go.
            type("\n")
            compose.waitUntil(5_000) { !arrowKeysShown() }
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
            prompts.cancel()
        }
    }
}
