package io.itermux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.DownloadPhase
import io.itermux.app.session.TransferStatus
import io.itermux.ssh.formatRemoteSize

private enum class TransferMood { RUNNING, SUCCESS, PROBLEM }

/** Shared-storage prefixes say nothing to the reader; "Documents/…" is what the Files app shows. */
internal fun readableFolder(path: String): String =
    path.substringBeforeLast('/').removePrefix("/storage/emulated/0/").removePrefix("/sdcard/").ifEmpty { "/" }

@Composable
internal fun TransferStatusDialog(status: TransferStatus, onCancel: () -> Unit, onDismiss: () -> Unit,
    onInsertPath: ((String) -> Unit)? = null) {
    val transferring = status.running
    val result = status.result
    val downloads = status.downloads
    val totalBytes = status.total
    val context = LocalContext.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.52f).dp
    val active = downloads.firstOrNull { it.phase == DownloadPhase.RUNNING }
    val completed = downloads.count { it.phase == DownloadPhase.COMPLETE }
    val failed = downloads.count { it.phase == DownloadPhase.FAILED }
    val allComplete = downloads.isNotEmpty() && completed == downloads.size
    var details by rememberSaveable(status.name, transferring) { mutableStateOf(false) }
    val commonDirectory = downloads.map { it.path.substringBeforeLast('/', "/").ifBlank { "/" } }.distinct().singleOrNull()
    val savedFolder = downloads.mapNotNull { it.localPath?.let(::readableFolder) }.distinct().singleOrNull()
    val redundantResult = downloads.isNotEmpty() && (result == tr("传输完成") ||
        result == trf("已完成 %d 个，失败 %d 个", completed, failed))
    val fraction = if (totalBytes > 0) (status.bytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        else if (totalBytes == 0L) 1f else 0f
    val mood = when {
        transferring -> TransferMood.RUNNING
        downloads.isNotEmpty() -> if (allComplete) TransferMood.SUCCESS else TransferMood.PROBLEM
        status.succeeded == false -> TransferMood.PROBLEM
        else -> TransferMood.SUCCESS
    }
    val title = tr(if (downloads.isNotEmpty()) {
        if (transferring) "正在下载" else if (allComplete) "下载完成" else "下载结果"
    } else "文件传输")
    val subtitle = when {
        downloads.size > 1 && allComplete && !transferring -> trf("已下载 %d 个文件", completed)
        downloads.size > 1 -> trf("已完成 %d/%d · 失败 %d", completed, downloads.size, failed)
        downloads.size == 1 && allComplete -> formatRemoteSize(downloads.single().bytes)
        else -> null
    }
    AlertDialog(onDismissRequest = { if (!transferring) onDismiss() }, modifier = Modifier.testTag("transfer-dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(mood)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (downloads.size > 1) Modifier.testTag("transfer-count") else Modifier)
                }
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transferring) {
                    if (downloads.size > 1) item {
                        RoundedProgress({ (completed + failed + if (active != null) fraction else 0f) / downloads.size },
                            Modifier.padding(bottom = 4.dp).testTag("transfer-overall-progress"))
                    }
                    item { ActiveTransfer(active?.name ?: status.name, status.bytes, totalBytes, fraction, status.bytesPerSecond) }
                } else if (!redundantResult && !result.isNullOrBlank()) item {
                    SelectionContainer {
                        Text(result, Modifier.testTag("transfer-status"), style = MaterialTheme.typography.bodyMedium,
                            color = if (mood == TransferMood.PROBLEM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!transferring) items(downloads, key = { it.path }) { file ->
                    DownloadResultCard(file, details && commonDirectory == null, details)
                }
                if (!transferring && downloads.isNotEmpty()) item {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!details && savedFolder != null) LocationLine(R.drawable.ic_folder, trf("保存到 %s", savedFolder),
                            Modifier.testTag("download-saved-to"))
                        if (details && commonDirectory != null) LocationLine(R.drawable.ic_os_server, trf("来源目录：%s", commonDirectory))
                    }
                }
                if (!transferring) status.remotePath?.let { remotePath -> item {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            SelectionContainer {
                                Text(remotePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText(tr("远端文件路径"), remotePath))
                                }, modifier = Modifier.testTag("copy-upload-path")) { Text(tr("复制路径")) }
                                if (onInsertPath != null) FilledTonalButton(onClick = { onInsertPath(remotePath); onDismiss() },
                                    modifier = Modifier.testTag("insert-upload-path")) { Text(tr("放入编辑区")) }
                            }
                        }
                    }
                } }
            }
        },
        dismissButton = if (!transferring && downloads.isNotEmpty()) { {
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("download-result-details")) {
                Text(tr(if (details) "收起详情" else "查看详情"))
            }
        } } else null,
        confirmButton = {
            if (transferring) TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(tr(if (downloads.size > 1) "取消全部" else "取消传输"))
            } else FilledTonalButton(onClick = onDismiss) { Text(tr("完成")) }
        })
}

