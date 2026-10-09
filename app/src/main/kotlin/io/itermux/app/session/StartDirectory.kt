package io.itermux.app.session

/** Where a terminal opened from the host list starts. Blank or `~` means the login directory, which needs no command. */
object StartDirectory {
    const val HOME = "~"
    const val UNSUPPORTED_MESSAGE = "文件夹路径含有不支持的控制字符"
    private const val RECENT_LIMIT = 6

    /** Paths become interactive terminal input; shell quoting cannot protect terminal control bytes. */
    fun isSupported(directory: String): Boolean = directory.none { Character.isISOControl(it) }

    fun normalize(input: String): String {
        require(isSupported(input)) { UNSUPPORTED_MESSAGE }
        val trimmed = input.trim()
        if (WindowsShell.isPath(trimmed)) return WindowsShell.normalize(trimmed)
        return if (trimmed.isEmpty() || trimmed == "~/") HOME else if (trimmed.length > 1) trimmed.trimEnd('/') else trimmed
    }

    fun isHome(directory: String) = isSupported(directory) && normalize(directory) == HOME

    /** Typed into the new shell; `~/` stays outside the quotes so the shell still expands it. */
    fun command(directory: String): String? {
        val dir = normalize(directory)
        return when {
            dir == HOME -> null
            WindowsShell.isPath(dir) -> WindowsShell.launch(dir)
            dir.startsWith("~/") -> "cd -- ~/${shellQuote(dir.removePrefix("~/"))}"
            else -> "cd -- ${shellQuote(dir)}"
        }
    }

    /** Each folder on the way to [directory] as (name, path), from `~` or `/`, so any of them is one tap away. */
    fun segments(directory: String): List<Pair<String, String>> {
        if (!isSupported(directory)) return emptyList()
        val dir = normalize(directory)
        val (root, rest) = when {
            dir == HOME || dir.startsWith("~/") -> HOME to dir.removePrefix(HOME)
            WindowsShell.isPath(dir) -> WindowsShell.root(dir).let { it to dir.removePrefix(it) }
            dir.startsWith("/") -> "/" to dir
            else -> return listOf(dir to dir)
        }
        var path = root
        return listOf(root.removeSuffix("/").ifEmpty { "/" } to root) + rest.split('/').filter { it.isNotEmpty() }.map { name ->
            path = if (path.endsWith('/')) "$path$name" else "$path/$name"
            name to path
        }
    }

    /** [directory] first, without duplicates; home is always offered separately, so it is never stored. */
    fun remember(recent: List<String>, directory: String): List<String> {
        val dir = normalize(directory)
        val safe = sanitizeRecent(recent)
        if (dir == HOME) return safe
        return (listOf(dir) + safe.filter { it != dir }).take(RECENT_LIMIT)
    }

    fun sanitizeRecent(recent: List<String>): List<String> = recent.filter(::isSupported)
        .map(::normalize).filter { it != HOME }.distinct().take(RECENT_LIMIT)
}
