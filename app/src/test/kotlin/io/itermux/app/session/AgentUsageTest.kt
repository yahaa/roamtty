package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

class AgentUsageTest {
    @Test fun parsesSessionUsageWithQuotasAndContext() {
        val raw = """
            agent=codex
            model=gpt-5.4
            effort=xhigh
            ctx_used=164000
            ctx_limit=200000
            ctx_cache_read=104000
            ctx_cache_create=24000
            ctx_turn=36000
            sess_input=78000
            sess_output=44000
            sess_cache_read=1060000
            sess_cache_create=0
            q5h_rem=38.0
            q5h_reset=1795608000
            q7d_rem=59.2
            q7d_reset=1795900000
        """.trimIndent()
        val u = AgentUsageCollector.parseSession(raw)!!
        assertEquals(CodingAgent.CODEX, u.agent)
        assertEquals("gpt-5.4", u.formattedModel)
        assertEquals(ReasoningEffort.XHIGH, u.reasoningEffort)
        assertEquals(4, u.reasoningEffort?.bars)
        assertEquals(82, u.contextPercent)
        assertEquals(36_000L, u.contextRemaining)
        assertEquals(1_182_000L, u.sessionTotalTokens)
        assertEquals(93, u.cacheHitPercent)
        assertEquals(38, u.quota5h?.remainingRounded)
        assertEquals(62, u.quota5h?.usedRounded)
        assertEquals(59, u.quota7d?.remainingRounded)
        assertEquals(41, u.quota7d?.usedRounded)
    }

    @Test fun degradesGracefullyWhenQuotasOrContextAreAbsent() {
        val raw = """
            agent=grok
            model=grok-4.7
            sess_input=120000
            sess_output=8000
            sess_cache_read=252000
            sess_cache_create=0
        """.trimIndent()
        val u = AgentUsageCollector.parseSession(raw)!!
        assertEquals(CodingAgent.GROK, u.agent)
        assertNull(u.contextPercent)
        assertNull(u.quota5h)
        assertNull(u.quota7d)
        assertEquals(380_000L, u.sessionTotalTokens)
        assertNull(CodingAgent.GROK.compactCommand)
        assertEquals("/compact", CodingAgent.CLAUDE.compactCommand)
        assertEquals("/compact", CodingAgent.CODEX.compactCommand)
        assertEquals("/compact", CodingAgent.AGY.compactCommand)
    }

    @Test fun parsesHostMultiAgentUsageSortedByTotalTokens() {
        val lines = listOf(
            "range=7d",
            "agent=grok|1|120000|10000|250000|0||||",
            "agent=claude|3|200000|100000|3160000|0||||",
            "agent=codex|2|100000|50000|1650000|0|66.0|1795608000|81.0|1795900000",
        )
        val host = AgentUsageCollector.parseHost(lines, HostUsageRange.TODAY)
        assertEquals(HostUsageRange.WEEK, host.range)
        assertEquals(listOf(CodingAgent.CLAUDE, CodingAgent.CODEX, CodingAgent.GROK), host.agents.map { it.agent })
        assertEquals(5_640_000L, host.totalTokens)
        assertEquals(92, host.cacheHitPercent)
        assertEquals(66, host.agents[1].quota5h?.remainingRounded)
        assertEquals(81, host.agents[1].quota7d?.remainingRounded)
        assertNull(host.agents[2].quota5h)
    }

