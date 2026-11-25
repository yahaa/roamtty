package io.itermux.app.session

import io.itermux.app.tr
import io.itermux.app.trf
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt

/** Remaining percentage and optional reset epoch (UTC seconds) of a rate-limit window (e.g. 5h or 7d). */
data class QuotaWindow(
    val remainingPercent: Double,
    val resetsAtEpochSeconds: Long? = null,
) {
    val remainingRounded: Int get() = remainingPercent.coerceIn(0.0, 100.0).roundToInt()
    val usedRounded: Int get() = (100 - remainingRounded).coerceIn(0, 100)
}

/** Live context-window, quota and cumulative token telemetry for one AI agent session. */
data class AgentSessionUsage(
    val agent: CodingAgent?,
    val model: String = "",
    val contextUsed: Long? = null,
    val contextLimit: Long? = null,
    val contextCacheRead: Long = 0,
    val contextCacheCreate: Long = 0,
    val contextTurn: Long = 0,
    val sessionInput: Long = 0,
    val sessionOutput: Long = 0,
    val sessionCacheRead: Long = 0,
    val sessionCacheCreate: Long = 0,
    val quota5h: QuotaWindow? = null,
    val quota7d: QuotaWindow? = null,
) {
    val contextPercent: Int?
        get() {
            val used = contextUsed ?: return null
            val limit = contextLimit?.takeIf { it > 0 } ?: return null
            return ((used * 100.0) / limit).roundToInt().coerceIn(0, 100)
        }

    val contextRemaining: Long?
        get() {
            val used = contextUsed ?: return null
            val limit = contextLimit?.takeIf { it > 0 } ?: return null
            return (limit - used).coerceAtLeast(0L)
        }

    val sessionTotalTokens: Long get() = sessionInput + sessionOutput + sessionCacheRead + sessionCacheCreate

    val cacheHitPercent: Int?
        get() {
            val promptTokens = sessionInput + sessionCacheRead + sessionCacheCreate
            if (promptTokens <= 0 || sessionCacheRead <= 0) return null
            return ((sessionCacheRead * 100.0) / promptTokens).roundToInt().coerceIn(0, 100)
        }

    val hasAnyData: Boolean
        get() = contextPercent != null || sessionTotalTokens > 0 || quota5h != null || quota7d != null

    val formattedModel: String get() = formatModelName(model)
}

/** Time window for machine-wide AI token statistics on the host detail page. */
enum class HostUsageRange(val arg: String, val label: String) {
    TODAY("today", "今日"),
    WEEK("7d", "7天"),
    MONTH("30d", "30天"),
}

/** Aggregated token consumption and active subscription quota for one agent on a host. */
data class HostAgentUsage(
    val agent: CodingAgent,
    val sessions: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreateTokens: Long,
    val quota5h: QuotaWindow? = null,
    val quota7d: QuotaWindow? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheCreateTokens
}

/** Machine-wide multi-agent AI token snapshot for one [HostUsageRange]. */
data class HostAiUsage(
    val range: HostUsageRange,
    val agents: List<HostAgentUsage>,
) {
    val totalTokens: Long get() = agents.sumOf { it.totalTokens }
    val totalInput: Long get() = agents.sumOf { it.inputTokens }
    val totalOutput: Long get() = agents.sumOf { it.outputTokens }
    val totalCacheRead: Long get() = agents.sumOf { it.cacheReadTokens }
    val totalCacheCreate: Long get() = agents.sumOf { it.cacheCreateTokens }
    val cacheHitPercent: Int?
        get() {
            val prompt = totalInput + totalCacheRead + totalCacheCreate
            if (prompt <= 0 || totalCacheRead <= 0) return null
            return ((totalCacheRead * 100.0) / prompt).roundToInt().coerceIn(0, 100)
        }
}

/** Slash command sent behind the native "压缩上下文" button; null when the agent has no built-in compaction command. */
val CodingAgent.compactCommand: String?
    get() = when (this) {
        CodingAgent.CLAUDE, CodingAgent.CODEX, CodingAgent.AGY -> "/compact"
        CodingAgent.GROK -> null
    }

