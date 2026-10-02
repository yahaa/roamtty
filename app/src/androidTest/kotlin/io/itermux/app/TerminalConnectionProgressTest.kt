package io.itermux.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.itermux.app.ui.TerminalConnectionProgress
import io.itermux.ssh.Phase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalConnectionProgressTest {
    @get:Rule val compose = createComposeRule()

    @Test fun busyPhasesShowAccessibleProgressAndCancellationThenDisappear() {
        var phase by mutableStateOf(Phase.CONNECTING)
        var cancelled = false
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            TerminalConnectionProgress(phase, { cancelled = true }, Modifier.align(Alignment.Center))
        } } }
        for (busy in listOf(Phase.CONNECTING, Phase.VERIFYING, Phase.AUTHENTICATING)) {
            compose.runOnIdle { phase = busy }
            compose.onNodeWithTag("terminal-connection-progress").assertIsDisplayed()
            compose.onNodeWithTag("terminal-connection-motion").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        }
        compose.onNodeWithTag("terminal-connection-cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        for (finished in Phase.entries.filterNot { it in listOf(Phase.CONNECTING, Phase.VERIFYING, Phase.AUTHENTICATING) }) {
            compose.runOnIdle { phase = finished }
            compose.onNodeWithTag("terminal-connection-progress").assertDoesNotExist()
        }
        compose.runOnIdle { phase = Phase.CONNECTING }
        compose.onNodeWithTag("terminal-connection-progress").assertIsDisplayed()
    }

    @Test fun progressActuallyAnimates() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { TerminalConnectionProgress(Phase.CONNECTING, {}) } }
        compose.mainClock.advanceTimeBy(100)
        val first = compose.onNodeWithTag("terminal-connection-motion").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(400)
        val second = compose.onNodeWithTag("terminal-connection-motion").captureToImage().asAndroidBitmap()
        assertFalse("Connection progress must animate", first.sameAs(second))
    }
}
