package io.itermux.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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

    private fun assertControlsHidden() {
        compose.onNodeWithTag("restore-dock").assertDoesNotExist()
        compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
        compose.onNodeWithTag("terminal-menu").assertDoesNotExist()
        compose.onNodeWithTag("terminal-shortcuts").assertDoesNotExist()
    }

    @Test fun controlsWaitForConnectionAndPinnedDockReturnsAfterReconnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItermuxApp
        val prefs = context.getSharedPreferences("terminal-settings", android.content.Context.MODE_PRIVATE)
        val previousPinned = if (prefs.contains("dockPinned")) prefs.getBoolean("dockPinned", false) else null
        prefs.edit().putBoolean("dockPinned", false).commit()
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val original = withContext(Dispatchers.Main) {
            service.open(Host(name = "Reconnect controls", address = "127.0.0.1", port = 22228, username = "itermux_test"),
                SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        try {
            // Hold real host verification to inspect the page before it can connect.
            val firstPrompt = withTimeout(15_000) { original.pending.first { it != null } }!!
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("sessions-tab").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${original.id}").performClick()
            assertControlsHidden()
            firstPrompt.answer.complete(listOf("trust"))
            withTimeout(15_000) { original.state.first { it.phase == Phase.CONNECTED } }
            compose.onNodeWithTag("restore-dock").assertIsDisplayed().performClick()
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText(tr("固定操作栏")).performClick()
            compose.onNodeWithTag("terminal-dock").assertIsDisplayed()
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText(tr("取消固定操作栏")).assertIsDisplayed()

            withContext(Dispatchers.Main) { original.disconnect() }
            compose.onNodeWithText(tr("手动重连")).assertIsDisplayed()
            assertControlsHidden()
            compose.onNodeWithText(tr("取消固定操作栏")).assertDoesNotExist()
            assertTrue(prefs.getBoolean("dockPinned", false))

            app.database.hosts().forgetPin("[127.0.0.1]:22228")
            val replacement = withContext(Dispatchers.Main) {
                service.reconnect(original.id, Credentials("test-only".toByteArray()))
            }
            val retryPrompt = withTimeout(15_000) { replacement.pending.first { it != null } }!!
            assertControlsHidden()
            retryPrompt.answer.complete(listOf("trust"))
            withTimeout(15_000) { replacement.state.first { it.phase == Phase.CONNECTED } }
            compose.onNodeWithTag("terminal-dock").assertIsDisplayed()
            compose.onNodeWithTag("restore-dock").assertDoesNotExist()
            compose.onNodeWithText(tr("取消固定操作栏")).assertDoesNotExist()
            compose.onNodeWithTag("collapse-dock").performClick()
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
        } finally {
            withContext(Dispatchers.Main) { service.close(original.id) }
            prefs.edit().apply {
                previousPinned?.let { putBoolean("dockPinned", it) } ?: remove("dockPinned")
            }.commit()
        }
        Unit
    }

    @Test fun disconnectedDialogTracksKeyboardAndRotationWithoutShrinkingTerminal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(Host(name = "Dialog layout", address = "127.0.0.1", port = 1, username = "test"),
                SessionMode.SHELL, Credentials("fixture-only".toByteArray())).also { it.disconnect() }
        }
        fun imeVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        fun checkNotice() {
            assertControlsHidden()
            val viewport = compose.onNodeWithTag("terminal-notice-viewport").fetchSemanticsNode().boundsInRoot
            val card = compose.onNodeWithTag("terminal-connection-notice").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val workspace = compose.onNodeWithTag("terminal-workspace").fetchSemanticsNode().boundsInRoot
            val grid = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
            assertEquals("Connection notices must not remove terminal rows", workspace.height, grid.height, 1f)
            assertTrue("Dialog stays inside the area above the IME", card.top >= viewport.top && card.bottom <= viewport.bottom)
            assertEquals(viewport.center.x, card.center.x, 1f)
            if (card.height / 2 + 16 * context.resources.displayMetrics.density < viewport.height * 0.382f) {
                assertEquals(viewport.top + viewport.height * 0.382f, card.center.y, 1f)
            }
            compose.onNodeWithText(tr("手动重连")).assertIsDisplayed()
        }
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("sessions-tab").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            for (landscape in listOf(false, true)) {
                compose.activityRule.scenario.onActivity {
                    it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                compose.waitUntil(8_000) {
                    (compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) == landscape
                }
                compose.waitForIdle()
                checkNotice()
                val fullHeight = compose.onNodeWithTag("terminal-workspace").fetchSemanticsNode().boundsInRoot.height
                compose.activityRule.scenario.onActivity { activity ->
                    requireNotNull(terminal(activity.window.decorView)).let { view ->
                        view.requestFocus()
                        SoftwareKeyboardControllerCompat(view).show()
                    }
                }
                compose.waitUntil(8_000) {
                    imeVisible() && compose.onNodeWithTag("terminal-workspace").fetchSemanticsNode().boundsInRoot.height < fullHeight - 50
                }
                compose.waitForIdle()
                checkNotice()
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "terminal-notice-${if (landscape) "landscape" else "portrait"}-display.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                compose.activityRule.scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null), "terminal-notice-${if (landscape) "landscape" else "portrait"}-ime.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    SoftwareKeyboardControllerCompat(root).hide()
                }
                compose.waitUntil(8_000) { !imeVisible() &&
                    compose.onNodeWithTag("terminal-workspace").fetchSemanticsNode().boundsInRoot.height >= fullHeight - 2 }
                compose.waitForIdle()
                checkNotice()
            }
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    @Test fun reconnectShowsGoldenPositionAnimationAndCanBeCancelled() = runBlocking {
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
                compose.onNodeWithTag("terminal-connection-motion").assertIsDisplayed()
                assertControlsHidden()
                compose.onNodeWithText(tr("手动重连")).assertDoesNotExist()
                val progress = compose.onNodeWithTag("terminal-connection-progress").fetchSemanticsNode().boundsInRoot
                val terminalBounds = compose.onNodeWithTag("terminal-notice-viewport").fetchSemanticsNode().boundsInRoot
                assertEquals(terminalBounds.center.x, progress.center.x, 1f)
                assertEquals(terminalBounds.top + terminalBounds.height * 0.382f, progress.center.y, 1f)
                val firstFrame = compose.onNodeWithTag("terminal-connection-motion").captureToImage().asAndroidBitmap()
                compose.mainClock.advanceTimeBy(400)
                val secondFrame = compose.onNodeWithTag("terminal-connection-motion").captureToImage().asAndroidBitmap()
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
                assertControlsHidden()
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
