package io.itermux.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.itermux.app.R
import io.itermux.code.CodeDocument
import io.itermux.code.MarkdownParser
import io.itermux.code.MdBlock
import io.itermux.code.Languages
import io.itermux.code.SyntaxHighlighter
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.*
import kotlinx.coroutines.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.ColumnScope

private class FilePreview(val text: String? = null, val document: CodeDocument? = null, val markdown: List<MdBlock>? = null, val image: ImageBitmap? = null,
    val imageWidth: Int = 0, val imageHeight: Int = 0, val truncated: Boolean = false, val size: Long = 0)

/** What the preview of one file shows and what its reader is doing: searching, reading it rendered, full screen. */
@Stable
private class PreviewState(line: Int) {
    var preview by mutableStateOf<FilePreview?>(null)
    var error by mutableStateOf<String?>(null)
    /** Bumped to read the file again. */
    var revision by mutableIntStateOf(0)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var matches by mutableStateOf(emptyList<CodeMatch>())
    var currentMatch by mutableIntStateOf(0)
    var menu by mutableStateOf(false)
    // A line to show is a line of the source, so Markdown opens as source there.
    var rendered by mutableStateOf(line <= 0)
    var fullscreen by mutableStateOf(false)
    /** Markdown shown as a page rather than as its source. */
    val showRendered: Boolean get() = rendered && preview?.markdown != null

    fun step(delta: Int) {
        if (matches.isNotEmpty()) currentMatch = (currentMatch + delta).mod(matches.size)
    }

    fun closeSearch() {
        searching = false
        query = ""
    }
}

