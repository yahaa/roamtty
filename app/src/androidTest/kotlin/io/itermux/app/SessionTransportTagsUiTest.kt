package io.itermux.app

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
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

/**
 * A session card without agent news tags what keeps the session beside its status, as a host card does: tmux for a
 * resumable session, nothing for a plain shell. Needs the loopback SSH fixture with tmux; pass its password with
 * `-e fixturePassword …`.
 */
@RunWith(AndroidJUnit4::class)
class SessionTransportTagsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun resumableSessionIsTaggedTmuxAndPlainShellIsNot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val host = Host(name = "Tags", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "TMUX")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val tmux = withContext(Dispatchers.Main) { service.open(host, SessionMode.TMUX, Credentials(password.toByteArray())) }
        val shell = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = CoroutineScope(Dispatchers.Main).launch {
            listOf(tmux, shell).forEach { s -> launch { s.pending.collect { it?.answer?.complete(listOf("trust")) } } }
        }
        try {
            withTimeout(20_000) { tmux.state.first { it.phase == Phase.CONNECTED } }
            withTimeout(20_000) { shell.state.first { it.phase == Phase.CONNECTED } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("home-session-tag-tmux-${tmux.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("home-session-tags-${shell.id}", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("home-session-tag-tmux-${shell.id}", useUnmergedTree = true).assertDoesNotExist()
            context.getExternalFilesDir(null)?.let { dir ->
                java.io.FileOutputStream(java.io.File(dir, "session-tags-tmux.png")).use {
                    compose.onNodeWithTag("home-session-${tmux.id}").captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            prompts.cancel()
            tmux.write("exit\r")
            withContext(Dispatchers.Main) { service.close(tmux.id); service.close(shell.id) }
        }
        Unit
    }
}
