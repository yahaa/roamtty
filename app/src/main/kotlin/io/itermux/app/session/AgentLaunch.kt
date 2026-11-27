package io.itermux.app.session

/**
 * AI coding CLIs the file browser can start in a folder. [readOnlyFlag] starts a plan (Claude, Antigravity) or
 * read-only sandbox; [yoloFlag] skips approval prompts; [standardFlag] makes the agent ask before edits and commands
 * when its own default would not (newer Claude Code starts in auto mode). Users can subsequently change the CLI's
 * permissions.
 */
enum class CodingAgent(val label: String, val shortLabel: String, val command: String, val readOnlyFlag: String,
    val yoloFlag: String, val standardFlag: String = "") {
    // "default" is the asking mode's old name; newer versions call it manual but still take it, older ones only know it.
    CLAUDE("Claude Code", "Claude", "claude", "--permission-mode plan", "--dangerously-skip-permissions", "--permission-mode default"),
    CODEX("Codex", "Codex", "codex", "--sandbox read-only", "--dangerously-bypass-approvals-and-sandbox"),
    GROK("Grok", "Grok", "grok", "--sandbox read-only", "--always-approve"),
    AGY("Antigravity CLI", "Antigravity", "agy", "--mode plan", "--dangerously-skip-permissions");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: CODEX

        /** The agent a hook reported itself as (its [command]), or null for one this app does not know. */
        fun fromCommand(value: String?) = entries.firstOrNull { it.command == value }

        /** The agent a session named by [AgentLaunch.title] was started for, or null for any other title. */
        fun fromTitle(title: String) = entries.firstOrNull { title.startsWith("${it.shortLabel} · ") || title.startsWith("${it.label} · ") }
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
    /** Agents report their state through hooks on hosts that run POSIX scripts. */
    val reportsStatus: Boolean get() = !WindowsShell.isPath(StartDirectory.normalize(directory))

    val agentCommand: String get() = when (mode) {
        AgentMode.READ_ONLY -> "${agent.command} ${agent.readOnlyFlag}"
        AgentMode.STANDARD -> if (agent.standardFlag.isEmpty()) agent.command else "${agent.command} ${agent.standardFlag}"
        AgentMode.YOLO -> "${agent.command} ${agent.yoloFlag}"
    }

    /**
     * [agentCommand] as typed: with the hooks [AgentHooks.hostFile] puts on the host, which Claude Code reads from its
     * settings and the other agents get from their launcher.
     */
    private fun typedCommand(session: String?): String = when {
        !reportsStatus -> agentCommand
        agent == CodingAgent.CLAUDE -> "$agentCommand --settings ${AgentHooks.settingsPath(session)}"
        else -> AgentHooks.command(agent) + agentCommand.removePrefix(agent.command)
    }

    val commandLine: String get() = commandLine(null)

    /**
     * What starts the agent, in tmux session [session], which Claude Code's hooks then report to from outside tmux too.
     * Windows folders open PowerShell; keep npm's .ps1 execution policy from blocking its native .cmd shim.
     * When [antigravityMic] is set on Windows for [CodingAgent.AGY], exports `$env:ANTIGRAVITY_MIC` before starting `agy`.
     */
    fun commandLine(session: String?, antigravityMic: String? = null): String {
        val dir = StartDirectory.normalize(directory)
        val typed = typedCommand(session)
        if (!WindowsShell.isPath(dir)) return StartDirectory.command(dir)?.let { "$it && $typed" } ?: typed
        val cmd = "${agent.command}.cmd"
        // cmd.exe silently falls back to another cwd for UNC paths. Never run an agent there.
        val uncGuard = "if ((Get-Location).ProviderPath.StartsWith('\\\\')) { throw 'Please map this network folder to a drive letter before starting the agent.' }"
        val micPrefix = if (agent == CodingAgent.AGY) {
            val micSetup = if (antigravityMic != null) {
                require(AgentHooks.isValidLoopbackMicEndpoint(antigravityMic)) { "invalid ANTIGRAVITY_MIC endpoint: $antigravityMic" }
                "\$env:ANTIGRAVITY_MIC = ${WindowsShell.literal(antigravityMic)}; "
            } else {
                val micFile = "\$env:USERPROFILE\\.roamtty\\voice-mic"
                "if (-not \$env:ANTIGRAVITY_MIC) { if (Test-Path \"$micFile\") { \$env:ANTIGRAVITY_MIC = (Get-Content \"$micFile\" -Raw).Trim() }; if (-not \$env:ANTIGRAVITY_MIC) { \$env:ANTIGRAVITY_MIC = ${WindowsShell.literal(AgentHooks.DEFAULT_ANTIGRAVITY_MIC)} } }; "
            }
            val settingsDir = "\$env:USERPROFILE\\.roamtty"
            val settingsFile = "\$env:USERPROFILE\\.roamtty\\agy-settings.json"
            "${micSetup}New-Item -ItemType Directory -Force \"$settingsDir\" | Out-Null; [IO.File]::WriteAllText(\"$settingsFile\", '{\"voice\":{\"fullDuplex\":true}}'); "
        } else ""
        val effectiveCommand = if (agent == CodingAgent.AGY) {
            "$agentCommand --settings \"\$env:USERPROFILE\\.roamtty\\agy-settings.json\""
        } else {
            agentCommand
        }
        return WindowsShell.launch(dir, "${micPrefix}if (Get-Command $cmd -CommandType Application -ErrorAction SilentlyContinue) { " +
            "$uncGuard; ${effectiveCommand.replaceFirst(agent.command, cmd)} } else { $effectiveCommand }")
    }

    /** Session title: the agent and the folder it works in. */
    val title: String get() = "${agent.shortLabel} · ${directory.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}"
}

internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
