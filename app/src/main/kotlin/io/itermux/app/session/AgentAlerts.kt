package io.itermux.app.session

import android.content.Context
import androidx.core.content.edit
import io.itermux.ssh.AgentEvent

/**
 * Turns `events.jsonl` lines into reminder actions: at most one reminder per session, withdrawn
 * when the CLI moves on or the user opens the session. Holds only memory, never the event text
 * after it is shown. Every call comes from the main thread.
 */
class AgentAlerts(private val now: () -> Long = System::currentTimeMillis) {
    /** A session that can own a reminder; [tmuxName] is its remote tmux session, if any. */
    data class Candidate(val sessionId: String, val tmuxName: String?)

    sealed interface Action {
        val sessionId: String
        /** Shows or replaces the session's reminder; [alert] is false for a quiet update. */
        data class Show(override val sessionId: String, val event: AgentEvent, val alert: Boolean) : Action
        data class Cancel(override val sessionId: String) : Action
    }

    private val seen = mutableMapOf<String, LinkedHashSet<String>>()
    /** "host key" → the session whose reminder the key raised. */
    private val owners = mutableMapOf<String, String>()
    /** Session → the key its reminder shows. */
    private val shown = mutableMapOf<String, String>()
    private val lastAlert = mutableMapOf<String, Long>()

    /**
     * [candidates] are the host's live sessions, the preferred owner first for events from outside a
     * Roamtty tmux session. [viewing] is the session on screen while the app is in the foreground.
     */
    fun onEvent(hostId: String, event: AgentEvent, candidates: List<Candidate>, viewing: String?): Action? {
        val ids = seen.getOrPut(hostId) { LinkedHashSet() }
        if (!ids.add(event.id)) return null
        if (ids.size > MAX_SEEN) ids.remove(ids.first())
        val ownerKey = "$hostId ${event.key}"
        if (event.kind == AgentEvent.RESOLVED) {
            val sessionId = owners.remove(ownerKey) ?: return null
            if (shown[sessionId] != event.key) return null
            shown.remove(sessionId)
            return Action.Cancel(sessionId)
        }
        // A reminder replayed after a reconnect is only worth showing while it may still be waiting.
        if (event.ts > 0 && now() - event.ts * 1000 > REPLAY_WINDOW_MS) return null
        val sessionId = owner(event, candidates)?.sessionId ?: return null
        owners[ownerKey] = sessionId
        if (sessionId == viewing) return null
        shown[sessionId] = event.key
        val time = now()
        val alert = time - (lastAlert[sessionId] ?: Long.MIN_VALUE / 2) >= THROTTLE_MS
        if (alert) lastAlert[sessionId] = time
        return Action.Show(sessionId, event, alert)
    }

    /** The user opened [sessionId]: its reminder has done its job. */
    fun onViewed(sessionId: String): Action? = shown.remove(sessionId)?.let { Action.Cancel(sessionId) }

    /** The session is gone; nothing it raised is pending any more. */
    fun forget(sessionId: String): Action? {
        owners.values.removeAll { it == sessionId }
        lastAlert.remove(sessionId)
        return onViewed(sessionId)
    }

    private fun owner(event: AgentEvent, candidates: List<Candidate>): Candidate? =
        if (event.tmux.isNotEmpty()) candidates.firstOrNull { it.tmuxName == event.tmux } ?: candidates.firstOrNull()
        else candidates.firstOrNull()

    companion object {
        const val THROTTLE_MS = 5_000L
        const val REPLAY_WINDOW_MS = 10 * 60_000L
        private const val MAX_SEEN = 500
    }
}

/** The "AI 交互提醒" switch in settings; on by default. */
object AgentAlertSettings {
    private const val KEY = "agent-alerts"
    private fun prefs(context: Context) = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, true)
    fun set(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY, enabled) }
}
