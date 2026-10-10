package io.itermux.app.session

/** A host read from pasted text; [identityFile] only tells the editor to ask for a key. */
data class ImportedHost(val name: String, val address: String, val port: Int = 22, val username: String = "",
    val identityFile: String? = null)

/**
 * Reads hosts from what people copy on a computer: an `ssh` command line (`ssh -p 2222 me@dev`), an
 * `ssh://me@dev:2222` URL, a bare `me@dev:2222`, or `~/.ssh/config` entries. Wildcard config entries are skipped.
 */
object SshImport {
    fun parse(text: String): List<ImportedHost> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.lineSequence().any { CONFIG_HOST.matches(it.trim()) }) return parseConfig(trimmed)
        return listOfNotNull(parseCommand(trimmed.lineSequence().first { it.isNotBlank() }))
    }

    private fun parseConfig(text: String): List<ImportedHost> {
        val hosts = mutableListOf<ImportedHost>()
        var aliases = emptyList<String>()
        val options = mutableMapOf<String, String>()
        fun flush() {
            for (alias in aliases) {
                if (alias.any { it in "*?!" }) continue
                val address = options["hostname"]?.replace("%h", alias) ?: alias
                hosts += ImportedHost(alias.take(80), address, options["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                    options["user"].orEmpty(), options["identityfile"])
            }
            options.clear()
        }
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(' ').substringBefore('=').substringBefore('\t')
            val value = line.substring(key.length).trim().removePrefix("=").trim().removeSurrounding("\"")
            when (key.lowercase()) {
                "host" -> { flush(); aliases = value.split(WHITESPACE).filter { it.isNotEmpty() } }
                // A Match block's options do not belong to the Host above it.
                "match" -> { flush(); aliases = emptyList() }
                else -> options.putIfAbsent(key.lowercase(), value)
            }
        }
        flush()
        return hosts.distinctBy { it.name }
    }

    private fun parseCommand(line: String): ImportedHost? {
        val words = tokenize(line).toMutableList()
        if (words.firstOrNull()?.substringAfterLast('/')?.let { it == "ssh" || it == "ssh.exe" } == true) words.removeAt(0)
        // Without `ssh` in front, only a lone target counts; anything longer is ordinary text.
        else if (words.size != 1) return null
        var port: Int? = null
        var user: String? = null
        var identity: String? = null
        var destination: String? = null
        var i = 0
        while (i < words.size) {
            val word = words[i]
            if (word.startsWith("-") && word.length > 1 && destination == null) {
                val flag = word[1]
                val inline = word.substring(2)
                val argument = if (flag in OPTIONS_WITH_ARGUMENT) inline.ifEmpty { words.getOrNull(++i) } else null
                when (flag) {
                    'p' -> port = argument?.toIntOrNull()
                    'l' -> user = argument
                    'i' -> identity = argument
                    'o' -> argument?.let { option ->
                        val key = option.substringBefore('=').substringBefore(' ').lowercase()
                        val value = option.substring(key.length).trim().removePrefix("=").trim()
                        when (key) {
                            "port" -> port = value.toIntOrNull()
                            "user" -> user = value
                            "identityfile" -> identity = value
                        }
                    }
                }
            } else if (destination == null) destination = word
            else break // the remote command
            i++
        }
        val target = destination ?: return null
        var rest = target.removePrefix("ssh://")
        val uri = rest.length != target.length
        if ('@' in rest) {
            user = user ?: rest.substringBeforeLast('@').ifEmpty { null }
            rest = rest.substringAfterLast('@')
        }
        rest = rest.trimEnd('/')
        val address: String
        if (rest.startsWith("[")) {
            address = rest.substringAfter('[').substringBefore(']')
            rest.substringAfter("]:", "").toIntOrNull()?.let { if (port == null) port = it }
        } else if (rest.count { it == ':' } == 1) {
            // `host:port` is not ssh syntax, but people paste it, and ssh:// URLs use it.
            address = rest.substringBefore(':')
            val parsed = rest.substringAfter(':').toIntOrNull() ?: if (uri) return null else null
            if (parsed != null && port == null) port = parsed
        } else address = rest
        if (address.isEmpty() || address.any { it.isWhitespace() || it == '/' }) return null
        return ImportedHost(address.take(80), address, port?.takeIf { it in 1..65535 } ?: 22, user.orEmpty(), identity)
    }

    /** Splits like a shell: whitespace separates words, quotes group them, a backslash escapes the next character. */
    internal fun tokenize(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var started = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> { quote = c; started = true }
                c == '\\' && quote != '\'' && i + 1 < line.length -> { word.append(line[++i]); started = true }
                quote == null && c.isWhitespace() -> {
                    if (started) words += word.toString()
                    word.clear(); started = false
                }
                else -> { word.append(c); started = true }
            }
            i++
        }
        if (started) words += word.toString()
        return words
    }

    private val CONFIG_HOST = Regex("(?i)host(\\s+|\\s*=\\s*)\\S.*")
    private val WHITESPACE = Regex("\\s+")
    private const val OPTIONS_WITH_ARGUMENT = "BbcDEeFIiJLlmOoPpQRSWw"
}
