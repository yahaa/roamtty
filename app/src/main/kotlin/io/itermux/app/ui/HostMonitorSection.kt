package io.itermux.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.HostMetrics
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import java.util.Locale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign

@Composable
internal fun MonitorLoginCard(onLogin: () -> Unit) {
    GroupedSection(header = tr("实时监控"), footer = tr("密码仅用于本次查看，不会保存。")) {
        GroupedRow(tr("输入密码以查看 CPU、内存、磁盘和网络"), Modifier.testTag("host-monitor-login"),
            leading = { Icon(painterResource(R.drawable.ic_live), contentDescription = null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary) },
            trailing = { Chevron() }, onClick = onLogin)
    }
}

@Composable
internal fun MonitorSection(monitor: HostMonitor, onLogin: () -> Unit) {
    val connection by monitor.connection.collectAsStateWithLifecycle()
    val metrics by monitor.metrics.collectAsStateWithLifecycle()
    val samplingFailed by monitor.samplingFailed.collectAsStateWithLifecycle()
    val rate by monitor.netRate.collectAsStateWithLifecycle()
    val cpuHistory by monitor.cpuHistory.collectAsStateWithLifecycle()
    val memHistory by monitor.memHistory.collectAsStateWithLifecycle()
    val gpuHistory by monitor.gpuHistory.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.testTag("host-monitor")) {
        Row(Modifier.fillMaxWidth().padding(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr("实时监控"), style = MaterialTheme.typography.sectionHeader, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            MonitorStatus(connection, metrics != null)
        }
        if (!connection.active || samplingFailed) {
            MonitorProblem(monitor, connection, samplingFailed, onLogin)
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
        m?.gpus?.takeIf { it.isNotEmpty() }?.let { gpus ->
            Spacer(Modifier.height(Spacing.Section))
            GroupedSection(Modifier.testTag("host-gpus"), header = tr("GPU")) {
                gpus.forEachIndexed { index, gpu ->
                    if (index > 0) GroupedDivider()
                    GpuRow(gpu, gpuHistory[gpu.index].orEmpty())
                }
            }
        }
        m?.gpuProcesses?.takeIf { it.isNotEmpty() }?.let { processes ->
            Spacer(Modifier.height(Spacing.Section))
            GroupedSection(header = tr("GPU 进程（按显存排序）")) {
                processes.take(5).forEachIndexed { index, process ->
                    if (index > 0) GroupedDivider()
                    ProcessRow(process.name, "PID ${process.pid}", trf("显存 %s", process.memBytes?.let(::bytes) ?: "—"))
                }
            }
        }
        m?.processes?.takeIf { it.isNotEmpty() }?.let { processes ->
            Spacer(Modifier.height(Spacing.Section))
            GroupedSection(header = tr("进程（按 CPU 排序）")) {
                processes.forEachIndexed { index, process ->
                    if (index > 0) GroupedDivider()
                    ProcessRow(process.name, "CPU ${percent(process.cpuPercent)}", trf("内存 %s", percent(process.memPercent)))
                }
            }
        }
    }
}

/** How the monitor is doing, at the end of a line: refreshing every few seconds, or connecting and reading. */
@Composable
internal fun MonitorStatus(connection: ConnectionState, hasMetrics: Boolean) {
    val colors = MaterialTheme.colorScheme
    when {
        connection.phase == Phase.CONNECTED && hasMetrics -> {
            Box(Modifier.size(6.dp).clip(CircleShape).background(StatusColors.success))
            Spacer(Modifier.width(6.dp))
            Text(trf("每 %d 秒刷新", (SAMPLE_INTERVAL_MS / 1000).toInt()), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, modifier = Modifier.testTag("host-monitor-status"))
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

/** Why there are no readings, with a retry and, when the login itself failed, a way to type the password. */
@Composable
internal fun MonitorProblem(monitor: HostMonitor, connection: ConnectionState, samplingFailed: Boolean, onLogin: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    GroupedSection {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Row),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (samplingFailed) tr("无法读取监控数据，请检查远端系统查询权限后重试。")
                    else connection.message.takeIf { it.isNotBlank() }?.let { tr(it) } ?: phaseLabel(connection.phase),
                    style = MaterialTheme.typography.bodyMedium, color = colors.error,
                    modifier = Modifier.testTag("host-monitor-error"))
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
}

/** A process: its name, then two figures in columns of their own. */
@Composable
internal fun ProcessRow(name: String, first: String, second: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = Spacing.Page, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(first, style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 84.dp))
        Text(second, style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 84.dp))
    }
}

