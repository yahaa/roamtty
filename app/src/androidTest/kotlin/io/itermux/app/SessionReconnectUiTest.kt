package io.itermux.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.session.SessionJournal
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import io.itermux.ssh.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

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

    @Test fun reconnectShowsCenteredAnimationAndCanBeCancelled() = runBlocking {
        // An accepting TCP socket without an SSH banner keeps the handshake pending deterministically.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
            val original = withContext(Dispatchers.Main) {
                service.open(Host(name = "Pending handshake", address = "127.0.0.1", port = listener.localPort, username = "test"),
                    SessionMode.SHELL, Credentials("fixture-only".toByteArray())).also { it.disconnect() }
            }
            try {
                compose.waitUntil(8_000) { compose.onAllNodesWithTag("sessions-tab").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("sessions-tab").performClick()
                compose.onNodeWithTag("session-${original.id}").performClick()
                compose.onNodeWithText(tr("手动重连")).assertIsDisplayed()
                // Keep the test harness from cancelling infinite animations when they start.
                compose.mainClock.autoAdvance = false
                val replacement = withContext(Dispatchers.Main) {
                    service.reconnect(original.id, Credentials("fixture-only".toByteArray()))
                }
                compose.mainClock.advanceTimeBy(100)
                compose.onNodeWithTag("terminal-connection-spinner").assertIsDisplayed()
                compose.onNodeWithText(tr("手动重连")).assertDoesNotExist()
                val progress = compose.onNodeWithTag("terminal-connection-progress").fetchSemanticsNode().boundsInRoot
                val terminalBounds = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
                assertEquals(terminalBounds.center.x, progress.center.x, 1f)
                assertEquals(terminalBounds.center.y, progress.center.y, 1f)
                val firstFrame = compose.onNodeWithTag("terminal-connection-spinner").captureToImage().asAndroidBitmap()
                compose.mainClock.advanceTimeBy(400)
                val secondFrame = compose.onNodeWithTag("terminal-connection-spinner").captureToImage().asAndroidBitmap()
                assertFalse("The reconnect page must animate", firstFrame.sameAs(secondFrame))
                compose.waitForIdle()
                compose.activityRule.scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null), "terminal-reconnect-progress.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                compose.mainClock.autoAdvance = true
                compose.onNodeWithTag("terminal-connection-cancel").performClick()
                compose.onNodeWithTag("terminal-connection-progress").assertDoesNotExist()
                compose.onNodeWithText(tr("手动重连")).assertIsDisplayed()
                assertEquals(Phase.DISCONNECTED, replacement.state.value.phase)
            } finally {
                withContext(Dispatchers.Main) { service.close(original.id) }
            }
        }
    }

    @Test fun reconnectOnTerminalPageBindsReplacementWithSameJournalId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val original = withContext(Dispatchers.Main) {
            service.open(Host(name = "Reconnect regression", address = "127.0.0.1", port = 1, username = "test"),
                SessionMode.TMUX, Credentials("fixture-only".toByteArray()))
        }
        val sibling = withContext(Dispatchers.Main) {
            service.open(original.host, SessionMode.TMUX, Credentials("fixture-only".toByteArray()))
        }
        try {
            assertNotEquals(original.defaultTitle, sibling.defaultTitle)
            val restored = SessionJournal(context).read().associateBy { it.id }
            assertEquals(original.sessionNumber, restored.getValue(original.id).sessionNumber)
            assertEquals(sibling.sessionNumber, restored.getValue(sibling.id).sessionNumber)
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
                assertEquals(original.sessionNumber, replacement.sessionNumber)
                assertEquals(original.remoteSessionName, replacement.remoteSessionName)
                assertSame("Visible output and keyboard input must use the replacement", replacement, view.currentSession)
                assertNull("Old callbacks must be detached", original.onScreenChanged)
                assertNotNull("Replacement output must redraw the view", replacement.onScreenChanged)
            }
        } finally {
            withContext(Dispatchers.Main) { service.close(original.id); service.close(sibling.id) }
        }
    }
}
