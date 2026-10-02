package io.itermux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.DownloadPhase
import io.itermux.app.session.TransferStatus
import io.itermux.ssh.formatRemoteSize

@Composable
internal fun TransferStatusDialog(status: TransferStatus, onCancel: () -> Unit, onDismiss: () -> Unit,
    onInsertPath: ((String) -> Unit)? = null) = TransferStatusDialog(status.name, status.bytes, status.total,
    status.running, status.result, onCancel, onDismiss, status.remotePath, onInsertPath, status.downloads, status.bytesPerSecond)

@Composable
internal fun TransferStatusDialog(name: String, transferred: Long, totalBytes: Long,
    transferring: Boolean, result: String?, onCancel: () -> Unit, onDismiss: () -> Unit,
    remotePath: String? = null, onInsertPath: ((String) -> Unit)? = null,
    downloads: List<DownloadItem> = emptyList(), bytesPerSecond: Double = 0.0) {
    val context = LocalContext.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.52f).dp
    val active = downloads.firstOrNull { it.phase == DownloadPhase.RUNNING }
    val completed = downloads.count { it.phase == DownloadPhase.COMPLETE }
    val failed = downloads.count { it.phase == DownloadPhase.FAILED }
    val allComplete = downloads.isNotEmpty() && completed == downloads.size
    var details by rememberSaveable(name, transferring) { mutableStateOf(false) }
    val commonDirectory = downloads.map { it.path.substringBeforeLast('/', "/").ifBlank { "/" } }
        .distinct().singleOrNull()
    val redundantResult = downloads.isNotEmpty() && (result == tr("传输完成") ||
        result == trf("已完成 %d 个，失败 %d 个", completed, failed))
    val fraction = if (totalBytes > 0) (transferred.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        else if (totalBytes == 0L) 1f else 0f
    AlertDialog(onDismissRequest = { if (!transferring) onDismiss() }, modifier = Modifier.testTag("transfer-dialog"),
        title = { Text(tr(if (downloads.isNotEmpty()) {
            if (transferring) "正在下载" else if (allComplete) "下载完成" else "下载结果"
        } else "文件传输")) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (downloads.size > 1) item {
                    Text(if (allComplete) trf("已下载 %d 个文件", completed)
                        else trf("已完成 %d/%d · 失败 %d", completed, downloads.size, failed),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("transfer-count"))
                    if (transferring && downloads.size > 1) LinearProgressIndicator(
                        progress = { (completed + failed + if (active != null) fraction else 0f) / downloads.size },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("transfer-overall-progress"))
                }
                if (transferring) item {
                    Text(active?.name ?: name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (totalBytes >= 0) "${(fraction * 100).toInt()}%" else tr("正在传输…"),
                            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("transfer-percent"))
                        if (bytesPerSecond > 0) Text("${formatRemoteSize(bytesPerSecond.toLong())}/s",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("transfer-speed"))
                    }
                    if (totalBytes >= 0) LinearProgressIndicator(progress = { fraction },
                        modifier = Modifier.fillMaxWidth().testTag("transfer-progress"))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("transfer-progress"))
                    Text(if (totalBytes >= 0) "${formatRemoteSize(transferred)} / ${formatRemoteSize(totalBytes)}"
                        else trf("已传输 %s", formatRemoteSize(transferred)),
                        modifier = Modifier.padding(top = 8.dp).testTag("transfer-status"), style = MaterialTheme.typography.bodySmall)
                } else if (!redundantResult && !result.isNullOrBlank()) item {
                    SelectionContainer { Text(result.orEmpty(), modifier = Modifier.testTag("transfer-status")) }
                }
                if (!transferring && details && commonDirectory != null) item {
                    SelectionContainer {
                        Text(trf("来源目录：%s", commonDirectory), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!transferring) items(downloads, key = { it.path }) { file ->
                    if (downloads.size > 1) HorizontalDivider(Modifier.padding(bottom = 12.dp))
                    val label = when (file.phase) {
                        DownloadPhase.WAITING -> tr("等待中")
                        DownloadPhase.RUNNING -> tr("正在下载")
                        DownloadPhase.COMPLETE -> tr("已完成")
                        DownloadPhase.FAILED -> tr("失败")
                        DownloadPhase.CANCELLED -> tr("已取消")
                    }
                    val color = if (file.phase == DownloadPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(file.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        if (!allComplete) Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                    if (details && commonDirectory == null) Text(trf("来源目录：%s", file.path.substringBeforeLast('/', "/").ifBlank { "/" }), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (details || file.phase != DownloadPhase.COMPLETE) Text(if (file.phase == DownloadPhase.COMPLETE) formatRemoteSize(file.bytes)
                        else "${formatRemoteSize(file.bytes)} / ${if (file.total < 0) tr("大小未知") else formatRemoteSize(file.total)}",
                        style = MaterialTheme.typography.bodySmall, color = color)
                    file.localPath?.let { path -> SelectionContainer {
                        Text(trf(if (file.phase == DownloadPhase.COMPLETE) "下载路径：%s" else "未完成文件：%s", path),
                            style = MaterialTheme.typography.bodySmall, color = color,
                            maxLines = if (details || file.phase != DownloadPhase.COMPLETE) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("download-local-path-${file.path}"))
                    } }
                    file.error?.let { message -> SelectionContainer {
                        Text(tr(message), color = color, style = MaterialTheme.typography.bodySmall)
                    } }
                }
                if (!transferring && downloads.isNotEmpty()) item {
                    TextButton(onClick = { details = !details }, modifier = Modifier.testTag("download-result-details"),
                        contentPadding = PaddingValues(0.dp)) {
                        Text(tr(if (details) "收起详情" else "查看详情"))
                    }
                }
                if (!transferring && remotePath != null) item {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(tr("远端文件路径"), remotePath))
                    }, modifier = Modifier.testTag("copy-upload-path")) { Text(tr("复制路径")) }
                    if (onInsertPath != null) TextButton(onClick = { onInsertPath(remotePath); onDismiss() },
                        modifier = Modifier.testTag("insert-upload-path")) { Text(tr("放入编辑区")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (transferring) onCancel() else onDismiss() }) {
            Text(tr(if (transferring && downloads.size > 1) "取消全部" else if (transferring) "取消传输" else "完成"))
        } })
}
