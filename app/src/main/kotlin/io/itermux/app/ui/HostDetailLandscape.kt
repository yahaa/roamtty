package io.itermux.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.HostMetrics
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.HostSystem
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.ui.geometry.CornerRadius

/** The landscape host page's sections, each with its tab. */
private enum class HostDetailTab(val label: String, val tag: String) {
    OVERVIEW("概览", "host-tab-overview"), PROCESSES("进程", "host-tab-processes"), INFO("信息", "host-tab-info")
}

/**
 * The host page in landscape, built like the home page there: one flat 48 dp bar with back, the 概览 · 进程 · 信息
 * tabs and the actions, and below it the whole page in one scroll. The tabs follow the scroll: the one whose
 * section is under the bar is selected, and tapping one scrolls to its section.
 */
@Composable
internal fun HostDetailLandscape(
    host: Host,
    reachability: HostReachability,
    monitor: HostMonitor?,
    onBack: () -> Unit,
    onAgent: () -> Unit,
    onTerminal: () -> Unit,
    /** Opens the host's running tmux sessions; null hides the button (no tmux). */
    onContinue: (() -> Unit)?,
    onBrowseFiles: () -> Unit,
    onDesktop: (() -> Unit)?,
    /** The overflow menu around the button it is handed. */
    menu: @Composable (anchor: @Composable (open: () -> Unit) -> Unit) -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = monitor?.metrics?.collectAsStateWithLifecycle()?.value
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val tops = remember { mutableStateMapOf<HostDetailTab, Int>() }
    var viewport by remember { mutableIntStateOf(0) }
    /** The tab tapped, held while the scroll passes the sections in between. */
    var tapped by remember { mutableStateOf<HostDetailTab?>(null) }
    // The last section whose top has passed the upper third of the screen, or 信息 once the page is scrolled to its
    // end, since the last section may be too short to ever reach the top.
    val followed by remember {
        derivedStateOf {
            val atEnd = scroll.value > 0 && scroll.value >= scroll.maxValue - 1
            if (atEnd) HostDetailTab.INFO
            else listOf(HostDetailTab.INFO, HostDetailTab.PROCESSES)
                .firstOrNull { (tops[it] ?: Int.MAX_VALUE) - scroll.value <= viewport / 3 } ?: HostDetailTab.OVERVIEW
        }
    }
    val selected = tapped ?: followed

    Column(modifier.fillMaxSize().testTag("host-detail")) {
        Row(Modifier.fillMaxWidth().height(HOME_COMPACT_BAR_HEIGHT).padding(horizontal = 4.dp).testTag("host-detail-bar"),
            verticalAlignment = Alignment.CenterVertically) {
            ToolbarIcon(R.drawable.ic_chevron_left, tr("返回主机列表"), Modifier.testTag("host-detail-back"), onClick = onBack)
            HostDetailTabs(selected, metrics?.processes?.size?.takeIf { it > 0 }) { tab ->
                tapped = tab
                scope.launch {
                    try { scroll.animateScrollTo(tops[tab] ?: 0) } finally { tapped = null }
                }
            }
            HostDetailActions(onAgent, onTerminal, onContinue, onBrowseFiles, onDesktop, Modifier.weight(1f).padding(start = Spacing.Tight))
            menu { ToolbarIcon(R.drawable.ic_more, tr("更多"), Modifier.testTag("host-detail-more"), onClick = it) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewport = it.height }.verticalScroll(scroll)
            .testTag("host-detail-scroll").padding(start = Spacing.Row, end = Spacing.Row, top = 6.dp, bottom = Spacing.Page),
            verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
            @Composable fun Section(tab: HostDetailTab, content: @Composable () -> Unit) =
                Box(Modifier.fillMaxWidth().onGloballyPositioned { tops[tab] = it.positionInParent().y.toInt() }
                    .testTag("host-section-${tab.name.lowercase()}")) { content() }
            Section(HostDetailTab.OVERVIEW) { Overview(host, reachability, monitor, metrics, onLogin) }
            Section(HostDetailTab.PROCESSES) { Processes(monitor, metrics) }
            Section(HostDetailTab.INFO) { Information(host, metrics) }
        }
    }
}

