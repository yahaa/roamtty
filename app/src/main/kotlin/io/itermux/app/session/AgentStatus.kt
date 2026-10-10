package io.itermux.app.session

/**
 * What an AI agent in a tmux session reports through the hooks [AgentHooks] adds: its state, the tool it is running and
 * the task (the last prompt). Read with [Tmux.agentStatusCommand]; see [parse].
 */
data class AgentStatus(
    val state: State,
    val agent: String,
    val tool: String,
    val detail: String,
    val task: String,
    /** Elapsed milliseconds the state has lasted, on the host's clock. */
    val elapsedMillis: Long,
) {
    enum class State { WORKING, WAITING, IDLE }

    companion object {
        private val shells = setOf("sh", "bash", "zsh", "fish", "dash", "ash", "ksh", "csh", "tcsh", "nu", "pwsh", "login")

        /**
         * Output of the status command: `pane_command|state|since|agent|tool|detail|task`, then the host's time. The
         * state counts only while the pane still runs a program: an agent killed before it could report leaves its last
         * state behind, but the pane is back at a shell.
         */
        fun parse(output: String?): AgentStatus? {
            val lines = output?.lines()?.filter { it.isNotBlank() } ?: return null
            val fields = lines.firstOrNull()?.split('|', limit = 7)?.takeIf { it.size == 7 } ?: return null
            if (fields[0].trimStart('-') in shells) return null
            val state = when (fields[1]) {
                "working" -> State.WORKING
                "waiting" -> State.WAITING
                "idle" -> State.IDLE
                else -> return null
            }
            val since = fields[2].toLongOrNull()
            val now = lines.getOrNull(1)?.trim()?.toLongOrNull()
            val elapsed = if (since != null && now != null) ((now - since) * 1000).coerceAtLeast(0) else 0
            return AgentStatus(state, fields[3], fields[4].trim(), fields[5].trim(), clean(fields[6]), elapsed)
        }

        /** A byte-limited hook value may end in half a character. */
        private fun clean(value: String) = value.trim().trimEnd('�')
    }
}

/** Claude Code hooks that report to [AgentStatus] through a script kept on the host. */
object AgentHooks {
    /** Where the script and Claude Code's hook settings live on the host; the shell expands the home. */
    const val SCRIPT_PATH = "~/.roamtty/agent-hook.sh"
    const val SETTINGS_PATH = "~/.roamtty/claude-hooks.json"

    val script: String by lazy {
        checkNotNull(AgentHooks::class.java.getResourceAsStream("/io/itermux/app/agent-hook.sh")) { "agent-hook.sh missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Contents of [SETTINGS_PATH], for Claude Code's `--settings`: merged with the user's own settings and hooks. */
    val claudeSettings: String by lazy {
        fun hook(event: String, action: String) =
            "\"$event\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"sh $SCRIPT_PATH claude $action\"}]}]"
        "{\"hooks\":{" + listOf("SessionStart" to "start", "UserPromptSubmit" to "prompt", "PreToolUse" to "tool",
            "PostToolUse" to "post", "Notification" to "notify", "Stop" to "stop", "SessionEnd" to "end")
            .joinToString(",") { (event, action) -> hook(event, action) } + "}}"
    }
}
