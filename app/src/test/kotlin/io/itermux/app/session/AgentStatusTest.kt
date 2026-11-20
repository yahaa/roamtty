package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.itermux.ssh.TmuxSessionInfo
import java.io.File
import java.nio.file.Files

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
        // On macOS /bin/sh is bash, so the launcher shows as "bash".
        assertEquals(AgentStatus.State.IDLE, AgentStatus.parse("bash|idle|1000|codex|||task\n1042")!!.state)
        assertEquals(AgentStatus.State.WORKING, AgentStatus.parse("bash|working|1000|grok|||task\n1042")!!.state)
        assertEquals(AgentStatus.State.WORKING, AgentStatus.parse("sh|working|1000|agy|run_command|ls|task\n1042")!!.state)
        assertEquals(AgentStatus.State.IDLE, AgentStatus.parse("bash|idle|1000|agy|||task\n1042")!!.state)
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
        assertTrue("case \"\$agent\" in grok|agy) [ \"\$ROAMTTY_AGENT\" = \"\$agent\" ] || exit 0 ;; esac" in AgentHooks.script)
    }

    @Test fun agyLauncherInstallsPluginAndReportsStartAndEnd() {
        val (name, launcher, executable) = AgentHooks.hostFile(CodingAgent.AGY)
        assertEquals("agy", name)
        assertTrue(executable)
        assertTrue(launcher.startsWith("#!/bin/sh\n"))
        assertTrue("dir=\"\$HOME/.gemini/config/plugins/roamtty\"" in launcher)
        assertTrue("\"PreInvocation\":[{\"type\":\"command\",\"command\":\"sh ~/.roamtty/agent-hook.sh agy prompt\"}]" in launcher)
        assertTrue("\"PreToolUse\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":\"sh ~/.roamtty/agent-hook.sh agy tool\"}]}]" in launcher)
        assertTrue("\"PostToolUse\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":\"sh ~/.roamtty/agent-hook.sh agy post\"}]}]" in launcher)
        assertTrue("\"Stop\":[{\"type\":\"command\",\"command\":\"sh ~/.roamtty/agent-hook.sh agy stop\"}]" in launcher)
        assertTrue("export ROAMTTY_AGENT=agy\nsh ~/.roamtty/agent-hook.sh agy start </dev/null\nagy \"\$@\"\n" in launcher)
        assertTrue("status=\$?\nsh ~/.roamtty/agent-hook.sh agy end </dev/null\nexit \$status\n" in launcher)
    }

    @Test fun agyHooksReportTurnToolsQuestionsTranscriptAndBackgroundWork() {
        val dir = Files.createTempDirectory("agent-hook-agy").toFile()
        try {
            val script = File(dir, "agent-hook.sh").apply { writeText(AgentHooks.script) }
            File(dir, "tmux").apply {
                writeText("#!/bin/sh\ncase \"\$1\" in\n  show-options) cat \"$dir/opt\$5\" 2>/dev/null ;;\n" +
                    "  set-option) if [ \"\$2\" = -u ]; then rm -f \"$dir/opt\$6\"; else printf '%s' \"\$6\" > \"$dir/opt\$5\"; fi ;;\nesac\n")
                setExecutable(true)
            }
            val transcript = File(dir, "transcript_full.jsonl")
            fun hook(event: String, json: String = "{}", marker: String? = "agy"): String {
                val process = ProcessBuilder("sh", script.path, "agy", event).apply {
                    environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
                    environment()["TMUX_PANE"] = "%1"
                    if (marker != null) environment()["ROAMTTY_AGENT"] = marker else environment().remove("ROAMTTY_AGENT")
                }.start()
                process.outputStream.use { it.write(json.toByteArray()) }
                val stdout = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor())
                return stdout
            }
            fun option(name: String) = File(dir, "opt$name").takeIf { it.exists() }?.readText()
            fun reported() = option("@roamtty_agent")!!.split('|').let { "${it[0]}|${it[2]}|${it[3]}|${it[4]}" }

            // Outside Roamtty (no ROAMTTY_AGENT): ignored, empty stdout so PreToolUse never blocks.
            assertEquals("", hook("tool", """{"toolCall":{"args":{"CommandLine":"ls"},"id":"c0","name":"run_command"}}""", marker = null))
            assertNull(option("@roamtty_agent"))

            // Launcher reports idle on start.
            assertEquals("", hook("start"))
            assertEquals("idle|agy||", reported())

            // PreInvocation invocationNum 0 starts a turn.
            assertEquals("", hook("prompt", """{"invocationNum":0,"lastUserInput":"fix the build"}"""))
            assertEquals("working|agy||", reported())
            assertEquals("fix the build", option("@roamtty_task"))
            val turn = option("@roamtty_turn")
            assertNotNull(turn)

            // PreToolUse reads toolCall.name and CommandLine, with empty stdout so agy keeps normal permissions.
            assertEquals("", hook("tool",
                """{"lastUserInput":"fix the build","toolCall":{"args":{"CommandLine":"./gradlew test","toolSummary":"Run tests"},"id":"c1","name":"run_command"}}"""))
            assertEquals("working|agy|run_command|./gradlew test", reported())
            assertEquals("", hook("post", """{"toolCall":{"args":{"CommandLine":"./gradlew test"},"id":"c1","name":"run_command"}}"""))
            // PreInvocation invocationNum 1 keeps the turn and finished tool.
            assertEquals("", hook("prompt", """{"invocationNum":1,"lastUserInput":"fix the build"}"""))
            assertEquals("working|agy|run_command|./gradlew test", reported())
            assertEquals(turn, option("@roamtty_turn"))

            // write_file uses Path; escaped keys inside Content must not hijack CommandLine or Path.
            assertEquals("", hook("tool",
                """{"toolCall":{"args":{"Content":"{\"CommandLine\":\"fake\"}","Path":"/repo/src/Main.kt"},"id":"c2","name":"write_file"}}"""))
            assertEquals("working|agy|write_file|/repo/src/Main.kt", reported())

            // ask_question waits for the user; PostToolUse returns to working.
            assertEquals("", hook("tool",
                """{"toolCall":{"args":{"questions":[{"question":"Which option?","options":["A","B"]}]},"id":"c3","name":"ask_question"}}"""))
            assertEquals("waiting|agy|ask_question|Which option?", reported())
            assertEquals("", hook("post"))
            assertEquals("working|agy|ask_question|Which option?", reported())

            // Stop with fullyIdle:false stays working on background tasks.
            assertEquals("", hook("stop", """{"fullyIdle":false,"terminationReason":"NO_TOOL_CALL","transcriptPath":"${transcript.path}"}"""))
            assertEquals("working|agy|Background|", reported())
            assertNull(option("@roamtty_worked"))

            // Background wakeup with same lastUserInput keeps the turn clock.
            assertEquals("", hook("prompt", """{"invocationNum":0,"lastUserInput":"fix the build"}"""))
            assertEquals(turn, option("@roamtty_turn"))

            // Stop with fullyIdle:true reads the final PLANNER_RESPONSE content from transcriptPath.
            transcript.writeText(
                """{"step_index":1,"type":"PLANNER_RESPONSE","thinking":"check \"content\":\"ignored\"","tool_calls":[{"name":"run_command"}]}""" + "\n" +
                    """{"step_index":2,"type":"GENERIC","content":"tool output"}""" + "\n" +
                    """{"step_index":3,"type":"PLANNER_RESPONSE","thinking":"done","content":"**All** tests pass.\nReady | green"}""" + "\n")
            assertEquals("", hook("stop", """{"fullyIdle":true,"terminationReason":"NO_TOOL_CALL","transcriptPath":"${transcript.path}"}"""))
            assertEquals("idle|agy||", reported())
            assertNotNull(option("@roamtty_worked"))
            assertEquals("**All** tests pass. Ready / green", option("@roamtty_reply"))

            // End clears options.
            assertEquals("", hook("end"))
            assertNull(option("@roamtty_agent"))
            assertNull(option("@roamtty_reply"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun agyHooksExtractToolCallNameRegardlessOfPropertyOrder() {
        val dir = Files.createTempDirectory("agent-hook-agy-order").toFile()
        try {
            val script = File(dir, "agent-hook.sh").apply { writeText(AgentHooks.script) }
            File(dir, "tmux").apply {
                writeText("#!/bin/sh\ncase \"\$1\" in\n  show-options) cat \"$dir/opt\$5\" 2>/dev/null ;;\n" +
                    "  set-option) if [ \"\$2\" = -u ]; then rm -f \"$dir/opt\$6\"; else printf '%s' \"\$6\" > \"$dir/opt\$5\"; fi ;;\nesac\n")
                setExecutable(true)
            }
            fun hook(json: String): String {
                val process = ProcessBuilder("sh", script.path, "agy", "tool").apply {
                    environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
                    environment()["TMUX_PANE"] = "%1"
                    environment()["ROAMTTY_AGENT"] = "agy"
                }.start()
                process.outputStream.use { it.write(json.toByteArray()) }
                val stdout = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor())
                return stdout
            }
            fun reported() = File(dir, "opt@roamtty_agent").readText().split('|').let { "${it[0]}|${it[2]}|${it[3]}|${it[4]}" }

            // Compact payloads with toolCall.name first (Google's PreToolUse doc order) and last.
            assertEquals("", hook("""{"toolCall":{"name":"run_command","args":{"CommandLine":"./gradlew test"}}}"""))
            assertEquals("working|agy|run_command|./gradlew test", reported())
            assertEquals("", hook("""{"toolCall":{"args":{"CommandLine":"make check"},"id":"c1","name":"run_command"}}"""))
            assertEquals("working|agy|run_command|make check", reported())

            assertEquals("", hook("""{"toolCall":{"name":"ask_question","args":{}}}"""))
            assertEquals("waiting|agy|ask_question|", reported())
            assertEquals("", hook("""{"toolCall":{"name":"ask_question","args":{"questions":[{"question":"Ship it?"}]}}}"""))
            assertEquals("waiting|agy|ask_question|Ship it?", reported())
            assertEquals("", hook("""{"toolCall":{"args":{"questions":[{"question":"Deploy?"}]},"id":"c2","name":"ask_question"}}"""))
            assertEquals("waiting|agy|ask_question|Deploy?", reported())

            // Nested "name" inside args and sibling "name" outside toolCall must not shadow toolCall.name.
            assertEquals("", hook("""{"toolCall":{"name":"swarm","args":{"action":"add","name":"worker-1"}}}"""))
            assertEquals("working|agy|swarm|", reported())
            assertEquals("", hook("""{"toolCall":{"args":{"action":"add","name":"worker-1"},"id":"c3","name":"swarm"}}"""))
            assertEquals("working|agy|swarm|", reported())
            assertEquals("", hook("""{"plugin":{"name":"roamtty"},"toolCall":{"name":"run_command","args":{"CommandLine":"ls"}},"extra":{"name":"ignored"}}"""))
            assertEquals("working|agy|run_command|ls", reported())

            // Multi-line pretty-printed PreToolUse JSON also resolves toolCall.name.
            assertEquals("", hook("{\n  \"toolCall\": {\n    \"name\": \"ask_question\",\n    \"args\": {}\n  }\n}"))
            assertEquals("waiting|agy|ask_question|", reported())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun activityShowsWhatTheAgentRuns() {
        val working = AgentStatus.parse("sh|working|1|codex|Bash|make test|t\n2")!!
        assertEquals("Running make test", agentActivity(working))
        assertEquals("Running sleep 6", agentActivity(working.copy(tool = "run_terminal_command", detail = "sleep 6")))
        assertEquals("Running ./gradlew test", agentActivity(working.copy(agent = "agy", tool = "run_command", detail = "./gradlew test")))
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

    @Test fun aFinishedToolStaysShownUntilTheNextOneOrTheTurnsEnd() {
        val dir = Files.createTempDirectory("agent-hook").toFile()
        try {
            val script = File(dir, "agent-hook.sh").apply { writeText(AgentHooks.script) }
            // A stand-in tmux keeping each option in a file: `show-options -qv -t P NAME`, `set-option -q -t P NAME VALUE`,
            // `set-option -u -q -t P NAME`.
            File(dir, "tmux").apply {
                writeText("#!/bin/sh\ncase \"\$1\" in\n  show-options) cat \"$dir/opt\$5\" 2>/dev/null ;;\n" +
                    "  set-option) if [ \"\$2\" = -u ]; then rm -f \"$dir/opt\$6\"; else printf '%s' \"\$6\" > \"$dir/opt\$5\"; fi ;;\nesac\n")
                setExecutable(true)
            }
            fun hook(event: String, json: String = "{}") {
                val process = ProcessBuilder("sh", script.path, "claude", event).apply {
                    environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
                    environment()["TMUX_PANE"] = "%1"
                }.start()
                process.outputStream.use { it.write(json.toByteArray()) }
                assertEquals(0, process.waitFor())
            }
            fun reported() = File(dir, "opt@roamtty_agent").readText().split('|').let { "${it[0]}|${it[3]}|${it[4]}" }

            hook("prompt", """{"prompt":"fix the bug"}""")
            assertEquals("working||", reported())
            hook("tool", """{"tool_name":"Bash","tool_input":{"command":"make test"}}""")
            assertEquals("working|Bash|make test", reported())
            hook("post")
            assertEquals("working|Bash|make test", reported())
            hook("tool", """{"tool_name":"Read","tool_input":{"file_path":"src/a.kt"}}""")
            hook("post")
            assertEquals("working|Read|src/a.kt", reported())
            val worked = File(dir, "opt@roamtty_worked")
            assertFalse("no turn has stopped yet", worked.exists())
            hook("stop")
            assertEquals("idle||", reported())
            // The stopped turn leaves how long it worked, in seconds; the next prompt clears it.
            assertTrue(worked.readText(), worked.readText().toLong() in 0L..60L)
            hook("prompt", """{"prompt":"again"}""")
            assertFalse(worked.exists())
            hook("end")
            assertFalse(File(dir, "opt@roamtty_turn").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aStoppedTurnLeavesTheStartOfItsAnswerUntilTheNextPrompt() {
        val dir = Files.createTempDirectory("agent-hook").toFile()
        try {
            val script = File(dir, "agent-hook.sh").apply { writeText(AgentHooks.script) }
            File(dir, "tmux").apply {
                writeText("#!/bin/sh\ncase \"\$1\" in\n  show-options) cat \"$dir/opt\$5\" 2>/dev/null ;;\n" +
                    "  set-option) if [ \"\$2\" = -u ]; then rm -f \"$dir/opt\$6\"; else printf '%s' \"\$6\" > \"$dir/opt\$5\"; fi ;;\nesac\n")
                setExecutable(true)
            }
            fun hook(agent: String, event: String, json: String = "{}") {
                val process = ProcessBuilder("sh", script.path, agent, event).apply {
                    environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
                    environment()["TMUX_PANE"] = "%1"
                    environment()["ROAMTTY_AGENT"] = "grok"
                }.start()
                process.outputStream.use { it.write(json.toByteArray()) }
                assertEquals(0, process.waitFor())
            }
            val reply = File(dir, "opt@roamtty_reply")
            hook("claude", "prompt", """{"prompt":"fix the bug"}""")
            // Claude Code and Codex name it in snake_case, its lines joined and any "|" kept out of the report.
            hook("claude", "stop", """{"stop_hook_active":false,"last_assistant_message":"**Fixed** it.\nTests pass | 3/3"}""")
            assertEquals("**Fixed** it. Tests pass / 3/3", reply.readText())
            hook("claude", "prompt", """{"prompt":"again"}""")
            assertFalse(reply.exists())
            // Grok in camelCase.
            hook("grok", "stop", """{"stopHookActive":false,"lastAssistantMessage":"Done."}""")
            assertEquals("Done.", reply.readText())
            hook("grok", "end")
            assertFalse(reply.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aTurnWaitingForBackgroundWorkGoesOnUntilThatWorkWakesItAndEnds() {
        val dir = Files.createTempDirectory("agent-hook").toFile()
        try {
            val script = File(dir, "agent-hook.sh").apply { writeText(AgentHooks.script) }
            File(dir, "tmux").apply {
                writeText("#!/bin/sh\ncase \"\$1\" in\n  show-options) cat \"$dir/opt\$5\" 2>/dev/null ;;\n" +
                    "  set-option) if [ \"\$2\" = -u ]; then rm -f \"$dir/opt\$6\"; else printf '%s' \"\$6\" > \"$dir/opt\$5\"; fi ;;\nesac\n")
                setExecutable(true)
            }
            fun hook(agent: String, event: String, json: String = "{}") {
                val process = ProcessBuilder("sh", script.path, agent, event).apply {
                    environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
                    environment()["TMUX_PANE"] = "%1"
                    environment()["ROAMTTY_AGENT"] = "grok"
                }.start()
                process.outputStream.use { it.write(json.toByteArray()) }
                assertEquals(0, process.waitFor())
            }
            fun option(name: String) = File(dir, "opt$name").takeIf { it.exists() }?.readText()
            fun reported() = option("@roamtty_agent")!!.split('|').let { "${it[0]}|${it[3]}|${it[4]}" }

            hook("claude", "prompt", """{"prompt":"run the suite"}""")
            val turn = option("@roamtty_turn")
            // Claude Code's Stop as it comes while a background shell runs (an answer quoting the key changes nothing).
            hook("claude", "stop", """{"stop_hook_active":false,"last_assistant_message":"started \"background_tasks\":[{}]",""" +
                """"background_tasks":[{"id":"b1","type":"shell","status":"running","description":"Run the suite","command":"make test"}],"session_crons":[]}""")
            assertEquals("working|Background|make test", reported())
            assertNull(option("@roamtty_worked"))
            // Idle for a minute only to look at: it stays at work.
            hook("claude", "notify", """{"notification_type":"idle_prompt","message":"Claude is waiting for your input"}""")
            assertEquals("working|Background|make test", reported())
            // The work ends and wakes it: the same turn and task go on, then end.
            hook("claude", "prompt", """{"prompt":"<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n</task-notification>"}""")
            assertEquals(turn, option("@roamtty_turn"))
            assertEquals("run the suite", option("@roamtty_task"))
            hook("claude", "stop", """{"last_assistant_message":"All green.","background_tasks":[],"session_crons":[]}""")
            assertEquals("idle||", reported())
            assertNotNull(option("@roamtty_worked"))
            assertEquals("All green.", option("@roamtty_reply"))
            // Grok names it in camelCase; a subagent says what it does. Only a scheduled wakeup is no work in flight.
            hook("grok", "prompt", """{"prompt":"review"}""")
            hook("grok", "stop", """{"lastAssistantMessage":"spawned","backgroundTasks":[{"id":"a1","type":"subagent","status":"running","description":"Review the diff","agentType":"general"}],"sessionCrons":[]}""")
            assertEquals("working|Background|Review the diff", reported())
            hook("grok", "stop", """{"lastAssistantMessage":"ok","backgroundTasks":[],"sessionCrons":[{"id":"c1","schedule":"*/5 * * * *"}]}""")
            assertEquals("idle||", reported())
            // A new prompt of the user's while work runs in the background starts a turn of its own.
            hook("claude", "stop", """{"background_tasks":[{"id":"b2","type":"shell","status":"running","command":"sleep 60"}]}""")
            File(dir, "opt@roamtty_turn").delete()
            hook("claude", "prompt", """{"prompt":"something else"}""")
            assertNotNull("the user's prompt starts a turn", option("@roamtty_turn"))
            assertEquals("something else", option("@roamtty_task"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun readsTheReplyOfAnIdleAgentOnly() {
        val idle = AgentStatus.parse("claude|idle|1000|claude|||75|fix the bug|All fixed.\n/home/v\n1042\n")!!
        assertEquals("fix the bug", idle.task)
        assertEquals("All fixed.", idle.reply)
        assertEquals(75L, idle.workedSeconds)
        assertEquals("", AgentStatus.parse("claude|working|1000|claude|Bash|ls||fix the bug|old\n1042")!!.reply)
        assertEquals("fix the bug", AgentStatus.parse("claude|working|1000|claude|Bash|ls||fix the bug|old\n1042")!!.task)
    }

    @Test fun aReplyReadsAsPlainText() {
        assertEquals("Fixed the parser in AgentStatus.kt, see docs; ready to merge.",
            replyPreview("## Fixed **the parser** in `AgentStatus.kt`, see [docs](https://x.y/z); ready   to merge."))
        assertEquals("Two branches - both fine", replyPreview("- Two branches - both fine"))
        assertEquals("__init__.py updated", replyPreview("__init__.py updated"))
    }

    @Test fun aSessionSentToTheBackgroundStillReportsToItsPane() {
        val dir = Files.createTempDirectory("agent-hook").toFile()
        try {
            val script = File(dir, "agent-hook.sh").apply { writeText(AgentHooks.script) }
            // The stand-in tmux of the test above, with one pane, %1, listed with its @roamtty_session.
            File(dir, "tmux").apply {
                writeText("#!/bin/sh\ncase \"\$1\" in\n  show-options) cat \"$dir/opt\$5\" 2>/dev/null ;;\n" +
                    "  set-option) if [ \"\$2\" = -u ]; then rm -f \"$dir/opt\$6\"; else printf '%s' \"\$6\" > \"$dir/opt\$5\"; fi ;;\n" +
                    "  list-panes) printf '%%1 %s\\n' \"\$(cat \"$dir/opt@roamtty_session\" 2>/dev/null)\" ;;\nesac\n")
                setExecutable(true)
            }
            val id = "32c4597e-61a4-4ddd-ba0a-51f68f9d1148"
            // In the pane, or (pane = null) under a process resuming the session, as Claude Code's background job runs.
            fun hook(event: String, json: String, pane: String?, resumed: String = id) {
                val command = if (pane != null) listOf("sh", script.path, "claude", event)
                    else listOf("sh", "-c", "sh '${script.path}' claude $event; :", "--resume", "/p/$resumed.jsonl", "--reply-on-resume")
                val process = ProcessBuilder(command).apply {
                    environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
                    environment().remove("TMUX_PANE")
                    if (pane != null) environment()["TMUX_PANE"] = pane
                    // The job's narrow terminal must not cut its command line short.
                    environment()["COLUMNS"] = "20"
                }.start()
                process.outputStream.use { it.write(json.toByteArray()) }
                assertEquals(0, process.waitFor())
            }
            fun reported() = File(dir, "opt@roamtty_agent").readText().split('|').let { "${it[0]}|${it[3]}|${it[4]}" }

            hook("tool", """{"session_id":"$id","tool_name":"Bash","tool_input":{"command":"make"}}""", "%1")
            assertEquals(id, File(dir, "opt@roamtty_session").readText())
            hook("post", """{"session_id":"forked"}""", null)
            hook("stop", """{"session_id":"forked"}""", null)
            assertEquals("idle||", reported())
            hook("prompt", """{"session_id":"forked","prompt":"next"}""", null)
            assertEquals("working||", reported())
            assertEquals("next", File(dir, "opt@roamtty_task").readText())
            // Its own session ids never replace the pane's, and its end leaves the pane's session for it to report by.
            hook("end", """{"session_id":"forked"}""", null)
            hook("start", """{"session_id":"cleared"}""", null)
            assertEquals("idle||", reported())
            assertEquals(id, File(dir, "opt@roamtty_session").readText())
            // A background session resumed from no pane reports nowhere.
            hook("prompt", """{"session_id":"other","prompt":"elsewhere"}""", null, resumed = "00000000-0000-0000-0000-000000000000")
            assertEquals("idle||", reported())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aStoppedTurnSaysHowLongItWorked() {
        val idle = AgentStatus.parse("claude|idle|1000|claude|||75|fix the bug\n/home/v\n1042\n")!!
        assertEquals(75L, idle.workedSeconds)
        assertEquals("fix the bug", idle.task)
        assertEquals("/home/v", idle.directory)
        // While a turn runs, or before any, there is nothing to say.
        assertNull(AgentStatus.parse("claude|working|1000|claude|Bash|ls||fix the bug\n1042")!!.workedSeconds)
        assertNull(AgentStatus.parse("claude|idle|1000|claude||||fix the bug\n1042")!!.workedSeconds)
        // A report without the field still reads.
        assertNull(AgentStatus.parse("claude|idle|1000|claude|||fix the bug\n1042")!!.workedSeconds)
    }

    @Test fun readsThePanesFolderBeforeTheClock() {
        val status = AgentStatus.parse("claude|idle|1000|claude|||\n/home/v/roamtty\n1042\n")!!
        assertEquals("/home/v/roamtty", status.directory)
        assertEquals(42_000, status.elapsedMillis)
        assertEquals("", AgentStatus.parse("claude|idle|1000|claude|||\n1042")!!.directory)
    }

    @Test fun readsThePanesFolderWithoutAnAgent() {
        // A shell, or an agent whose hooks have not reported yet: no status, but the folder is still known.
        assertNull(AgentStatus.parse("bash||||||\n/home/v/work\n1042"))
        assertEquals("/home/v/work", AgentStatus.directory("bash||||||\n/home/v/work\n1042"))
        assertEquals("~/work", sessionListPath("/home/v/work", "v"))
        assertEquals("", AgentStatus.directory("bash||||||\n1042"))
        assertEquals("", AgentStatus.directory(null))
        assertEquals("", sessionListPath("", "v"))
    }

    @Test fun listTitleIsTheTaskThenTheAgentAndFolderThenTheTitle() {
        val working = AgentStatus.parse("claude|working|1|claude|||fix the bug\n/home/v/roamtty\n2")!!
        assertEquals("fix the bug", sessionListTitle("Claude · roamtty", working, CodingAgent.CLAUDE, "v"))
        val fresh = working.copy(task = "")
        assertEquals("Claude · roamtty", sessionListTitle("Claude · roamtty", fresh, CodingAgent.CLAUDE, "v"))
        assertEquals("~/roamtty", sessionListPath(fresh, "v"))
        assertEquals("Claude · ~", sessionListTitle("", fresh.copy(directory = "/Users/v"), CodingAgent.CLAUDE, "v"))
        assertEquals("Codex · /", sessionListTitle("", fresh.copy(directory = "/"), CodingAgent.CODEX, "v"))
        assertEquals("web", sessionListTitle("", fresh.copy(directory = "/srv/web"), null, "v"))
        assertEquals("/srv/web", sessionListPath(fresh.copy(directory = "/srv/web"), "v"))
        assertEquals("~/x", sessionListPath(fresh.copy(directory = "/root/x"), "root"))
        assertEquals("", sessionListPath(null, "v"))
        assertEquals("Claude · roamtty", sessionListTitle("Claude · roamtty", null, CodingAgent.CLAUDE, "v"))
        assertEquals("Claude · roamtty", sessionListTitle("Claude Code · roamtty", null, CodingAgent.CLAUDE, "v"))
        assertEquals("部署脚本", sessionListTitle("部署脚本", null, CodingAgent.CLAUDE, "v"))
        assertEquals("Claude · roamtty", sessionListTitle("Claude · roamtty", null, null, "v"))
    }

    @Test fun aListedTmuxSessionTellsItsAgentOrWhetherOnlyAShellRuns() {
        val claude = TmuxSessionInfo("a", 1, 0, 0, "/home/v/app", "claude", "working|1|claude|Bash|ls||fix it|")
        assertEquals(AgentStatus.State.WORKING, claude.agent?.state)
        assertEquals("fix it", claude.agent?.task)
        assertEquals("/home/v/app", claude.agent?.directory)
        assertFalse(claude.idleShell)
        // A pane back at its shell leaves an agent's last report behind: it no longer counts; a program still shows.
        val stale = TmuxSessionInfo("b", 1, 0, 0, "/tmp", "-zsh", "idle|0|claude||||task|")
        assertNull(stale.agent); assertTrue(stale.idleShell)
        val vim = TmuxSessionInfo("c", 1, 0, 0, "", "vim", "|||")
        assertNull(vim.agent); assertFalse(vim.idleShell)
    }
}
