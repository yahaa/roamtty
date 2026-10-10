package io.itermux.app

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.AgentStatus
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
 * A tmux session whose "agent" reports through the installed hook script, as Claude Code's hooks would. Needs the
 * loopback SSH fixture with tmux; pass its password with `-e fixturePassword …`.
 */
@RunWith(AndroidJUnit4::class)
class AgentStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun sessionRowShowsWhatTheAgentReports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val host = Host(name = "Agent status", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "TMUX")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.TMUX, Credentials(password.toByteArray())) }
        val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED } }
            val hook = "sh ~/.roamtty/agent-hook.sh claude"
            withContext(Dispatchers.Main) {
                session.runWhenConnected("printf '{\"prompt\":\"refactor the parser\"}' | $hook prompt; " +
                    "printf '{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"make test\"}}' | $hook tool; sleep 600",
                    withAgentHooks = true)
            }
            val working = withTimeout(40_000) { session.agentStatus.first { it?.state == AgentStatus.State.WORKING } }!!
            check(working.tool == "Bash" && working.detail == "make test" && working.task == "refactor the parser") { "$working" }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-session-${session.id}").fetchSemanticsNodes().isNotEmpty() }
            // The row merges its texts into one node.
            val row = compose.onNodeWithTag("home-session-${session.id}")
            compose.waitUntil(10_000) {
                runCatching { row.assertTextContains("Bash: make test", substring = true) }.isSuccess
            }
            row.assertTextContains(tr("运行中"), substring = true)
            row.assertTextContains("refactor the parser", substring = true)
            compose.onNodeWithTag("agent-icon-claude", useUnmergedTree = true).assertExists()
            context.getExternalFilesDir(null)?.let { dir ->
                java.io.FileOutputStream(java.io.File(dir, "agent-row.png")).use {
                    row.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            session.write("\u0003exit\r")
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }
}
