package io.itermux.app.session

/**
 * What an AI agent in a tmux session reports through the hooks [AgentHooks] adds: its state, the tool it is running and
 * the task (the last prompt), and the folder the pane is in. Read with [Tmux.agentStatusCommand]; see [parse].
 */
data class AgentStatus(
    val state: State,
    val agent: String,
    val tool: String,
    val detail: String,
    val task: String,
    /** Elapsed milliseconds the state has lasted, on the host's clock. */
    val elapsedMillis: Long,
    /** The pane's working directory; empty when tmux did not say. */
    val directory: String = "",
    /** Seconds the last turn worked, once it stopped; null while one runs, or before the first. */
    val workedSeconds: Long? = null,
) {
    enum class State { WORKING, WAITING, IDLE }

    companion object {
        /** Not "sh": Codex and Grok run under their `/bin/sh` launcher, which ends the report itself ([AgentHooks]). */
        private val shells = setOf("bash", "zsh", "fish", "dash", "ash", "ksh", "csh", "tcsh", "nu", "pwsh", "login")

        /**
         * Output of the status command: `pane_command|state|since|agent|tool|detail|worked|task` (`worked` missing in
         * older reports), the pane's path, then the
         * host's time. The state counts only while the pane still runs a program: an agent killed before it could report
         * leaves its last state behind, but the pane is back at a shell.
         */
        fun parse(output: String?): AgentStatus? {
            val lines = output?.lines()?.filter { it.isNotBlank() } ?: return null
            val fields = lines.firstOrNull()?.split('|', limit = 8)?.takeIf { it.size >= 7 } ?: return null
            val worked = if (fields.size == 8) fields[6].trim().toLongOrNull() else null
            if (fields[0].trimStart('-') in shells) return null
            val state = when (fields[1]) {
                "working" -> State.WORKING
                "waiting" -> State.WAITING
                "idle" -> State.IDLE
                else -> return null
            }
            val since = fields[2].toLongOrNull()
            val now = lines.drop(1).lastOrNull()?.trim()?.toLongOrNull()
            val directory = if (lines.size >= 3) lines[1].trim() else ""
            val elapsed = if (since != null && now != null) ((now - since) * 1000).coerceAtLeast(0) else 0
            return AgentStatus(state, fields[3], fields[4].trim(), fields[5].trim(), clean(fields.last()), elapsed, directory,
                worked.takeIf { state == State.IDLE })
        }

        /** A byte-limited hook value may end in half a character. */
        private fun clean(value: String) = value.trim().trimEnd('�')
    }
}

/** The tools agents run shell commands with. */
private val shellTools = setOf("Bash", "PowerShell", "run_terminal_command", "shell", "exec_command", "local_shell")

/**
 * What the agent is running, for the session list: a shell command as `Running command`, as Claude Code says it, another tool by its name and what it
 * works on; empty while it runs nothing.
 */
fun agentActivity(status: AgentStatus?): String {
    if (status == null || status.state == AgentStatus.State.IDLE) return ""
    return when {
        status.detail.isEmpty() -> status.tool
        status.tool.isEmpty() -> status.detail
        status.tool in shellTools -> "Running ${status.detail}"
        else -> "${status.tool} ${status.detail}"
    }
}

/**
 * A session's name in the session list: the task its agent was given, else the agent's name and the name of the folder
 * it works in ("Claude · roamtty", the home as `~`), else [title] (one saved as "Claude Code · roamtty" shortened to
 * "Claude · roamtty"). Without a known agent the folder stands alone. Empty when there is none of these. The whole
 * folder is [sessionListPath].
 */
fun sessionListTitle(title: String, status: AgentStatus?, agent: CodingAgent?, username: String): String {
    status?.task?.takeIf { it.isNotBlank() }?.let { return it }
    sessionListPath(status, username).takeIf { it.isNotEmpty() }?.let { path ->
        val folder = if (path == "~" || path == "/") path else path.trimEnd('/').substringAfterLast('/')
        return if (agent == null) folder else "${agent.shortLabel} · $folder"
    }
    if (agent == null) return title
    return title.removePrefix("${agent.label} · ").takeIf { it != title }?.let { "${agent.shortLabel} · $it" } ?: title
}

/** The folder the session's agent works in, the home as `~`; empty when tmux did not say. */
fun sessionListPath(status: AgentStatus?, username: String): String =
    status?.directory?.takeIf { it.isNotBlank() }?.let { homeRelative(it, username) } ?: ""

private fun homeRelative(path: String, username: String): String {
    val homes = if (username == "root") listOf("/root") else listOf("/home/$username", "/Users/$username")
    val home = homes.firstOrNull { path == it || path.startsWith("$it/") } ?: return path
    return "~" + path.removePrefix(home)
}

/**
 * Hooks of the coding agents that report to [AgentStatus] through a script kept on the host. Claude Code reads its hooks
 * from `--settings`; Codex and Grok start through a launcher in the same folder that adds theirs.
 */
object AgentHooks {
    /** Where the script and the agents' hook files live on the host; the shell expands the home. */
    const val SCRIPT_PATH = "~/.roamtty/agent-hook.sh"
    const val SETTINGS_PATH = "~/.roamtty/claude-hooks.json"

