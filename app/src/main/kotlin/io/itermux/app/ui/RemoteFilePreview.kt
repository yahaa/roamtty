package io.itermux.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.*
import kotlinx.coroutines.*

private data class FilePreview(val text: String? = null, val image: ImageBitmap? = null,
    val truncated: Boolean = false, val size: Long = 0)

@Composable
internal fun RemoteFilePreview(session: TerminalConnection, path: String, connected: Boolean, canDownload: Boolean,
    onDownload: () -> Unit, onDismiss: () -> Unit) {
    var preview by remember(path) { mutableStateOf<FilePreview?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var revision by remember(path) { mutableIntStateOf(0) }
    LaunchedEffect(session.id, path, connected, revision) {
        preview = null
        error = null
        if (!connected) { error = tr("连接已断开，请重连后重试。"); return@LaunchedEffect }
        try {
            preview = withTimeout(30_000) {
                withContext(Dispatchers.IO) {
                    val image = remoteFileKind(path.substringAfterLast('/')) == RemoteFileKind.IMAGE
                    val content = session.readFileContent(path, if (image) IMAGE_PREVIEW_BYTES else TEXT_PREVIEW_BYTES)
                    val bitmap = if (image && !content.truncated) decodePreviewImage(content.bytes) else null
                    val textContent = if (content.bytes.size <= TEXT_PREVIEW_BYTES) content else
                        RemoteFileContent(content.bytes.copyOf(TEXT_PREVIEW_BYTES), content.size, true)
                    FilePreview(if (bitmap == null) textContent.decodeText() else null, bitmap,
                        if (bitmap == null) textContent.truncated else false, content.size)
                }
            }
        } catch (_: TimeoutCancellationException) {
            error = tr("读取文件超时，请重试。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = trf("无法读取文件：%s", tr(e.message ?: "请检查路径和 SFTP 服务"))
        }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SheetSystemBars()
        Surface(Modifier.fillMaxSize().testTag("file-preview"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("file-preview-close")) {
                        Icon(painterResource(R.drawable.ic_chevron_left), tr("返回文件列表"))
                    }
                    Text(path.substringAfterLast('/'), Modifier.weight(1f).testTag("file-preview-title"),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDownload, enabled = canDownload, modifier = Modifier.testTag("file-preview-download")) {
                        Icon(painterResource(R.drawable.ic_download), tr("下载"))
                    }
                }
                SelectionContainer {
                    Text(path, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                preview?.let { value ->
                    Text(formatRemoteSize(value.size), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                val value = preview
                when {
                    error != null -> PreviewMessage(error!!, Modifier.weight(1f)) {
                        TextButton(onClick = { revision++ }, enabled = connected, modifier = Modifier.testTag("file-preview-retry")) { Text(tr("重试")) }
                    }
                    value == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.testTag("file-preview-loading"))
                    }
                    value.image != null -> Image(value.image, contentDescription = path.substringAfterLast('/'),
                        contentScale = ContentScale.Fit, modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp).testTag("file-preview-image"))
                    value.text != null -> {
                        if (value.truncated) Text(tr("仅显示文件开头 256 KiB，下载可查看完整内容。"),
                            Modifier.padding(12.dp).testTag("file-preview-truncated"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                        if (value.text.isEmpty()) PreviewMessage(tr("此文件为空"), Modifier.weight(1f))
                        else {
                            // Bound each paragraph too: minified files can contain one enormous line.
                            val paragraphs = remember(value.text) { value.text.lineSequence()
                                .flatMap { if (it.isEmpty()) sequenceOf("") else it.chunked(4096).asSequence() }.toList() }
                            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                                LazyColumn(Modifier.fillMaxSize().testTag("file-preview-text"), contentPadding = PaddingValues(16.dp)) {
                                    items(paragraphs) { line -> Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                    else -> PreviewMessage(tr("此文件格式暂不支持预览，或图片超过 8 MiB。请下载后查看。"),
                        Modifier.weight(1f).testTag("file-preview-unsupported")) {
                        TextButton(onClick = onDownload, enabled = canDownload) { Text(tr("下载")) }
                    }
                }
            }
        }
    }
}

private fun decodePreviewImage(bytes: ByteArray): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@Composable
private fun PreviewMessage(message: String, modifier: Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}
