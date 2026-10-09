package io.itermux.app.session

import io.itermux.ssh.RemoteHost

/** The visible subfolders of one remote directory, for picking where a terminal or agent starts. */
data class RemoteFolders(val home: String, val path: String, val folders: List<String>) {
    /** [path] as the user knows it: `~`, `~/work`, or an absolute path outside home. */
    val display: String get() = displayPath(path, home)

    fun child(name: String): String {
        require(isSupportedName(name) && (!WindowsShell.isPath(path) || '\\' !in name)) { "无效的文件夹名称" }
        require(StartDirectory.isSupported(home) && StartDirectory.isSupported(path)) { StartDirectory.UNSUPPORTED_MESSAGE }
        return display.trimEnd('/').let { if (it.isEmpty()) "/$name" else "$it/$name" }
    }

    /** The folder above, or null at `/`. */
    val parent: String? get() = when {
        WindowsShell.isPath(path) -> if (path == WindowsShell.root(path)) null else WindowsShell.normalize(path.substringBeforeLast('/') + "/")
        path == "/" -> null
        else -> path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
    }?.let { displayPath(it, home) }

    /** Native Windows home-relative paths must be resolved before becoming terminal input. */
    fun startDirectory(requested: String): String = if (WindowsShell.isPath(path) && !StartDirectory.isHome(requested)) path else requested

    companion object {
        const val LIMIT = 500
        private const val TIMEOUT_MS = 10_000L

        /** Prefer the existing POSIX shell (including MSYS/Cygwin), then try native Windows. */
        suspend fun load(remote: RemoteHost, directory: String): RemoteFolders? {
            if (!WindowsShell.isPath(directory)) {
                val lines = mutableListOf<String>()
                // A timeout means the login is gone: don't start another request on it.
                when (remote.run(script(directory), TIMEOUT_MS) { lines += it }) {
                    null -> return null
                    0 -> parse(lines)?.let { return it }
                }
            }
            val lines = mutableListOf<String>()
            return if (remote.runCommand(WindowsShell.command(windowsScript(directory)), TIMEOUT_MS) { lines += it } == 0) parse(lines) else null
        }

        internal fun windowsScript(directory: String): String {
            val dir = StartDirectory.normalize(directory)
            val target = when {
                dir == StartDirectory.HOME -> "\$HOME"
                dir.startsWith("~/") -> "([IO.Path]::Combine(\$HOME, ${WindowsShell.literal(dir.removePrefix("~/"))}))"
                else -> WindowsShell.literal(dir)
            }
            return """
                ${'$'}ErrorActionPreference = 'Stop'
                [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding
                try {
                    Set-Location -LiteralPath $target
                    ${'$'}path = (Get-Location).ProviderPath
                    if (${'$'}HOME -match '[\x00-\x1f\x7f-\x9f]' -or ${'$'}path -match '[\x00-\x1f\x7f-\x9f]') { exit 3 }
                    [Console]::WriteLine(${'$'}HOME)
                    [Console]::WriteLine(${'$'}path)
                    Get-ChildItem -LiteralPath . -Directory | Where-Object {
                        -not ${'$'}_.Name.StartsWith('.') -and ${'$'}_.Name -notmatch '[\x00-\x1f\x7f-\x9f]'
                    } | Select-Object -First $LIMIT | ForEach-Object { [Console]::WriteLine(${'$'}_.Name) }
                    exit 0
                } catch { exit 3 }
            """.trimIndent()
        }

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
                control_pattern=${'$'}(printf '*[\001-\037\177]*')
                safe_path() {
                  case "${'$'}1" in ${'$'}control_pattern) return 1 ;; esac
                }
                safe_path "${'$'}HOME" || exit 3
                path=${'$'}(pwd -P && printf '.') || exit 3
                path=${'$'}{path%.}
                path=${'$'}{path%?}
                safe_path "${'$'}path" || exit 3
                printf '%s\n' "${'$'}HOME" "${'$'}path"
                n=0
                for f in *; do
                  [ -d "${'$'}f" ] || continue
                  safe_path "${'$'}f" || continue
                  printf '%s\n' "${'$'}f"
                  n=${'$'}((n + 1)); [ "${'$'}n" -ge $LIMIT ] && break
                done
                exit 0
            """.trimIndent()
        }

        fun parse(lines: List<String>): RemoteFolders? {
            if (lines.size < 2 || lines.take(2).any { (!it.startsWith("/") && !WindowsShell.isPath(it)) || !StartDirectory.isSupported(it) }) return null
            val windows = WindowsShell.isPath(lines[1])
            val folders = lines.drop(2).filter { it != "*" && isSupportedName(it) && (!windows || '\\' !in it) }
                .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            fun path(value: String) = if (WindowsShell.isPath(value)) WindowsShell.normalize(value) else value.trimEnd('/').ifEmpty { "/" }
            return RemoteFolders(path(lines[0]), path(lines[1]), folders)
        }

        private fun isSupportedName(name: String) = name.isNotEmpty() && name != "." && name != ".." &&
            '/' !in name && StartDirectory.isSupported(name)

        fun displayPath(path: String, home: String): String = when {
            home.isEmpty() || home == "/" -> path
            path.equals(home, ignoreCase = WindowsShell.isPath(home)) -> StartDirectory.HOME
            path.startsWith("${home.trimEnd('/')}/", ignoreCase = WindowsShell.isPath(home)) -> "~/" + path.substring(home.trimEnd('/').length + 1)
            else -> path
        }
    }
}
