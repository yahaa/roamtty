package io.itermux.app

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.SessionService
import io.itermux.app.session.sessionListPath
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
import org.junit.Assert.assertEquals
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
            // Codex's hooks come with its launcher, which must arrive runnable.
            val hook = "sh ~/.roamtty/agent-hook.sh codex"
            withContext(Dispatchers.Main) {
                // Started, before any prompt: idle, awaiting one.
                session.runWhenConnected("[ -x ~/.roamtty/codex ] && $hook start </dev/null; sleep 15; " +
                    "printf '{\"prompt\":\"refactor the parser\"}' | $hook prompt; " +
                    "printf '{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"make test\"}}' | $hook tool; sleep 20; " +
                    // Then a new prompt: working on, with no command yet.
                    "printf '{\"prompt\":\"refactor the parser\"}' | $hook prompt; sleep 15; " +
                    // Then the turn stops with its answer.
                    "printf '{\"last_assistant_message\":\"## Parser **refactored**, tests pass.\"}' | $hook stop; " +
                    // The follow-up typed on the row arrives as the next prompt; a program reads it, as the agent would (the
                    // shell's own read, or one in a subshell, leaves the pane at the shell: the agent gone).
                    "head -n 1 > /tmp/roamtty-follow-up-\$\$; printf '{\"prompt\":\"%s\"}' \"\$(cat /tmp/roamtty-follow-up-\$\$)\" | $hook prompt; " +
                    // Which asks for permission, granted with Enter from the row.
                    "printf '{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"rm -rf build\"}}' | $hook ask; " +
                    "head -n 1 >/dev/null; rm -f /tmp/roamtty-follow-up-\$\$; $hook post </dev/null; sleep 600",
                    agentHooks = CodingAgent.CODEX)
            }
            fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            // Idle without a prompt: a compact row says it awaits one, its folder under the name, and the
            // host keeps its chip beside the name.
            compose.waitUntil(30_000) { exists("home-session-awaiting-${session.id}") }
            compose.onNodeWithTag("home-session-awaiting-${session.id}", useUnmergedTree = true).assertTextEquals(tr("等待输入中…"))
            compose.onNodeWithTag("home-session-host-${session.id}", useUnmergedTree = true).assertTextEquals("Agent status")
            compose.onNodeWithTag("home-session-location-${session.id}", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("home-session-follow-up-${session.id}", useUnmergedTree = true).assertExists()
            context.getExternalFilesDir(null)?.let { dir ->
                java.io.FileOutputStream(java.io.File(dir, "agent-row-awaiting.png")).use {
                    compose.onNodeWithTag("home-sessions-list").captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val working = withTimeout(40_000) { session.agentStatus.first { it?.state == AgentStatus.State.WORKING } }!!
            check(working.tool == "Bash" && working.detail == "make test" && working.task == "refactor the parser" &&
                working.directory.isNotEmpty()) { "$working" }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-session-${session.id}").fetchSemanticsNodes().isNotEmpty() }
            val row = compose.onNodeWithTag("home-session-${session.id}")
            // Named by its folder, the folder and the prompt beneath, the command across the row and the
            // status line last.
            compose.waitUntil(10_000) { exists("home-session-working-${session.id}") }
            val folder = working.directory.trimEnd('/').substringAfterLast('/')
            compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true).assertTextEquals(folder)
            compose.onNodeWithTag("home-session-prompt-${session.id}", useUnmergedTree = true)
                .assertTextEquals(tr("你: ") + "refactor the parser")
            compose.onNodeWithTag("home-session-location-${session.id}", useUnmergedTree = true)
                .assertTextEquals(sessionListPath(working.directory, host.username))
            compose.onNode(hasTestTag("agent-icon-codex") and hasAnyAncestor(hasTestTag("home-session-${session.id}")),
                useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("home-session-activity-${session.id}", useUnmergedTree = true).assertTextEquals("Running make test")
            fun capture(name: String) = context.getExternalFilesDir(null)?.let { dir ->
                java.io.FileOutputStream(java.io.File(dir, "$name.png")).use {
                    compose.onNodeWithTag("home-sessions-list").captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            capture("agent-row")
            val titleTop = compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().top
            // Working on without a command: no command line, and the name stays where it was.
            compose.waitUntil(30_000) { !exists("home-session-activity-${session.id}") }
            assertEquals(titleTop, compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().top)
            capture("agent-row-working")
            // Once the turn stops: how long it worked, and the start of its answer read as plain text.
            compose.waitUntil(30_000) { exists("home-session-done-${session.id}") }
            compose.onNodeWithTag("home-session-reply-${session.id}", useUnmergedTree = true)
                .assertTextEquals("Parser refactored, tests pass.")
            assertEquals(titleTop, compose.onNodeWithTag("home-session-title-${session.id}", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().top)
            capture("agent-row-done")
            // A follow-up typed on the row reaches the agent as its next prompt.
            compose.onNodeWithTag("home-session-follow-up-${session.id}", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("home-session-follow-up-input-${session.id}", useUnmergedTree = true).performTextInput("next step")
            capture("agent-row-follow-up")
            compose.onNodeWithTag("home-session-follow-up-send-${session.id}", useUnmergedTree = true).performClick()
            // Waited for in the compose clock: the send pauses between the text and Enter on the row's scope.
            compose.waitUntil(30_000) { session.agentStatus.value?.state == AgentStatus.State.WAITING }
            assertEquals("next step", session.agentStatus.value?.task)
            // Waiting for permission: 允许 answers it without moving the card.
            compose.waitUntil(10_000) { exists("home-session-approve-${session.id}") }
            compose.onNodeWithTag("home-session-activity-${session.id}", useUnmergedTree = true).assertTextEquals("Running rm -rf build")
            capture("agent-row-waiting")
            compose.onNodeWithTag("home-session-approve-${session.id}", useUnmergedTree = true).performClick()
            compose.waitUntil(30_000) { session.agentStatus.value?.state == AgentStatus.State.WORKING }
            compose.waitUntil(10_000) { exists("home-session-working-${session.id}") }
            row.assertExists()
        } finally {
            session.write("\u0003exit\r")
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }

    @Test fun sessionRowShowsWhatAntigravityReports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val host = Host(name = "Agy status", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "TMUX")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.TMUX, Credentials(password.toByteArray())) }
        val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED } }
            val hook = "ROAMTTY_AGENT=agy sh ~/.roamtty/agent-hook.sh agy"
            withContext(Dispatchers.Main) {
                session.runWhenConnected("[ -x ~/.roamtty/agy ] && $hook start </dev/null; sleep 10; " +
                    "printf '{\"invocationNum\":0,\"lastUserInput\":\"support agy\"}' | $hook prompt; " +
                    "printf '{\"toolCall\":{\"args\":{\"CommandLine\":\"./gradlew test\"},\"id\":\"c1\",\"name\":\"run_command\"}}' | $hook tool; sleep 12; " +
                    "printf '{\"toolCall\":{\"args\":{\"questions\":[{\"question\":\"Ship it?\"}]},\"id\":\"c2\",\"name\":\"ask_question\"}}' | $hook tool; sleep 12; " +
                    "printf '%s\\n' '{\"step_index\":1,\"type\":\"PLANNER_RESPONSE\",\"content\":\"**Shipped** agy support.\"}' > /tmp/agy-tx-\$\$; " +
                    "printf '{\"fullyIdle\":true,\"terminationReason\":\"NO_TOOL_CALL\",\"transcriptPath\":\"/tmp/agy-tx-%s\"}' \"\$\$\" | $hook stop; " +
                    "rm -f /tmp/agy-tx-\$\$; sleep 600",
                    agentHooks = CodingAgent.AGY)
            }
            fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            compose.waitUntil(30_000) { exists("home-session-awaiting-${session.id}") }
            compose.onNode(hasTestTag("agent-icon-agy") and hasAnyAncestor(hasTestTag("home-session-${session.id}")),
                useUnmergedTree = true).assertExists()

            val working = withTimeout(30_000) { session.agentStatus.first { it?.state == AgentStatus.State.WORKING } }!!
            check(working.agent == "agy" && working.tool == "run_command" && working.detail == "./gradlew test" &&
                working.task == "support agy") { "$working" }
            compose.waitUntil(10_000) { exists("home-session-activity-${session.id}") }
            compose.onNodeWithTag("home-session-activity-${session.id}", useUnmergedTree = true).assertTextEquals("Running ./gradlew test")
            context.getExternalFilesDir(null)?.let { dir ->
                java.io.FileOutputStream(java.io.File(dir, "agy-row-working.png")).use {
                    compose.onNodeWithTag("home-sessions-list").captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }

            compose.waitUntil(25_000) { session.agentStatus.value?.state == AgentStatus.State.WAITING }
            compose.waitUntil(10_000) { exists("home-session-approve-${session.id}") }
            compose.onNodeWithTag("home-session-activity-${session.id}", useUnmergedTree = true).assertTextEquals("ask_question Ship it?")

            compose.waitUntil(25_000) { exists("home-session-done-${session.id}") }
            compose.onNodeWithTag("home-session-reply-${session.id}", useUnmergedTree = true).assertTextEquals("Shipped agy support.")
            context.getExternalFilesDir(null)?.let { dir ->
                java.io.FileOutputStream(java.io.File(dir, "agy-row-done.png")).use {
                    compose.onNodeWithTag("home-sessions-list").captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
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
