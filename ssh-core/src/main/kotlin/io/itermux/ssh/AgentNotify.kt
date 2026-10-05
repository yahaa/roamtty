package io.itermux.ssh

/**
 * The "AI 交互提醒" plugin: Claude Code, Codex and Grok hooks run `~/.roamtty/bin/roamtty-notify`,
 * which appends a JSON line to `~/.roamtty/events.jsonl` whenever a CLI waits for a permission or
 * an answer, and again once it moves on. The app follows that file over the session's SSH
 * connection ([WATCH_COMMAND]) and turns the lines into Android notifications.
 */
object AgentNotify {
    const val SCRIPT = ".roamtty/bin/roamtty-notify"
    const val CLI_MARKER = "__ITERMUX_NOTIFY_CLI__="
    const val TRUST_MARKER = "__ITERMUX_CODEX_TRUST__="
    const val NO_PYTHON = "nopython"

    /**
     * Prints the last lines, then each new one. The app keeps the channel's stdin open, so
     * `cat` returns once the channel closes and takes the `tail` with it.
     */
    const val WATCH_COMMAND = "/bin/sh -c 'mkdir -p \"\$HOME/.roamtty\" && touch \"\$HOME/.roamtty/events.jsonl\" && " +
        "{ tail -n 50 -F \"\$HOME/.roamtty/events.jsonl\" 2>/dev/null & t=\$!; cat >/dev/null; kill \$t 2>/dev/null; }'"

    /**
     * Typed before `codex` in the user's own shell, where `codex` is on PATH: trusts these hooks
     * the way `/hooks` would, so Codex never skips them. Exits 0 when nothing needed trust.
     */
    const val TRUST_CODEX_COMMAND = "~/$SCRIPT --trust-codex"

    internal val hookScript: String by lazy { resource("roamtty-notify.sh") }
    private val setupScript: String by lazy { resource("roamtty-notify-setup.py") }

    private fun resource(name: String): String =
        checkNotNull(AgentNotify::class.java.getResourceAsStream(name)) { "missing $name" }.use { it.readBytes().toString(Charsets.UTF_8) }

    /** CLIs often live in user prefixes that a non-login SSH command does not have on PATH. */
    private const val CLI_PATH = "PATH=\"\$PATH:\$HOME/.local/bin:\$HOME/.npm-global/bin:\$HOME/.local/share/mise/shims:" +
        "\$HOME/.volta/bin:\$HOME/.bun/bin:\$HOME/.grok/bin:\$HOME/.cargo/bin\"; " +
        "for d in \"\$HOME\"/.nvm/versions/node/*/bin \"\$HOME\"/.local/share/mise/installs/node/*/bin \"\$HOME\"/.local/share/fnm/node-versions/*/installation/bin; do [ -d \"\$d\" ] && PATH=\"\$PATH:\$d\"; done; export PATH"

    private fun hooksJson(command: String, codex: Boolean, grok: Boolean = false): String {
        fun handler(vararg args: String) = """{"type":"command","command":"$command ${args.joinToString(" ")}","timeout":5}"""
        if (codex) return """{"hooks":{"PermissionRequest":[{"hooks":[${handler("attention", "codex")}]}],""" +
            """"PostToolUse":[{"hooks":[${handler("resolved", "codex")}]}],"Stop":[{"hooks":[${handler("resolved", "codex")}]}]}}"""
        val notification = if (grok) "permission_prompt" else "permission_prompt|elicitation_dialog|elicitation_url_dialog|agent_needs_input"
        val question = if (grok) "AskUserQuestion|ask_user_question" else "AskUserQuestion"
        return """{"hooks":{"Notification":[{"matcher":"$notification","hooks":[${handler("attention")}]}],""" +
            """"PreToolUse":[{"matcher":"$question","hooks":[${handler("attention")}]}],""" +
            """"PostToolUse":[{"hooks":[${handler("resolved")}]}],"UserPromptSubmit":[{"hooks":[${handler("resolved")}]}],""" +
            """"Stop":[{"hooks":[${handler("resolved")}]}]}}"""
    }

