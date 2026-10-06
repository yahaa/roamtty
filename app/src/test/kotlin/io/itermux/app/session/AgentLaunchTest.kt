package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentLaunchTest {
    @Test fun standardPermissionRunsTheBareAgent() {
        assertEquals("cd -- '/home/v/app' && claude", AgentLaunch(CodingAgent.CLAUDE, "/home/v/app", AgentMode.STANDARD).commandLine)
    }
    @Test fun yoloAddsEachAgentsBypassFlag() {
        assertEquals("claude --dangerously-skip-permissions", AgentLaunch(CodingAgent.CLAUDE, "/a", AgentMode.YOLO).agentCommand)
        assertEquals("codex --dangerously-bypass-approvals-and-sandbox", AgentLaunch(CodingAgent.CODEX, "/a", AgentMode.YOLO).agentCommand)
        assertEquals("grok --always-approve", AgentLaunch(CodingAgent.GROK, "/a", AgentMode.YOLO).agentCommand)
    }
    @Test fun restrictedModeUsesClaudePlanAndCodexGrokSandboxes() {
        assertEquals("claude --permission-mode plan", AgentLaunch(CodingAgent.CLAUDE, "/a", AgentMode.READ_ONLY).agentCommand)
        assertEquals("codex --sandbox read-only", AgentLaunch(CodingAgent.CODEX, "/a", AgentMode.READ_ONLY).agentCommand)
        assertEquals("grok --sandbox read-only", AgentLaunch(CodingAgent.GROK, "/a", AgentMode.READ_ONLY).agentCommand)
    }
    @Test fun unknownStoredModeIsStandard() {
        assertEquals(AgentMode.STANDARD, AgentMode.from(null))
        assertEquals(AgentMode.READ_ONLY, AgentMode.from("READ_ONLY"))
    }
    @Test fun directoryIsQuotedForTheShell() {
        assertEquals("cd -- '/srv/it'\\''s $(x) dir' && codex",
            AgentLaunch(CodingAgent.CODEX, "/srv/it's \$(x) dir").commandLine)
    }
    @Test fun homeFolderNeedsNoCd() {
        assertEquals("claude", AgentLaunch(CodingAgent.CLAUDE, "~").commandLine)
        assertEquals("cd -- ~/'work/app' && grok", AgentLaunch(CodingAgent.GROK, "~/work/app").commandLine)
    }
    @Test fun titleNamesAgentAndFolder() {
        assertEquals("Grok · web", AgentLaunch(CodingAgent.GROK, "/srv/web/").title)
        assertEquals("Codex · /", AgentLaunch(CodingAgent.CODEX, "/").title)
    }
    @Test fun unknownStoredAgentFallsBackToClaude() {
        assertEquals(CodingAgent.CODEX, CodingAgent.from("nope"))
        assertEquals(CodingAgent.GROK, CodingAgent.from("GROK"))
    }
    @Test fun noAgentModeCanTurnControlCharactersIntoTerminalInput() {
        CodingAgent.entries.forEach { agent -> AgentMode.entries.forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                AgentLaunch(agent, "/tmp/\u0015printf injected #", mode).commandLine
            }
        } }
    }
}
