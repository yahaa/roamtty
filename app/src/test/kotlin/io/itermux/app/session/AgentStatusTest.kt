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
