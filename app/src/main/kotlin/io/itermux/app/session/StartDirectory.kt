package io.itermux.app.session

/** Where a terminal opened from the host list starts. Blank or `~` means the login directory, which needs no command. */
object StartDirectory {
    const val HOME = "~"
    private const val RECENT_LIMIT = 6

    fun normalize(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.isEmpty() || trimmed == "~/") HOME else if (trimmed.length > 1) trimmed.trimEnd('/') else trimmed
    }

    fun isHome(directory: String) = normalize(directory) == HOME

    /** Typed into the new shell; `~/` stays outside the quotes so the shell still expands it. */
    fun command(directory: String): String? {
        val dir = normalize(directory)
        return when {
            dir == HOME -> null
            dir.startsWith("~/") -> "cd ~/${shellQuote(dir.removePrefix("~/"))}"
            else -> "cd ${shellQuote(dir)}"
        }
    }

    /** Each folder on the way to [directory] as (name, path), from `~` or `/`, so any of them is one tap away. */
    fun segments(directory: String): List<Pair<String, String>> {
        val dir = normalize(directory)
        val (root, rest) = when {
            dir == HOME || dir.startsWith("~/") -> HOME to dir.removePrefix(HOME)
            dir.startsWith("/") -> "/" to dir
            else -> return listOf(dir to dir)
        }
        var path = root
        return listOf(root to root) + rest.split('/').filter { it.isNotEmpty() }.map { name ->
            path = if (path.endsWith('/')) "$path$name" else "$path/$name"
            name to path
        }
    }

    /** [directory] first, without duplicates; home is always offered separately, so it is never stored. */
    fun remember(recent: List<String>, directory: String): List<String> {
        val dir = normalize(directory)
        if (dir == HOME) return recent
        return (listOf(dir) + recent.filter { it != dir }).take(RECENT_LIMIT)
    }
}