    @Test fun formatsTokensModelsAndResets() {
        assertEquals("840", formatTokens(840))
        assertEquals("4.2k", formatTokens(4_200))
        assertEquals("164k", formatTokens(164_000))
        assertEquals("1.18M", formatTokens(1_180_000))
        assertEquals("1.54B", formatTokens(1_540_000_000))
        assertEquals("Opus 4.6", formatModelName("claude-opus-4-6-20260301"))
        assertEquals("Fable 5.2", formatModelName("claude-fable-5-2"))
        assertEquals("Gemini 4 Neon", formatModelName("gemini-4-neon-xhigh"))
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.fromRaw("med"))
        assertEquals(2, ReasoningEffort.MEDIUM.bars)
        assertEquals("2h 14m 后重置", formatQuotaReset(10_000 + 8_040, nowEpochSeconds = 10_000))
        assertEquals("2h14m", formatQuotaReset(10_000 + 8_040, nowEpochSeconds = 10_000, compact = true))
        val weekly = formatQuotaReset(1_795_900_800, nowEpochSeconds = 1_795_600_000, zone = ZoneOffset.UTC)
        assertNotNull(weekly)
        assertTrue(weekly!!.endsWith("重置"))
    }

    @Test fun collectorScriptParsesRealFixtureTranscripts() {
        val home = Files.createTempDirectory("roamtty-usage-fixture").toFile()
        try {
            val nowSec = System.currentTimeMillis() / 1000L
            val futureReset = nowSec + 7200
            val codexDir = File(home, ".codex/sessions/2026/11/25").apply { mkdirs() }
            File(codexDir, "rollout-test-abc.jsonl").writeText(
                """{"timestamp":"2099-01-01T00:00:00Z","type":"session_meta","payload":{"cwd":"/work/app"}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:00:01Z","type":"turn_context","payload":{"model":"gpt-5.4","effort":"high","cwd":"/work/app"}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:00:02Z","type":"token_usage_record","payload":{"usage":{"input_tokens":50000,"cached_input_tokens":40000,"cache_write_input_tokens":0,"output_tokens":2000}}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:00:03Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":50000,"cached_input_tokens":40000,"cache_write_input_tokens":0,"output_tokens":2000},"last_token_usage":{"input_tokens":50000,"cached_input_tokens":40000,"output_tokens":2000},"model_context_window":200000},"rate_limits":{"primary":{"used_percent":30.0,"window_minutes":300,"resets_at":$futureReset},"secondary":{"used_percent":15.0,"window_minutes":10080,"resets_at":${futureReset + 100000}}}}}""" + "\n"
            )
            // Exercise real agent-hook.sh claude statusline caching 5h + 7d weekly quotas.
            val hookFile = File(home, "agent-hook.sh").apply { writeText(AgentHooks.script); setExecutable(true) }
            val claudeDir = File(home, ".claude/projects/-work-app").apply { mkdirs() }
            File(claudeDir, "s1.jsonl").writeText(
                """{"type":"assistant","effort":"xhigh","cwd":"/work/app","timestamp":"2099-01-01T00:00:01Z","message":{"id":"m1","model":"claude-opus-4-6","usage":{"input_tokens":1000,"output_tokens":500,"cache_read_input_tokens":9000,"cache_creation_input_tokens":0}}}""" + "\n"
            )
            val slProc = ProcessBuilder("sh", hookFile.absolutePath, "claude", "statusline").apply {
                environment()["HOME"] = home.absolutePath
            }.start()
            slProc.outputStream.use {
                it.write("""{"rate_limits":{"five_hour":{"used_percentage":25.0,"resets_at":$futureReset},"seven_day":{"used_percentage":40.0,"resets_at":${futureReset + 100000}}}}""".toByteArray())
            }
            assertEquals(0, slProc.waitFor())
            // Exercise Antigravity cached quota group selection + effort parsing.
            val agyLogDir = File(home, ".gemini/antigravity-cli/brain/conv1/.system_generated/logs").apply { mkdirs() }
            File(agyLogDir, "transcript.jsonl").writeText(
                """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:00:01Z","model":"gemini-4-neon-xhigh","input_tokens":10000,"output_tokens":1000,"cache_read_tokens":90000}""" + "\n"
            )
            File(home, ".roamtty/quota-agy.json").apply { parentFile?.mkdirs() }.writeText(
                """{"_fetched_at":$nowSec,"command":{"data":{"groups":[{"name":"Gemini Models","buckets":[{"window":"5h","remaining_fraction":0.88,"reset_time":"2099-01-01T05:00:00Z"},{"window":"weekly","remaining_fraction":0.76,"reset_time":"2099-01-07T00:00:00Z"}]}]}}}"""
            )
            val proc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.hostScript(HostUsageRange.TODAY)).apply {
                environment()["HOME"] = home.absolutePath
                environment()["ROAMTTY_NO_LIVE_QUOTA"] = "1"
            }.start()
            val out = proc.inputStream.bufferedReader().readLines()
            assertEquals(0, proc.waitFor())
            val host = AgentUsageCollector.parseHost(out, HostUsageRange.TODAY)
            assertEquals(3, host.agents.size)
            val codex = host.agents.first { it.agent == CodingAgent.CODEX }
            assertEquals(1, codex.sessions)
            assertEquals(52_000L, codex.totalTokens)
            assertEquals(70, codex.quota5h?.remainingRounded)
            assertEquals(85, codex.quota7d?.remainingRounded)
            val claudeHost = host.agents.first { it.agent == CodingAgent.CLAUDE }
            assertEquals(75, claudeHost.quota5h?.remainingRounded)
            assertEquals(60, claudeHost.quota7d?.remainingRounded)
            val agyHost = host.agents.first { it.agent == CodingAgent.AGY }
            assertEquals(88, agyHost.quota5h?.remainingRounded)
            assertEquals(76, agyHost.quota7d?.remainingRounded)
            val sessProc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.sessionCommand(CodingAgent.CODEX, null, "/work/app")).apply {
                environment()["HOME"] = home.absolutePath
                environment()["ROAMTTY_NO_LIVE_QUOTA"] = "1"
            }.start()
            sessProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            val sessOut = sessProc.inputStream.bufferedReader().readText()
            assertEquals(0, sessProc.waitFor())
            val sess = AgentUsageCollector.parseSession(sessOut)!!
            assertEquals(CodingAgent.CODEX, sess.agent)
            assertEquals(ReasoningEffort.HIGH, sess.reasoningEffort)
            assertEquals(25, sess.contextPercent)
            assertEquals(70, sess.quota5h?.remainingRounded)
            val agySessProc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.sessionCommand(CodingAgent.AGY, null, "/work/app")).apply {
                environment()["HOME"] = home.absolutePath
                environment()["ROAMTTY_NO_LIVE_QUOTA"] = "1"
            }.start()
            agySessProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            val agySess = AgentUsageCollector.parseSession(agySessProc.inputStream.bufferedReader().readText())!!
            assertEquals("Gemini 4 Neon", agySess.formattedModel)
            assertEquals(ReasoningEffort.XHIGH, agySess.reasoningEffort)
            assertEquals(88, agySess.quota5h?.remainingRounded)
            assertEquals(76, agySess.quota7d?.remainingRounded)
        } finally {
            home.deleteRecursively()
        }
    }
}
