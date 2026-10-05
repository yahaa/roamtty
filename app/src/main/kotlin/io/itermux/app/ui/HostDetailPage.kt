package io.itermux.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.data.installedPlugins
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.HostMetrics
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.HostPlugin
import io.itermux.ssh.HostSystem
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import io.itermux.ssh.TerminalTransport
import java.util.Locale

/**
 * What tapping a host opens: its details, live health read over a private SSH login, and the ways to work on it.
 * Hosts with a saved secret (or a key) start monitoring at once; a password-only host asks for it first.
 */
@Composable
internal fun HostDetailPage(
    host: Host,
    reachability: HostReachability,
    /** This host's sessions, most active first. */
    sessions: List<TerminalConnection>,
    onBack: () -> Unit,
    onOpenSession: (TerminalConnection) -> Unit,
    /** Opens a session in the folder picked on the launch sheet, starting [AgentLaunch] there when given. */
    onLaunch: (directory: String, agent: AgentLaunch?) -> Unit,
    onBrowseFiles: () -> Unit,
    onEdit: () -> Unit,
    onManagePlugins: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val app = LocalContext.current.applicationContext as ItermuxApp
    val scope = rememberCoroutineScope()
    var login by remember(host.id) { mutableStateOf(if (host.needsTypedPassword) null else PluginTarget(host)) }
    var askPassword by remember(host.id) { mutableStateOf(false) }
    /** null, or whether the launch sheet starts an AI agent. */
    var launching by remember(host.id) { mutableStateOf<Boolean?>(null) }
    val monitor = login?.let { target ->
        remember(target) { HostMonitor(app, target, scope).also { it.start() } }
    }
    monitor?.let { DisposableEffect(it) { onDispose { it.close() } } }
    LifecycleResumeEffect(monitor) {
        monitor?.visible?.value = true
        onPauseOrDispose { monitor?.visible?.value = false }
    }

    Column(modifier.fillMaxSize().testTag("host-detail")) {
        AppToolbar(title = host.name,
            navigation = { ToolbarIcon(R.drawable.ic_chevron_left, tr("返回主机列表"), Modifier.testTag("host-detail-back"), onClick = onBack) },
            actions = {
                ToolbarIcon(R.drawable.ic_edit, tr("编辑主机"), Modifier.testTag("host-detail-edit"), onClick = onEdit)
                var menu by remember { mutableStateOf(false) }
                Box {
                    ToolbarIcon(R.drawable.ic_more, tr("更多"), Modifier.testTag("host-detail-more")) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("安装插件")) }, onClick = { menu = false; onManagePlugins() },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(tr("删除主机")) }, onClick = { menu = false; onDelete() },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error))
                    }
                }
            })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = Spacing.Page),
                verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                val metrics = monitor?.metrics?.collectAsStateWithLifecycle()?.value
                HostHeader(host, reachability, metrics)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HostAction(tr("终端"), R.drawable.ic_sessions, primary = true, tag = "host-detail-terminal",
                        modifier = Modifier.weight(1f)) { launching = false }
                    HostAction(tr("AI 助手"), R.drawable.ic_agent, tag = "host-detail-agent", modifier = Modifier.weight(1f)) {
                        launching = true
                    }
                    HostAction(tr("文件"), R.drawable.ic_folder, tag = "host-detail-files", modifier = Modifier.weight(1f),
                        onClick = onBrowseFiles)
                }
                HostSessionsSection(sessions, onOpenSession)
                if (monitor == null) MonitorLoginCard { askPassword = true }
                else MonitorSection(monitor, onLogin = { if (host.needsTypedPassword || host.auth == "KEY") askPassword = true else monitor.start() })
                HostInfoSection(host, metrics)
                Spacer(Modifier.height(Spacing.Page))
            }
        }
    }
    if (askPassword) CredentialsDialog(ConnectRequest(host, SessionMode.SHELL), { askPassword = false }) { password, passphrase ->
        askPassword = false
        login = PluginTarget(host, password, passphrase)
    }
    monitor?.pending?.collectAsStateWithLifecycle()?.value?.let { PromptDialog(it) }
    launching?.let { forAgent ->
        // The page's own login lists folders, so the sheet opens straight onto them.
        LaunchSheet(host, forAgent, monitor, onDismiss = { launching = null }) { directory, agent ->
            launching = null
            onLaunch(directory, agent)
        }
    }
}