@Composable
internal fun GaugeCard(label: String, value: Double?, detail: String?, history: List<Double>?, tag: String, modifier: Modifier = Modifier) {
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

/** One GPU: name, frequency, temperature and power on top, then utilization and VRAM bars and the utilization trend. */
@Composable
internal fun GpuRow(gpu: HostMetrics.Gpu, history: List<Double>) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Row).testTag("host-gpu-${gpu.index}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${gpu.index} · ${gpu.name}", style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val extras = listOfNotNull(
                gpu.freqMHz?.let { f -> String.format(Locale.US, "%.0f", f) + (gpu.freqMaxMHz?.let { String.format(Locale.US, "/%.0f", it) } ?: "") + " MHz" },
                gpu.memUsedBytes?.takeIf { gpu.memTotalBytes == null }?.let { trf("内存 %s", bytes(it)) },
                gpu.temperatureC?.let { String.format(Locale.US, "%.0f°C", it) },
                gpu.powerWatts?.let { w -> String.format(Locale.US, "%.0f", w) + (gpu.powerLimitWatts?.let { String.format(Locale.US, "/%.0f", it) } ?: "") + " W" },
            )
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.Tight))
        }
        Spacer(Modifier.height(Spacing.Tight))
        UsageBar(tr("利用率"), gpu.utilPercent, gpu.utilPercent?.let(::percent))
        // Integrated GPUs share system memory, so there is no VRAM total to fill a bar against.
        if (gpu.memTotalBytes != null) {
            Spacer(Modifier.height(6.dp))
            UsageBar(tr("显存"), gpu.memPercent, gpu.memUsedBytes?.let { usage(it, gpu.memTotalBytes) })
        }
        if (history.size >= 2) {
            Spacer(Modifier.height(6.dp))
            Sparkline(history, levelColor(gpu.utilPercent), Modifier.fillMaxWidth().height(22.dp))
        }
    }
}

@Composable
internal fun UsageBar(label: String, value: Double?, detail: String?) {
    val colors = MaterialTheme.colorScheme
    val tone = levelColor(value)
    val track = colors.onSurface.copy(alpha = 0.08f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 52.dp))
        Canvas(Modifier.weight(1f).height(6.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            if (value != null) drawRoundRect(tone, size = size.copy(width = size.width * (value.coerceIn(0.0, 100.0) / 100.0).toFloat()),
                cornerRadius = radius)
        }
        Text(detail ?: "—", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(start = Spacing.Tight).widthIn(min = 72.dp), textAlign = TextAlign.End)
    }
}

@Composable
internal fun Ring(value: Double?, tone: Color, size: Dp) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(Modifier.size(size)) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (value != null) drawArc(tone, -90f, (value.coerceIn(0.0, 100.0) * 3.6).toFloat(), false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
internal fun Sparkline(values: List<Double>, tone: Color, modifier: Modifier = Modifier) {
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
internal fun levelColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.outline
    value >= 90 -> StatusColors.danger
    value >= 70 -> StatusColors.warning
    else -> MaterialTheme.colorScheme.primary
}

internal fun percent(value: Double) = if (value < 10) String.format(Locale.US, "%.1f%%", value) else String.format(Locale.US, "%.0f%%", value)

/** "21.5/30.9 GB": both sizes in the total's unit, short enough for a gauge card. */
internal fun usage(used: Long, total: Long): String {
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

internal fun uptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> trf("%d 天 %d 小时", days, hours)
        hours > 0 -> trf("%d 小时 %d 分钟", hours, minutes)
        else -> trf("%d 分钟", minutes)
    }
}
