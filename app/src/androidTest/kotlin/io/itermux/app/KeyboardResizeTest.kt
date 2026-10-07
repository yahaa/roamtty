package io.itermux.app

import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opening or closing the keyboard resizes the terminal once, not on every frame of the IME slide. */
@RunWith(AndroidJUnit4::class)
class KeyboardResizeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var previousImeSetting: String? = null
    private val animationScales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    private var previousScales: List<String> = emptyList()

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
    }

    @Before fun enableEmulatorKeyboard() {
        assumeTrue("Keyboard fixture runs only on emulators", Build.HARDWARE in listOf("ranchu", "goldfish"))
        previousImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        // Test devices often disable animations; the IME slide is what this test is about.
        previousScales = animationScales.map { shell("settings get global $it") }
        animationScales.forEach { shell("settings put global $it 1") }
    }

    @After fun restoreEmulatorKeyboard() {
        previousImeSetting?.let {
            shell(if (it == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $it")
        }
        animationScales.zip(previousScales).forEach { (key, value) ->
            shell(if (value == "null") "settings delete global $key" else "settings put global $key $value")
        }
    }

    @Test fun keyboardResizesTerminalOnce() = runBlocking {
        val context = instrumentation.targetContext
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val password = runCatching {
            instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        }.getOrDefault("test-only")
        val host = Host(name = "键盘验证工作站", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            delay(800)
            // A fresh shell's cursor sits near the top, so the bottom of the terminal is another line.
            compose.onNodeWithTag("terminal").performTouchInput { click(Offset(centerX, bottom - 4f)) }
            delay(1500)
            assertFalse("Tapping off the cursor line must not open the keyboard",
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            repeat(2) {
                compose.tapCursorLine()
                val opening = sizesUntil(session) { it.isVisible(WindowInsetsCompat.Type.ime()) }
                shell("input keyevent 4")
                val closing = sizesUntil(session) { !it.isVisible(WindowInsetsCompat.Type.ime()) }
                // At most the start and end sizes, nothing in between.
                assertTrue("Terminal sizes while opening: $opening", opening.size <= 2)
                assertTrue("Terminal sizes while closing: $closing", closing.size <= 2)
            }
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
    }

    /** The (view height, emulator rows) pairs seen until [done] holds and the slide has finished. */
    private suspend fun sizesUntil(session: io.itermux.app.session.TerminalConnection, done: (WindowInsetsCompat) -> Boolean): Set<Pair<Int, Int>> {
        val sizes = linkedSetOf<Pair<Int, Int>>()
        fun sample() = instrumentation.runOnMainSync {
            val view = compose.activity.window.decorView.findTerminalView() ?: return@runOnMainSync
            sizes += view.height to session.getEmulator().mRows
        }
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            sample()
            if (ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.let(done) == true) break
            delay(5)
        }
        // The slide continues after the IME reports its new visibility.
        repeat(100) { sample(); delay(5) }
        return sizes
    }
}

private fun View.findTerminalView(): TerminalView? {
    if (this is TerminalView) return this
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findTerminalView()?.let { return it }
    return null
}
