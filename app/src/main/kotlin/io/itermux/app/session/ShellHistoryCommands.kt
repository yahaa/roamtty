package io.itermux.app.session

/**
 * Pure helpers for shell history → ranked common-command shortcuts.
 *
 * Ranking: most-recent occurrence first, with duplicate commands removed.
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
            if (!zshStyle && command.matches(Regex("^#\\d+$"))) continue
            if (command.any { it.isISOControl() && it != '\t' }) continue
            out.add(command)
        }
        return out
    }

    fun rankByRecency(commands: List<String>, limit: Int = DEFAULT_LIMIT): List<String> =
        commands.asReversed().distinct().take(limit.coerceAtLeast(0))

    /** Local selections precede disk history, which shells may only flush on exit. */
    fun mergeRecent(recent: List<String>, saved: List<String>): List<String> =
        (recent + saved).distinct().take(DEFAULT_LIMIT)

    /** Submit Enter outside the paste delimiters, in the same SSH queue entry. */
    fun executionInput(command: String, bracketed: Boolean): String {
        require(command.isNotBlank() && command.length <= MAX_COMMAND_LENGTH)
        require(command.none { it.isISOControl() && it != '\t' })
        return (if (bracketed) "\u001b[200~$command\u001b[201~" else command) + "\r"
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
