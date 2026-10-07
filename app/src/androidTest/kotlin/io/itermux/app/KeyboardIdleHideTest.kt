package io.itermux.app

import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.ui.TerminalToolbox
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Needs the loopback SSH fixture; pass its password with `-e fixturePassword …` when it is not the default. */
@RunWith(AndroidJUnit4::class)
class KeyboardIdleHideTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private var previousImeSetting: String? = null
    private val emulator = Build.HARDWARE in listOf("ranchu", "goldfish")

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }

    @Before fun showEmulatorIme() {
        if (emulator) {
            previousImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
            shell("settings put secure show_ime_with_hard_keyboard 1")
        }
    }

    @After fun restore() {
        TerminalToolbox.keyboardIdleHideMillis = 0
        previousImeSetting?.let { value ->
            shell(if (value == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $value")
        }
    }

    private fun terminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) terminal(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** What the IME sends while dictating: a growing composing text, then the commit. */
    private fun dictate(text: String) {
        compose.activityRule.scenario.onActivity { activity ->
            val connection = requireNotNull(terminal(activity.window.decorView)).onCreateInputConnection(EditorInfo())
            connection.setComposingText(text, 1)
        }
    }

    @Test fun keyboardFoldsAwayAfterAQuietSpellButNotWhileDictating() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(Host(name = "Keyboard idle", address = "127.0.0.1", port = 22228, username = "itermux_test"),
                SessionMode.SHELL, Credentials(password.toByteArray()))
        }
        val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            withTimeout(15_000) { session.state.first { it.phase == Phase.CONNECTED } }
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("sessions-tab").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("terminal-viewport").fetchSemanticsNodes().isNotEmpty() }
            TerminalToolbox.keyboardIdleHideMillis = 2_000
            compose.tapCursorLine()
            compose.waitUntil(8_000) { imeVisible() }

            // Dictated words keep arriving faster than the timeout: the keyboard stays.
            listOf("ls", "ls -", "ls -l", "ls -la").forEach { words ->
                dictate(words)
                Thread.sleep(1_200)
            }
            assertTrue("keyboard folded while dictating", imeVisible())
            // Then nothing: it folds away.
            compose.waitUntil(5_000) { !imeVisible() }
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }
}