@Composable
internal fun RemoteFilePreview(session: TerminalConnection, path: String, connected: Boolean, canDownload: Boolean,
    onDownload: () -> Unit, onDismiss: () -> Unit, line: Int = 0) {
    val name = path.substringAfterLast('/')
    val options = rememberRemoteFileOptions()
    val state = remember(path) { PreviewState(line) }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    LaunchedEffect(session.id, path, connected, state.revision) {
        state.preview = null
        state.error = null
        if (!connected) { state.error = tr("连接已断开，请重连后重试。"); return@LaunchedEffect }
        try {
            state.preview = withTimeout(30_000) { withContext(Dispatchers.IO) { readFilePreview(session, path) } }
        } catch (_: TimeoutCancellationException) {
            state.error = tr("读取文件超时，请重试。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = trf("无法读取文件：%s", tr(e.message ?: "请检查路径和 SFTP 服务"))
        }
    }
    PreviewScrollEffects(state, options, listState, horizontal, line)
    val loadImage: suspend (String) -> ImageBitmap? = { source ->
        try {
            withContext(Dispatchers.IO) {
                val content = session.readFileContent(resolveRelativeRemotePath(path.substringBeforeLast('/', ""), source), IMAGE_PREVIEW_BYTES)
                if (content.truncated) null else decodePreviewImage(content.bytes)?.first
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    Dialog(onDismissRequest = { when {
            state.searching -> state.closeSearch()
            state.fullscreen -> state.fullscreen = false
            else -> onDismiss()
        } },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Landscape is short on rows: hide the system bars and fold the status line into the header.
        val landscape = isLandscape()
        val fullscreen = state.fullscreen
        // Portrait fullscreen keeps the status bar, see-through, with the content scrolling under it.
        val underStatusBar = fullscreen && !landscape
        SheetSystemBars(immersive = landscape, hideNavigation = underStatusBar)
        // Content backgrounds run under a side cutout; only their text is inset.
        val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        val topInset = if (underStatusBar) WindowInsets.safeDrawing.only(WindowInsetsSides.Top) else WindowInsets(0)
        Surface(Modifier.fillMaxSize().testTag("file-preview"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(
                if (underStatusBar) WindowInsetsSides.Bottom else WindowInsetsSides.Vertical))) {
                if (!fullscreen) {
                    if (state.searching) SearchBar(state.query, { state.query = it }, state.matches.size, state.currentMatch, state::step,
                        onClose = state::closeSearch, Modifier.windowInsetsPadding(sideInsets))
                    else PreviewHeader(state, options, path, landscape, canDownload, onDownload, onDismiss,
                        Modifier.windowInsetsPadding(sideInsets))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                PreviewBody(state, options, name, PreviewScroll(listState, horizontal, line), PreviewInsets(sideInsets, topInset),
                    loadImage, connected, canDownload, onDownload)
                if (!landscape && !fullscreen) state.preview?.let { StatusBar(it, Modifier.windowInsetsPadding(sideInsets)) }
            }
        }
    }
}

/** Reads [path] for the preview: an image when it decodes as one, else its text, highlighted and, for Markdown, parsed. */
private suspend fun readFilePreview(session: TerminalConnection, path: String): FilePreview {
    val name = path.substringAfterLast('/')
    val image = remoteFileKind(name) == RemoteFileKind.IMAGE
    val content = session.readFileContent(path, if (image) IMAGE_PREVIEW_BYTES else TEXT_PREVIEW_BYTES)
    val decoded = if (image && !content.truncated) decodePreviewImage(content.bytes) else null
    if (decoded != null) return FilePreview(image = decoded.first, imageWidth = decoded.second,
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
    return FilePreview(text, document, markdown, truncated = textContent.truncated, size = content.size)
}

/** Scrolls to the line the preview was opened at, finds the search's matches and brings the current one into view. */
@Composable
private fun PreviewScrollEffects(state: PreviewState, options: RemoteFileOptions, listState: LazyListState, horizontal: ScrollState,
    line: Int) {
    val document = state.preview?.document
    val query = state.query
    LaunchedEffect(document, line) {
        val row = document?.lines?.indexOfFirst { it.number == line }?.takeIf { line > 0 && it >= 0 } ?: return@LaunchedEffect
        listState.scrollToItem((row - 3).coerceAtLeast(0))
    }
    LaunchedEffect(document, query) {
        if (document == null || query.isEmpty()) { state.matches = emptyList(); return@LaunchedEffect }
        delay(150)
        state.matches = withContext(Dispatchers.Default) { findCodeMatches(document, query) }
        state.currentMatch = state.matches.indexOfFirst { it.row >= listState.firstVisibleItemIndex }.coerceAtLeast(0)
    }
    val density = LocalDensity.current
    LaunchedEffect(state.matches, state.currentMatch) {
        val match = state.matches.getOrNull(state.currentMatch) ?: return@LaunchedEffect
        listState.animateScrollToItem((match.row - 3).coerceAtLeast(0))
        if (!options.codeWrap) {
            val charWidth = with(density) { (options.codeFontSize * 0.6f).dp.toPx() }
            horizontal.animateScrollTo((match.start * charWidth - horizontal.viewportSize / 3f).toInt().coerceAtLeast(0))
        }
    }
}

/** The bar over the file: back, its name, the Markdown toggle, search, download and the menu. */
@Composable
private fun PreviewHeader(state: PreviewState, options: RemoteFileOptions, path: String, landscape: Boolean, canDownload: Boolean,
    onDownload: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val preview = state.preview
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("file-preview-close")) {
            Icon(painterResource(R.drawable.ic_chevron_left), tr("返回文件列表"))
        }
        // The full path stays one tap away under "Copy path" in the overflow menu.
        Text(path.substringAfterLast('/'), Modifier.weight(1f).padding(start = 4.dp).testTag("file-preview-title"),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (landscape && preview != null) {
            Text(statusText(preview), Modifier.padding(horizontal = 8.dp).testTag("file-preview-status"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (preview?.markdown != null) IconButton(onClick = { state.rendered = !state.rendered },
            modifier = Modifier.testTag("file-preview-markdown-toggle")) {
            Icon(painterResource(if (state.rendered) R.drawable.ic_file_code else R.drawable.ic_preview),
                tr(if (state.rendered) "查看源码" else "预览"))
        }
        if (preview?.document != null) IconButton(onClick = { state.rendered = false; state.searching = true },
            modifier = Modifier.testTag("file-preview-search")) {
            Icon(painterResource(R.drawable.ic_search), tr("在文件中查找"))
        }
        IconButton(onClick = onDownload, enabled = canDownload, modifier = Modifier.testTag("file-preview-download")) {
            Icon(painterResource(R.drawable.ic_download), tr("下载"))
        }
        PreviewMenu(state, options, path)
    }
}

/** The overflow menu: how code is shown (wrap, font size) and what can be copied. */
@Composable
private fun PreviewMenu(state: PreviewState, options: RemoteFileOptions, path: String) {
    val context = LocalContext.current
    val preview = state.preview
    Box {
        IconButton(onClick = { state.menu = true }, modifier = Modifier.testTag("file-preview-menu")) {
            Icon(painterResource(R.drawable.ic_more), tr("更多操作"))
        }
        DropdownMenu(expanded = state.menu, onDismissRequest = { state.menu = false }, modifier = Modifier.widthIn(min = 220.dp)) {
            if (preview?.document != null && !state.showRendered) {
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
                    onClick = { context.copyText(text); state.menu = false },
                    modifier = Modifier.testTag("file-preview-copy"))
            }
            DropdownMenuItem(text = { Text(tr("复制路径")) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) },
                onClick = { context.copyText(path); state.menu = false },
                modifier = Modifier.testTag("file-preview-copy-path"))
        }
    }
}

/** Where the code is scrolled to, and the line the preview was opened at. */
private class PreviewScroll(val list: LazyListState, val horizontal: ScrollState, val markedLine: Int)

/** The cutout's sides, and the status bar's height while the content runs under it. */
private class PreviewInsets(val side: WindowInsets, val top: WindowInsets)

/** The file itself: its image, its text as code or as a Markdown page, or why there is nothing to show. */
@Composable
private fun ColumnScope.PreviewBody(state: PreviewState, options: RemoteFileOptions, name: String, scroll: PreviewScroll,
    insets: PreviewInsets, loadImage: suspend (String) -> ImageBitmap?, connected: Boolean, canDownload: Boolean, onDownload: () -> Unit) {
    val value = state.preview
    val failure = state.error
    val toggleFullscreen = Modifier.observeDoubleTap { if (!state.searching) state.fullscreen = !state.fullscreen }
    when {
        failure != null -> PreviewMessage(failure, Modifier.weight(1f)) {
            TextButton(onClick = { state.revision++ }, enabled = connected, modifier = Modifier.testTag("file-preview-retry")) { Text(tr("重试")) }
        }
        value == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag("file-preview-loading"))
        }
        value.image != null -> ZoomableImage(value.image, name, Modifier.weight(1f).fillMaxWidth(),
            onTap = { state.fullscreen = !state.fullscreen })
        value.text != null -> {
            if (value.truncated) Text(tr("仅显示文件开头 256 KiB，下载可查看完整内容。"),
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                    .windowInsetsPadding(insets.top).padding(horizontal = 16.dp, vertical = 8.dp).testTag("file-preview-truncated"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            val textInsets = if (value.truncated) insets.side else insets.side.add(insets.top)
            if (value.document == null) PreviewMessage(tr("此文件为空"), Modifier.weight(1f))
            else if (state.showRendered && value.markdown != null) MarkdownView(value.markdown, loadImage,
                Modifier.weight(1f).fillMaxWidth().then(toggleFullscreen), insets = textInsets)
            else CodeViewer(value.document, options.codeWrap, options.codeFontSize, scroll.list, scroll.horizontal,
                state.matches, state.currentMatch, Modifier.weight(1f).fillMaxWidth().then(toggleFullscreen), textInsets,
                markedLine = scroll.markedLine)
        }
        else -> PreviewMessage(tr("此文件格式暂不支持预览，或图片超过 8 MiB。请下载后查看。"),
            Modifier.weight(1f).testTag("file-preview-unsupported")) {
            TextButton(onClick = onDownload, enabled = canDownload) { Text(tr("下载")) }
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

/** Pinch to zoom, drag to pan, double-tap to toggle 2x; a single tap is left to [onTap]. */
@Composable
private fun ZoomableImage(image: ImageBitmap, description: String, modifier: Modifier, onTap: () -> Unit) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .pointerInput(image) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = {
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

/**
 * Sees double taps without consuming anything, so text selection, links and scrolling underneath keep working.
 * Taps that move past touch slop, last as long as a long press, or use a second finger do not count.
 */
internal fun Modifier.observeDoubleTap(onDoubleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var lastUp = 0L
    var lastPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var tap = true
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) tap = false
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
            if (change.pressed) continue
            tap = tap && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
            if (tap && change.uptimeMillis - lastUp < viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastPosition).getDistance() < viewConfiguration.touchSlop * 4) {
                lastUp = 0L
                onDoubleTap()
            } else if (tap) {
                lastUp = change.uptimeMillis
                lastPosition = down.position
            } else lastUp = 0L
            break
        }
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
