package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the real install, uninstall and hook scripts in a temporary HOME. */
class AgentNotifyScriptTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var home: File
    private lateinit var bin: File

    @Before fun setUp() {
        assumeTrue(File("/bin/sh").canExecute())
        home = temp.newFolder("home")
        bin = temp.newFolder("bin")
    }

    private val python get() = listOf("/usr/bin/python3", "/usr/local/bin/python3", "/bin/python3").firstOrNull { File(it).canExecute() }

    @Test fun installMergesIntoExistingConfigsAndIsIdempotent() {
        assumeTrue(python != null)
        val claude = File(home, ".claude/settings.json").apply {
            parentFile.mkdirs()
            writeText("""{"model":"opus","hooks":{"Stop":[{"hooks":[{"type":"command","command":"say done"}]}]}}""")
        }
        File(home, ".codex").mkdirs()
        File(home, ".grok").mkdirs()
        val before = claude.readText()

        val (status, output) = run(AgentNotify.installScript(), withPython = true)
        assertEquals(output, 0, status)
        assertTrue(output, HostPluginInstaller.DONE in output)
        assertEquals(mapOf("claude" to "ok", "codex" to "ok", "grok" to "ok"), AgentNotify.cliResults(output))
        // No codex binary on this PATH: it is trusted when Roamtty launches it.
        assertEquals("unavailable", AgentNotify.codexTrust(output))
        assertTrue(File(home, AgentNotify.SCRIPT).canExecute())
        assertTrue(File(home, ".claude/settings.json.roamtty.bak").exists())
        assertTrue(File(home, ".grok/hooks/roamtty.json").exists())
        val merged = claude.readText()
        assertTrue(merged, "say done" in merged && "\"opus\"" in merged)

        run(AgentNotify.installScript(), withPython = true)
        assertEquals(merged, claude.readText())
        assertEquals(2, Regex("roamtty-notify resolved codex").findAll(File(home, ".codex/hooks.json").readText()).count())

        val (removed, log) = run(AgentNotify.uninstallScript(), withPython = true)
        assertEquals(log, 0, removed)
        assertTrue(sameJson(before, claude.readText()))
        assertFalse(File(home, ".codex/hooks.json").exists())
        assertFalse(File(home, ".grok/hooks/roamtty.json").exists())
        assertFalse(File(home, ".roamtty").exists())
    }

    @Test fun withoutPythonOnlyMissingConfigsAreWritten() {
        File(home, ".claude/settings.json").apply { parentFile.mkdirs(); writeText("""{"model":"opus"}""") }
        File(home, ".codex").mkdirs()
        val (status, output) = run(AgentNotify.installScript(), withPython = false)
        assertEquals(output, 0, status)
        assertEquals(mapOf("claude" to AgentNotify.NO_PYTHON, "codex" to "ok", "grok" to "absent"), AgentNotify.cliResults(output))
        assertEquals("""{"model":"opus"}""", File(home, ".claude/settings.json").readText())
        val codex = File(home, ".codex/hooks.json").readText()
        assertTrue(codex, "\"${home.path}/${AgentNotify.SCRIPT} attention codex\"" in codex)
        if (python != null) assertTrue(sameJson(codex, codex))
    }

    @Test fun hookWritesAttentionThenResolvedOnce() = hookRoundTrip(withPython = true)

    @Test fun hookWorksWithoutPython() = hookRoundTrip(withPython = false)

    private fun hookRoundTrip(withPython: Boolean) {
        if (withPython) assumeTrue(python != null)
        run(AgentNotify.installScript(), withPython)
        val events = File(home, ".roamtty/events.jsonl")
        val hook = File(home, AgentNotify.SCRIPT).path

        hook(hook, listOf("resolved"), """{"session_id":"s1","hook_event_name":"PostToolUse"}""", withPython)
        assertEquals("", events.readText())

        hook(hook, listOf("attention"), """{"session_id":"s1","hook_event_name":"Notification","notification_type":"permission_prompt",""" +
            """"message":"Claude needs your permission to use \"Bash\"","cwd":"/work"}""", withPython)
        val attention = AgentEvent.parse(events.readLines().last())
        assertNotNull(events.readText(), attention)
        assertEquals(AgentEvent.ATTENTION, attention!!.kind)
        assertEquals("claude", attention.agent)
        assertEquals("permission", attention.reason)
        assertEquals("s1", attention.key)
        assertEquals("Claude needs your permission to use \"Bash\"", attention.msg)

        hook(hook, listOf("attention", "codex"), """{"session_id":"s2","hook_event_name":"PermissionRequest",""" +
            """"tool_name":"Bash","tool_input":{"command":"rm -rf build","description":"Remove the build folder"}}""", withPython)
        val codex = AgentEvent.parse(events.readLines().last())!!
        assertEquals("codex", codex.agent)
        assertEquals("s2", codex.key)
        assertEquals("Remove the build folder", codex.msg)

        hook(hook, listOf("resolved"), """{"session_id":"s1","hook_event_name":"PostToolUse"}""", withPython)
        hook(hook, listOf("resolved"), """{"session_id":"s1","hook_event_name":"Stop"}""", withPython)
        val resolved = events.readLines().drop(2).mapNotNull(AgentEvent::parse)
        assertEquals(listOf(AgentEvent.RESOLVED to "s1"), resolved.map { it.kind to it.key })
    }

    @Test fun questionsFromClaudeAndGrok() {
        assumeTrue(python != null)
        run(AgentNotify.installScript(), withPython = true)
        val hook = File(home, AgentNotify.SCRIPT).path
        hook(hook, listOf("attention"), """{"session_id":"s1","hook_event_name":"PreToolUse","tool_name":"AskUserQuestion",""" +
            """"tool_input":{"questions":[{"question":"Which database?","options":[]}]}}""", true)
        hook(hook, listOf("attention"), """{"sessionId":"g1","hook_event_name":"PreToolUse","toolName":"ask_user_question",""" +
            """"toolInput":{"question":"Ship it?"}}""", true, mapOf("GROK_SESSION_ID" to "g1"))
        val events = File(home, ".roamtty/events.jsonl").readLines().map { AgentEvent.parse(it)!! }
        assertEquals(listOf("claude" to "Which database?", "grok" to "Ship it?"), events.map { it.agent to it.msg })
        assertEquals(listOf("question", "question"), events.map { it.reason })
    }

    @Test fun probeReportsNotify() {
        val base = "${HostPluginProbe.TMUX}/usr/bin/tmux\n${HostPluginProbe.MOSH}\n"
        assertEquals(setOf(HostPlugin.TMUX), HostPluginProbe.parse(base))
        assertEquals(setOf(HostPlugin.TMUX, HostPlugin.NOTIFY), HostPluginProbe.parse(base + "${HostPluginProbe.NOTIFY}1\n"))
    }

    private fun hook(script: String, args: List<String>, input: String, withPython: Boolean, env: Map<String, String> = emptyMap()) {
        val process = ProcessBuilder(listOf("/bin/sh", script) + args).redirectErrorStream(true).apply {
            environment().apply { clear(); put("PATH", path(withPython)); put("HOME", home.path); putAll(env) }
        }.start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        // Grok reads a PostToolUse hook's stdout; the hook must stay silent.
        assertEquals("", output)
        assertEquals(0, process.exitValue())
    }

    private fun run(script: String, withPython: Boolean): Pair<Int, String> {
        val process = ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true).apply {
            environment().apply { clear(); put("PATH", path(withPython)); put("HOME", home.path) }
        }.start()
        process.outputStream.use { it.write(script.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    /** Core tools only, so no real claude, codex or grok on this machine is touched. */
    private fun path(withPython: Boolean): String {
        val tools = listOf("sh", "cat", "mkdir", "chmod", "mv", "touch", "grep", "sed", "tr", "cut", "head", "tail", "wc",
            "date", "rm", "rmdir", "printf", "uname", "id", "dirname") + if (withPython) listOf("python3") else emptyList()
        val dir = File(bin, if (withPython) "py" else "plain").apply { mkdirs() }
        for (tool in tools) {
            val link = File(dir, tool)
            if (link.exists()) continue
            val target = listOf("/usr/bin/$tool", "/bin/$tool", "/usr/local/bin/$tool").firstOrNull { File(it).canExecute() } ?: continue
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(target).toPath())
        }
        return dir.path
    }

    private fun sameJson(a: String, b: String): Boolean {
        val process = ProcessBuilder(python!!, "-c", "import json,sys; print(json.loads(sys.argv[1]) == json.loads(sys.argv[2]))", a, b).start()
        return process.inputStream.bufferedReader().readText().trim() == "True"
    }
}
