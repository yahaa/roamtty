package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.*
import io.itermux.app.tr
import io.itermux.app.trf

/** Machine-wide AI Agent token consumption & active subscription quotas on the host detail page. */
@Composable
internal fun HostAiUsageSection(monitor: HostMonitor, modifier: Modifier = Modifier) {
    val range by monitor.aiUsageRange.collectAsStateWithLifecycle()
    val usage by monitor.aiUsage.collectAsStateWithLifecycle()
    val loading by monitor.aiUsageLoading.collectAsStateWithLifecycle()
    val ready by monitor.ready.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    if (!monitor.supportsAiUsage || (!ready && usage == null)) return

    Column(modifier.fillMaxWidth().testTag("host-ai-usage")) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("AI 用量"), style = MaterialTheme.typography.sectionHeader, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            RangeMiniSegment(range, onSelect = monitor::setAiUsageRange)
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(enabled = ready && !loading, role = Role.Button, onClickLabel = tr("刷新")) {
                        monitor.refreshAiUsage(force = true, fetchAgyQuota = true)
                    }
                    .testTag("host-ai-usage-refresh"),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp, color = colors.onSurfaceVariant)
                } else {
                    Icon(
                        painterResource(R.drawable.ic_refresh),
                        contentDescription = tr("刷新"),
                        modifier = Modifier.size(16.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }

        GroupedSection {
            val snapshot = usage
            when {
                snapshot == null && loading -> {
                    Row(
                        Modifier.fillMaxWidth().padding(Spacing.Page),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(tr("正在读取本机 AI 用量…"), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                    }
                }
                snapshot == null || snapshot.agents.isEmpty() -> {
                    Text(
                        tr("该时间范围内暂无 AI Agent 用量记录"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.Page),
                    )
                }
                else -> {
                    HostAiUsageSummaryHeader(snapshot)
                    snapshot.agents.forEach { row ->
                        GroupedDivider()
                        HostAgentUsageRow(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeMiniSegment(selected: HostUsageRange, onSelect: (HostUsageRange) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(colors.groupedContainer).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HostUsageRange.entries.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (active) colors.surfaceContainerHighest else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = 9.dp, vertical = 3.dp)
                    .testTag("host-ai-range-${option.arg}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tr(option.label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HostAiUsageSummaryHeader(snapshot: HostAiUsage) {
    val colors = MaterialTheme.colorScheme
    val total = snapshot.totalTokens.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    formatTokens(snapshot.totalTokens),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"
                    ),
                )
                Text("tokens", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
            snapshot.cacheHitPercent?.let { hit ->
                Text(
                    trf("缓存命中 %d%%", hit),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusColors.success,
                    modifier = Modifier.clip(CircleShape).background(StatusColors.success.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SegmentedRatioBar(
            segments = snapshot.agents.map { (it.totalTokens.toFloat() / total) to it.agent.brand() },
            height = 6.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            trf("输入 %s · 输出 %s · 缓存 %s",
                formatTokens(snapshot.totalInput),
                formatTokens(snapshot.totalOutput),
                formatTokens(snapshot.totalCacheRead)),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HostAgentUsageRow(row: HostAgentUsage) {
    val colors = MaterialTheme.colorScheme
    val brand = row.agent.brand()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(brand))
            Spacer(Modifier.width(8.dp))
            Text(row.agent.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatTokens(row.totalTokens),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"
                ),
            )
            if (row.sessions > 0) {
                Text(
                    " · ${trf("%d会话", row.sessions)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (row.quota5h != null || row.quota7d != null) {
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.quota5h?.let { q ->
                    val reset = formatQuotaReset(q.resetsAtEpochSeconds, compact = true)
                    val label = if (reset != null) "${tr("5h 剩")} · $reset" else tr("5h 剩余")
                    HostQuotaMiniBar(label, q.remainingRounded, brand, Modifier.weight(1f))
                }
                row.quota7d?.let { q ->
                    val reset = formatQuotaReset(q.resetsAtEpochSeconds, compact = true)
                    val label = if (reset != null) "${tr("周剩")} · $reset" else tr("周剩余")
                    HostQuotaMiniBar(label, q.remainingRounded, brand, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HostQuotaMiniBar(label: String, remaining: Int, brand: Color, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val barColor = if (remaining <= 25) quotaRemainingColor(remaining) else brand
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            Text(
                "$remaining%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"
                ),
                color = if (remaining <= 25) barColor else colors.onSurface,
            )
        }
        SegmentedRatioBar(listOf((remaining / 100f) to barColor), height = 4.dp)
    }
}