    val script: String by lazy {
        checkNotNull(AgentHooks::class.java.getResourceAsStream("/io/itermux/app/agent-hook.sh")) { "agent-hook.sh missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The hook file [agent] needs on the host, as a name in `~/.roamtty` and its contents; true when it must run. */
    fun hostFile(agent: CodingAgent): Triple<String, String, Boolean> = when (agent) {
        CodingAgent.CLAUDE -> Triple(SETTINGS_PATH.substringAfterLast('/'), claudeSettings, false)
        CodingAgent.CODEX -> Triple("codex", codexLauncher, true)
        CodingAgent.GROK -> Triple("grok", grokLauncher, true)
    }

    /** What [agent] is typed as: Claude Code with its settings, the others through their launcher. */
    fun command(agent: CodingAgent): String = when (agent) {
        CodingAgent.CLAUDE -> agent.command
        else -> "~/.roamtty/${hostFile(agent).first}"
    }

    private fun hookCommand(agent: CodingAgent, action: String) = "sh $SCRIPT_PATH ${agent.command} $action"

    /** Contents of [SETTINGS_PATH], for Claude Code's `--settings`: merged with the user's own settings and hooks. */
    val claudeSettings: String by lazy {
        fun hook(event: String, action: String) =
            "\"$event\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"${hookCommand(CodingAgent.CLAUDE, action)}\"}]}]"
        "{\"hooks\":{" + listOf("SessionStart" to "start", "UserPromptSubmit" to "prompt", "PreToolUse" to "tool",
            "PostToolUse" to "post", "Notification" to "notify", "Stop" to "stop", "SessionEnd" to "end")
            .joinToString(",") { (event, action) -> hook(event, action) } + "}}"
    }

    /** Codex events, their key in Codex's hook state, and what the script makes of them. */
    private val codexEvents = listOf(
        Triple("SessionStart", "session_start", "start"), Triple("UserPromptSubmit", "user_prompt_submit", "prompt"),
        Triple("PreToolUse", "pre_tool_use", "tool"), Triple("PermissionRequest", "permission_request", "ask"),
        Triple("PostToolUse", "post_tool_use", "post"), Triple("Stop", "stop", "stop"),
        Triple("Interrupt", "interrupt", "stop"), Triple("SessionEnd", "session_end", "end"))

    /** Seconds a Codex hook may take; within Codex's limit for SessionEnd and Interrupt hooks, so it hashes as written. */
    private const val CODEX_TIMEOUT = 3

    /**
     * The hash Codex trusts a hook by: SHA-256 of its normalized definition as sorted, compact JSON (codex-rs hooks
     * `hook_hash`). Codex runs a session's hooks only once trusted, a trust it keeps in its config.toml.
     */
    internal fun codexHookHash(eventKey: String, command: String): String {
        val json = "{\"event_name\":\"$eventKey\",\"hooks\":[{\"async\":false,\"command\":\"$command\"," +
            "\"timeout\":$CODEX_TIMEOUT,\"type\":\"command\"}]}"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * `~/.roamtty/codex`: records the trust Codex's own "Trust all" would for the hooks it adds (once; a different trust
     * already there is left for the user), then runs Codex with them. It stays the pane's program, so restarting the
     * pane types it again.
     */
    val codexLauncher: String by lazy {
        val trust = codexEvents.map { (_, key, action) -> "trust $key ${codexHookHash(key, hookCommand(CodingAgent.CODEX, action))}" }
        val hooks = codexEvents.map { (event, _, action) ->
            "  -c 'hooks.$event=[{hooks=[{type=\"command\",command=\"${hookCommand(CodingAgent.CODEX, action)}\",timeout=$CODEX_TIMEOUT}]}]' \\"
        }
        (listOf(
            "#!/bin/sh",
            "# Roamtty: starts Codex with hooks that report its state to agent-hook.sh.",
            "config=\"\${CODEX_HOME:-\$HOME/.codex}/config.toml\"",
            "trust() {",
            "  header=\"[hooks.state.\\\"/<session-flags>/config.toml:\$1:0:0\\\"]\"",
            "  grep -qF \"\$header\" \"\$config\" 2>/dev/null || printf '\\n%s\\ntrusted_hash = \"%s\"\\n' \"\$header\" \"\$2\" >> \"\$config\"",
            "}",
            "mkdir -p \"\$(dirname \"\$config\")\"",
        ) + trust + "codex \\" + hooks + "  \"\$@\"" + launcherEnd(CodingAgent.CODEX)).joinToString("\n", postfix = "\n")
    }

    /** A launcher ends the report however its agent ended, as the agent's own end hook would, and exits as the agent did. */
    private fun launcherEnd(agent: CodingAgent) = listOf("status=\$?", "${hookCommand(agent, "end")} </dev/null", "exit \$status")

    /** Grok events and what the script makes of them. */
    private val grokEvents = listOf("SessionStart" to "start", "UserPromptSubmit" to "prompt", "PreToolUse" to "tool",
        "PostToolUse" to "post", "PostToolUseFailure" to "post", "Notification" to "notify", "Stop" to "stop",
        "StopFailure" to "stop", "StopCancelled" to "stop", "SessionEnd" to "end")

    /**
     * `~/.roamtty/grok`: Grok reads hooks only from its own folders, so this puts them in `~/.grok/hooks`, where they act
     * only for a Grok started here ([script] checks `ROAMTTY_AGENT`), then runs Grok.
     */
    val grokLauncher: String by lazy {
        val hooks = grokEvents.joinToString(",") { (event, action) ->
            "\"$event\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"${hookCommand(CodingAgent.GROK, action)}\"}]}]"
        }
        (listOf(
            "#!/bin/sh",
            "# Roamtty: starts Grok with hooks that report its state to agent-hook.sh.",
            "dir=\"\${GROK_HOME:-\$HOME/.grok}/hooks\"",
            "mkdir -p \"\$dir\" && printf '%s\\n' '{\"hooks\":{$hooks}}' > \"\$dir/roamtty.json\"",
            "export ROAMTTY_AGENT=grok",
            "grok \"\$@\"",
        ) + launcherEnd(CodingAgent.GROK)).joinToString("\n", postfix = "\n")
    }
}
