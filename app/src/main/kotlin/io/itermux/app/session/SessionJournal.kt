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
        val sessionNumber: Int = 0,
        /** A tmux session that was already on the server, which the app attached to and never ends. */
        val external: Boolean = false,
        /** What its agent reported last, for the session list to show until the host answers again. */
        val agentStatus: AgentStatus? = null
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
                    item.optInt("sessionNumber", 0).coerceAtLeast(0),
                    item.optBoolean("external", false),
                    item.optJSONObject("agentStatus")?.let(::decodeAgentStatus)
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
                    .put("external", session.external)
                    .put("agentStatus", session.lastAgentStatus.value?.let(::encodeAgentStatus))
            )
        }
        // apply updates memory immediately and Android flushes pending writes at lifecycle boundaries.
        preferences.edit { putString("sessions", array.toString()) }
    }
}

private fun encodeAgentStatus(status: AgentStatus) = JSONObject()
    .put("state", status.state.name).put("agent", status.agent).put("tool", status.tool).put("detail", status.detail)
    .put("task", status.task).put("elapsedMillis", status.elapsedMillis).put("directory", status.directory)
    .put("workedSeconds", status.workedSeconds).put("reply", status.reply)

private fun decodeAgentStatus(item: JSONObject): AgentStatus? = runCatching {
    AgentStatus(AgentStatus.State.valueOf(item.getString("state")), item.optString("agent"), item.optString("tool"),
        item.optString("detail"), item.optString("task"), item.optLong("elapsedMillis"), item.optString("directory"),
        if (item.has("workedSeconds")) item.getLong("workedSeconds") else null, item.optString("reply"))
}.getOrNull()

/** Agent sessions used to be titled "Claude Code · folder"; they now carry the short agent name. */
internal fun shortenLegacyAgentTitle(title: String): String =
    if (title.startsWith("Claude Code · ")) "Claude" + title.removePrefix("Claude Code") else title
