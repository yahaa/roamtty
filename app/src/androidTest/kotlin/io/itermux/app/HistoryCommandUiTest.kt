package io.itermux.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.app.data.Host
import io.itermux.app.session.*
import io.itermux.app.ui.TerminalPane
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryCommandUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortcutSubmitsExactlyOneEnterAndStaysFirstAfterRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pins = object : PinStore {
            private var accepted: HostPin? = null
            override suspend fun get(endpoint: String) = accepted
            override suspend fun putIfAbsent(pin: HostPin) = accepted ?: pin.also { accepted = it }
        }
        val session = TerminalConnection(Host(name = "History", address = "127.0.0.1", port = 22228,
            username = "itermux_test", mode = "SHELL"), SessionMode.SHELL, Credentials("test-only".toByteArray()), pins, scope)
        val prompts = scope.launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        fun received() = Regex("KEY_([0-9A-F]{2})").findAll(session.getEmulator().screen.transcriptText)
            .map { it.groupValues[1].toInt(16) }.toList()
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            withTimeout(10_000) { session.commonCommands.first { it is CommonCommandsState.Ready } }
            compose.setContent { MaterialTheme {
                TerminalPane(session, Modifier.fillMaxSize(), listOf(session), false, {}, {}, {}, {}, {}, {}, {}, {})
            } }
            withContext(Dispatchers.Main) { session.draft = "unsent draft" }
            for (bracketed in listOf(false, true)) {
                withContext(Dispatchers.Main) {
                    val mode = (if (bracketed) "\u001b[?2004h" else "\u001b[?2004l").toByteArray()
                    session.getEmulator().append(mode, mode.size)
                }
                if (compose.onAllNodesWithTag("restore-dock").fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithTag("restore-dock").performClick()
                compose.onNodeWithTag("common-commands").performScrollTo().performClick()
                compose.onNodeWithTag("common-commands-list").performScrollToNode(hasTestTag("common-command-git status"))
                val before = withContext(Dispatchers.Main) { received().size }
                compose.onNodeWithTag("common-command-git status").performClick()
                val expected = ShellHistoryCommands.executionInput("git status", bracketed).map { it.code }
                withTimeout(8_000) { while (withContext(Dispatchers.Main) { received().size } < before + expected.size) delay(20) }
                delay(150)
                assertEquals(expected, withContext(Dispatchers.Main) { received().drop(before) })
                assertEquals("unsent draft", session.draft)
                assertEquals("git status", (session.commonCommands.value as CommonCommandsState.Ready).commands.first())
                withContext(Dispatchers.Main) { session.refreshCommonCommands() }
                withTimeout(8_000) { session.commonCommands.first { it is CommonCommandsState.Ready } }
                assertEquals("git status", (session.commonCommands.value as CommonCommandsState.Ready).commands.first())
            }
        } finally { prompts.cancel(); session.disconnect(); scope.cancel() }
    }
}
