package io.itermux.ssh

/**
 * A TCP port something on the server listens on: the listening process's command line and folder when the login may
 * see them ([pid] null otherwise: another user's process, root's or a container's).
 */
data class ListeningPort(val port: Int, val address: String, val pid: Int?, val command: String, val directory: String) {
    /** The page a tap opens: the port as the server reaches it. */
    val url: LoopbackUrl get() = LoopbackUrl(
        when {
            isWildcard(address) -> "localhost"
            ':' in address -> "[$address]"
            else -> address
        }, port, "/")

    /**
     * [command] as a person reads it: programs and scripts by their file name, without their folders. A path may hold
     * spaces (`/Applications/Visual Studio Code.app/…`): it runs on to the last word with a slash before an option.
     */
    val shortCommand: String get() {
        val words = command.split(' ').filter { it.isNotEmpty() }
        val shown = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            if (!words[i].startsWith('/')) { shown += words[i++]; continue }
            var end = i
            for (k in i + 1 until words.size) {
                if (words[k].startsWith('-') || words[k].startsWith('/')) break
                if ('/' in words[k]) end = k
            }
            val path = words.subList(i, end + 1).joinToString(" ")
            shown += if (path.endsWith('/')) path else path.substringAfterLast('/')
            i = end + 1
        }
        return shown.joinToString(" ")
    }

    companion object {
        /**
         * POSIX sh: `HOME\t<home>` then one `PORT\t<address:port>\t<pid>\t<cwd>\t<command line>` line per listening
         * socket. Linux reads `ss` and /proc; macOS reads `lsof` and `ps`, which list only the login's own processes.
         */
        val SCRIPT = """
            set -f
            printf 'HOME\t%s\n' "${'$'}HOME"
            emit() {
              cmd=; cwd=
              if [ -n "${'$'}2" ]; then
                if [ -r "/proc/${'$'}2/cmdline" ]; then
                  cmd=${'$'}(tr '\000\t\n' '   ' < "/proc/${'$'}2/cmdline" 2>/dev/null)
                  cwd=${'$'}(readlink "/proc/${'$'}2/cwd" 2>/dev/null)
                else
                  cmd=${'$'}(ps -o command= -p "${'$'}2" 2>/dev/null | tr '\t\n' '  ')
                  cwd=${'$'}(lsof -a -p "${'$'}2" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)
                fi
              fi
              printf 'PORT\t%s\t%s\t%s\t%s\n' "${'$'}1" "${'$'}2" "${'$'}cwd" "${'$'}cmd"
            }
            if command -v ss >/dev/null 2>&1; then
              ss -ltnp 2>/dev/null | while IFS= read -r line; do
                case ${'$'}line in State*|'') continue ;; esac
                set -- ${'$'}line
                emit "${'$'}4" "${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')"
              done
            elif command -v lsof >/dev/null 2>&1; then
              lsof -nP -iTCP -sTCP:LISTEN -Fpn 2>/dev/null | while IFS= read -r line; do
                case ${'$'}line in p*) pid=${'$'}{line#p} ;; n*) emit "${'$'}{line#n}" "${'$'}pid" ;; esac
              done
            else
              exit 3
            fi
        """.trimIndent()

        /**
         * [SCRIPT]'s output, one entry per port: the login's own processes first, then the rest, each by port. Folders
         * under the home show as `~/…`. Ports from [EPHEMERAL_PORTS] up are left out: editors and agents hold dozens
         * there for their own use, and dev servers pick fixed, lower ones.
         */
        fun parse(lines: List<String>): List<ListeningPort> {
            val home = lines.firstOrNull { it.startsWith("HOME\t") }?.substringAfter('\t')?.trimEnd('/').orEmpty()
            val sockets = lines.mapNotNull { line ->
                val fields = line.split('\t', limit = 5)
                if (fields.size < 5 || fields[0] != "PORT") return@mapNotNull null
                val local = fields[1]
                val port = local.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1 until EPHEMERAL_PORTS } ?: return@mapNotNull null
                val address = local.substringBeforeLast(':').removePrefix("[").removeSuffix("]").substringBefore('%')
                val directory = fields[3].let { if (home.isNotEmpty() && (it == home || it.startsWith("$home/"))) "~" + it.removePrefix(home) else it }
                ListeningPort(port, address, fields[2].toIntOrNull(), fields[4].trim(), directory)
            }
            return sockets.groupBy { it.port }.map { (_, same) ->
                val known = same.firstOrNull { it.pid != null } ?: same.first()
                // One reachable from the server's loopback, the way the page will be asked for.
                val address = same.firstOrNull { isWildcard(it.address) }?.address
                    ?: same.map { it.address }.firstOrNull { it.startsWith("127.") }
                    ?: same.map { it.address }.firstOrNull { it == "::1" }
                    ?: known.address
                known.copy(address = address)
            }.sortedWith(compareBy({ it.pid == null }, { it.port }))
        }

        /** Where Linux starts handing out ports for outgoing and unnamed sockets (macOS: 49152). */
        const val EPHEMERAL_PORTS = 32768

        private fun isWildcard(address: String) = address == "*" || address == "0.0.0.0" || address == "::" || address.isEmpty()
    }
}
