package io.itermux.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalToolboxAutoHideTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun collapsedToolboxHidesAfterFiveIdleSecondsAndReturnsOnTouch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("terminal-settings", android.content.Context.MODE_PRIVATE)
        val previousPinned = if (prefs.contains("dockPinned")) prefs.getBoolean("dockPinned", false) else null
        prefs.edit().putBoolean("dockPinned", false).commit()
        TerminalToolbox.autoHideMillis = 5_000
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(Host(name = "Toolbox auto-hide", address = "127.0.0.1", port = 22228, username = "itermux_test"),
                SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            withTimeout(15_000) { session.state.first { it.phase == Phase.CONNECTED } }
            compose.openSession(session.id)
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()

            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(4_000)
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_600)
            compose.onNodeWithTag("restore-dock").assertDoesNotExist()

            // Any touch on the terminal brings it straight back, and restarts the idle timer.
            compose.onNodeWithTag("terminal").performTouchInput { down(center); up() }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(4_000)
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_600)
            compose.onNodeWithTag("restore-dock").assertDoesNotExist()

            // An open dock never hides on its own.
            compose.onNodeWithTag("terminal").performTouchInput { down(center); up() }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("restore-dock").performClick()
            compose.mainClock.advanceTimeBy(10_000)
            compose.onNodeWithTag("terminal-dock").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            TerminalToolbox.autoHideMillis = 0
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
            prefs.edit().apply {
                previousPinned?.let { putBoolean("dockPinned", it) } ?: remove("dockPinned")
            }.commit()
        }
        Unit
    }
}
