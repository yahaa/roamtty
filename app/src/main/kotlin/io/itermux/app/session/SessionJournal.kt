package io.itermux.app.session

import android.content.Context
import androidx.core.content.edit
import io.itermux.ssh.SessionMode
import io.itermux.ssh.Tmux
import org.json.JSONArray
import org.json.JSONObject

/** Only references and user titles survive process death; never credentials, output or input. */
internal class SessionJournal(context: Context) {
    private val preferences = context.getSharedPreferences("session-journal", Context.MODE_PRIVATE)
    data class Entry(
        val id: String,
        val hostId: String,
        val mode: SessionMode,
        val title: String,
        /** Remote tmux session name; legacy entries without the field keep [Tmux.LEGACY_SESSION]. */
        val remoteSessionName: String = Tmux.LEGACY_SESSION,
        val sessionNumber: Int = 0
    )

    fun read(): List<Entry> = runCatching {
        val array = JSONArray(preferences.getString("sessions", "[]"))
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val remote = item.optString("remoteSessionName").ifBlank { Tmux.LEGACY_SESSION }
                Entry(
                    item.getString("id"),
                    item.getString("hostId"),
                    SessionMode.valueOf(item.getString("mode")),
                    clipSessionTitle(shortenLegacyAgentTitle(item.optString("title"))),
                    Tmux.sanitizeSessionName(remote),
                    item.optInt("sessionNumber", 0).coerceAtLeast(0)
                )
            }.getOrNull()
        }.distinctBy { it.id }.let { entries ->
            // Keeps list order while capping each host at the live per-host limit.
            val perHost = HashMap<String, Int>()
            entries.filter { perHost.merge(it.hostId, 1, Int::plus)!! <= SessionService.MAX_SESSIONS_PER_HOST }
        }
    }.getOrDefault(emptyList())

    fun save(sessions: List<TerminalConnection>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("hostId", session.host.id)
                    .put("mode", session.mode.name)
                    .put("title", session.title.value)
                    .put("remoteSessionName", session.remoteSessionName)
                    .put("sessionNumber", session.sessionNumber)
            )
        }
        // apply updates memory immediately and Android flushes pending writes at lifecycle boundaries.
        preferences.edit { putString("sessions", array.toString()) }
    }
}

/** Agent sessions used to be titled "Claude Code · folder"; they now carry the short agent name. */
internal fun shortenLegacyAgentTitle(title: String): String =
    if (title.startsWith("Claude Code · ")) "Claude" + title.removePrefix("Claude Code") else title
