package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusTest {
    @Test fun readsTheStateTheToolAndTheTask() {
        val status = AgentStatus.parse("claude|working|1000|claude|Bash|npm test|fix the bug\n1042\n")!!
        assertEquals(AgentStatus.State.WORKING, status.state)
        assertEquals("Bash", status.tool)
        assertEquals("npm test", status.detail)
        assertEquals("fix the bug", status.task)
        assertEquals(42_000, status.elapsedMillis)
    }

    @Test fun anAgentThatLeftLeavesNothing() {
        assertNull(AgentStatus.parse("zsh|working|1000|claude|Bash|x|task\n1042"))
        assertNull(AgentStatus.parse("-bash|idle|1000|claude|||task\n1042"))
        // Codex and Grok run under their launcher.
        assertEquals(AgentStatus.State.IDLE, AgentStatus.parse("sh|idle|1000|codex|||task\n1042")!!.state)
    }

    @Test fun noReportsOrUnknownStatesMeanNoStatus() {
        assertNull(AgentStatus.parse(null))
        assertNull(AgentStatus.parse("claude|||||\n1042"))
        assertNull(AgentStatus.parse("claude|sleeping|1|claude|||\n2"))
        assertNull(AgentStatus.parse("garbage"))
    }

    @Test fun halfACharacterAtTheEndOfTheTaskIsDropped() {
        assertEquals("修复", AgentStatus.parse("node|idle|1|claude|||修复�\n2")!!.task)
    }

    @Test fun clockSkewNeverGivesNegativeTime() {
        assertEquals(0, AgentStatus.parse("node|waiting|50|claude|||\n40")!!.elapsedMillis)
    }

    @Test fun hookSettingsNeedNoShellQuoting() {
        val json = AgentHooks.claudeSettings
        assertFalse('\'' in json || '\\' in json)
        listOf("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Notification", "Stop", "SessionEnd")
            .forEach { assertTrue(it, "\"$it\"" in json) }
        assertTrue("sh ~/.roamtty/agent-hook.sh claude prompt" in json)
    }

    @Test fun codexTrustsItsHooksByTheHashCodexComputes() {
        // Codex 0.162 recorded these when its "Trust all" trusted the hooks, given with a 3 s timeout.
        val log = "sh /tmp/claude-1000/-home-v-work-roamtty/50a4e347-10a1-4c15-b785-1225642298e2/scratchpad/log.sh"
        assertEquals("sha256:4f1b2dd4a5d87385b74e8495a0d38d8a4b88cccbd10f8b0b1efe806677c4e1ad", AgentHooks.codexHookHash("stop", "$log stop"))
        assertEquals("sha256:9d388d80df6c839ca91447ad8ec4ad0b9b42f961b8d1d704b77381cd7e484800",
            AgentHooks.codexHookHash("interrupt", "$log stop"))
        val launcher = AgentHooks.codexLauncher
        assertTrue(launcher.startsWith("#!/bin/sh"))
        listOf("SessionStart", "UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse", "Stop", "Interrupt",
            "SessionEnd").forEach { assertTrue(it, "-c 'hooks.$it=[{hooks=[{type=\"command\",command=\"sh ~/.roamtty/agent-hook.sh codex " in launcher) }
        assertTrue("trust stop ${AgentHooks.codexHookHash("stop", "sh ~/.roamtty/agent-hook.sh codex stop")}" in launcher)
        assertTrue("  \"\$@\"\nstatus=\$?\nsh ~/.roamtty/agent-hook.sh codex end </dev/null\nexit \$status\n" in launcher)
    }

    @Test fun grokHooksActOnlyForAGrokStartedByTheApp() {
        val launcher = AgentHooks.grokLauncher
        assertTrue("\"StopCancelled\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"sh ~/.roamtty/agent-hook.sh grok stop\"}]}]" in launcher)
        assertTrue("export ROAMTTY_AGENT=grok\ngrok \"\$@\"\n" in launcher)
        assertTrue("[ \"\$ROAMTTY_AGENT\" = grok ]" in AgentHooks.script)
    }

    @Test fun activityShowsWhatTheAgentRuns() {
        val working = AgentStatus.parse("sh|working|1|codex|Bash|make test|t\n2")!!
        assertEquals("$ make test", agentActivity(working))
        assertEquals("$ sleep 6", agentActivity(working.copy(tool = "run_terminal_command", detail = "sleep 6")))
        assertEquals("Read src/a.kt", agentActivity(working.copy(tool = "Read", detail = "src/a.kt")))
        assertEquals("WebSearch", agentActivity(working.copy(tool = "WebSearch", detail = "")))
        assertEquals("Claude needs your permission to use Bash",
            agentActivity(working.copy(state = AgentStatus.State.WAITING, tool = "", detail = "Claude needs your permission to use Bash")))
        assertEquals("", agentActivity(working.copy(tool = "", detail = "")))
        assertEquals("", agentActivity(working.copy(state = AgentStatus.State.IDLE)))
        assertEquals("", agentActivity(null))
    }

    @Test fun theScriptShipsWithTheApp() {
        assertNotNull(AgentHooks.script)
        assertTrue(AgentHooks.script.startsWith("#!/bin/sh"))
    }

    @Test fun readsThePanesFolderBeforeTheClock() {
        val status = AgentStatus.parse("claude|idle|1000|claude|||\n/home/v/roamtty\n1042\n")!!
        assertEquals("/home/v/roamtty", status.directory)
        assertEquals(42_000, status.elapsedMillis)
        assertEquals("", AgentStatus.parse("claude|idle|1000|claude|||\n1042")!!.directory)
    }

    @Test fun listTitleIsTheTaskThenTheFolderThenTheTitleWithoutTheAgent() {
        val working = AgentStatus.parse("claude|working|1|claude|||fix the bug\n/home/v/roamtty\n2")!!
        assertEquals("fix the bug", sessionListTitle("Claude · roamtty", working, CodingAgent.CLAUDE, "v"))
        val fresh = working.copy(task = "")
        assertEquals("roamtty", sessionListTitle("Claude · roamtty", fresh, CodingAgent.CLAUDE, "v"))
        assertEquals("~/roamtty", sessionListPath(fresh, "v"))
        assertEquals("~", sessionListTitle("", fresh.copy(directory = "/Users/v"), CodingAgent.CLAUDE, "v"))
        assertEquals("/", sessionListTitle("", fresh.copy(directory = "/"), CodingAgent.CLAUDE, "v"))
        assertEquals("web", sessionListTitle("", fresh.copy(directory = "/srv/web"), CodingAgent.CLAUDE, "v"))
        assertEquals("/srv/web", sessionListPath(fresh.copy(directory = "/srv/web"), "v"))
        assertEquals("~/x", sessionListPath(fresh.copy(directory = "/root/x"), "root"))
        assertEquals("", sessionListPath(null, "v"))
        assertEquals("roamtty", sessionListTitle("Claude · roamtty", null, CodingAgent.CLAUDE, "v"))
        assertEquals("roamtty", sessionListTitle("Claude Code · roamtty", null, CodingAgent.CLAUDE, "v"))
        assertEquals("部署脚本", sessionListTitle("部署脚本", null, CodingAgent.CLAUDE, "v"))
        assertEquals("Claude · roamtty", sessionListTitle("Claude · roamtty", null, null, "v"))
    }
}