    /**
     * Installs the script and registers its hooks for every CLI the host has, then trusts the Codex
     * ones. Runs on `/bin/sh -s` like the package installs and ends with [HostPluginInstaller.DONE].
     */
    fun installScript(): String {
        // Without python3 an existing config cannot be merged safely, so only missing files are written.
        val fallback = listOf(
            Triple("claude", ".claude", "\$HOME/.claude/settings.json") to hooksJson("\$n", codex = false),
            Triple("codex", ".codex", "\$HOME/.codex/hooks.json") to hooksJson("\$n", codex = true),
        ).joinToString("\n") { (cli, json) ->
            val (name, folder, path) = cli
            """
            if [ -d "${'$'}HOME/$folder" ] || command -v $name >/dev/null 2>&1; then
              if [ -s "$path" ]; then if grep -q roamtty-notify "$path"; then cli $name ok; else cli $name $NO_PYTHON; fi
              else mkdir -p "${'$'}HOME/$folder" && cat > "$path" <<ROAMTTY_JSON
            $json
            ROAMTTY_JSON
              cli $name ok; fi
            else cli $name absent; fi
            """.trimIndent()
        }
        val grokJson = hooksJson("\$n", codex = false, grok = true)
        return """
exec 2>&1
${Tmux.PATH_SETUP}
$CLI_PATH
N="${'$'}HOME/$SCRIPT"
cli() { echo "$CLI_MARKER${'$'}1 ${'$'}2"; }
mkdir -p "${'$'}HOME/.roamtty/bin" "${'$'}HOME/.roamtty/pending" || exit 1
cat > "${'$'}N.tmp" <<'ROAMTTY_NOTIFY_SH'
$hookScript
ROAMTTY_NOTIFY_SH
chmod 755 "${'$'}N.tmp" && mv -f "${'$'}N.tmp" "${'$'}N" || exit 1
touch "${'$'}HOME/.roamtty/events.jsonl"
if command -v python3 >/dev/null 2>&1; then
python3 - install "${'$'}N" <<'ROAMTTY_SETUP_PY'
$setupScript
ROAMTTY_SETUP_PY
else
n=${'$'}(printf '%s' "${'$'}N" | sed 's/\\/\\\\/g; s/"/\\"/g')
$fallback
if [ -d "${'$'}HOME/.grok" ] || command -v grok >/dev/null 2>&1; then
mkdir -p "${'$'}HOME/.grok/hooks" && cat > "${'$'}HOME/.grok/hooks/roamtty.json" <<ROAMTTY_JSON
$grokJson
ROAMTTY_JSON
cli grok ok
else cli grok absent; fi
fi
ROAMTTY_REPORT=1 "${'$'}N" --trust-codex
echo ${HostPluginInstaller.DONE}
        """.trimIndent()
    }

    /** Removes Roamtty's hook entries, the Grok hook file and everything under ~/.roamtty. */
    fun uninstallScript(): String = """
exec 2>&1
N="${'$'}HOME/$SCRIPT"
if command -v python3 >/dev/null 2>&1; then
python3 - uninstall "${'$'}N" <<'ROAMTTY_SETUP_PY'
$setupScript
ROAMTTY_SETUP_PY
else
for f in "${'$'}HOME/.claude/settings.json" "${'$'}HOME/.codex/hooks.json"; do
  [ -f "${'$'}f" ] && grep -q roamtty-notify "${'$'}f" && echo "$CLI_MARKER${'$'}f $NO_PYTHON"
done
rm -f "${'$'}HOME/.grok/hooks/roamtty.json"
fi
rm -rf "${'$'}HOME/.roamtty/bin" "${'$'}HOME/.roamtty/pending" "${'$'}HOME/.roamtty/created" "${'$'}HOME/.roamtty/events.jsonl"
rmdir "${'$'}HOME/.roamtty" 2>/dev/null
echo ${HostPluginInstaller.DONE}
    """.trimIndent()

