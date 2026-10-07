package io.itermux.app

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
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

/** Needs the loopback SSH fixture; pass its password with `-e fixturePassword …` when it is not the default. */
@RunWith(AndroidJUnit4::class)
class PetShortcutRingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    private fun screen(session: TerminalConnection): String = InstrumentationRegistry.getInstrumentation().let {
        var text = ""
        it.runOnMainSync { text = session.getEmulator().screen.transcriptText }
        text
    }

    private fun waitForScreen(session: TerminalConnection, what: String) = try {
        compose.waitUntil(8_000) { what in screen(session) }
    } catch (e: Throwable) {
        throw AssertionError("no \"$what\" on screen:\n${screen(session)}", e)
    }

    /** Holds the pet, then lets go over the item tagged [tag]; [beforeRelease] runs while the ring is open. */
    private fun holdAndDropOn(tag: String, beforeRelease: () -> Unit = {}) {
        compose.onNodeWithTag("restore-dock").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 150)
            moveBy(Offset(-2f, 0f))
        }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        beforeRelease()
        val pet = compose.onNodeWithTag("restore-dock").fetchSemanticsNode().boundsInRoot
        val item = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("restore-dock").performTouchInput {
            val target = item.center - pet.topLeft
            moveTo((center + target) / 2f)
            moveTo(target)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pet-shortcut-arc").assertDoesNotExist()
    }

    @Test fun ringFollowsTheProgramInFrontAndSendsTheDroppedKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        val prefs = context.getSharedPreferences("terminal-settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dockPinned", false).commit()
        TerminalToolbox.autoHideMillis = 0
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(Host(name = "Pet ring", address = "127.0.0.1", port = 22228, username = "itermux_test"),
                SessionMode.SHELL, Credentials(password.toByteArray()))
        }
        val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            withTimeout(15_000) { session.state.first { it.phase == Phase.CONNECTED } }
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("sessions-tab").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("restore-dock").fetchSemanticsNodes().isNotEmpty() }

            // A plain shell gets the keyboard ring; Esc dropped into `cat -v` comes back as ^[.
            session.write("clear; cat -v\r")
            Thread.sleep(800)
            holdAndDropOn("pet-escape") { compose.onNodeWithTag("pet-fn").assertExists() }
            session.write("\r")
            waitForScreen(session, "^[")
            holdAndDropOn("pet-ctrl-c")
            waitForScreen(session, "^C")

            // A Codex footer by the cursor switches to the Codex ring; Shift+Tab reaches the remote as ESC [ Z.
            session.write("clear; printf '› Ask Codex to do anything\\n\\n  GPT xhigh · Context 100%% left\\n'; cat -v\r")
            waitForScreen(session, "Context 100% left")
            Thread.sleep(500)
            holdAndDropOn("pet-shift-tab") { compose.onNodeWithTag("pet-shift-left").assertExists() }
            session.write("\r")
            waitForScreen(session, "^[[Z")
            session.write("\u0003")
        } finally {
            TerminalToolbox.autoHideMillis = 0
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
            prefs.edit().remove("dockPinned").commit()
        }
        Unit
    }
}