@Composable
private fun HostHeader(host: Host, reachability: HostReachability, metrics: HostMetrics?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        HostSystemIcon(host.os, 56.dp)
        Column(Modifier.weight(1f).padding(start = Spacing.Page)) {
            Text("${host.username}@${host.address}:${host.port}", style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metrics?.os ?: tr(HostSystem.fromStored(host.os).label), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            ReachabilityDot(reachability)
        }
    }
}

@Composable
private fun ReachabilityDot(reachability: HostReachability) {
    val (color, label) = when (reachability) {
        is HostReachability.Online -> StatusColors.success to (reachability.latencyMs?.let { trf("在线 · %s", latencyLabel(it)) } ?: tr("在线"))
        HostReachability.Offline -> StatusColors.danger to tr("离线")
        HostReachability.Checking -> StatusColors.idle to tr("检测中")
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("host-detail-status")) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (reachability is HostReachability.Online) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HostAction(label: String, icon: Int, tag: String, modifier: Modifier = Modifier, primary: Boolean = false,
    onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    val shape = MaterialTheme.shapes.medium
    val padding = PaddingValues(vertical = Spacing.Row)
    if (primary) Button(onClick, modifier.heightIn(min = 64.dp).testTag(tag), shape = shape, contentPadding = padding, content = content)
    else FilledTonalButton(onClick, modifier.heightIn(min = 64.dp).testTag(tag), shape = shape, contentPadding = padding, content = content)
}

/** Every session the app holds for this host; a tap opens its terminal. */
@Composable
private fun HostSessionsSection(sessions: List<TerminalConnection>, onOpen: (TerminalConnection) -> Unit) {
    GroupedSection(header = trf("会话  %d", sessions.size), modifier = Modifier.testTag("host-sessions")) {
        if (sessions.isEmpty()) GroupedRow(tr("暂无会话"), subtitle = tr("点上方“终端”或“AI 助手”开启新会话"))
        else sessions.forEachIndexed { index, session ->
            if (index > 0) GroupedDivider()
            HostSessionRow(session) { onOpen(session) }
        }
    }
}

@Composable
private fun HostSessionRow(session: TerminalConnection, onClick: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val transport by session.transport.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = tr("打开终端"), role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = Spacing.Page, vertical = 10.dp).testTag("host-session-${session.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.ifBlank { session.defaultTitle }, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight), verticalAlignment = Alignment.CenterVertically) {
                StatusLabel(state.phase)
                MetaTag(if (session.mode == SessionMode.TMUX) "tmux" else "SSH")
                if (transport == TerminalTransport.MOSH) MetaTag("mosh")
            }
        }
        Spacer(Modifier.width(Spacing.Tight))
        Chevron()
    }
}

@Composable
private fun MonitorLoginCard(onLogin: () -> Unit) {
    GroupedSection(header = tr("实时监控"), footer = tr("密码仅用于本次查看，不会保存。")) {
        GroupedRow(tr("输入密码以查看 CPU、内存、磁盘和网络"), Modifier.testTag("host-monitor-login"),
            leading = { Icon(painterResource(R.drawable.ic_live), contentDescription = null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary) },
            trailing = { Chevron() }, onClick = onLogin)
    }
}

