package io.itermux.app.ui

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.itermux.app.R
import io.itermux.app.code.CodeDocument
import io.itermux.app.code.MarkdownParser
import io.itermux.app.code.MdBlock
import io.itermux.app.code.Languages
import io.itermux.app.code.SyntaxHighlighter
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.*
import kotlinx.coroutines.*

private class FilePreview(val text: String? = null, val document: CodeDocument? = null, val markdown: List<MdBlock>? = null, val image: ImageBitmap? = null,
    val imageWidth: Int = 0, val imageHeight: Int = 0, val truncated: Boolean = false, val size: Long = 0)

@Composable
internal fun RemoteFilePreview(session: TerminalConnection, path: String, connected: Boolean, canDownload: Boolean,
    onDownload: () -> Unit, onDismiss: () -> Unit) {
    val name = path.substringAfterLast('/')
    val options = rememberRemoteFileOptions()
    val clipboard = LocalClipboardManager.current
    var preview by remember(path) { mutableStateOf<FilePreview?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var revision by remember(path) { mutableIntStateOf(0) }
    var searching by remember(path) { mutableStateOf(false) }
    var query by remember(path) { mutableStateOf("") }
    var matches by remember(path) { mutableStateOf(emptyList<CodeMatch>()) }
    var currentMatch by remember(path) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var rendered by remember(path) { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    LaunchedEffect(session.id, path, connected, revision) {
        preview = null
        error = null
        if (!connected) { error = tr("连接已断开，请重连后重试。"); return@LaunchedEffect }
        try {
            preview = withTimeout(30_000) {
                withContext(Dispatchers.IO) {
                    val image = remoteFileKind(name) == RemoteFileKind.IMAGE
                    val content = session.readFileContent(path, if (image) IMAGE_PREVIEW_BYTES else TEXT_PREVIEW_BYTES)
                    val decoded = if (image && !content.truncated) decodePreviewImage(content.bytes) else null
                    if (decoded != null) return@withContext FilePreview(image = decoded.first, imageWidth = decoded.second,
                        imageHeight = decoded.third, size = content.size)
                    val textContent = if (content.bytes.size <= TEXT_PREVIEW_BYTES) content else
                        RemoteFileContent(content.bytes.copyOf(TEXT_PREVIEW_BYTES), content.size, true)
                    val text = textContent.decodeText()
                    val document = text?.takeIf { it.isNotEmpty() }?.let {
                        withContext(Dispatchers.Default) {
                            SyntaxHighlighter.highlight(it, Languages.detect(name, it.substringBefore('\n').take(200)))
                        }
                    }
                    val markdown = if (document?.language == Languages.MARKDOWN) withContext(Dispatchers.Default) { MarkdownParser.parse(text) } else null
                    FilePreview(text, document, markdown, truncated = textContent.truncated, size = content.size)
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
    val document = preview?.document
    val showRendered = rendered && preview?.markdown != null
    val loadImage: suspend (String) -> ImageBitmap? = { source ->
        try {
            withContext(Dispatchers.IO) {
                val content = session.readFileContent(resolveRelativeRemotePath(path.substringBeforeLast('/', ""), source), IMAGE_PREVIEW_BYTES)
                if (content.truncated) null else decodePreviewImage(content.bytes)?.first
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    LaunchedEffect(document, query) {
        if (document == null || query.isEmpty()) { matches = emptyList(); return@LaunchedEffect }
        delay(150)
        matches = withContext(Dispatchers.Default) { findCodeMatches(document, query) }
        currentMatch = matches.indexOfFirst { it.row >= listState.firstVisibleItemIndex }.coerceAtLeast(0)
    }
    val density = LocalDensity.current
    LaunchedEffect(matches, currentMatch) {
        val match = matches.getOrNull(currentMatch) ?: return@LaunchedEffect
        listState.animateScrollToItem((match.row - 3).coerceAtLeast(0))
        if (!options.codeWrap) {
            val charWidth = with(density) { (options.codeFontSize * 0.6f).dp.toPx() }
            horizontal.animateScrollTo((match.start * charWidth - horizontal.viewportSize / 3f).toInt().coerceAtLeast(0))
        }
    }
    fun step(delta: Int) { if (matches.isNotEmpty()) currentMatch = (currentMatch + delta).mod(matches.size) }

    Dialog(onDismissRequest = { if (searching) { searching = false; query = "" } else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Landscape is short on rows: hide the system bars and fold the status line into the header.
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        SheetSystemBars(immersive = landscape)
        // Content backgrounds run under a side cutout; only their text is inset.
        val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        Surface(Modifier.fillMaxSize().testTag("file-preview"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
                if (searching) SearchBar(query, { query = it }, matches.size, currentMatch, ::step,
                    onClose = { searching = false; query = "" }, Modifier.windowInsetsPadding(sideInsets))
                else Row(Modifier.fillMaxWidth().windowInsetsPadding(sideInsets).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("file-preview-close")) {
                        Icon(painterResource(R.drawable.ic_chevron_left), tr("返回文件列表"))
                    }
                    // The full path stays one tap away under "Copy path" in the overflow menu.
                    Text(name, Modifier.weight(1f).padding(start = 4.dp).testTag("file-preview-title"),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (landscape) preview?.let {
                        Text(statusText(it), Modifier.padding(horizontal = 8.dp).testTag("file-preview-status"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (preview?.markdown != null) IconButton(onClick = { rendered = !rendered },
                        modifier = Modifier.testTag("file-preview-markdown-toggle")) {
                        Icon(painterResource(if (rendered) R.drawable.ic_file_code else R.drawable.ic_preview),
                            tr(if (rendered) "查看源码" else "预览"))
                    }
                    if (document != null) IconButton(onClick = { rendered = false; searching = true }, modifier = Modifier.testTag("file-preview-search")) {
                        Icon(painterResource(R.drawable.ic_search), tr("在文件中查找"))
                    }
                    IconButton(onClick = onDownload, enabled = canDownload, modifier = Modifier.testTag("file-preview-download")) {
                        Icon(painterResource(R.drawable.ic_download), tr("下载"))
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("file-preview-menu")) {
                            Icon(painterResource(R.drawable.ic_more), tr("更多操作"))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.widthIn(min = 220.dp)) {
                            if (document != null && !showRendered) {
                                DropdownMenuItem(text = { Text(tr("自动换行")) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_wrap), null) },
                                    trailingIcon = { Checkbox(options.codeWrap, onCheckedChange = null) },
                                    onClick = { options.changeCodeWrap(!options.codeWrap) },
                                    modifier = Modifier.testTag("file-preview-wrap"))
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(R.drawable.ic_text_size), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(tr("字号"), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                                    TextButton(onClick = { options.changeCodeFontSize(-1f) },
                                        enabled = options.codeFontSize > RemoteFileOptions.MIN_CODE_FONT_SIZE,
                                        modifier = Modifier.testTag("file-preview-font-smaller")) { Text("A−") }
                                    Text(options.codeFontSize.toInt().toString(), style = MaterialTheme.typography.labelLarge)
                                    TextButton(onClick = { options.changeCodeFontSize(1f) },
                                        enabled = options.codeFontSize < RemoteFileOptions.MAX_CODE_FONT_SIZE,
                                        modifier = Modifier.testTag("file-preview-font-larger")) { Text("A+") }
                                }
                                HorizontalDivider()
                            }
                            preview?.text?.takeIf { it.isNotEmpty() }?.let { text ->
                                DropdownMenuItem(text = { Text(tr("复制全部内容")) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) },
                                    onClick = { clipboard.setText(AnnotatedString(text)); menu = false },
                                    modifier = Modifier.testTag("file-preview-copy"))
                            }
                            DropdownMenuItem(text = { Text(tr("复制路径")) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) },
                                onClick = { clipboard.setText(AnnotatedString(path)); menu = false },
                                modifier = Modifier.testTag("file-preview-copy-path"))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val value = preview
                when {
                    error != null -> PreviewMessage(error!!, Modifier.weight(1f)) {
                        TextButton(onClick = { revision++ }, enabled = connected, modifier = Modifier.testTag("file-preview-retry")) { Text(tr("重试")) }
                    }
                    value == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.testTag("file-preview-loading"))
                    }
                    value.image != null -> ZoomableImage(value.image, name, Modifier.weight(1f).fillMaxWidth())
                    value.text != null -> {
                        if (value.truncated) Text(tr("仅显示文件开头 256 KiB，下载可查看完整内容。"),
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("file-preview-truncated"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        if (value.document == null) PreviewMessage(tr("此文件为空"), Modifier.weight(1f))
                        else if (showRendered) MarkdownView(value.markdown!!, loadImage, Modifier.weight(1f).fillMaxWidth(), sideInsets = sideInsets)
                        else CodeViewer(value.document, options.codeWrap, options.codeFontSize, listState, horizontal,
                            matches, currentMatch, Modifier.weight(1f).fillMaxWidth(), sideInsets)
                    }
                    else -> PreviewMessage(tr("此文件格式暂不支持预览，或图片超过 8 MiB。请下载后查看。"),
                        Modifier.weight(1f).testTag("file-preview-unsupported")) {
                        TextButton(onClick = onDownload, enabled = canDownload) { Text(tr("下载")) }
                    }
                }
                if (!landscape) value?.let { StatusBar(it, Modifier.windowInsetsPadding(sideInsets)) }
            }
        }
    }
}

/** Editor-style status line: language, line count, size. */
private fun statusText(preview: FilePreview): String = buildList {
        preview.document?.let {
            add(it.language.displayName.let { label -> if (label == "Plain Text") tr("纯文本") else label })
            add(trf("%d 行", it.lineCount))
        }
        if (preview.image != null) add("${preview.imageWidth} × ${preview.imageHeight}")
        add(formatRemoteSize(preview.size))
    }.joinToString("  ·  ")

@Composable
private fun StatusBar(preview: FilePreview, modifier: Modifier = Modifier) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(statusText(preview), modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("file-preview-status"),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, count: Int, current: Int, onStep: (Int) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).testTag("file-preview-search-bar"),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("file-preview-search-close")) {
            Icon(painterResource(R.drawable.ic_chevron_left), tr("关闭查找"))
        }
        AppSearchField(query, onQuery, tr("在文件中查找"),
            trailing = if (query.isEmpty()) null else { {
                Text(if (count == 0) tr("无结果") else "${current + 1}/$count", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp).testTag("file-preview-search-count"))
            } },
            keyboardActions = KeyboardActions(onSearch = { onStep(1) }),
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("file-preview-search-input"))
        IconButton(onClick = { onStep(-1) }, enabled = count > 0, modifier = Modifier.testTag("file-preview-search-previous")) {
            Icon(painterResource(R.drawable.ic_expand_up), tr("上一个"))
        }
        IconButton(onClick = { onStep(1) }, enabled = count > 0, modifier = Modifier.testTag("file-preview-search-next")) {
            Icon(painterResource(R.drawable.ic_expand_down), tr("下一个"))
        }
    }
}

/** Pinch to zoom, drag to pan, double-tap to toggle 2x. */
@Composable
private fun ZoomableImage(image: ImageBitmap, description: String, modifier: Modifier) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .pointerInput(image) {
            detectTapGestures(onDoubleTap = {
                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2f
            })
        }
        .pointerInput(image) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
        }, contentAlignment = Alignment.Center) {
        Image(image, contentDescription = description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(16.dp).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }.testTag("file-preview-image"))
    }
}

private fun decodePreviewImage(bytes: ByteArray): Triple<ImageBitmap, Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val width = options.outWidth
    val height = options.outHeight
    options.inSampleSize = 1
    while (maxOf(width, height) / options.inSampleSize > 2048) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return Triple(bitmap.asImageBitmap(), width, height)
}

@Composable
private fun PreviewMessage(message: String, modifier: Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

/** Resolves a document-relative link such as `../img/a.png` against [directory]; absolute paths pass through. */
internal fun resolveRelativeRemotePath(directory: String, source: String): String {
    val clean = source.substringBefore('#').substringBefore('?')
    val segments = ArrayList<String>()
    val absolute = clean.startsWith('/') || directory.startsWith('/')
    (if (clean.startsWith('/')) clean else "$directory/$clean").split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += part
        }
    }
    return (if (absolute) "/" else "") + segments.joinToString("/")
}