/** Loads `agent-usage.py`, builds safe SSH commands and parses its `key=value` output. */
object AgentUsageCollector {
    val script: String by lazy {
        checkNotNull(AgentUsageCollector::class.java.getResourceAsStream("/io/itermux/app/agent-usage.py")) {
            "agent-usage.py missing"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private val scriptBase64: String by lazy {
        Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
    }

    private const val EXTRA_PATH =
        "PATH=\"\$PATH:/opt/homebrew/bin:/usr/local/bin:/opt/local/bin:/home/linuxbrew/.linuxbrew/bin:/snap/bin\"; export PATH"

    /** Command that reads `script` on stdin and prints single-session usage lines. */
    fun sessionCommand(agent: CodingAgent?, tmuxSession: String?, directory: String): String {
        val a = shellQuote(agent?.command.orEmpty())
        val t = shellQuote(tmuxSession.orEmpty())
        val d = shellQuote(directory)
        return "/bin/sh -c " + shellQuote("$EXTRA_PATH; command -v python3 >/dev/null 2>&1 || exit 0; exec python3 - session $a $t $d")
    }

    /** Self-contained POSIX shell snippet for [io.itermux.ssh.RemoteHost.run] (no stdin channel). */
    fun hostScript(range: HostUsageRange): String =
        "$EXTRA_PATH; command -v python3 >/dev/null 2>&1 || exit 0; " +
            "python3 -c 'import base64,sys; code=base64.b64decode(sys.argv.pop(1)); exec(compile(code,\"agent-usage.py\",\"exec\"))' " +
            "'$scriptBase64' host ${range.arg}"

    fun parseSession(output: String?): AgentSessionUsage? {
        if (output.isNullOrBlank()) return null
        val kv = mutableMapOf<String, String>()
        output.lineSequence().forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) kv[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        val agent = CodingAgent.fromCommand(kv["agent"])
        val q5h = kv["q5h_rem"]?.toDoubleOrNull()?.let { QuotaWindow(it, kv["q5h_reset"]?.toLongOrNull()) }
        val q7d = kv["q7d_rem"]?.toDoubleOrNull()?.let { QuotaWindow(it, kv["q7d_reset"]?.toLongOrNull()) }
        val parsed = AgentSessionUsage(
            agent = agent,
            model = kv["model"].orEmpty(),
            contextUsed = kv["ctx_used"]?.toLongOrNull(),
            contextLimit = kv["ctx_limit"]?.toLongOrNull(),
            contextCacheRead = kv["ctx_cache_read"]?.toLongOrNull() ?: 0L,
            contextCacheCreate = kv["ctx_cache_create"]?.toLongOrNull() ?: 0L,
            contextTurn = kv["ctx_turn"]?.toLongOrNull() ?: 0L,
            sessionInput = kv["sess_input"]?.toLongOrNull() ?: 0L,
            sessionOutput = kv["sess_output"]?.toLongOrNull() ?: 0L,
            sessionCacheRead = kv["sess_cache_read"]?.toLongOrNull() ?: 0L,
            sessionCacheCreate = kv["sess_cache_create"]?.toLongOrNull() ?: 0L,
            quota5h = q5h,
            quota7d = q7d,
        )
        return parsed.takeIf { it.hasAnyData || it.model.isNotEmpty() }
    }

    fun parseHost(lines: List<String>, requestedRange: HostUsageRange): HostAiUsage {
        var range = requestedRange
        val agents = mutableListOf<HostAgentUsage>()
        for (line in lines) {
            when {
                line.startsWith("range=") -> {
                    val raw = line.removePrefix("range=").trim()
                    HostUsageRange.entries.firstOrNull { it.arg == raw }?.let { range = it }
                }
                line.startsWith("agent=") -> {
                    val parts = line.removePrefix("agent=").split('|')
                    if (parts.size >= 6) {
                        val agent = CodingAgent.fromCommand(parts[0].trim()) ?: continue
                        val sessions = parts[1].toIntOrNull() ?: 0
                        val input = parts[2].toLongOrNull() ?: 0L
                        val output = parts[3].toLongOrNull() ?: 0L
                        val cacheRead = parts[4].toLongOrNull() ?: 0L
                        val cacheCreate = parts[5].toLongOrNull() ?: 0L
                        val q5h = parts.getOrNull(6)?.toDoubleOrNull()?.let { QuotaWindow(it, parts.getOrNull(7)?.toLongOrNull()) }
                        val q7d = parts.getOrNull(8)?.toDoubleOrNull()?.let { QuotaWindow(it, parts.getOrNull(9)?.toLongOrNull()) }
                        agents += HostAgentUsage(agent, sessions, input, output, cacheRead, cacheCreate, q5h, q7d)
                    }
                }
            }
        }
        return HostAiUsage(range, agents.sortedByDescending { it.totalTokens })
    }
}

/** Compact token count: `840`, `38k`, `164k`, `1.18M`, `1.54B`. */
fun formatTokens(count: Long): String {
    val n = count.coerceAtLeast(0L)
    return when {
        n < 1_000 -> n.toString()
        n < 10_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
        n < 1_000_000 -> "${(n / 1_000.0).roundToInt()}k"
        n < 100_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
        n < 1_000_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        else -> String.format(Locale.US, "%.2fB", n / 1_000_000_000.0)
    }
}

/** Turns raw CLI model ids (`claude-opus-4-6`, `gpt-5.4`) into clean short titles without fabricating unknowns. */
fun formatModelName(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    val lower = s.lowercase(Locale.US)
    if (lower.startsWith("claude-")) {
        val parts = lower.removePrefix("claude-").split('-').filter { it.isNotEmpty() && !(it.length == 8 && it.all(Char::isDigit)) }
        if (parts.isNotEmpty()) {
            val family = parts[0].replaceFirstChar { it.uppercase(Locale.US) }
            val ver = parts.drop(1).takeWhile { it.all { c -> c.isDigit() || c == '.' } }.joinToString(".")
            return if (ver.isNotEmpty()) "$family $ver" else family
        }
    }
    return s
}

/** Human reset label: `2h 14m 后重置` for < 24h windows, or `周日 08:00 重置` for weekly windows. */
fun formatQuotaReset(
    epochSeconds: Long?,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    compact: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val target = epochSeconds ?: return null
    val diff = (target - nowEpochSeconds).coerceAtLeast(0L)
    if (diff < 86_400L) {
        val h = diff / 3600
        val m = (diff % 3600) / 60
        val dur = if (h > 0) "${h}h${if (compact) "" else " "}${m}m" else "${m.coerceAtLeast(1)}m"
        return if (compact) dur else trf("%s 后重置", dur)
    }
    val dt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(target), zone)
    val day = when (dt.dayOfWeek) {
        DayOfWeek.MONDAY -> tr("周一")
        DayOfWeek.TUESDAY -> tr("周二")
        DayOfWeek.WEDNESDAY -> tr("周三")
        DayOfWeek.THURSDAY -> tr("周四")
        DayOfWeek.FRIDAY -> tr("周五")
        DayOfWeek.SATURDAY -> tr("周六")
        DayOfWeek.SUNDAY -> tr("周日")
    }
    val hhmm = String.format(Locale.US, "%02d:%02d", dt.hour, dt.minute)
    return if (compact) "$day$hhmm" else trf("%s %s 重置", day, hhmm)
}