@Composable
private fun StatusBadge(mood: TransferMood) {
    val scheme = MaterialTheme.colorScheme
    val (icon, container, content) = when (mood) {
        TransferMood.RUNNING -> Triple(R.drawable.ic_download, scheme.secondaryContainer, scheme.onSecondaryContainer)
        TransferMood.SUCCESS -> Triple(R.drawable.ic_check, scheme.primaryContainer, scheme.onPrimaryContainer)
        TransferMood.PROBLEM -> Triple(R.drawable.ic_alert, scheme.errorContainer, scheme.onErrorContainer)
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RoundedProgress(progress: (() -> Float)?, modifier: Modifier = Modifier) {
    val size = Modifier.fillMaxWidth().height(6.dp)
    if (progress != null) LinearProgressIndicator(progress = progress, modifier = modifier.then(size),
        strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    else LinearProgressIndicator(modifier = modifier.then(size), strokeCap = StrokeCap.Round, gapSize = 0.dp)
}

@Composable
private fun ActiveTransfer(name: String, bytes: Long, total: Long, fraction: Float, bytesPerSecond: Double) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteFileIcon(name, size = 28.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(if (total >= 0) "${formatRemoteSize(bytes)} / ${formatRemoteSize(total)}" else trf("已传输 %s", formatRemoteSize(bytes)),
                        Modifier.testTag("transfer-status"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RoundedProgress(if (total >= 0) ({ fraction }) else null, Modifier.padding(top = 12.dp).testTag("transfer-progress"))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (total >= 0) "${(fraction * 100).toInt()}%" else tr("正在传输…"), Modifier.weight(1f).testTag("transfer-percent"),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (bytesPerSecond > 0) Text("${formatRemoteSize(bytesPerSecond.toLong())}/s", Modifier.testTag("transfer-speed"),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DownloadResultCard(file: DownloadItem, showSource: Boolean, details: Boolean) {
    val problem = file.phase == DownloadPhase.FAILED || file.phase == DownloadPhase.CANCELLED
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = when (file.phase) {
        DownloadPhase.WAITING -> tr("等待中")
        DownloadPhase.RUNNING -> tr("正在下载")
        DownloadPhase.COMPLETE -> null
        DownloadPhase.FAILED -> tr("失败")
        DownloadPhase.CANCELLED -> tr("已取消")
    }
    val size = if (file.phase == DownloadPhase.COMPLETE) formatRemoteSize(file.bytes)
        else "${formatRemoteSize(file.bytes)} / ${if (file.total < 0) tr("大小未知") else formatRemoteSize(file.total)}"
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteFileIcon(file.name, size = 28.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium)
                    Text(listOfNotNull(size, label).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = if (problem) MaterialTheme.colorScheme.error else muted)
                }
                if (file.phase == DownloadPhase.COMPLETE) Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                else if (problem) Icon(painterResource(R.drawable.ic_alert), contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
            file.error?.let { message ->
                SelectionContainer { Text(tr(message), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
            if (showSource) Text(trf("来源目录：%s", file.path.substringBeforeLast('/', "/").ifBlank { "/" }),
                Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodySmall, color = muted)
            // A partial file is always worth locating; a finished one only on request.
            if (details || file.phase != DownloadPhase.COMPLETE) file.localPath?.let { path ->
                SelectionContainer {
                    Text(trf(if (file.phase == DownloadPhase.COMPLETE) "下载路径：%s" else "未完成文件：%s", path),
                        Modifier.padding(top = 8.dp).testTag("download-local-path-${file.path}"),
                        style = MaterialTheme.typography.bodySmall, color = if (problem) MaterialTheme.colorScheme.error else muted)
                }
            }
        }
    }
}

@Composable
private fun LocationLine(icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
