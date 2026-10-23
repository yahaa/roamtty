package io.itermux.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** User navigation order, independent of background connections and metadata updates. Accessed on Main. */
internal class RecentUsage(private val preferences: SharedPreferences) {
    private val mutableHosts = MutableStateFlow(read("hosts"))
    private val mutableSessions = MutableStateFlow(read("sessions"))
    val hostIds = mutableHosts.asStateFlow()
    val sessionIds = mutableSessions.asStateFlow()

    fun useHost(id: String) = update("hosts", mutableHosts, listOf(id) + mutableHosts.value.filterNot { it == id })

    fun useSession(id: String, hostId: String) {
        update("sessions", mutableSessions, listOf(id) + mutableSessions.value.filterNot { it == id })
        useHost(hostId)
    }

    /** Call only after both the hosts and session journal have loaded. */
    fun retain(hostIds: Set<String>, sessionIds: Set<String>) {
        update("hosts", mutableHosts, mutableHosts.value.filter { it in hostIds })
        update("sessions", mutableSessions, mutableSessions.value.filter { it in sessionIds })
    }

    private fun read(key: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).map { array.getString(it) }.distinct()
    }.getOrDefault(emptyList())

    private fun update(key: String, state: MutableStateFlow<List<String>>, ids: List<String>) {
        if (state.value == ids) return
        preferences.edit { putString(key, JSONArray(ids).toString()) }
        state.value = ids
    }
}

/** Unused entries keep their input order; the stored order may include IDs absent from this particular list. */
internal fun <T> List<T>.recentFirst(recentIds: List<String>, id: (T) -> String): List<T> {
    val rank = recentIds.withIndex().associate { it.value to it.index }
    return sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
}
