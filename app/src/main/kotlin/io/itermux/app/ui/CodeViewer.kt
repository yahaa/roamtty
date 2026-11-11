package io.itermux.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.TerminalFonts
import io.itermux.code.CodeDocument
import io.itermux.code.CodeLine
import io.itermux.code.TokenType

/** Token colors follow VS Code Dark+ and GitHub Light. */
internal class CodeTheme(val styles: Array<SpanStyle>, val gutter: Color, val match: Color, val currentMatch: Color) {
    companion object {
        private fun theme(colors: Map<TokenType, Long>, gutter: Long, match: Long, current: Long) = CodeTheme(
            TokenType.entries.map { type ->
                val color = Color(colors.getValue(type))
                when (type) {
                    TokenType.HEADING, TokenType.STRONG -> SpanStyle(color, fontWeight = FontWeight.Bold)
                    TokenType.EMPHASIS, TokenType.COMMENT -> SpanStyle(color, fontStyle = FontStyle.Italic)
                    TokenType.LINK -> SpanStyle(color, textDecoration = TextDecoration.Underline)
                    TokenType.ERROR -> SpanStyle(color, fontWeight = FontWeight.Bold)
                    else -> SpanStyle(color)
                }
            }.toTypedArray(), Color(gutter), Color(match), Color(current))

        val Dark = theme(mapOf(
            TokenType.KEYWORD to 0xFF569CD6, TokenType.TYPE to 0xFF4EC9B0, TokenType.STRING to 0xFFCE9178,
            TokenType.NUMBER to 0xFFB5CEA8, TokenType.COMMENT to 0xFF6A9955, TokenType.FUNCTION to 0xFFDCDCAA,
            TokenType.PROPERTY to 0xFF9CDCFE, TokenType.VARIABLE to 0xFF9CDCFE, TokenType.CONSTANT to 0xFF569CD6,
            TokenType.META to 0xFFC586C0, TokenType.TAG to 0xFF569CD6, TokenType.ATTRIBUTE to 0xFF9CDCFE,
            TokenType.PUNCTUATION to 0xFF808080, TokenType.HEADING to 0xFF569CD6, TokenType.STRONG to 0xFFE2E2E2,
            TokenType.EMPHASIS to 0xFFE2E2E2, TokenType.LINK to 0xFF4FC1FF, TokenType.INSERTED to 0xFF89D185,
            TokenType.DELETED to 0xFFF48771, TokenType.ERROR to 0xFFF14C4C, TokenType.WARNING to 0xFFCCA700,
            TokenType.INFO to 0xFF3794FF
        ), gutter = 0xFF858585, match = 0x66623315, current = 0xCC9E6A03)

        /** GitHub Light: calmer than Light+'s pure blues next to the app's warm paper tones. */
        val Light = theme(mapOf(
            TokenType.KEYWORD to 0xFFCF222E, TokenType.TYPE to 0xFF953800, TokenType.STRING to 0xFF0A3069,
            TokenType.NUMBER to 0xFF0550AE, TokenType.COMMENT to 0xFF6E7781, TokenType.FUNCTION to 0xFF8250DF,
            TokenType.PROPERTY to 0xFF0550AE, TokenType.VARIABLE to 0xFF953800, TokenType.CONSTANT to 0xFF0550AE,
            TokenType.META to 0xFF8250DF, TokenType.TAG to 0xFF116329, TokenType.ATTRIBUTE to 0xFF0550AE,
            TokenType.PUNCTUATION to 0xFF6E7781, TokenType.HEADING to 0xFF0550AE, TokenType.STRONG to 0xFF1F2328,
            TokenType.EMPHASIS to 0xFF1F2328, TokenType.LINK to 0xFF0969DA, TokenType.INSERTED to 0xFF116329,
            TokenType.DELETED to 0xFF82071E, TokenType.ERROR to 0xFFCF222E, TokenType.WARNING to 0xFF9A6700,
            TokenType.INFO to 0xFF0969DA
        ), gutter = 0xFFA0A7AF, match = 0x55FFD33D, current = 0xCCFFB000)
    }
}

@Composable
internal fun rememberCodeTheme(): CodeTheme {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) CodeTheme.Dark else CodeTheme.Light
}

