package io.itermux.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Exact names rank first, followed by prefixes, substrings, and ordered abbreviations. */
fun remoteNameMatchRank(name: String, query: String): Int? {
    val value = name.lowercase(Locale.ROOT)
    val needle = query.lowercase(Locale.ROOT)
    if (needle.isEmpty() || value == needle) return 0
    if (value.startsWith(needle)) return 1
    if (value.contains(needle)) return 2
    var next = 0
    for (char in needle) {
        next = value.indexOf(char, next)
        if (next < 0) return null
        next++
    }
    return 3
}

data class RemoteSearchDirectories(
    val basePath: String,
    val directories: List<RemoteDirectory>,
    val limited: Boolean = false,
    val unreadable: Boolean = false
)

/** Resolve only the requested depth, with a bound on remote reads even for broad abbreviations. */
suspend fun resolveRemoteSearchDirectories(
    directory: String,
    prefix: String,
    showHidden: Boolean,
    maxDirectories: Int = 32,
    listDirectory: suspend (String) -> RemoteDirectory
): RemoteSearchDirectories {
    require(maxDirectories > 0)
    val root = when {
        prefix.startsWith('/') -> "/"
        prefix.startsWith("~/") -> ""
        else -> directory
    }
    val base = listDirectory(root)
    val parts = prefix.removePrefix("~/").split('/').filter { it.isNotEmpty() && it != "." }
    var directories = listOf(base)
    var reads = 1
    var limited = false
    var unreadable = false
    for (part in parts) {
        currentCoroutineContext().ensureActive()
        val paths = if (part == "..") directories.map { parentRemotePath(it.path) }.distinct()
        else directories.flatMap { parent ->
            parent.entries.filter { it.isDirectory && (showHidden || !it.name.startsWith('.')) }
                .mapNotNull { entry -> remoteNameMatchRank(entry.name, part)?.let { rank ->
                    Triple(joinRemotePath(parent.path, entry.name), entry.name, rank)
                } }
        }.sortedWith(compareBy<Triple<String, String, Int>> { it.third }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.second })
            .map { it.first }.distinct()
        val next = mutableListOf<RemoteDirectory>()
        for (path in paths) {
            if (reads >= maxDirectories) { limited = true; break }
            reads++
            try { next += listDirectory(path) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { unreadable = true }
        }
        directories = next.distinctBy { it.path }
        if (directories.isEmpty()) break
    }
    return RemoteSearchDirectories(base.path, directories, limited, unreadable)
}

/** A path prefix selects a directory; its final component filters that directory's entries. */
data class RemotePathSearch(val directory: String, val name: String)

fun remotePathSearch(directory: String, query: String): RemotePathSearch {
    val slash = query.lastIndexOf('/')
    if (slash < 0) return RemotePathSearch(directory, query)
    val prefix = query.substring(0, slash + 1)
    val path = when {
        prefix.startsWith('/') -> prefix
        prefix.startsWith("~/") -> prefix.removePrefix("~/")
        directory.isEmpty() -> prefix
        else -> "$directory/$prefix"
    }
    val parts = mutableListOf<String>()
    for (part in path.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex)
            else if (!path.startsWith('/')) parts.add(part)
        else -> parts.add(part)
    }
    return RemotePathSearch((if (path.startsWith('/')) "/" else "") + parts.joinToString("/"), query.substring(slash + 1))
}

fun validWorkingDirectory(output: String?): String? = output?.removeSuffix("\n")?.removeSuffix("\r")
    ?.takeIf { it.startsWith('/') && it.length <= 4096 && it.none { c -> c == '\u0000' || c == '\n' || c == '\r' } }

/** Find the login shell belonging to this SSH transport, without writing commands into its PTY. */
internal val shellWorkingDirectoryCommand = """
    test -n "${'$'}SSH_CONNECTION" || exit 1
    for pid in ${'$'}(ps -u "${'$'}(id -u)" -o pid=,sid= | awk '${'$'}1 == ${'$'}2 { print ${'$'}1 }'); do
        p=/proc/${'$'}pid
        test -r "${'$'}p/environ" || continue
        env_lines=${'$'}(tr '\000' '\n' < "${'$'}p/environ" 2>/dev/null) || continue
        printf '%s\n' "${'$'}env_lines" | grep -Fxq "SSH_CONNECTION=${'$'}SSH_CONNECTION" || continue
        printf '%s\n' "${'$'}env_lines" | grep -q '^SSH_TTY=/dev/' || continue
        readlink "${'$'}p/cwd" && exit 0
    done
    exit 1
""".trimIndent()