/** The three tabs as the home page's segmented capsule; 进程 carries how many processes it lists. */
@Composable
private fun HostDetailTabs(selected: HostDetailTab, processes: Int?, onSelect: (HostDetailTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.clip(CircleShape).background(colors.onSurface.copy(alpha = 0.07f)).padding(3.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically) {
        HostDetailTab.entries.forEach { tab ->
            val active = tab == selected
            val color by animateColorAsState(if (active) colors.onSurface else colors.onSurfaceVariant, label = "host-tab")
            Row(Modifier.height(32.dp).clip(CircleShape).background(if (active) colors.onSurface.copy(alpha = 0.14f) else Color.Transparent)
                .selectable(active, role = Role.Tab, onClick = { onSelect(tab) }).padding(horizontal = 14.dp).testTag(tab.tag),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tr(tab.label), style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                if (tab == HostDetailTab.PROCESSES && processes != null) {
                    Spacer(Modifier.width(5.dp))
                    Text("$processes", style = MaterialTheme.typography.titleSmall, color = color.copy(alpha = 0.7f), maxLines = 1,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Agent, terminal, files and desktop as pills at the end of the bar. Every one carries its name when the bar has room
 * (Chinese always does); otherwise only Agent keeps it and the rest are round icons.
 */
@Composable
private fun HostDetailActions(onAgent: () -> Unit, onTerminal: () -> Unit, onContinue: (() -> Unit)?, onBrowseFiles: () -> Unit,
    onDesktop: (() -> Unit)?, modifier: Modifier = Modifier) {
    val labels = listOfNotNull(tr("终端"), onContinue?.let { tr("继续") }, tr("文件"), onDesktop?.let { tr("桌面") })
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        val agent = with(density) { measurer.measure("Agent", style).size.width.toDp() } + PILL_CHROME
        val titledWidth = labels.sumOf { label -> with(density) { measurer.measure(label, style).size.width.toDp() + PILL_CHROME }.value.toDouble() }
        val titled = (agent.value + titledWidth + 8 * labels.size) <= maxWidth.value
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HostPill("Agent", R.drawable.ic_agent, "host-detail-agent", filled = true, onClick = onAgent)
            HostPill(tr("终端"), R.drawable.ic_sessions, "host-detail-terminal", titled = titled, onClick = onTerminal)
            if (onContinue != null) HostPill(tr("继续"), R.drawable.ic_history, "host-detail-continue", titled = titled, onClick = onContinue)
            HostPill(tr("文件"), R.drawable.ic_folder, "host-detail-files", titled = titled, onClick = onBrowseFiles)
            if (onDesktop != null) HostPill(tr("桌面"), R.drawable.ic_desktop, "host-detail-desktop", titled = titled, onClick = onDesktop)
        }
    }
}

/** Everything a titled pill adds around its label: the icon, the gap and the padding on each side. */
private val PILL_CHROME = 18.dp + 6.dp + 11.dp + 13.dp

/** A 34 dp capsule inside a 48 dp target; untitled it is a circle around the icon. */
@Composable
private fun HostPill(label: String, icon: Int, tag: String, titled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = if (filled) colors.onPrimary else colors.onSurface
    Box(Modifier.height(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Row(Modifier.height(34.dp).widthIn(min = 34.dp).clip(CircleShape)
            .background(if (filled) colors.primary else colors.onSurface.copy(alpha = 0.08f))
            .padding(start = if (titled) 11.dp else 0.dp, end = if (titled) 13.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(18.dp), tint = content)
            if (titled) {
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            }
        }
    }
}

// 概览

@Composable
private fun Overview(host: Host, reachability: HostReachability, monitor: HostMonitor?, metrics: HostMetrics?, onLogin: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Row)) {
        val connection = monitor?.connection?.collectAsStateWithLifecycle()?.value
        val samplingFailed = monitor?.samplingFailed?.collectAsStateWithLifecycle()?.value == true
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HostSystemIcon(host.os, 40.dp)
            Column(Modifier.weight(1f).padding(start = Spacing.Row)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(10.dp))
                    HostReachabilityLabel(reachability, Modifier.testTag("host-detail-status"))
                }
                val line = listOfNotNull("${host.username}@${host.address}:${host.port}",
                    metrics?.os ?: tr(HostSystem.fromStored(host.os).label),
                    metrics?.uptimeSeconds?.let { trf("已运行 %s", uptime(it)) })
                Text(line.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (connection != null) MonitorStatus(connection, metrics != null)
        }
        when {
            monitor == null -> MonitorLoginCardRow(onLogin)
            connection != null && (!connection.active || samplingFailed) -> MonitorProblem(monitor, connection, samplingFailed, onLogin)
        }
        if (monitor != null) {
            val rate by monitor.netRate.collectAsStateWithLifecycle()
            val cpuHistory by monitor.cpuHistory.collectAsStateWithLifecycle()
            val memHistory by monitor.memHistory.collectAsStateWithLifecycle()
            val m = metrics
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(tr("CPU"), m?.cpus?.let { trf("%d 核", it) }, m?.cpuPercent, "host-gauge-cpu", Modifier.weight(1f)) {
                    Sparkline(cpuHistory, levelColor(m?.cpuPercent), Modifier.fillMaxWidth().height(26.dp))
                }
                Tile(tr("内存"), m?.memUsedBytes?.let { used -> m.memTotalBytes?.let { usage(used, it) } }, m?.memPercent,
                    "host-gauge-memory", Modifier.weight(1f)) {
                    Sparkline(memHistory, levelColor(m?.memPercent), Modifier.fillMaxWidth().height(26.dp))
                }
                val root = m?.disks?.firstOrNull()
                Tile(tr("磁盘"), root?.let { usage(it.usedBytes, it.totalBytes) }, root?.percent, "host-gauge-disk", Modifier.weight(1f)) {
                    Meter(root?.percent)
                }
                NetworkTile(rate, Modifier.weight(1f))
            }
            if (m != null) Summary(m)
        }
    }
}

/** The portrait page's login card, as one row: the password is typed only for this view. */
@Composable
private fun MonitorLoginCardRow(onLogin: () -> Unit) {
    GroupedSection(footer = tr("密码仅用于本次查看，不会保存。")) {
        GroupedRow(tr("输入密码以查看 CPU、内存、磁盘和网络"), Modifier.testTag("host-monitor-login"),
            leading = { Icon(painterResource(R.drawable.ic_live), contentDescription = null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary) },
            trailing = { Chevron() }, onClick = onLogin)
    }
}

/** One reading: its name and context on top, the number large, and its history or fill along the bottom. */
@Composable
private fun Tile(label: String, detail: String?, value: Double?, tag: String, modifier: Modifier = Modifier, bottom: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.height(116.dp).testTag(tag), shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.weight(1f).widthIn(min = 4.dp))
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(value?.let(::percent) ?: "—", fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = if (value != null && value >= 70) levelColor(value) else colors.onSurface)
            Spacer(Modifier.weight(1f))
            bottom()
        }
    }
}