/** A search hit in display-row coordinates. */
internal data class CodeMatch(val row: Int, val start: Int, val end: Int)

internal fun findCodeMatches(document: CodeDocument, query: String, limit: Int = 10_000): List<CodeMatch> {
    if (query.isEmpty()) return emptyList()
    val matches = ArrayList<CodeMatch>()
    document.lines.forEachIndexed { row, line ->
        var at = line.text.indexOf(query, ignoreCase = true)
        while (at >= 0) {
            matches += CodeMatch(row, at, at + query.length)
            if (matches.size >= limit) return matches
            at = line.text.indexOf(query, at + query.length, ignoreCase = true)
        }
    }
    return matches
}

private fun CodeLine.annotated(theme: CodeTheme, matches: List<CodeMatch>, current: CodeMatch?): AnnotatedString {
    if (spanCount == 0 && matches.isEmpty()) return AnnotatedString(text)
    return AnnotatedString.Builder(text).apply {
        for (s in 0 until spanCount) addStyle(theme.styles[spans[s * 3 + 2]], spans[s * 3], spans[s * 3 + 1])
        for (match in matches) addStyle(SpanStyle(background = if (match == current) theme.currentMatch else theme.match),
            match.start, match.end)
    }.toAnnotatedString()
}

/** A whole document as one string, for short snippets such as Markdown code blocks. */
internal fun annotatedCode(document: CodeDocument, theme: CodeTheme): AnnotatedString = AnnotatedString.Builder().apply {
    document.lines.forEachIndexed { index, line ->
        if (index > 0 && line.number > 0) append('\n')
        append(line.annotated(theme, emptyList(), null))
    }
}.toAnnotatedString()

/** The face the terminal draws with, so code reads the same in previews as in the shell. */
@Composable
internal fun rememberTerminalFontFamily(): FontFamily {
    val context = LocalContext.current
    return remember(TerminalFonts.selection, TerminalFonts.revision) { FontFamily(TerminalFonts.resolveTypeface(context)) }
}

@Composable
internal fun CodeViewer(document: CodeDocument, wrap: Boolean, fontSize: Float, listState: LazyListState,
    horizontalScroll: ScrollState, matches: List<CodeMatch>, currentMatch: Int, modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets(0), markedLine: Int = 0) {
    val theme = rememberCodeTheme()
    val marked = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val fontFamily = rememberTerminalFontFamily()
    val textStyle = TextStyle(fontFamily = fontFamily, fontSize = fontSize.sp, lineHeight = (fontSize * 1.5f).sp,
        color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val charWidth = remember(fontSize, fontFamily) { measurer.measure("0000000000", textStyle).size.width / 10f }
    val density = LocalDensity.current
    val gutterWidth = with(density) { (charWidth * maxOf(3, document.lineCount.toString().length)).toDp() }
    val contentWidth = with(density) { (charWidth * (document.maxColumns + 2)).toDp() }
    val byRow = remember(matches) { matches.groupBy { it.row } }
    val current = matches.getOrNull(currentMatch)
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).testTag("file-preview-text"),
            state = listState, contentPadding = insets.add(WindowInsets(top = 8.dp, bottom = 8.dp)).asPaddingValues()) {
            itemsIndexed(document.lines) { row, line ->
                val rowMatches = byRow[row].orEmpty()
                val text = remember(line, theme, rowMatches, current) {
                    line.annotated(theme, rowMatches, current?.takeIf { it.row == row })
                }
                // A wrapped line's continuation rows carry number 0; only the line's first row is marked.
                val mark = if (markedLine > 0 && line.number == markedLine) Modifier.background(marked).testTag("file-preview-marked-line") else Modifier
                Row(Modifier.fillMaxWidth().then(mark)) {
                    Text(if (line.number > 0) line.number.toString() else "", style = textStyle,
                        color = theme.gutter, textAlign = TextAlign.End, maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp).width(gutterWidth))
                    Spacer(Modifier.width(14.dp))
                    if (wrap) Text(text, style = textStyle, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    else Box(Modifier.weight(1f).horizontalScroll(horizontalScroll)) {
                        // Every row shares one width so the shared scroll range is stable.
                        Text(text, style = textStyle, softWrap = false, maxLines = 1, modifier = Modifier.width(contentWidth))
                    }
                }
            }
        }
    }
}
