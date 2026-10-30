package io.itermux.app.session

import io.itermux.app.session.AgentAlerts.Change
import io.itermux.app.session.AgentAlerts.Kind
import io.itermux.app.session.AgentStatus.State
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentAlertsTest {
    private fun status(state: State, tool: String = "", detail: String = "", task: String = "fix the bug",
        worked: Long? = null, reply: String = "") = AgentStatus(state, "claude", tool, detail, task, 0, "/srv", worked, reply)

    private val working = status(State.WORKING, "Bash", "npm test")
    private val waiting = status(State.WAITING, "Bash", "./gradlew lint")
    private val done = status(State.IDLE, worked = 720, reply = "All 48 passed")

    @Test fun aRequestForPermissionIsShown() {
        assertEquals(Change.Show(Kind.WAITING), AgentAlerts.next(working, waiting, null))
        assertEquals(Change.Show(Kind.WAITING), AgentAlerts.next(null, waiting, null))
        // The same request polled again is not shown twice; the next one is.
        assertEquals(Change.Keep, AgentAlerts.next(waiting, waiting, Kind.WAITING))
        assertEquals(Change.Show(Kind.WAITING), AgentAlerts.next(waiting, waiting.copy(detail = "git push"), Kind.WAITING))
    }

    @Test fun workingAgainWithdrawsEitherNotification() {
        assertEquals(Change.Withdraw, AgentAlerts.next(waiting, working, Kind.WAITING))
        assertEquals(Change.Withdraw, AgentAlerts.next(done, working, Kind.DONE))
        assertEquals(Change.Keep, AgentAlerts.next(null, working, null))
    }

    @Test fun aLongTurnThatEndsIsShown() {
        assertEquals(Change.Show(Kind.DONE), AgentAlerts.next(working, done, null))
        // It replaces a request answered on another screen.
        assertEquals(Change.Show(Kind.DONE), AgentAlerts.next(waiting, done, Kind.WAITING))
        // A whole turn that ran while the phone was away still counts.
        assertEquals(Change.Show(Kind.DONE), AgentAlerts.next(done, done.copy(task = "next", workedSeconds = 300), null))
    }

    @Test fun aShortTurnOrTheSameTurnAgainIsNot() {
        val short = done.copy(workedSeconds = AgentAlerts.MIN_DONE_SECONDS - 1)
        assertEquals(Change.Keep, AgentAlerts.next(working, short, null))
        assertEquals(Change.Withdraw, AgentAlerts.next(waiting, short, Kind.WAITING))
        // Only the folder moved.
        assertEquals(Change.Keep, AgentAlerts.next(done, done.copy(directory = "/tmp"), Kind.DONE))
    }

    @Test fun anAgentThatLeftWithdrawsOnlyARequest() {
        assertEquals(Change.Withdraw, AgentAlerts.next(waiting, null, Kind.WAITING))
        assertEquals(Change.Keep, AgentAlerts.next(done, null, Kind.DONE))
    }

    @Test fun theNotificationSaysWhoWhatAndWhere() {
        assertEquals("Claude 等你确认", AgentAlerts.title(Kind.WAITING, CodingAgent.CLAUDE))
        assertEquals("Agent 已完成", AgentAlerts.title(Kind.DONE, null))
        assertEquals("dev · ./gradlew lint", AgentAlerts.text(Kind.WAITING, "dev", waiting))
        assertEquals("dev · AskUserQuestion", AgentAlerts.text(Kind.WAITING, "dev", status(State.WAITING, "AskUserQuestion")))
        assertEquals("macbook · 12 分钟 · All 48 passed", AgentAlerts.text(Kind.DONE, "macbook", done))
        assertEquals("macbook · 1 小时 5 分钟", AgentAlerts.text(Kind.DONE, "macbook", done.copy(workedSeconds = 3900, reply = "")))
    }
}
