package io.itermux.app

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session list groups sessions by what their agent does, the one waiting for the user first, and its chips show one
 * group alone. Needs the loopback SSH fixture with tmux; pass its password with `-e fixturePassword …`.
 */
@RunWith(AndroidJUnit4::class)
class HomeSessionGroupsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun groupsByAgentStateAndFiltersToOne() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val host = Host(name = "Groups", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "TMUX")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val all = withContext(Dispatchers.Main) {
            List(3) { service.open(host, SessionMode.TMUX, Credentials(password.toByteArray())) }
        }
        val (done, waiting, plain) = all
        val prompts = CoroutineScope(Dispatchers.Main).launch {
            all.forEach { s -> launch { s.pending.collect { it?.answer?.complete(listOf("trust")) } } }
        }
        try {
            all.forEach { s -> withTimeout(20_000) { s.state.first { it.phase == Phase.CONNECTED } } }
            // `head` stands in for the agent, so the pane runs a program as an agent's does.
            val hook = "sh ~/.roamtty/agent-hook.sh claude"
            withContext(Dispatchers.Main) {
                done.runWhenConnected("printf '{\"prompt\":\"write the release notes\"}' | $hook prompt; " +
                    "printf '{\"last_assistant_message\":\"Release notes written to CHANGELOG.md.\"}' | $hook stop; head -n 1",
                    agentHooks = CodingAgent.CLAUDE)
                waiting.runWhenConnected("printf '{\"prompt\":\"clean the build\"}' | $hook prompt; " +
                    "printf '{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"rm -rf build\"}}' | $hook tool; " +
                    "printf '{\"notification_type\":\"permission_prompt\",\"message\":\"needs permission\"}' | $hook notify; head -n 1",
                    agentHooks = CodingAgent.CLAUDE)
            }
            withTimeout(40_000) { done.agentStatus.first { it?.state == AgentStatus.State.IDLE } }
            withTimeout(40_000) { waiting.agentStatus.first { it?.state == AgentStatus.State.WAITING } }
            fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            compose.waitUntil(15_000) {
                listOf("waiting", "done", "other").all { exists("home-session-group-$it") } && exists("home-session-filter-all")
            }
            fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().top
            // Waiting first, then done, then the plain terminal.
            assertTrue(top("home-session-${waiting.id}") < top("home-session-${done.id}"))
            assertTrue(top("home-session-${done.id}") < top("home-session-${plain.id}"))
            compose.onNodeWithTag("home-session-reply-${done.id}", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("home-session-approve-${waiting.id}", useUnmergedTree = true).assertExists()
            capture(context, "session-groups")
            // A chip shows its group alone; tapped again, everything.
            compose.onNodeWithTag("home-session-filter-done").performClick()
            compose.waitUntil(5_000) { !exists("home-session-${waiting.id}") }
            assertTrue(exists("home-session-${done.id}") && !exists("home-session-${plain.id}"))
            capture(context, "session-groups-done")
            compose.onNodeWithTag("home-session-filter-done").performClick()
            compose.waitUntil(5_000) { exists("home-session-${waiting.id}") && exists("home-session-${plain.id}") }
        } finally {
            prompts.cancel()
            all.forEach { it.write("\u0003exit\r") }
            withContext(Dispatchers.Main) { all.forEach { service.close(it.id) } }
        }
        Unit
    }

    private fun capture(context: android.content.Context, name: String) {
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
