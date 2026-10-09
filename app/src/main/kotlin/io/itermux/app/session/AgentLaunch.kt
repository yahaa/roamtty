package io.itermux.app.session

/**
 * AI coding CLIs the file browser can start in a folder. [readOnlyFlag] starts a plan (Claude) or read-only sandbox;
 * [yoloFlag] skips approval prompts. Users can subsequently change the CLI's permissions.
 */
enum class CodingAgent(val label: String, val shortLabel: String, val command: String, val readOnlyFlag: String,
    val yoloFlag: String) {
    CLAUDE("Claude Code", "Claude", "claude", "--permission-mode plan", "--dangerously-skip-permissions"),
    CODEX("Codex", "Codex", "codex", "--sandbox read-only", "--dangerously-bypass-approvals-and-sandbox"),
    GROK("Grok", "Grok", "grok", "--sandbox read-only", "--always-approve");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: CODEX
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

    /** Windows folders open PowerShell; keep npm's .ps1 execution policy from blocking its native .cmd shim. */
    val commandLine: String get() {
        val dir = StartDirectory.normalize(directory)
        if (!WindowsShell.isPath(dir)) return StartDirectory.command(dir)?.let { "$it && $agentCommand" } ?: agentCommand
        val cmd = "${agent.command}.cmd"
        // cmd.exe silently falls back to another cwd for UNC paths. Never run an agent there.
        val uncGuard = "if ((Get-Location).ProviderPath.StartsWith('\\\\')) { throw 'Please map this network folder to a drive letter before starting the agent.' }"
        return WindowsShell.launch(dir, "if (Get-Command $cmd -CommandType Application -ErrorAction SilentlyContinue) { " +
            "$uncGuard; ${agentCommand.replaceFirst(agent.command, cmd)} } else { $agentCommand }")
    }

    /** Session title: the agent and the folder it works in. */
    val title: String get() = "${agent.shortLabel} · ${directory.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}"
}

internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
