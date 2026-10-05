package io.itermux.app.session

/** The visible subfolders of one remote directory, for picking where a terminal or agent starts. */
data class RemoteFolders(val home: String, val path: String, val folders: List<String>) {
    /** [path] as the user knows it: `~`, `~/work`, or an absolute path outside home. */
    val display: String get() = displayPath(path, home)

    fun child(name: String) = display.trimEnd('/').let { if (it.isEmpty()) "/$name" else "$it/$name" }

    /** The folder above, or null at `/`. */
    val parent: String? get() = if (path == "/") null else
        displayPath(path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }, home)

    companion object {
        const val LIMIT = 500

        /** Prints home, the resolved directory, then one visible subfolder per line; exits 3 when it cannot enter. */
        fun script(directory: String): String {
            val dir = StartDirectory.normalize(directory)
            val target = when {
                dir == StartDirectory.HOME -> "\"\$HOME\""
                dir.startsWith("~/") -> "\"\$HOME\"/" + shellQuote(dir.removePrefix("~/"))
                else -> shellQuote(dir)
            }
            return """
                cd -- $target 2>/dev/null || exit 3
                printf '%s\n' "${'$'}HOME"
                pwd
                n=0
                for f in *; do
                  [ -d "${'$'}f" ] || continue
                  printf '%s\n' "${'$'}f"
                  n=${'$'}((n + 1)); [ "${'$'}n" -ge $LIMIT ] && break
                done
                exit 0
            """.trimIndent()
        }

        fun parse(lines: List<String>): RemoteFolders? {
            if (lines.size < 2 || !lines[1].startsWith("/")) return null
            val folders = lines.drop(2).filter { it.isNotEmpty() && it != "*" }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            return RemoteFolders(lines[0].trimEnd('/').ifEmpty { "/" }, lines[1], folders)
        }

        fun displayPath(path: String, home: String): String = when {
            home.isEmpty() || home == "/" -> path
            path == home -> StartDirectory.HOME
            path.startsWith("$home/") -> "~/" + path.removePrefix("$home/")
            else -> path
        }
    }
}
