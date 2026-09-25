package io.itermux.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection
import io.itermux.app.ui.FileAction
import io.itermux.app.ui.RemotePathDropdown
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadDirectoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun browsingIntoAnotherDirectoryUpdatesTheUploadTarget() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pins = object : PinStore {
            private var accepted: HostPin? = null
            override suspend fun get(endpoint: String) = accepted
            override suspend fun putIfAbsent(pin: HostPin) = accepted ?: pin.also { accepted = it }
        }
        val session = TerminalConnection(Host(name = "fixture", address = "127.0.0.1", port = 22228,
            username = "itermux_test", mode = "SHELL"), SessionMode.SHELL,
            Credentials("test-only".toByteArray()), pins, scope)
        val prompts = scope.launch {
            session.pending.collect { pending ->
                if (pending?.prompt is Prompt.VerifyHost) pending.answer.complete(listOf("trust"))
            }
        }
        try {
            assertEquals(Phase.CONNECTED,
                withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            var selected by mutableStateOf("sample.bin")
            compose.setContent {
                MaterialTheme {
                    Column {
                        RemotePathDropdown(session, FileAction.UPLOAD, "sample.bin") { selected = it }
                        Text(selected, Modifier.testTag("selected-upload-path"))
                    }
                }
            }
            compose.onNodeWithTag("remote-path-dropdown").performClick()
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("remote-entry-phone-a").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("remote-entry-phone-a").performClick()
            compose.onNodeWithTag("selected-upload-path").assertTextContains("/phone-a/sample.bin")
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("remote-parent-directory").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("remote-parent-directory").performClick()
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("remote-entry-phone-b").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("remote-entry-phone-b").performClick()
            compose.onNodeWithTag("selected-upload-path").assertTextContains("/phone-b/sample.bin")
        } finally {
            prompts.cancel()
            session.disconnect()
            scope.cancel()
        }
        Unit
    }
}