@Composable
private fun MonitorSection(monitor: HostMonitor, onLogin: () -> Unit) {
    val connection by monitor.connection.collectAsStateWithLifecycle()
    val metrics by monitor.metrics.collectAsStateWithLifecycle()
    val rate by monitor.netRate.collectAsStateWithLifecycle()
    val cpuHistory by monitor.cpuHistory.collectAsStateWithLifecycle()
    val memHistory by monitor.memHistory.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.testTag("host-monitor")) {
        Row(Modifier.fillMaxWidth().padding(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr("实时监控"), style = MaterialTheme.typography.sectionHeader, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            when {
                connection.phase == Phase.CONNECTED && metrics != null -> {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(StatusColors.success))
                    Spacer(Modifier.width(6.dp))
                    Text(trf("每 %d 秒刷新", (SAMPLE_INTERVAL_MS / 1000).toInt()), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                connection.active -> {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(when {
                        connection.phase == Phase.CONNECTED -> tr("正在读取…")
                        connection.message == MONITOR_RECONNECTING -> tr("正在重连…")
                        else -> tr("正在连接主机…")
                    },
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        if (!connection.active) {
            GroupedSection {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Row),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(connection.message.takeIf { it.isNotBlank() }?.let { tr(it) } ?: phaseLabel(connection.phase),
                            style = MaterialTheme.typography.bodyMedium, color = colors.error)
                        connection.detail?.let {
                            SelectionContainer {
                                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).testTag("host-monitor-detail"))
                            }
                        }
                    }
                    TextButton(onClick = monitor::start, modifier = Modifier.testTag("host-monitor-retry")) { Text(tr("重新连接")) }
                    if (connection.phase == Phase.AUTH_FAILED || connection.phase == Phase.FAILED)
                        TextButton(onClick = onLogin) { Text(tr("输入密码")) }
                }
            }
            Spacer(Modifier.height(Spacing.Row))
        }
        val m = metrics
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GaugeCard(tr("CPU"), m?.cpuPercent, m?.cpus?.let { trf("%d 核", it) }, cpuHistory, "host-gauge-cpu", Modifier.weight(1f))
            GaugeCard(tr("内存"), m?.memPercent,
                m?.memUsedBytes?.let { used -> m.memTotalBytes?.let { usage(used, it) } }, memHistory,
                "host-gauge-memory", Modifier.weight(1f))
            val root = m?.disks?.firstOrNull()
            GaugeCard(tr("磁盘"), root?.percent, root?.let { usage(it.usedBytes, it.totalBytes) }, null,
                "host-gauge-disk", Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.Row))
        GroupedSection {
            MetricRow(tr("负载"), m?.load?.takeIf { it.isNotEmpty() }?.joinToString("  ") { String.format(Locale.US, "%.2f", it) },
                tr("1 / 5 / 15 分钟"))
            GroupedDivider()
            MetricRow(tr("网络"), rate?.let { (rx, tx) -> "↓ ${bytes(rx.toLong())}/s   ↑ ${bytes(tx.toLong())}/s" })
            GroupedDivider()
            MetricRow(tr("运行时间"), m?.uptimeSeconds?.let(::uptime))
            m?.swapTotalBytes?.takeIf { it > 0 }?.let { total ->
                GroupedDivider()
                MetricRow(tr("交换分区"), "${bytes(m.swapUsedBytes ?: 0)} / ${bytes(total)}")
            }
            m?.tasks?.let { GroupedDivider(); MetricRow(tr("任务"), it, tr("运行中 / 总数")) }
            m?.users?.let { GroupedDivider(); MetricRow(tr("登录会话"), it.toString()) }
            m?.disks?.drop(1)?.forEach { disk ->
                GroupedDivider()
                MetricRow(disk.mount, "${bytes(disk.usedBytes)} / ${bytes(disk.totalBytes)} · ${percent(disk.percent)}", tr("磁盘"))
            }
        }
        m?.processes?.takeIf { it.isNotEmpty() }?.let { processes ->
            Spacer(Modifier.height(Spacing.Section))
            GroupedSection(header = tr("进程（按 CPU 排序）")) {
                processes.forEachIndexed { index, process ->
                    if (index > 0) GroupedDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = Spacing.Page, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(process.name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("CPU ${percent(process.cpuPercent)}", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 84.dp))
                        Text(trf("内存 %s", percent(process.memPercent)), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 84.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GaugeCard(label: String, value: Double?, detail: String?, history: List<Double>?, tag: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tone = levelColor(value)
    Surface(modifier.testTag(tag), shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
        Column(Modifier.padding(Spacing.Row), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                Ring(value, tone, 72.dp)
                Text(value?.let { percent(it) } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(Spacing.Tight))
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(detail ?: " ", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (history != null) {
                Spacer(Modifier.height(6.dp))
                Sparkline(history, tone, Modifier.fillMaxWidth().height(22.dp))
            } else Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Ring(value: Double?, tone: Color, size: Dp) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(Modifier.size(size)) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (value != null) drawArc(tone, -90f, (value.coerceIn(0.0, 100.0) * 3.6).toFloat(), false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun Sparkline(values: List<Double>, tone: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val step = size.width / (MONITOR_HISTORY - 1)
        val start = size.width - step * (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = start + step * i
            val y = size.height * (1f - (v.coerceIn(0.0, 100.0) / 100.0).toFloat())
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(start, size.height); close() }
        drawPath(fill, tone.copy(alpha = 0.14f))
        drawPath(line, tone, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun MetricRow(label: String, value: String?, hint: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.Page, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun HostInfoSection(host: Host, metrics: HostMetrics?) {
    val rows = buildList {
        add(tr("地址") to host.address)
        add(tr("端口") to host.port.toString())
        add(tr("用户名") to host.username)
        add(tr("认证方式") to (authLabel(host.auth) + (host.keyLabel?.takeIf { host.auth == "KEY" }?.let { " · $it" } ?: "")))
        add(tr("新会话") to modeLabel(host.mode == SessionMode.TMUX.name))
        add(tr("插件") to (host.installedPlugins?.let { found ->
            HostPlugin.entries.filter { it in found }.joinToString(" · ") { it.id }.ifEmpty { tr("未安装") }
        } ?: tr("未检测")))
        metrics?.hostname?.let { add(tr("主机名") to it) }
        metrics?.kernel?.let { add(tr("内核") to it) }
        metrics?.arch?.let { add(tr("架构") to it) }
        metrics?.cpuModel?.let { add(tr("处理器") to it) }
    }
    GroupedSection(header = tr("主机信息")) {
        SelectionContainer {
            Column {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) GroupedDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.Page, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = Spacing.Page))
                        Spacer(Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun levelColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.outline
    value >= 90 -> StatusColors.danger
    value >= 70 -> StatusColors.warning
    else -> MaterialTheme.colorScheme.primary
}

private fun percent(value: Double) = if (value < 10) String.format(Locale.US, "%.1f%%", value) else String.format(Locale.US, "%.0f%%", value)

/** "21.5/30.9 GB": both sizes in the total's unit, short enough for a gauge card. */
private fun usage(used: Long, total: Long): String {
    val parts = bytes(total).split(' ')
    val scale = Math.pow(1024.0, listOf("B", "KB", "MB", "GB", "TB", "PB").indexOf(parts[1]).toDouble())
    val usedScaled = used / scale
    val number = if (usedScaled >= 100 || scale == 1.0) String.format(Locale.US, "%.0f", usedScaled) else String.format(Locale.US, "%.1f", usedScaled)
    return "$number/${parts[0]} ${parts[1]}"
}

internal fun bytes(value: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var size = value.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return if (unit == 0 || size >= 100) String.format(Locale.US, "%.0f %s", size, units[unit])
    else String.format(Locale.US, "%.1f %s", size, units[unit])
}

private fun uptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> trf("%d 天 %d 小时", days, hours)
        hours > 0 -> trf("%d 小时 %d 分钟", hours, minutes)
        else -> trf("%d 分钟", minutes)
    }
}

