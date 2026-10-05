package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLaunchTest {
    @Test fun standardPermissionRunsTheBareAgent() {
        assertEquals("cd '/home/v/app' && claude", AgentLaunch(CodingAgent.CLAUDE, "/home/v/app", yolo = false).commandLine)
    }
    @Test fun yoloAddsEachAgentsBypassFlag() {
        assertEquals("claude --dangerously-skip-permissions", AgentLaunch(CodingAgent.CLAUDE, "/a", true).agentCommand)
        assertEquals("codex --dangerously-bypass-approvals-and-sandbox", AgentLaunch(CodingAgent.CODEX, "/a", true).agentCommand)
        assertEquals("grok --always-approve", AgentLaunch(CodingAgent.GROK, "/a", true).agentCommand)
    }
    @Test fun directoryIsQuotedForTheShell() {
        assertEquals("cd '/srv/it'\\''s $(x) dir' && codex",
            AgentLaunch(CodingAgent.CODEX, "/srv/it's \$(x) dir", false).commandLine)
    }
    @Test fun homeFolderNeedsNoCd() {
        assertEquals("claude", AgentLaunch(CodingAgent.CLAUDE, "~", false).commandLine)
        assertEquals("cd ~/'work/app' && grok", AgentLaunch(CodingAgent.GROK, "~/work/app", false).commandLine)
    }
    @Test fun titleNamesAgentAndFolder() {
        assertEquals("Grok · web", AgentLaunch(CodingAgent.GROK, "/srv/web/", false).title)
        assertEquals("Codex · /", AgentLaunch(CodingAgent.CODEX, "/", false).title)
    }
    @Test fun unknownStoredAgentFallsBackToClaude() {
        assertEquals(CodingAgent.CLAUDE, CodingAgent.from("nope"))
        assertEquals(CodingAgent.GROK, CodingAgent.from("GROK"))
    }
}
