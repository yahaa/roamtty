package io.itermux.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.itermux.app.R
import io.itermux.app.code.*
import io.itermux.app.tr

private class MarkdownColors(val link: Color, val codeBackground: Color, val muted: Color, val rule: Color, val codeFont: FontFamily)

private val bullets = listOf("•", "◦", "▪")

private fun isWebLink(url: String) = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")

/** Opening a link must never crash the preview, for example when no browser is installed. */
private fun UriHandler.openSafely(url: String) { runCatching { openUri(url) } }

private fun MdText.annotated(colors: MarkdownColors, uriHandler: UriHandler): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        when (span.style) {
            MdStyle.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), span.start, span.end)
            MdStyle.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
            MdStyle.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
            MdStyle.CODE -> addStyle(SpanStyle(fontFamily = colors.codeFont, fontSize = 0.88.em,
                background = colors.codeBackground), span.start, span.end)
            MdStyle.LINK, MdStyle.IMAGE -> {
                val url = span.url.orEmpty()
                val style = SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                // Relative links point into the repository, not the web; style them without opening anything.
                if (isWebLink(url)) addLink(LinkAnnotation.Clickable(url, TextLinkStyles(style)) { uriHandler.openSafely(url) },
                    span.start, span.end)
                else addStyle(style.copy(textDecoration = null), span.start, span.end)
            }
        }
    }
}

@Composable
internal fun MarkdownView(blocks: List<MdBlock>, loadImage: (suspend (String) -> ImageBitmap?)?, modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
    val scheme = MaterialTheme.colorScheme
    val colors = MarkdownColors(scheme.primary, scheme.surfaceContainerHighest, scheme.onSurfaceVariant, scheme.outlineVariant,
        rememberTerminalFontFamily())
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().background(background).testTag("file-preview-markdown"),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // List items sit close together; every other block gets a little more air.
            items(blocks) { block ->
                Box(if (block is MdBlock.ListItem) Modifier else Modifier.padding(vertical = 3.dp)) { MarkdownBlock(block, colors, loadImage) }
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, colors: MarkdownColors, loadImage: (suspend (String) -> ImageBitmap?)?) {
    val uriHandler = LocalUriHandler.current
    val body = MaterialTheme.typography.bodyLarge
    when (block) {
        is MdBlock.Heading -> Column(Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp)) {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                4 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.labelLarge
            }
            Text(block.text.annotated(colors, uriHandler), style = style.copy(fontWeight = FontWeight.SemiBold),
                color = if (block.level >= 5) colors.muted else Color.Unspecified)
            if (block.level <= 2) HorizontalDivider(Modifier.padding(top = 6.dp), color = colors.rule)
        }
        is MdBlock.Paragraph -> Text(block.text.annotated(colors, uriHandler), style = body)
        is MdBlock.Code -> CodeBlock(block, colors.codeFont)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.rule))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompositionLocalProvider(LocalContentColor provides colors.muted) {
                    block.blocks.forEach { MarkdownBlock(it, colors, loadImage) }
                }
            }
        }
        is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 20).dp)) {
            Box(Modifier.width(26.dp).padding(top = 2.dp), contentAlignment = Alignment.TopStart) {
                if (block.checked != null) Checkbox(block.checked, onCheckedChange = null, modifier = Modifier.size(20.dp))
                else Text(if (block.marker == "•") bullets[block.depth % bullets.size] else block.marker, style = body,
                    color = colors.muted)
            }
            Text(block.text.annotated(colors, uriHandler), style = body, modifier = Modifier.weight(1f))
        }
        is MdBlock.Table -> MarkdownTable(block, colors)
        is MdBlock.Image -> MarkdownImage(block, colors, loadImage)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.rule)
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, font: FontFamily) {
    val theme = rememberCodeTheme()
    val clipboard = LocalClipboardManager.current
    val language = remember(block.language) { Languages.forFence(block.language) ?: Languages.PLAIN }
    val text = remember(block.code, language, theme) { annotatedCode(SyntaxHighlighter.highlight(block.code, language), theme) }
    // Dark pages sink code into a deeper well; light pages need a tinted block to stand out from the paper.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Surface(shape = RoundedCornerShape(10.dp),
        color = if (dark) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.ifEmpty { tr("代码") }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }, modifier = Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), tr("复制代码"), Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(text, Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                fontFamily = font, fontSize = 13.sp, lineHeight = 19.sp, softWrap = false,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, colors: MarkdownColors) {
    val uriHandler = LocalUriHandler.current
    val widths = remember(table) {
        table.header.indices.map { column ->
            val longest = (listOf(table.header[column]) + table.rows.map { it[column] }).maxOf { it.text.length }
            (longest * 8 + 24).coerceIn(64, 260).dp
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(shape).border(1.dp, colors.rule, shape)) {
            (listOf(table.header) + table.rows).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = colors.rule)
                Row(if (index == 0) Modifier.background(colors.codeBackground) else Modifier) {
                    row.forEachIndexed { column, cell ->
                        Text(cell.annotated(colors, uriHandler), Modifier.width(widths[column]).padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (index == 0) FontWeight.SemiBold else null),
                            textAlign = when (table.alignments[column]) {
                                MdAlign.START -> TextAlign.Start
                                MdAlign.CENTER -> TextAlign.Center
                                MdAlign.END -> TextAlign.End
                            })
                    }
                }
            }
        }
    }
}

/** Images next to the document load over SFTP; web images stay links so opening a file never phones home. */
@Composable
private fun MarkdownImage(image: MdBlock.Image, colors: MarkdownColors, loadImage: (suspend (String) -> ImageBitmap?)?) {
    val uriHandler = LocalUriHandler.current
    val web = isWebLink(image.source) || image.source.startsWith("data:")
    var bitmap by remember(image.source) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(image.source) { mutableStateOf(web || loadImage == null) }
    if (!failed && bitmap == null && loadImage != null) LaunchedEffect(image.source) {
        bitmap = loadImage(image.source)
        failed = bitmap == null
    }
    bitmap?.let {
        Image(it, image.alt, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(8.dp)))
        return
    }
    Surface(onClick = { if (isWebLink(image.source)) uriHandler.openSafely(image.source) }, enabled = isWebLink(image.source),
        shape = RoundedCornerShape(8.dp), color = colors.codeBackground) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!failed) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else Icon(painterResource(R.drawable.ic_image), null, Modifier.size(16.dp), tint = colors.muted)
            Spacer(Modifier.width(8.dp))
            Text(image.alt.ifBlank { image.source.substringAfterLast('/') }, style = MaterialTheme.typography.labelMedium,
                color = colors.muted, maxLines = 1)
        }
    }
}