    /** Per-CLI outcome of an install: `ok`, `absent`, `invalid` or [NO_PYTHON]. */
    fun cliResults(output: String): Map<String, String> = output.lineSequence().map { it.trim() }
        .filter { it.startsWith(CLI_MARKER) }
        .mapNotNull { it.removePrefix(CLI_MARKER).split(' ', limit = 2).takeIf { parts -> parts.size == 2 } }
        .associate { (cli, status) -> cli to status }

    /** `trusted` or `ok` when Codex will run the hooks; `missing`, `failed` or `unavailable` otherwise. */
    fun codexTrust(output: String): String? = output.lineSequence().map { it.trim() }
        .lastOrNull { it.startsWith(TRUST_MARKER) }?.removePrefix(TRUST_MARKER)
}

/** One line of `~/.roamtty/events.jsonl`. */
data class AgentEvent(
    val id: String,
    val ts: Long,
    val agent: String,
    /** [ATTENTION] or [RESOLVED]. */
    val kind: String,
    /** `permission` or `question` on an attention event. */
    val reason: String,
    /** The tmux session the CLI runs in, or empty outside tmux. */
    val tmux: String,
    /** Pairs an attention event with its resolved event: the tmux session, else the CLI's session id. */
    val key: String,
    val cwd: String,
    val msg: String,
) {
    companion object {
        const val ATTENTION = "attention"
        const val RESOLVED = "resolved"

        /** Null for anything that is not a well-formed event line. */
        fun parse(line: String): AgentEvent? {
            val fields = FlatJson.parse(line.trim()) ?: return null
            val kind = fields["kind"] ?: return null
            if (kind != ATTENTION && kind != RESOLVED) return null
            return AgentEvent(
                id = fields["id"]?.takeIf { it.isNotEmpty() } ?: return null,
                ts = fields["ts"]?.toLongOrNull() ?: 0L,
                agent = fields["agent"].orEmpty(),
                kind = kind,
                reason = fields["reason"].orEmpty(),
                tmux = fields["tmux"].orEmpty(),
                key = fields["key"].orEmpty(),
                cwd = fields["cwd"].orEmpty(),
                msg = fields["msg"].orEmpty().take(MAX_MESSAGE),
            )
        }

        private const val MAX_MESSAGE = 200
    }
}

/** A JSON object of string, number, boolean or null values: all an event line holds. */
internal object FlatJson {
    fun parse(text: String): Map<String, String>? {
        var i = 0
        fun skip() { while (i < text.length && text[i].isWhitespace()) i++ }
        fun string(): String? {
            if (i >= text.length || text[i] != '"') return null
            i++
            val out = StringBuilder()
            while (i < text.length) {
                val c = text[i++]
                when {
                    c == '"' -> return out.toString()
                    c != '\\' -> out.append(c)
                    i >= text.length -> return null
                    else -> when (val e = text[i++]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                        'u' -> {
                            if (i + 4 > text.length) return null
                            out.append(text.substring(i, i + 4).toIntOrNull(16)?.let(::Char) ?: return null)
                            i += 4
                        }
                        else -> out.append(e)
                    }
                }
            }
            return null
        }
        skip()
        if (i >= text.length || text[i++] != '{') return null
        val fields = mutableMapOf<String, String>()
        skip()
        if (i < text.length && text[i] == '}') return fields
        while (true) {
            skip()
            val key = string() ?: return null
            skip()
            if (i >= text.length || text[i++] != ':') return null
            skip()
            val value = if (i < text.length && text[i] == '"') string() ?: return null else {
                val start = i
                while (i < text.length && text[i] != ',' && text[i] != '}') i++
                text.substring(start, i).trim().takeIf { it.isNotEmpty() && it[0] != '{' && it[0] != '[' } ?: return null
            }
            fields[key] = value
            skip()
            when (text.getOrNull(i++)) {
                ',' -> continue
                '}' -> return fields
                else -> return null
            }
        }
    }
}
