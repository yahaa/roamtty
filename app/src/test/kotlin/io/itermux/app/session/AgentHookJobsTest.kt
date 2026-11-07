package io.itermux.app.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Claude Code's background jobs run outside tmux and report to the session their settings name. */
class AgentHookJobsTest {
    private lateinit var dir: File
    private lateinit var script: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("agent-hook-jobs").toFile()
        script = File(dir, "agent-hook.sh").apply { writeText(AgentHooks.script) }
        // A stand-in tmux keeping each option in a file; every tmux session lives but "gone".
        File(dir, "tmux").apply {
            writeText("#!/bin/sh\ncase \"\$1\" in\n  show-options) cat \"$dir/opt\$5\" 2>/dev/null ;;\n" +
                "  set-option) if [ \"\$2\" = -u ]; then rm -f \"$dir/opt\$6\"; else printf '%s' \"\$6\" > \"$dir/opt\$5\"; fi ;;\n" +
                "  has-session) [ \"\$3\" != =gone ] ;;\nesac\n")
            setExecutable(true)
        }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun hook(event: String, json: String, pane: String? = null, agent: String = "claude", session: String? = "s") {
        val process = ProcessBuilder(listOfNotNull("sh", script.path, agent, event, session)).apply {
            environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
            environment().remove("TMUX_PANE")
            if (pane != null) environment()["TMUX_PANE"] = pane
        }.start()
        process.outputStream.use { it.write(json.toByteArray()) }
        assertEquals(0, process.waitFor())
    }
    private fun option(name: String) = File(dir, "opt$name").takeIf { it.exists() }?.readText()
    private fun reported() = option("@roamtty_agent").orEmpty().split('|').let { "${it[0]}|${it[3]}|${it[4]}" }

    @Test fun aBackgroundJobReportsToTheTmuxSessionItsSettingsName() {
        hook("start", """{"session_id":"pane"}""", pane = "%1")
        assertEquals("idle||", reported())
        assertEquals("pane", option("@roamtty_session"))
        // A spare process starts before any task: nothing changes until a job has one.
        hook("start", """{"session_id":"spare"}""")
        assertEquals("pane", option("@roamtty_owner"))
        hook("prompt", """{"session_id":"job","prompt":"fix it"}""")
        assertEquals("working||", reported())
        assertEquals("fix it", option("@roamtty_task"))
        hook("tool", """{"session_id":"job","tool_name":"Bash","tool_input":{"command":"make"}}""")
        assertEquals("working|Bash|make", reported())
        assertEquals("pane", option("@roamtty_session"))
        // The session left in the pane has nothing to say about the job's work.
        hook("notify", """{"session_id":"pane","notification_type":"idle_prompt"}""", pane = "%1")
        assertEquals("working|Bash|make", reported())
        hook("notify", """{"session_id":"job","notification_type":"permission_prompt","message":"needs permission"}""")
        assertEquals("waiting|Bash|make", reported())
        hook("post", """{"session_id":"job"}""")
        hook("stop", """{"session_id":"job","last_assistant_message":"done"}""")
        assertEquals("idle||", reported())
        assertEquals("done", option("@roamtty_reply"))
    }

    @Test fun aJobThatEndsMidTurnStopsTheWorkButLeavesThePanesOptions() {
        hook("prompt", """{"session_id":"job","prompt":"long"}""")
        hook("tool", """{"session_id":"job","tool_name":"Bash","tool_input":{"command":"make"}}""")
        hook("end", """{"session_id":"other"}""")
        assertEquals("working|Bash|make", reported())
        hook("end", """{"session_id":"job"}""")
        assertEquals("idle||", reported())
        assertEquals("long", option("@roamtty_task"))
    }

    @Test fun onlyTheSessionThatReportedLastSaysTheWorkStopped() {
        hook("prompt", """{"session_id":"a","prompt":"go"}""")
        hook("prompt", """{"session_id":"b","prompt":"go"}""")
        hook("stop", """{"session_id":"a"}""")
        assertEquals("working||", reported())
        hook("stop", """{"session_id":"b"}""")
        assertEquals("idle||", reported())
    }

    @Test fun aJobLeavesAPaneRunningAnotherAgentAlone() {
        hook("prompt", """{"prompt":"codex work"}""", pane = "%1", agent = "codex", session = null)
        val codex = option("@roamtty_agent")
        hook("prompt", """{"session_id":"job","prompt":"not yours"}""")
        assertEquals(codex, option("@roamtty_agent"))
        assertEquals("codex work", option("@roamtty_task"))
    }

    @Test fun aJobOfAnEndedSessionReportsNowhere() {
        hook("prompt", """{"session_id":"job","prompt":"late"}""", session = "gone")
        assertNull(option("@roamtty_agent"))
    }

    @Test fun codexKeepsReportingWhateverSessionIdsItSends() {
        hook("prompt", """{"session_id":"a","prompt":"go"}""", pane = "%1", agent = "codex", session = null)
        hook("stop", """{"session_id":"b"}""", pane = "%1", agent = "codex", session = null)
        assertEquals("idle||", reported())
        assertNull(option("@roamtty_owner"))
    }

    @Test fun pruningKeepsSettingsOfLiveSessionsAndRecentFiles() {
        val folder = File(dir, ".roamtty").apply { mkdirs() }
        val old = System.currentTimeMillis() - 40L * 86_400_000
        listOf("claude-hooks-gone.json" to old, "claude-hooks-live.json" to old, "claude-hooks.json" to old,
            "claude-hooks-new.json" to System.currentTimeMillis()).forEach { (name, time) ->
            File(folder, name).apply { writeText("") ; setLastModified(time) }
        }
        val process = ProcessBuilder("sh", "-c", AgentHooks.pruneSettings).apply {
            environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
            environment()["HOME"] = dir.path
        }.start()
        assertEquals(0, process.waitFor())
        assertEquals(listOf("claude-hooks-live.json", "claude-hooks-new.json", "claude-hooks.json"), folder.list()!!.sorted())
    }

    @Test fun claudeStartedInATmuxSessionNamesItInItsSettings() {
        val launch = AgentLaunch(CodingAgent.CLAUDE, "/home/v/app", AgentMode.YOLO)
        assertEquals("cd -- '/home/v/app' && claude --dangerously-skip-permissions --settings ~/.roamtty/claude-hooks-roamtty-AB12.json",
            launch.commandLine("roamtty-AB12"))
        val (name, content, executable) = AgentHooks.hostFile(CodingAgent.CLAUDE, "roamtty-AB12")
        assertEquals("claude-hooks-roamtty-AB12.json", name)
        assertFalse(executable)
        assertTrue(""""command":"sh ~/.roamtty/agent-hook.sh claude stop roamtty-AB12"""" in content)
        // Without a tmux session, or with a name that cannot go into a command as it is, as before.
        listOf(null, "", "a b", "a;b", "会话").forEach {
            assertEquals(launch.commandLine, launch.commandLine(it))
            assertEquals("claude-hooks.json", AgentHooks.hostFile(CodingAgent.CLAUDE, it).first)
        }
        assertEquals(AgentHooks.claudeSettings, AgentHooks.hostFile(CodingAgent.CLAUDE).second)
        // Other agents have one launcher and report from inside the pane.
        val codex = AgentLaunch(CodingAgent.CODEX, "~")
        assertEquals(codex.commandLine, codex.commandLine("s"))
        assertEquals("codex", AgentHooks.hostFile(CodingAgent.CODEX, "s").first)
    }
}
