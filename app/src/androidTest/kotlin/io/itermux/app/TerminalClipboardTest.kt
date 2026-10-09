package io.itermux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection
import io.itermux.app.ui.TerminalPane
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the isolated byte-echo SSH fixture on port 22228; no user remote host is contacted. */
class TerminalClipboardTest {
    @get:Rule val compose = createComposeRule()

    private suspend fun withSessions(test: suspend (TerminalConnection, suspend () -> TerminalConnection) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sessions = mutableListOf<TerminalConnection>()
        suspend fun connect(): TerminalConnection {
            val pins = object : PinStore {
                private var pin: HostPin? = null
                override suspend fun get(endpoint: String) = pin
                override suspend fun putIfAbsent(pin: HostPin) = this.pin ?: pin.also { this.pin = it }
            }
            val session = withContext(Dispatchers.Main) {
                TerminalConnection(Host(name = "Clipboard fixture", address = "127.0.0.1", port = 22228,
                    username = "itermux_test", mode = "SHELL"), SessionMode.SHELL,
                    Credentials("test-only".toByteArray()), pins, scope).also { sessions += it }
            }
            scope.launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
            assertEquals(Phase.CONNECTED, withTimeout(10_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            return session
        }
        var previous: ClipData? = null
        try {
            val session = connect()
            var active by mutableStateOf(session)
            compose.setContent {
                MaterialTheme { TerminalPane(active, Modifier.fillMaxSize(), listOf(active), false,
                    {}, {}, {}, {}, {}, {}, {}) }
            }
            compose.waitForIdle()
            compose.runOnIdle { previous = clipboard.primaryClip }
            test(session) {
                connect().also { replacement -> compose.runOnIdle { active = replacement } }
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (previous != null) clipboard.setPrimaryClip(previous!!)
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                sessions.forEach { it.disconnect() }
            }
            scope.cancel()
        }
    }

    private fun clipboard(text: String) = compose.runOnIdle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Fixture", text))
    }

    private fun tripleTap() = compose.onNodeWithTag("terminal").performTouchInput { tripleClick() }

    private suspend fun received(session: TerminalConnection): ByteArray = withContext(Dispatchers.Main) {
        Regex("KEY_([0-9A-F]{2})(?=\\s|$)").findAll(session.emulator.screen.transcriptText)
            .map { it.groupValues[1].toInt(16).toByte() }.toList().toByteArray()
    }

    private suspend fun awaitBytes(session: TerminalConnection, expected: String) {
        val bytes = expected.toByteArray()
        withTimeout(8_000) { while (received(session).size < bytes.size) delay(25) }
        delay(150) // Detect a duplicated paste or an unintended Enter after the expected bytes.
        assertArrayEquals(bytes, received(session))
    }

    @Test fun tripleTapPastesUnicodeOnceWithoutEnterOrChangingDraft() = runBlocking {
        withSessions { session, _ ->
            compose.runOnIdle { session.draft = "keep this draft" }
            clipboard("粘贴🙂")
            tripleTap()
            awaitBytes(session, "粘贴🙂")
            assertEquals("keep this draft", session.draft)
            compose.onNodeWithTag("prompt-editor").assertDoesNotExist()
        }
    }

    @Test fun multilineRequiresConfirmationAndBracketedPasteKeepsItsDelimiters() = runBlocking {
        withSessions { session, _ ->
            clipboard("first\nsecond")
            tripleTap()
            compose.onNodeWithText(tr("发送多行文本？")).assertIsDisplayed()
            assertTrue(received(session).isEmpty())
            compose.onNodeWithText(tr("取消")).performClick()
            compose.runOnIdle {
                val enable = "\u001b[?2004h".toByteArray()
                session.emulator.append(enable, enable.size)
            }
            tripleTap()
            awaitBytes(session, "\u001b[200~first\rsecond\u001b[201~")
            compose.onNodeWithText(tr("发送多行文本？")).assertDoesNotExist()
        }
    }

    @Test fun confirmingMultilineSendsOnlyTheChosenText() = runBlocking {
        withSessions { session, _ ->
            clipboard("first\nsecond")
            tripleTap()
            compose.onNodeWithText(tr("发送")).performClick()
            awaitBytes(session, "first\rsecond")
        }
    }

    @Test fun reusedViewPastesIntoReplacementSessionAndDisconnectedTapIsIgnored() = runBlocking {
        withSessions { original, replace ->
            val replacement = replace()
            clipboard("replacement")
            tripleTap()
            awaitBytes(replacement, "replacement")
            assertTrue(received(original).isEmpty())
            compose.runOnIdle { replacement.disconnect() }
            compose.waitForIdle()
            clipboard("must-not-send")
            tripleTap()
            compose.runOnIdle { assertNull(replacement.inputWarning.value) }
            assertArrayEquals("replacement".toByteArray(), received(replacement))
        }
    }

    @Test fun emptyClipboardDoesNothing() = runBlocking {
        withSessions { session, _ ->
            clipboard("")
            tripleTap()
            assertTrue(received(session).isEmpty())
            compose.onNodeWithTag("prompt-editor").assertDoesNotExist()
        }
    }
}