@Composable
private fun Meter(value: Double?) {
    val tone = levelColor(value)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(Modifier.fillMaxWidth().padding(bottom = 4.dp).height(5.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (value != null) drawRoundRect(tone, size = size.copy(width = size.width * (value.coerceIn(0.0, 100.0) / 100.0).toFloat()),
            cornerRadius = radius)
    }
}

@Composable
private fun NetworkTile(rate: Pair<Double, Double>?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.height(116.dp).testTag("host-network-rate"), shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Text(tr("网络"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(rate?.let { "↓ ${bytes(it.first.toLong())}/s" } ?: "—", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rate?.let { "↑ ${bytes(it.second.toLong())}/s" } ?: " ", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The smaller numbers side by side: load, tasks, swap and login sessions, whichever the host reports. */
@Composable
private fun Summary(metrics: HostMetrics) {
    val colors = MaterialTheme.colorScheme
    val cells = buildList {
        add(tr("负载 1/5/15") to (metrics.load.takeIf { it.isNotEmpty() }?.joinToString(" · ") { String.format(Locale.US, "%.2f", it) } ?: "—"))
        metrics.tasks?.let { add(tr("任务") to it) }
        metrics.swapTotalBytes?.takeIf { it > 0 }?.let { add(tr("交换分区") to "${bytes(metrics.swapUsedBytes ?: 0)} / ${bytes(it)}") }
        metrics.users?.let { add(tr("登录会话") to it.toString()) }
    }
    Surface(Modifier.fillMaxWidth().testTag("host-summary"), shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 10.dp)) {
            cells.forEachIndexed { index, (label, value) ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 2.dp).background(colors.outlineVariant.copy(alpha = 0.6f)))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                    Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// 进程

/** The processes by CPU, beside the GPUs and their processes when the host has any. */
@Composable
private fun Processes(monitor: HostMonitor?, metrics: HostMetrics?) {
    if (monitor == null || metrics == null) {
        GroupedSection(header = tr("进程")) { GroupedRow(tr("连接后可查看进程")) }
        return
    }
    val gpuHistory by monitor.gpuHistory.collectAsStateWithLifecycle()
    val processes: @Composable (Modifier) -> Unit = { modifier ->
        GroupedSection(modifier, header = tr("进程（按 CPU 排序）")) {
            if (metrics.processes.isEmpty()) GroupedRow(tr("暂无进程数据"))
            metrics.processes.forEachIndexed { index, process ->
                if (index > 0) GroupedDivider()
                ProcessRow(process.name, "CPU ${percent(process.cpuPercent)}", trf("内存 %s", percent(process.memPercent)))
            }
        }
    }
    if (metrics.gpus.isEmpty()) processes(Modifier)
    else Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Row)) {
        processes(Modifier.weight(1f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
            GroupedSection(Modifier.testTag("host-gpus"), header = tr("GPU")) {
                metrics.gpus.forEachIndexed { index, gpu ->
                    if (index > 0) GroupedDivider()
                    GpuRow(gpu, gpuHistory[gpu.index].orEmpty())
                }
            }
            metrics.gpuProcesses.takeIf { it.isNotEmpty() }?.let { gpuProcesses ->
                GroupedSection(header = tr("GPU 进程（按显存排序）")) {
                    gpuProcesses.take(5).forEachIndexed { index, process ->
                        if (index > 0) GroupedDivider()
                        ProcessRow(process.name, "PID ${process.pid}", trf("显存 %s", process.memBytes?.let(::bytes) ?: "—"))
                    }
                }
            }
        }
    }
}

// 信息

/** How the app reaches the host on the left; what the host is and its disks on the right. */
@Composable
private fun Information(host: Host, metrics: HostMetrics?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Row)) {
        InfoGroup(tr("连接信息"), connectionRows(host), Modifier.weight(1f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
            InfoGroup(tr("系统"), listOf(tr("系统") to (metrics?.os ?: tr(HostSystem.fromStored(host.os).label))) + systemRows(metrics))
            metrics?.disks?.takeIf { it.isNotEmpty() }?.let { disks ->
                InfoGroup(tr("磁盘"), disks.map { it.mount to "${usage(it.usedBytes, it.totalBytes)} · ${percent(it.percent)}" })
            }
        }
    }
}
