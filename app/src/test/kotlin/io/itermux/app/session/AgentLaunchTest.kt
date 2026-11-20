package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentLaunchTest {
    @Test fun standardPermissionAsksBeforeActing() {
        assertEquals("cd -- '/home/v/app' && claude --permission-mode default --settings ~/.roamtty/claude-hooks.json",
            AgentLaunch(CodingAgent.CLAUDE, "/home/v/app", AgentMode.STANDARD).commandLine)
        assertEquals("codex", AgentLaunch(CodingAgent.CODEX, "/a", AgentMode.STANDARD).agentCommand)
        assertEquals("grok", AgentLaunch(CodingAgent.GROK, "/a", AgentMode.STANDARD).agentCommand)
        assertEquals("agy", AgentLaunch(CodingAgent.AGY, "/a", AgentMode.STANDARD).agentCommand)
    }
    @Test fun yoloAddsEachAgentsBypassFlag() {
        assertEquals("claude --dangerously-skip-permissions", AgentLaunch(CodingAgent.CLAUDE, "/a", AgentMode.YOLO).agentCommand)
        assertEquals("codex --dangerously-bypass-approvals-and-sandbox", AgentLaunch(CodingAgent.CODEX, "/a", AgentMode.YOLO).agentCommand)
        assertEquals("grok --always-approve", AgentLaunch(CodingAgent.GROK, "/a", AgentMode.YOLO).agentCommand)
        assertEquals("agy --dangerously-skip-permissions", AgentLaunch(CodingAgent.AGY, "/a", AgentMode.YOLO).agentCommand)
    }
    @Test fun restrictedModeUsesClaudePlanAndCodexGrokSandboxes() {
        assertEquals("claude --permission-mode plan", AgentLaunch(CodingAgent.CLAUDE, "/a", AgentMode.READ_ONLY).agentCommand)
        assertEquals("codex --sandbox read-only", AgentLaunch(CodingAgent.CODEX, "/a", AgentMode.READ_ONLY).agentCommand)
        assertEquals("grok --sandbox read-only", AgentLaunch(CodingAgent.GROK, "/a", AgentMode.READ_ONLY).agentCommand)
        assertEquals("agy --mode plan", AgentLaunch(CodingAgent.AGY, "/a", AgentMode.READ_ONLY).agentCommand)
    }
    @Test fun unknownStoredModeIsStandard() {
        assertEquals(AgentMode.STANDARD, AgentMode.from(null))
        assertEquals(AgentMode.READ_ONLY, AgentMode.from("READ_ONLY"))
    }
    @Test fun directoryIsQuotedForTheShell() {
        assertEquals("cd -- '/srv/it'\\''s $(x) dir' && ~/.roamtty/codex",
            AgentLaunch(CodingAgent.CODEX, "/srv/it's \$(x) dir").commandLine)
    }
    @Test fun homeFolderNeedsNoCd() {
        assertEquals("claude --permission-mode default --settings ~/.roamtty/claude-hooks.json", AgentLaunch(CodingAgent.CLAUDE, "~").commandLine)
        assertEquals("cd -- ~/'work/app' && ~/.roamtty/grok", AgentLaunch(CodingAgent.GROK, "~/work/app").commandLine)
    }
    @Test fun codexAndGrokStartThroughTheirLauncherWithTheirFlags() {
        assertEquals("cd -- '/a' && ~/.roamtty/codex --sandbox read-only",
            AgentLaunch(CodingAgent.CODEX, "/a", AgentMode.READ_ONLY).commandLine)
        assertEquals("cd -- '/a' && ~/.roamtty/grok --always-approve", AgentLaunch(CodingAgent.GROK, "/a", AgentMode.YOLO).commandLine)
        assertEquals("cd -- '/a' && ~/.roamtty/agy --mode plan", AgentLaunch(CodingAgent.AGY, "/a", AgentMode.READ_ONLY).commandLine)
        assertEquals("~/.roamtty/agy --dangerously-skip-permissions", AgentLaunch(CodingAgent.AGY, "~", AgentMode.YOLO).commandLine)
    }
    @Test fun titleNamesAgentAndFolder() {
        assertEquals("Grok · web", AgentLaunch(CodingAgent.GROK, "/srv/web/").title)
        assertEquals("Codex · /", AgentLaunch(CodingAgent.CODEX, "/").title)
        assertEquals("Claude · roamtty", AgentLaunch(CodingAgent.CLAUDE, "/home/v/roamtty").title)
        assertEquals("Antigravity · roamtty", AgentLaunch(CodingAgent.AGY, "/home/v/roamtty").title)
    }
    @Test fun unknownStoredAgentFallsBackToClaude() {
        assertEquals(CodingAgent.CODEX, CodingAgent.from("nope"))
        assertEquals(CodingAgent.GROK, CodingAgent.from("GROK"))
        assertEquals(CodingAgent.AGY, CodingAgent.from("AGY"))
        assertEquals(CodingAgent.AGY, CodingAgent.fromCommand("agy"))
    }
    @Test fun noAgentModeCanTurnControlCharactersIntoTerminalInput() {
        CodingAgent.entries.forEach { agent -> AgentMode.entries.forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                AgentLaunch(agent, "/tmp/\u0015printf injected #", mode).commandLine
            }
        } }
    }

    @Test fun legacyClaudeCodeTitlesShorten() {
        assertEquals("Claude · roamtty", shortenLegacyAgentTitle("Claude Code · roamtty"))
        assertEquals("Claude Code notes", shortenLegacyAgentTitle("Claude Code notes"))
    }

    @Test fun restoredSessionsTellTheirAgentByTitle() {
        assertEquals(CodingAgent.GROK, CodingAgent.fromTitle(AgentLaunch(CodingAgent.GROK, "/srv/roamtty").title))
        assertEquals(CodingAgent.AGY, CodingAgent.fromTitle(AgentLaunch(CodingAgent.AGY, "/srv/roamtty").title))
        assertEquals(CodingAgent.AGY, CodingAgent.fromTitle("Antigravity CLI · roamtty"))
        assertEquals(CodingAgent.CODEX, CodingAgent.fromTitle("Codex · roamtty"))
        assertEquals(CodingAgent.CLAUDE, CodingAgent.fromTitle("Claude Code · roamtty"))
        assertEquals(null, CodingAgent.fromTitle("部署脚本"))
        assertEquals(null, CodingAgent.fromTitle("Grokking notes"))
    }
}
