package io.itermux.app.session

/** AI coding CLIs the file browser can start in a folder. [yoloFlag] skips every approval prompt. */
enum class CodingAgent(val label: String, val command: String, val yoloFlag: String) {
    CLAUDE("Claude Code", "claude", "--dangerously-skip-permissions"),
    CODEX("Codex", "codex", "--dangerously-bypass-approvals-and-sandbox"),
    GROK("Grok", "grok", "--always-approve");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: CLAUDE
    }
}

data class AgentLaunch(val agent: CodingAgent, val directory: String, val yolo: Boolean) {
    val agentCommand: String get() = if (yolo) "${agent.command} ${agent.yoloFlag}" else agent.command

    /** Typed into the new session's shell; single quotes work in sh, bash, zsh and fish alike. */
    val commandLine: String get() = "cd ${shellQuote(directory)} && $agentCommand"

    /** Session title: the agent and the folder it works in. */
    val title: String get() = "${agent.label} · ${directory.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}"
}

internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
