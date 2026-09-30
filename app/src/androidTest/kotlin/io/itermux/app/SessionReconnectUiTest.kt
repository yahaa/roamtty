package io.itermux.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionReconnectUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    private fun terminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            terminal(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test fun reconnectOnTerminalPageBindsReplacementWithSameJournalId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val original = withContext(Dispatchers.Main) {
            service.open(Host(name = "Reconnect regression", address = "127.0.0.1", port = 1, username = "test"),
                SessionMode.TMUX, Credentials("fixture-only".toByteArray()))
        }
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("sessions-tab").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${original.id}").performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertSame(original, terminal(compose.activity.window.decorView)?.currentSession) }

            val replacement = withContext(Dispatchers.Main) {
                service.reconnect(original.id, Credentials("fixture-only".toByteArray()))
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val view = requireNotNull(terminal(compose.activity.window.decorView))
                assertEquals(original.id, replacement.id)
                assertEquals(original.remoteSessionName, replacement.remoteSessionName)
                assertSame("Visible output and keyboard input must use the replacement", replacement, view.currentSession)
                assertNull("Old callbacks must be detached", original.onScreenChanged)
                assertNotNull("Replacement output must redraw the view", replacement.onScreenChanged)
            }
        } finally {
            withContext(Dispatchers.Main) { service.close(original.id) }
        }
    }
}
