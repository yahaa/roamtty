package io.itermux.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.app.session.AgentStatus
import io.itermux.app.ui.AgentStatusLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentStatusLineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachPollStartsFromTheTimeItReports() {
        val status = mutableStateOf(AgentStatus(AgentStatus.State.WORKING, "codex", "", "", "", 5_000))
        compose.mainClock.autoAdvance = false
        compose.setContent { AgentStatusLine(status.value) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(" · " + trf("%d 秒", 5)).assertExists()
        // Polls every 3 s: the host's time counts, not how many polls came.
        repeat(6) { i ->
            compose.mainClock.advanceTimeBy(3_000)
            status.value = status.value.copy(elapsedMillis = 5_000L + (i + 1) * 3_000)
            compose.mainClock.advanceTimeByFrame()
        }
        compose.onNodeWithText(" · " + trf("%d 秒", 23)).assertExists()
        // Between polls the clock moves it on.
        compose.mainClock.advanceTimeBy(10_100)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(" · " + trf("%d 秒", 33)).assertExists()
    }
}
