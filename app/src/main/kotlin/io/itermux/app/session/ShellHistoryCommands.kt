package io.itermux.app.session

/**
 * Pure helpers for shell history → ranked common-command shortcuts.
 *
 * Ranking: frequency descending, then most-recent occurrence (higher file index) as tie-break.
 */
object ShellHistoryCommands {
    const val DEFAULT_LIMIT = 200
    const val MAX_COMMAND_LENGTH = 500

    private val zshExtended = Regex("""^:\s*\d+:\d+;(.*)$""")

    fun extractCommands(raw: String, zshStyle: Boolean, skipFirstLine: Boolean = false): List<String> {
        val lines = raw.split('\n', '\r')
        val start = if (skipFirstLine && lines.isNotEmpty()) 1 else 0
        val out = ArrayList<String>(lines.size - start)
        for (index in start until lines.size) {
            val trimmed = lines[index].trimEnd()
            if (trimmed.isBlank()) continue
            val command = if (zshStyle) {
                zshExtended.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
            } else {
                trimmed
            }.trim()
            if (command.isBlank() || command.length > MAX_COMMAND_LENGTH) continue
            out.add(command)
        }
        return out
    }

    /** Frequency primary; recency (later in history file) breaks ties. */
    fun rankByFrequencyThenRecency(commands: List<String>, limit: Int = DEFAULT_LIMIT): List<String> {
        data class Acc(var count: Int = 0, var lastIndex: Int = 0)
        val map = LinkedHashMap<String, Acc>()
        commands.forEachIndexed { index, command ->
            val acc = map.getOrPut(command) { Acc() }
            acc.count++
            acc.lastIndex = index
        }
        return map.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Acc>> { it.value.count }
                    .thenByDescending { it.value.lastIndex }
            )
            .take(limit.coerceAtLeast(0))
            .map { it.key }
    }

    /** Case-insensitive contains, then subsequence fuzzy match. Empty query keeps all. */
    fun fuzzyFilter(commands: List<String>, query: String): List<String> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return commands
        return commands.filter { command ->
            val hay = command.lowercase()
            hay.contains(needle) || isSubsequence(hay, needle)
        }
    }

    private fun isSubsequence(haystack: String, needle: String): Boolean {
        var i = 0
        for (char in haystack) {
            if (char == needle[i]) {
                i++
                if (i == needle.length) return true
            }
        }
        return false
    }
}

sealed class CommonCommandsState {
    data object Idle : CommonCommandsState()
    data object Loading : CommonCommandsState()
    data class Ready(val commands: List<String>, val source: String) : CommonCommandsState()
    /** [reason] is a Chinese AppLocale key passed through [io.itermux.app.tr]. */
    data class Unavailable(val reason: String) : CommonCommandsState()
}
