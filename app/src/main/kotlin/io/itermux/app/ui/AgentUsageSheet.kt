package io.itermux.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.*
import io.itermux.app.tr
import io.itermux.app.trf
import kotlinx.coroutines.launch

/** Color for context-window usage percentage: quiet gray below 75%, amber 75-89%, coral-red >= 90%. */
@Composable
internal fun contextLevelColor(percent: Int?): Color = when {
    percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
    percent >= 90 -> StatusColors.danger
    percent >= 75 -> StatusColors.warning
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Color for remaining quota percentage: green > 25%, amber 11-25%, coral-red <= 10%. */
@Composable
internal fun quotaRemainingColor(remainingPercent: Int): Color = when {
    remainingPercent <= 10 -> StatusColors.danger
    remainingPercent <= 25 -> StatusColors.warning
    else -> StatusColors.success
}

/** Quiet 12dp hollow micro-ring + tabular percentage on line 2 of an AI Agent session card. */
@Composable
internal fun SessionContextMicroRing(percent: Int, modifier: Modifier = Modifier) {
    val tone = contextLevelColor(percent)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Row(modifier.testTag("session-context-ring"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(12.dp)) {
            val stroke = 1.9.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(tone, -90f, (percent.coerceIn(0, 100) * 3.6f), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$percent%", style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"
        ), color = tone, maxLines = 1)
    }
}

/**
 * Always-on 38dp frosted circular context progress ring anchored at the terminal's top-right corner
 * (co-axial with the 48dp right-edge dock below). Single tap entry to [AgentUsageSheet].
 */
@Composable
internal fun TerminalContextRing(
    percent: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tone = when {
        percent == null -> Color(0xFFC7C4BD)
        percent >= 90 -> Color(0xFFFFB4A8)
        percent >= 75 -> Color(0xFFE9C46A)
        else -> Color(0xFFD6D3CC)
    }
    val desc = percent?.let { trf("上下文已用 %d%%，点按查看用量与剩余额度", it) }
        ?: tr("查看 Agent 用量与剩余额度")
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = tr("查看用量"), onClick = onClick)
            .semantics { contentDescription = desc }
            .testTag("terminal-context-ring"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xD8262827))
                .border(0.75.dp, Color(0x1AF3F0EA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2f + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                drawArc(Color(0x24F3F0EA), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (percent != null) {
                    drawArc(tone, -90f, percent.coerceIn(0, 100) * 3.6f, false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text(
                percent?.let { "$it%" } ?: "--",
                fontSize = 9.5.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = tone,
                maxLines = 1,
            )
        }
    }
}

/** Native bottom sheet opened by tapping [TerminalContextRing]: strictly scoped to the current session. */
@Composable
internal fun AgentUsageSheet(
    session: TerminalConnection,
    onCompacted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val usage by session.agentUsage.collectAsStateWithLifecycle()
    val loading by session.agentUsageLoading.collectAsStateWithLifecycle()
    val status by session.agentStatus.collectAsStateWithLifecycle()
    val launched by session.launchedAgent.collectAsStateWithLifecycle()
    val directory by session.directory.collectAsStateWithLifecycle()
    val kind = CodingAgent.fromCommand(status?.agent) ?: usage?.agent ?: launched
    val path = sessionListPath(status, session.host.username)
        .ifEmpty { sessionListPath(directory, session.host.username) }
        .ifEmpty { "~" }
    val scope = rememberCoroutineScope()
    var compacting by remember { mutableStateOf(false) }

    LaunchedEffect(session.id) { session.refreshAgentUsage(force = true) }

    AppSheet(onDismiss, fillHeight = false) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Section)
                .testTag("agent-usage-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UsageSheetHeader(kind, "${session.host.name} · $path", loading) {
                session.refreshAgentUsage(force = true)
            }

            val u = usage
            val hasData = u != null && (
                u.formattedModel.isNotEmpty() || u.reasoningEffort != null ||
                    (u.contextPercent != null && u.contextUsed != null && u.contextLimit != null) ||
                    u.quota5h != null || u.quota7d != null || u.sessionTotalTokens > 0
                )
            if (u == null || !hasData) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(Spacing.Page),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(tr("正在读取会话用量…"), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(tr("暂未检测到本会话的用量记录，等待 Agent 回复后会自动更新。"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                if (u.formattedModel.isNotEmpty() || u.reasoningEffort != null) {
                    ModelEffortCard(u.formattedModel, u.reasoningEffort)
                }
                if (u.contextPercent != null && u.contextUsed != null && u.contextLimit != null) {
                    ContextWindowCard(
                        usage = u,
                        canCompact = kind?.compactCommand != null,
                        compacting = compacting,
                        onCompact = {
                            if (compacting) return@ContextWindowCard
                            compacting = true
                            scope.launch {
                                val ok = session.compactContext()
                                compacting = false
                                if (ok) onCompacted()
                            }
                        },
                    )
                }
                if (u.quota5h != null || u.quota7d != null) {
                    QuotaCardsRow(u.quota5h, u.quota7d)
                }
                if (u.sessionTotalTokens > 0) {
                    SessionTokenCard(u)
                }
            }
        }
    }
}

@Composable
private fun UsageSheetHeader(
    kind: CodingAgent?,
    subtitle: String,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (kind != null) {
            AgentIcon(kind, 36.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(kind?.label ?: "Agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(40.dp).testTag("agent-usage-refresh")) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(painterResource(R.drawable.ic_refresh), contentDescription = tr("刷新"),
                Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun effortToneColor(effort: ReasoningEffort): Color = when (effort) {
    ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    ReasoningEffort.MEDIUM -> CodingAgent.CODEX.brand()
    ReasoningEffort.HIGH -> MaterialTheme.colorScheme.primary
    ReasoningEffort.XHIGH, ReasoningEffort.MAX -> StatusColors.warning
}

@Composable
private fun ModelEffortCard(model: String, effort: ReasoningEffort?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.groupedContainer,
        modifier = Modifier.fillMaxWidth().testTag("agent-usage-model-effort"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(if (effort != null) 1.25f else 1f).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(tr("当前模型"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                Text(
                    model.ifEmpty { "—" },
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (effort != null) {
                Box(Modifier.fillMaxHeight().padding(vertical = 2.dp).width(1.dp).background(colors.onSurface.copy(alpha = 0.08f)))
                val tone = effortToneColor(effort)
                val localized = tr(effort.zhLabel)
                Column(
                    Modifier.weight(1f).padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(tr("思考等级"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EffortSignalBars(effort.bars, tone)
                        Text(localized, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = tone, maxLines = 1)
                        if (localized != effort.enShort) {
                            Text(effort.enShort, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffortSignalBars(filled: Int, color: Color) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.2.dp)) {
        listOf(4.5.dp, 7.dp, 9.5.dp, 12.dp).forEachIndexed { idx, h ->
            Box(
                Modifier
                    .width(2.8.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.2.dp))
                    .background(if (idx < filled) color else track),
            )
        }
    }
}

@Composable
private fun ContextWindowCard(
    usage: AgentSessionUsage,
    canCompact: Boolean,
    compacting: Boolean,
    onCompact: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pct = usage.contextPercent ?: return
    val used = usage.contextUsed ?: return
    val limit = usage.contextLimit ?: return
    val rem = usage.contextRemaining ?: 0L
    val pctColor = contextLevelColor(pct)
    val critical = pct >= 90
    val cacheCreateColor = Color(0xFF7AA2F7)
    val cacheReadColor = colors.primary
    val turnColor = if (critical) StatusColors.danger else StatusColors.warning

    Surface(shape = MaterialTheme.shapes.medium, color = colors.groupedContainer, modifier = Modifier.testTag("agent-usage-context")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("上下文窗口"), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                if (canCompact) {
                    val pillBg = if (critical) StatusColors.danger.copy(alpha = 0.14f) else colors.onSurface.copy(alpha = 0.06f)
                    val pillBorder = if (critical) StatusColors.danger.copy(alpha = 0.30f) else colors.onSurface.copy(alpha = 0.11f)
                    val pillFg = if (critical) StatusColors.danger else colors.onSurface.copy(alpha = 0.88f)
                    val pillIcon = if (critical) StatusColors.danger else colors.onSurfaceVariant
                    Row(
                        Modifier
                            .height(26.dp)
                            .clip(CircleShape)
                            .background(pillBg)
                            .border(0.75.dp, pillBorder, CircleShape)
                            .clickable(enabled = !compacting, role = Role.Button, onClickLabel = tr("压缩上下文"), onClick = onCompact)
                            .padding(horizontal = 9.5.dp)
                            .testTag("agent-usage-compact"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                    ) {
                        if (compacting) {
                            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.6.dp, color = pillFg)
                        } else {
                            Icon(painterResource(R.drawable.ic_compress), contentDescription = null,
                                Modifier.size(13.dp), tint = pillIcon)
                        }
                        Text(tr("压缩"), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = pillFg, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        formatTokens(used),
                        fontSize = 19.sp,
                        lineHeight = 21.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        "/ ${formatTokens(limit)}",
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 1.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
                Text(
                    trf("已用 %d%%", pct),
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = pctColor,
                    modifier = Modifier.padding(bottom = 1.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                )
            }
            Spacer(Modifier.height(9.dp))
            SegmentedRatioBar(
                segments = buildList {
                    if (usage.contextCacheCreate > 0) add(usage.contextCacheCreate.toFloat() / limit to cacheCreateColor)
                    if (usage.contextCacheRead > 0) add(usage.contextCacheRead.toFloat() / limit to cacheReadColor)
                    if (usage.contextTurn > 0) add(usage.contextTurn.toFloat() / limit to turnColor)
                    if (isEmpty()) add((used.toFloat() / limit) to pctColor)
                },
                height = 7.dp,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (usage.contextCacheCreate > 0) LegendDot(cacheCreateColor, trf("新建 %s", formatTokens(usage.contextCacheCreate)))
                    if (usage.contextCacheRead > 0) LegendDot(cacheReadColor, trf("缓存 %s", formatTokens(usage.contextCacheRead)))
                    if (usage.contextTurn > 0) LegendDot(turnColor, trf("本轮 %s", formatTokens(usage.contextTurn)))
                }
                Text(trf("余 %s", formatTokens(rem)),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun QuotaCardsRow(q5h: QuotaWindow?, q7d: QuotaWindow?) {
    Row(Modifier.fillMaxWidth().testTag("agent-usage-quotas"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        q5h?.let { QuotaCard(tr("5 小时额度"), it, Modifier.weight(1f)) }
        q7d?.let { QuotaCard(tr("周额度 (7 天)"), it, Modifier.weight(1f)) }
    }
}

@Composable
private fun QuotaCard(title: String, quota: QuotaWindow, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val rem = quota.remainingRounded
    val tone = quotaRemainingColor(rem)
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    tr("剩余"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tone,
                    modifier = Modifier.clip(CircleShape).background(tone.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$rem%",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"
                    ),
                    color = if (rem <= 25) tone else colors.onSurface,
                )
                Text(
                    trf("已用 %d%%", quota.usedRounded),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Spacer(Modifier.height(7.dp))
            SegmentedRatioBar(listOf((rem / 100f) to tone), height = 5.dp)
            formatQuotaReset(quota.resetsAtEpochSeconds)?.let { reset ->
                Spacer(Modifier.height(6.dp))
                Text(reset, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SessionTokenCard(usage: AgentSessionUsage) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.groupedContainer, modifier = Modifier.testTag("agent-usage-session-tokens")) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("本次会话 Token"), style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    formatTokens(usage.sessionTotalTokens),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"
                    ),
                )
                usage.cacheHitPercent?.let { hit ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        trf("缓存 %d%%", hit),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusColors.success,
                        modifier = Modifier.clip(CircleShape).background(StatusColors.success.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = colors.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenMetricColumn(tr("输入"), formatTokens(usage.sessionInput), Modifier.weight(1f))
                TokenMetricColumn(tr("输出"), formatTokens(usage.sessionOutput), Modifier.weight(1f))
                TokenMetricColumn(tr("缓存读取"), formatTokens(usage.sessionCacheRead), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TokenMetricColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"
        ))
    }
}

/** Smooth horizontal bar with optional multi-colored segments; fractions are relative to total capacity 1.0f. */
@Composable
internal fun SegmentedRatioBar(segments: List<Pair<Float, Color>>, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Row(
        modifier.fillMaxWidth().height(height).clip(CircleShape).background(track),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var remaining = 1f
        segments.forEach { (raw, color) ->
            val f = raw.coerceIn(0f, remaining)
            if (f > 0.005f) {
                Box(Modifier.weight(f).fillMaxHeight().background(color))
                remaining -= f
            }
        }
        if (remaining > 0.005f) Spacer(Modifier.weight(remaining))
    }
}
