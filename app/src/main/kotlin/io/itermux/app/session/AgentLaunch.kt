package io.itermux.app.session

/**
 * AI coding CLIs the file browser can start in a folder. [readOnlyFlag] lets it look but not touch (plan mode or a
 * read-only sandbox); [yoloFlag] skips every approval prompt.
 */
enum class CodingAgent(val label: String, val shortLabel: String, val command: String, val readOnlyFlag: String,
    val yoloFlag: String) {
    CLAUDE("Claude Code", "Claude", "claude", "--permission-mode plan", "--dangerously-skip-permissions"),
    CODEX("Codex", "Codex", "codex", "--sandbox read-only", "--dangerously-bypass-approvals-and-sandbox"),
    GROK("Grok", "Grok", "grok", "--permission-mode plan", "--always-approve");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: CLAUDE
    }
}

/** How much the agent may do without asking. */
enum class AgentMode {
    READ_ONLY, STANDARD, YOLO;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

data class AgentLaunch(val agent: CodingAgent, val directory: String, val mode: AgentMode = AgentMode.STANDARD) {
    val agentCommand: String get() = when (mode) {
        AgentMode.READ_ONLY -> "${agent.command} ${agent.readOnlyFlag}"
        AgentMode.STANDARD -> agent.command
        AgentMode.YOLO -> "${agent.command} ${agent.yoloFlag}"
    }

    /** Typed into the new session's shell; single quotes work in sh, bash, zsh and fish alike. Home needs no cd. */
    val commandLine: String get() = StartDirectory.command(directory)?.let { "$it && $agentCommand" } ?: agentCommand

    /** Session title: the agent and the folder it works in. */
    val title: String get() = "${agent.label} · ${directory.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}"
}

internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
