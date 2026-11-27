package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("/compact", CodingAgent.GROK.compactCommand)
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

    @Test fun collectorDropsContextUsageImmediatelyAfterCompactionAcrossAgents() {
        val home = Files.createTempDirectory("roamtty-usage-compact").toFile()
        try {
            fun runSession(agent: CodingAgent, cwd: String): AgentSessionUsage {
                val proc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.sessionCommand(agent, null, cwd)).apply {
                    environment()["HOME"] = home.absolutePath
                    environment()["ROAMTTY_NO_LIVE_QUOTA"] = "1"
                }.start()
                proc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
                val text = proc.inputStream.bufferedReader().readText()
                assertEquals(0, proc.waitFor())
                return AgentUsageCollector.parseSession(text)!!
            }

            // 1. Antigravity: CHECKPOINT after high context drops 43% -> ~2% before next PLANNER_RESPONSE, preserving 1M window.
            val agyLogDir = File(home, ".gemini/antigravity-cli/brain/conv-compact/.system_generated/logs").apply { mkdirs() }
            val agyFile = File(agyLogDir, "transcript.jsonl")
            agyFile.writeText(
                """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:00:01Z","model":"gemini-4-neon-xhigh","input_tokens":12180,"output_tokens":300,"cache_read_tokens":0}""" + "\n" +
                    """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:10:00Z","model":"gemini-4-neon-xhigh","input_tokens":2519,"output_tokens":1597,"cache_read_tokens":426997}""" + "\n" +
                    """{"type":"CHECKPOINT","created_at":"2099-01-01T00:15:00Z","input_tokens":2506,"output_tokens":5080,"cache_read_tokens":429517,"content":"# Resuming from a compaction"}""" + "\n"
            )
            val agyPost = runSession(CodingAgent.AGY, "/work/app")
            assertEquals(17_260L, agyPost.contextUsed)
            assertEquals(1_000_000L, agyPost.contextLimit)
            assertEquals(2, agyPost.contextPercent)
            assertEquals(0L, agyPost.contextCacheRead)
            assertEquals(0L, agyPost.contextTurn)
            // Once the next PLANNER_RESPONSE arrives, exact model-reported tokens take over.
            agyFile.appendText(
                """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:16:00Z","model":"gemini-4-neon-xhigh","input_tokens":9099,"output_tokens":500,"cache_read_tokens":11981}""" + "\n"
            )
            val agyNext = runSession(CodingAgent.AGY, "/work/app")
            assertEquals(21_080L, agyNext.contextUsed)
            assertEquals(11_981L, agyNext.contextCacheRead)
            assertEquals(9_099L, agyNext.contextTurn)

            // 2. Claude Code: compact_boundary drops context immediately to postTokens and keeps 1M window when preTokens > 200k.
            val claudeDir = File(home, ".claude/projects/-work-app").apply { mkdirs() }
            val claudeFile = File(claudeDir, "s-compact.jsonl")
            claudeFile.writeText(
                """{"type":"assistant","effort":"high","cwd":"/work/app","timestamp":"2099-01-01T00:00:01Z","message":{"id":"m1","model":"claude-opus-4-6","usage":{"input_tokens":5000,"output_tokens":400,"cache_read_input_tokens":781844,"cache_creation_input_tokens":0}}}""" + "\n" +
                    """{"type":"system","subtype":"compact_boundary","cwd":"/work/app","timestamp":"2099-01-01T00:05:00Z","compactMetadata":{"trigger":"manual","preTokens":786844,"postTokens":15880,"cumulativeDroppedTokens":770964}}""" + "\n"
            )
            val claudePost = runSession(CodingAgent.CLAUDE, "/work/app")
            assertEquals(15_880L, claudePost.contextUsed)
            assertEquals(1_000_000L, claudePost.contextLimit)
            assertEquals(2, claudePost.contextPercent)
            assertEquals(0L, claudePost.contextCacheRead)
            assertEquals(0L, claudePost.contextTurn)

            // 3. Codex: post-compaction token_count has input_tokens=0 and retained context in total_tokens.
            val codexDir = File(home, ".codex/sessions/2026/11/25").apply { mkdirs() }
            File(codexDir, "rollout-compact.jsonl").writeText(
                """{"timestamp":"2099-01-01T00:00:00Z","type":"session_meta","payload":{"cwd":"/work/app"}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:00:01Z","type":"turn_context","payload":{"model":"gpt-5.4","effort":"high","cwd":"/work/app"}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:01:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":243047,"cached_input_tokens":242944,"output_tokens":6845},"last_token_usage":{"input_tokens":243047,"cached_input_tokens":242944,"output_tokens":6845,"total_tokens":249892},"model_context_window":258400}}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:02:00Z","type":"compacted","payload":{"message":""}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:02:01Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":243047,"cached_input_tokens":242944,"output_tokens":6845},"last_token_usage":{"input_tokens":0,"cached_input_tokens":0,"output_tokens":0,"total_tokens":37754},"model_context_window":258400}}}""" + "\n"
            )
            val codexPost = runSession(CodingAgent.CODEX, "/work/app")
            assertEquals(37_754L, codexPost.contextUsed)
            assertEquals(258_400L, codexPost.contextLimit)
            assertEquals(15, codexPost.contextPercent)
            assertEquals(0L, codexPost.contextCacheRead)
            assertEquals(0L, codexPost.contextTurn)

            // 4. Grok: multi-call turn reads context from signals.json + _meta.totalTokens, honoring summary context_window.
            val grokDir = File(home, ".grok/sessions/%2Fwork%2Fgrok/sess-multi").apply { mkdirs() }
            File(grokDir, "summary.json").writeText(
                """{"current_model_id":"grok-4.7","reasoning_effort":"xhigh","context_window":500000}"""
            )
            File(grokDir, "signals.json").writeText(
                """{"contextTokensUsed":193909,"contextWindowTokens":500000,"contextWindowUsage":38}"""
            )
            File(grokDir, "updates.jsonl").writeText(
                """{"timestamp":4070908800,"method":"session/update","params":{"_meta":{"totalTokens":193909},"update":{"sessionUpdate":"agent_message_chunk"}}}""" + "\n" +
                    """{"timestamp":4070908801,"method":"_x.ai/session/update","params":{"update":{"sessionUpdate":"turn_completed","usage":{"inputTokens":1911236,"cachedReadTokens":1897344,"cacheCreationTokens":0,"outputTokens":6724,"modelCalls":10}}}}""" + "\n"
            )
            val grokSess = runSession(CodingAgent.GROK, "/work/grok")
            assertEquals("grok-4.7", grokSess.formattedModel)
            assertEquals(ReasoningEffort.XHIGH, grokSess.reasoningEffort)
            assertEquals(193_909L, grokSess.contextUsed)
            assertEquals(500_000L, grokSess.contextLimit)
            assertEquals(39, grokSess.contextPercent)
            assertEquals(0L, grokSess.contextCacheRead)
            assertEquals(0L, grokSess.contextTurn)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun agyQuotaStrictlyReadOnlyAndNeverInvokesAgyOrTmux() {
        val home = Files.createTempDirectory("roamtty-agy-readonly-quota").toFile()
        try {
            val binDir = File(home, ".local/bin").apply { mkdirs() }
            val agyMarker = File(home, "agy-invoked.log")
            val tmuxMarker = File(home, "tmux-runshell.log")
            File(binDir, "agy").apply {
                writeText("#!/bin/sh\necho \"\$@\" >> \"${agyMarker.absolutePath}\"\nexit 0\n")
                setExecutable(true)
            }
            File(binDir, "tmux").apply {
                writeText("#!/bin/sh\necho \"\$@\" >> \"${tmuxMarker.absolutePath}\"\nexit 0\n")
                setExecutable(true)
            }
            val agyLogDir = File(home, ".gemini/antigravity-cli/brain/conv-ro/.system_generated/logs").apply { mkdirs() }
            File(agyLogDir, "transcript.jsonl").writeText(
                """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:00:01Z","model":"gemini-4-neon-xhigh","input_tokens":5000,"output_tokens":500,"cache_read_tokens":20000}""" + "\n"
            )
            val envPath = "${binDir.absolutePath}:${System.getenv("PATH") ?: "/usr/bin:/bin"}"

            // 1. Without a statusLine cache, session + host reads (and even legacy --fetch-agy-quota) must NEVER invoke agy or tmux run-shell.
            val sessCmd = AgentUsageCollector.sessionCommand(CodingAgent.AGY, null, "/work/app")
            val sessProc = ProcessBuilder("/bin/sh", "-c", sessCmd).apply {
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = envPath
            }.start()
            sessProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            val sess = AgentUsageCollector.parseSession(sessProc.inputStream.bufferedReader().readText())!!
            assertEquals(0, sessProc.waitFor())
            assertNull(sess.quota5h)
            assertNull(sess.quota7d)
            assertFalse("session read must never invoke agy", agyMarker.exists())
            assertFalse("session read must never invoke tmux run-shell", tmuxMarker.exists())

            val legacyFlagProc = ProcessBuilder("python3", "-", "session", "agy", "", "/work/app", "--fetch-agy-quota").apply {
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = envPath
            }.start()
            legacyFlagProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            assertEquals(0, legacyFlagProc.waitFor())
            assertFalse("legacy --fetch-agy-quota must never invoke agy", agyMarker.exists())
            assertFalse("legacy --fetch-agy-quota must never invoke tmux run-shell", tmuxMarker.exists())

            val hostProc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.hostScript(HostUsageRange.TODAY)).apply {
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = envPath
            }.start()
            val host = AgentUsageCollector.parseHost(hostProc.inputStream.bufferedReader().readLines(), HostUsageRange.TODAY)
            assertEquals(0, hostProc.waitFor())
            assertEquals(1, host.agents.size)
            assertNull(host.agents[0].quota5h)
            assertFalse("host read must never invoke agy", agyMarker.exists())

            // 2. Once populated by official statusLine hook, both session & host reads report exact values without double-rounding.
            val hookFile = File(home, "agent-hook.sh").apply { writeText(AgentHooks.script); setExecutable(true) }
            val slProc = ProcessBuilder("sh", hookFile.absolutePath, "agy", "statusline").apply {
                environment()["HOME"] = home.absolutePath
                // Also verify statusline works even without ROAMTTY_AGENT (e.g. user-configured /statusline).
                environment().remove("ROAMTTY_AGENT")
            }.start()
            slProc.outputStream.use {
                val payload = """{"conversation_id":"conv-ro","model":{"id":"Gemini 4 Neon (XHigh)"},"quota":{""" +
                    """"gemini-5h":{"remaining_fraction":0.9454,"reset_time":"2099-01-01T05:00:00Z","reset_in_seconds":5000},""" +
                    """"gemini-weekly":{"remaining_fraction":0.7648,"reset_time":"2099-01-07T00:00:00Z","reset_in_seconds":500000}}}"""
                it.write(payload.toByteArray())
            }
            assertEquals(0, slProc.waitFor())

            val cachedSessProc = ProcessBuilder("/bin/sh", "-c", sessCmd).apply {
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = envPath
            }.start()
            cachedSessProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            val cachedSess = AgentUsageCollector.parseSession(cachedSessProc.inputStream.bufferedReader().readText())!!
            assertEquals(0, cachedSessProc.waitFor())
            assertEquals(95, cachedSess.quota5h?.remainingRounded)
            // 0.7648 -> 76.48% must round to 76%, not double-round 76.48 -> 76.5 -> 77%.
            assertEquals(76, cachedSess.quota7d?.remainingRounded)

            val cachedHostProc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.hostScript(HostUsageRange.TODAY)).apply {
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = envPath
            }.start()
            val cachedHost = AgentUsageCollector.parseHost(cachedHostProc.inputStream.bufferedReader().readLines(), HostUsageRange.TODAY)
            assertEquals(0, cachedHostProc.waitFor())
            assertEquals(95, cachedHost.agents[0].quota5h?.remainingRounded)
            assertEquals(76, cachedHost.agents[0].quota7d?.remainingRounded)
            assertFalse("cached reads must never invoke agy", agyMarker.exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun agyStatusLineCachesLiveQuotaAndCodexUsesNewestAccountQuota() {
        val home = Files.createTempDirectory("roamtty-quota-freshness").toFile()
        try {
            val nowSec = System.currentTimeMillis() / 1000L
            val futureReset = nowSec + 7200

            // 1. agy statusLine hook caches live quota (including untouched 1970-01-01T00:00:00Z 100% 3p buckets).
            val hookFile = File(home, "agent-hook.sh").apply { writeText(AgentHooks.script); setExecutable(true) }
            val slProc = ProcessBuilder("sh", hookFile.absolutePath, "agy", "statusline").apply {
                environment()["HOME"] = home.absolutePath
                environment()["ROAMTTY_AGENT"] = "agy"
            }.start()
            slProc.outputStream.use {
                val payload = """{"model":{"id":"Gemini 4 Neon (XHigh)"},"quota":{""" +
                    """"gemini-5h":{"remaining_fraction":0.835,"reset_time":"2099-01-01T05:00:00Z","reset_in_seconds":5214},""" +
                    """"gemini-weekly":{"remaining_fraction":0.798,"reset_time":"2099-01-07T00:00:00Z","reset_in_seconds":574014},""" +
                    """"3p-5h":{"remaining_fraction":1.0,"reset_time":"1970-01-01T00:00:00Z","reset_in_seconds":0},""" +
                    """"3p-weekly":{"remaining_fraction":1.0,"reset_time":"1970-01-01T00:00:00Z","reset_in_seconds":0}}}"""
                it.write(payload.toByteArray())
            }
            assertEquals(0, slProc.waitFor())

            val agyLogDir = File(home, ".gemini/antigravity-cli/brain/conv-sl/.system_generated/logs").apply { mkdirs() }
            File(agyLogDir, "transcript.jsonl").writeText(
                """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:00:01Z","model":"claude-opus-4-6","input_tokens":1000,"output_tokens":200,"cache_read_tokens":5000}""" + "\n"
            )
            // Claude model reads untouched 3p buckets (100% remaining, null reset instead of dropped as expired).
            val claude3pProc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.sessionCommand(CodingAgent.AGY, null, "/work/app")).apply {
                environment()["HOME"] = home.absolutePath
                environment()["ROAMTTY_NO_LIVE_QUOTA"] = "1"
            }.start()
            claude3pProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            val claude3p = AgentUsageCollector.parseSession(claude3pProc.inputStream.bufferedReader().readText())!!
            assertEquals(0, claude3pProc.waitFor())
            assertEquals(100, claude3p.quota5h?.remainingRounded)
            assertNull(claude3p.quota5h?.resetsAtEpochSeconds)
            assertEquals(100, claude3p.quota7d?.remainingRounded)

            // Gemini transcript reads gemini-5h / gemini-weekly from statusLine cache.
            File(agyLogDir, "transcript.jsonl").writeText(
                """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:00:01Z","model":"gemini-4-neon-xhigh","input_tokens":1000,"output_tokens":200,"cache_read_tokens":5000}""" + "\n"
            )
            val geminiProc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.sessionCommand(CodingAgent.AGY, null, "/work/app")).apply {
                environment()["HOME"] = home.absolutePath
                environment()["ROAMTTY_NO_LIVE_QUOTA"] = "1"
            }.start()
            geminiProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            val geminiSess = AgentUsageCollector.parseSession(geminiProc.inputStream.bufferedReader().readText())!!
            assertEquals(0, geminiProc.waitFor())
            assertEquals(84, geminiSess.quota5h?.remainingRounded)
            assertEquals(80, geminiSess.quota7d?.remainingRounded)

            // 2. Codex older session refreshes with the newer rollout's account-wide quota.
            val codexDir = File(home, ".codex/sessions/2026/11/26").apply { mkdirs() }
            File(codexDir, "rollout-old.jsonl").writeText(
                """{"timestamp":"2099-01-01T00:00:00Z","type":"session_meta","payload":{"cwd":"/work/old"}}""" + "\n" +
                    """{"timestamp":"2099-01-01T00:01:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":1000,"cached_input_tokens":0,"output_tokens":100},"last_token_usage":{"input_tokens":1000,"cached_input_tokens":0,"output_tokens":100},"model_context_window":200000},"rate_limits":{"limit_id":"codex","primary":{"used_percent":10.0,"window_minutes":300,"resets_at":$futureReset},"secondary":{"used_percent":5.0,"window_minutes":10080,"resets_at":${futureReset + 100000}}}}}""" + "\n"
            )
            File(codexDir, "rollout-new.jsonl").writeText(
                """{"timestamp":"2099-01-01T01:00:00Z","type":"session_meta","payload":{"cwd":"/work/new"}}""" + "\n" +
                    """{"timestamp":"2099-01-01T01:05:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":2000,"cached_input_tokens":0,"output_tokens":200},"last_token_usage":{"input_tokens":2000,"cached_input_tokens":0,"output_tokens":200},"model_context_window":200000},"rate_limits":{"limit_id":"codex","primary":{"used_percent":55.0,"window_minutes":300,"resets_at":$futureReset},"secondary":{"used_percent":40.0,"window_minutes":10080,"resets_at":${futureReset + 100000}}}}}""" + "\n"
            )
            val oldCodexProc = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.sessionCommand(CodingAgent.CODEX, null, "/work/old")).apply {
                environment()["HOME"] = home.absolutePath
            }.start()
            oldCodexProc.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
            val oldCodex = AgentUsageCollector.parseSession(oldCodexProc.inputStream.bufferedReader().readText())!!
            assertEquals(0, oldCodexProc.waitFor())
            assertEquals(45, oldCodex.quota5h?.remainingRounded)
            assertEquals(60, oldCodex.quota7d?.remainingRounded)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun agyStatusLineIdleRedrawAndOlderWindowDoNotClobberFresherQuotaCache() {
        val home = Files.createTempDirectory("roamtty-agy-sl-stale").toFile()
        try {
            val hookFile = File(home, "agent-hook.sh").apply { writeText(AgentHooks.script); setExecutable(true) }
            val agyLogDir = File(home, ".gemini/antigravity-cli/brain/conv-guard/.system_generated/logs").apply { mkdirs() }
            File(agyLogDir, "transcript.jsonl").writeText(
                """{"type":"PLANNER_RESPONSE","created_at":"2099-01-01T00:00:01Z","model":"gemini-4-neon-xhigh","input_tokens":1000,"output_tokens":200,"cache_read_tokens":5000}""" + "\n"
            )

            fun runStatusLine(convId: String, f5h: Double, f7d: Double, reset5h: String, reset7d: String, resetSec: Int) {
                val p = ProcessBuilder("sh", hookFile.absolutePath, "agy", "statusline").apply {
                    environment()["HOME"] = home.absolutePath
                    environment()["ROAMTTY_AGENT"] = "agy"
                }.start()
                p.outputStream.use {
                    val payload = """{"conversation_id":"$convId","model":{"id":"Gemini 4 Neon (XHigh)"},"quota":{""" +
                        """"gemini-5h":{"remaining_fraction":$f5h,"reset_time":"$reset5h","reset_in_seconds":$resetSec},""" +
                        """"gemini-weekly":{"remaining_fraction":$f7d,"reset_time":"$reset7d","reset_in_seconds":500000}}}"""
                    it.write(payload.toByteArray())
                }
                assertEquals(0, p.waitFor())
            }

            fun readQuota(): AgentSessionUsage {
                val p = ProcessBuilder("/bin/sh", "-c", AgentUsageCollector.sessionCommand(CodingAgent.AGY, null, "/work/app")).apply {
                    environment()["HOME"] = home.absolutePath
                }.start()
                p.outputStream.use { it.write(AgentUsageCollector.script.toByteArray()) }
                val u = AgentUsageCollector.parseSession(p.inputStream.bufferedReader().readText())!!
                assertEquals(0, p.waitFor())
                return u
            }

            // 1. Session A reports initial quota (98% / 78%).
            runStatusLine("sess-A", 0.978, 0.777, "2099-01-01T05:00:00Z", "2099-01-07T00:00:00Z", 15600)
            assertEquals(98, readQuota().quota5h?.remainingRounded)
            assertEquals(78, readQuota().quota7d?.remainingRounded)

            // 2. Session B actively works and writes fresher quota (94% / 76%).
            runStatusLine("sess-B", 0.942, 0.761, "2099-01-01T05:00:00Z", "2099-01-07T00:00:00Z", 15000)
            assertEquals(94, readQuota().quota5h?.remainingRounded)
            assertEquals(76, readQuota().quota7d?.remainingRounded)

            // 3. Idle Session A redraws (reset_in_seconds ticked down, fractions unchanged) -> must NOT clobber 94%/76%.
            runStatusLine("sess-A", 0.978, 0.777, "2099-01-01T05:00:00Z", "2099-01-07T00:00:00Z", 14200)
            assertEquals(94, readQuota().quota5h?.remainingRounded)
            assertEquals(76, readQuota().quota7d?.remainingRounded)

            // 4. First-time redraw from an older idle Session C holding stale 97%/78% in the same window -> must NOT clobber 94%/76%.
            runStatusLine("sess-C", 0.970, 0.776, "2099-01-01T05:00:00Z", "2099-01-07T00:00:00Z", 14100)
            assertEquals(94, readQuota().quota5h?.remainingRounded)
            assertEquals(76, readQuota().quota7d?.remainingRounded)

            // 5. Session A actually refreshes after a real turn (91% / 75%) -> MUST update cache.
            runStatusLine("sess-A", 0.912, 0.748, "2099-01-01T05:00:00Z", "2099-01-07T00:00:00Z", 13900)
            assertEquals(91, readQuota().quota5h?.remainingRounded)
            assertEquals(75, readQuota().quota7d?.remainingRounded)

            // 6. After a 5h reset window rolls over to a newer reset_time (100%), even a brand-new session MUST update cache.
            runStatusLine("sess-D", 1.0, 0.748, "2099-01-01T10:00:00Z", "2099-01-07T00:00:00Z", 18000)
            assertEquals(100, readQuota().quota5h?.remainingRounded)
            assertEquals(75, readQuota().quota7d?.remainingRounded)

            // 7. Unseen idle Session E holding the older 05:00 window (40%) MUST NOT overwrite the rolled-over 10:00 window.
            runStatusLine("sess-E", 0.40, 0.748, "2099-01-01T05:00:00Z", "2099-01-07T00:00:00Z", 100)
            assertEquals(100, readQuota().quota5h?.remainingRounded)
            assertEquals(75, readQuota().quota7d?.remainingRounded)
        } finally {
            home.deleteRecursively()
        }
    }
}
