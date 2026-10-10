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
}
