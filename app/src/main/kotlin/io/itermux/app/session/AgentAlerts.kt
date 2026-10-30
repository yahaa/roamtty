package io.itermux.app.session

import io.itermux.app.trf

/**
 * When a session's agent is worth a system notification, read from the reports [TerminalConnection.lastAgentStatus]
 * gives: it waits for the user, or it finished a turn that worked at least [MIN_DONE_SECONDS]. Each session has at most
 * one; it goes once the agent works again, or the request it showed is gone.
 */
object AgentAlerts {
    enum class Kind { WAITING, DONE }

    /** What a session's notification does on a new report. */
    sealed interface Change {
        data class Show(val kind: Kind) : Change
        data object Withdraw : Change
        data object Keep : Change
    }

    /** Turns shorter than this end without a notification: the user is likely still looking. */
    const val MIN_DONE_SECONDS = 60L

    /** The change [current] calls for after [previous], with [shown] the notification the session has now. */
    fun next(previous: AgentStatus?, current: AgentStatus?, shown: Kind?): Change = when (current?.state) {
        AgentStatus.State.WAITING ->
            if (previous?.state != AgentStatus.State.WAITING || previous.tool != current.tool || previous.detail != current.detail)
                Change.Show(Kind.WAITING) else Change.Keep
        AgentStatus.State.WORKING -> if (shown != null) Change.Withdraw else Change.Keep
        AgentStatus.State.IDLE -> when {
            (current.workedSeconds ?: 0) >= MIN_DONE_SECONDS && !sameTurn(previous, current) -> Change.Show(Kind.DONE)
            shown == Kind.WAITING -> Change.Withdraw
            else -> Change.Keep
        }
        // The agent left; what it finished still stands, a request it made does not.
        null -> if (shown == Kind.WAITING) Change.Withdraw else Change.Keep
    }

    /** A report of the turn [previous] already told of: polls repeat it until the next one. */
    private fun sameTurn(previous: AgentStatus?, current: AgentStatus) = previous?.state == AgentStatus.State.IDLE &&
        previous.task == current.task && previous.workedSeconds == current.workedSeconds && previous.reply == current.reply

    /** "Claude 等你确认", "Codex 已完成"; [agent] null for one this app does not know. */
    fun title(kind: Kind, agent: CodingAgent?): String {
        val name = agent?.shortLabel ?: "Agent"
        return when (kind) {
            Kind.WAITING -> trf("%s 等你确认", name)
            Kind.DONE -> trf("%s 已完成", name)
        }
    }

    /** One line: the host, then what the agent asks to run, or how long it worked and the start of its answer. */
    fun text(kind: Kind, host: String, status: AgentStatus): String = when (kind) {
        Kind.WAITING -> listOf(host, status.detail.ifEmpty { status.tool })
        Kind.DONE -> listOf(host, status.workedSeconds?.let(::duration).orEmpty(), status.reply)
    }.filter { it.isNotBlank() }.joinToString(" · ")
}

/** A length of time in words: "45 秒", "12 分钟", "1 小时 5 分钟". */
internal fun duration(seconds: Long): String = when {
    seconds < 60 -> trf("%d 秒", seconds)
    seconds < 3600 -> trf("%d 分钟", seconds / 60)
    else -> trf("%d 小时 %d 分钟", seconds / 3600, seconds % 3600 / 60)
}

