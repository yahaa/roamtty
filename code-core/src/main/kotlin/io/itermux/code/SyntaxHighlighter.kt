package io.itermux.code


/** One display row. [number] is 0 for continuation rows of a wrapped long line. */
class CodeLine(val number: Int, val text: String, val spans: IntArray) {
    /** Spans are (start, end, TokenType.ordinal) triples relative to [text]. */
    val spanCount: Int get() = spans.size / 3
}

/** [maxColumns] counts East Asian wide characters twice, for sizing unwrapped rows. */
class CodeDocument(val language: CodeLanguage, val lines: List<CodeLine>, val lineCount: Int, val maxColumns: Int)

private fun isWide(c: Char): Boolean {
    val code = c.code
    return code in 0x1100..0x115F || code in 0x2E80..0xA4CF || code in 0xAC00..0xD7A3 || code in 0xF900..0xFAFF ||
        code in 0xFE30..0xFE4F || code in 0xFF00..0xFF60 || code in 0xFFE0..0xFFE6
}

internal class SpanBuffer {
    var data = IntArray(256)
    var size = 0
    fun add(start: Int, end: Int, type: TokenType) {
        if (end <= start) return
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = start; data[size++] = end; data[size++] = type.ordinal
    }
}

object SyntaxHighlighter {
    /** Rows longer than this are split so a minified file cannot produce one enormous text layout. */
    const val MAX_ROW_LENGTH = 2000
    /** The longest pattern token (URL, timestamp, …) matched; longer runs are cut there. */
    internal const val MAX_TOKEN_LENGTH = 2000
    private const val TAB_WIDTH = 4

    fun highlight(rawText: String, language: CodeLanguage): CodeDocument {
        val text = expandTabs(rawText.replace("\r\n", "\n").replace('\r', '\n'))
        val spans = SpanBuffer()
        when (language.mode) {
            LanguageMode.PLAIN -> Unit
            LanguageMode.CODE -> tokenizeCode(language, text, 0, text.length, spans)
            LanguageMode.MARKUP -> tokenizeMarkup(text, 0, text.length, spans)
            LanguageMode.MARKDOWN -> tokenizeMarkdown(text, spans)
        }
        return splitRows(text, spans, language)
    }

    internal fun expandTabs(text: String): String {
        if ('\t' !in text) return text
        val out = StringBuilder(text.length + 64)
        var column = 0
        for (c in text) when (c) {
            '\t' -> { val pad = TAB_WIDTH - column % TAB_WIDTH; repeat(pad) { out.append(' ') }; column += pad }
            '\n' -> { out.append(c); column = 0 }
            else -> { out.append(c); column++ }
        }
        return out.toString()
    }

    private fun splitRows(text: String, spans: SpanBuffer, language: CodeLanguage): CodeDocument {
        val rows = ArrayList<CodeLine>(text.length / 32 + 1)
        val data = spans.data
        val count = spans.size
        var k = 0
        var lineStart = 0
        var number = 0
        var maxColumns = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            number++
            var rowStart = lineStart
            do {
                val rowEnd = minOf(newline, rowStart + MAX_ROW_LENGTH)
                while (k < count && data[k + 1] <= rowStart) k += 3
                var j = k
                var rowSpans = IntArray(0)
                var used = 0
                while (j < count && data[j] < rowEnd) {
                    val start = maxOf(data[j], rowStart) - rowStart
                    val end = minOf(data[j + 1], rowEnd) - rowStart
                    if (end > start) {
                        if (used + 3 > rowSpans.size) rowSpans = rowSpans.copyOf(maxOf(6, rowSpans.size * 2))
                        rowSpans[used++] = start; rowSpans[used++] = end; rowSpans[used++] = data[j + 2]
                    }
                    j += 3
                }
                var columns = rowEnd - rowStart
                for (index in rowStart until rowEnd) if (isWide(text[index])) columns++
                if (columns > maxColumns) maxColumns = columns
                rows += CodeLine(if (rowStart == lineStart) number else 0, text.substring(rowStart, rowEnd),
                    if (used == rowSpans.size) rowSpans else rowSpans.copyOf(used))
                rowStart = rowEnd
            } while (rowStart < newline)
            if (newline == text.length) break
            lineStart = newline + 1
        }
        return CodeDocument(language, rows, number, maxColumns)
    }

    internal fun isIdentStart(c: Char, language: CodeLanguage) = c.isLetter() || c == '_' || (c != '-' && c in language.identifierExtra)
    internal fun isIdentPart(c: Char, language: CodeLanguage) = c.isLetterOrDigit() || c in language.identifierExtra

    internal fun lineEnd(text: String, from: Int, to: Int): Int = text.indexOf('\n', from).let { if (it < 0 || it > to) to else it }

    /** Returns the end of a string literal, or -1 when the opening quote does not start one. */
    internal fun scanString(text: String, from: Int, to: Int, rule: StringRule): Int {
        var j = from + rule.open.length
        while (j < to) {
            val c = text[j]
            if (rule.escape != null && c == rule.escape && j + 1 < to) { j += 2; continue }
            if (text.startsWith(rule.close, j)) return j + rule.close.length
            if (c == '\n' && !rule.multiline) return if (rule.maxLength == Int.MAX_VALUE) j else -1
            if (j - from > rule.maxLength) return -1
            j++
        }
        return if (rule.maxLength == Int.MAX_VALUE) to else -1
    }

    internal fun scanNumber(text: String, from: Int, to: Int): Int {
        var j = from
        if (text[j] == '0' && j + 1 < to && text[j + 1] in "xXbBoO") {
            j += 2
            while (j < to && (text[j].isLetterOrDigit() || text[j] == '_')) j++
            return j
        }
        while (j < to && (text[j].isDigit() || text[j] == '_')) j++
        if (j + 1 < to && text[j] == '.' && text[j + 1].isDigit()) {
            j++
            while (j < to && (text[j].isDigit() || text[j] == '_')) j++
        }
        if (j < to && text[j] in "eE") {
            var e = j + 1
            if (e < to && text[e] in "+-") e++
            if (e < to && text[e].isDigit()) { j = e; while (j < to && text[j].isDigit()) j++ }
        }
        while (j < to && (text[j].isLetter() || text[j] == '%')) j++
        return j
    }

    internal fun tokenizeCode(language: CodeLanguage, text: String, from: Int, to: Int, out: SpanBuffer) =
        CodeTokenizer(language, text, from, to, out).run()

    internal fun nextNonSpace(text: String, from: Int, to: Int): Char {
        var j = from
        while (j < to && (text[j] == ' ' || text[j] == '\t')) j++
        return if (j < to) text[j] else '\n'
    }

    private val markupNameEnd = " \t\n/>=\"'"

    internal fun tokenizeMarkup(text: String, from: Int, to: Int, out: SpanBuffer) {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '<') {
                when {
                    text.startsWith("<!--", i) -> {
                        val end = text.indexOf("-->", i + 4).let { if (it < 0 || it >= to) to else it + 3 }
                        out.add(i, end, TokenType.COMMENT); i = end; continue
                    }
                    text.startsWith("<![CDATA[", i) -> {
                        val end = text.indexOf("]]>", i).let { if (it < 0 || it >= to) to else it + 3 }
                        out.add(i, end, TokenType.STRING); i = end; continue
                    }
                    i + 1 < to && (text[i + 1] == '!' || text[i + 1] == '?') -> {
                        val end = text.indexOf('>', i).let { if (it < 0 || it >= to) to else it + 1 }
                        out.add(i, end, TokenType.META); i = end; continue
                    }
                    i + 1 < to && (text[i + 1] == '/' || text[i + 1].isLetter()) -> { i = tokenizeTag(text, i, to, out); continue }
                }
            } else if (c == '&') {
                var j = i + 1
                while (j < to && j - i < 12 && (text[j].isLetterOrDigit() || text[j] == '#')) j++
                if (j < to && text[j] == ';' && j > i + 1) { out.add(i, j + 1, TokenType.CONSTANT); i = j + 1; continue }
            }
            i++
        }
    }

    /** Tokenizes one tag and, for script and style elements, the embedded code. */
    private fun tokenizeTag(text: String, start: Int, to: Int, out: SpanBuffer): Int {
        val closing = text[start + 1] == '/'
        var i = start + if (closing) 2 else 1
        out.add(start, i, TokenType.PUNCTUATION)
        val nameStart = i
        while (i < to && text[i] !in markupNameEnd) i++
        val name = text.substring(nameStart, i).lowercase()
        out.add(nameStart, i, TokenType.TAG)
        while (i < to) {
            val c = text[i]
            when {
                c == '>' -> { out.add(i, i + 1, TokenType.PUNCTUATION); i++; break }
                c == '/' && i + 1 < to && text[i + 1] == '>' -> { out.add(i, i + 2, TokenType.PUNCTUATION); return i + 2 }
                c == '"' || c == '\'' -> {
                    val end = text.indexOf(c, i + 1).let { if (it < 0 || it >= to) to else it + 1 }
                    out.add(i, end, TokenType.STRING); i = end
                }
                c.isWhitespace() || c == '=' -> i++
                else -> {
                    val attrStart = i
                    while (i < to && text[i] !in markupNameEnd) i++
                    if (i == attrStart) i++ else out.add(attrStart, i, TokenType.ATTRIBUTE)
                }
            }
        }
        if (!closing && (name == "script" || name == "style")) {
            val close = text.indexOf("</$name", i, ignoreCase = true).let { if (it < 0 || it > to) to else it }
            tokenizeCode(if (name == "script") Languages.JAVASCRIPT else Languages.CSS, text, i, close, out)
            return close
        }
        return i
    }

    private val fence = Regex("^\\s{0,3}(```+|~~~+)\\s*([^`\\s]*)")
    private val heading = Regex("^\\s{0,3}#{1,6}(\\s|$)")
    private val listMarker = Regex("^\\s*([-*+]|\\d+[.)])\\s+(\\[[ xX]\\]\\s)?")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")

    internal fun tokenizeMarkdown(text: String, out: SpanBuffer) {
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEndAt = lineEnd(text, lineStart, text.length)
            val line = text.substring(lineStart, lineEndAt)
            val fenceMatch = fence.find(line)
            when {
                fenceMatch != null -> {
                    out.add(lineStart, lineEndAt, TokenType.META)
                    val marker = fenceMatch.groupValues[1]
                    val bodyStart = minOf(lineEndAt + 1, text.length)
                    var closeLine = bodyStart
                    var closeEnd = text.length
                    var bodyEnd = text.length
                    while (closeLine < text.length) {
                        val end = lineEnd(text, closeLine, text.length)
                        if (text.substring(closeLine, end).trimStart().startsWith(marker)) { bodyEnd = closeLine; closeEnd = end; break }
                        closeLine = end + 1
                    }
                    val language = Languages.forFence(fenceMatch.groupValues[2])
                    when (language?.mode) {
                        LanguageMode.CODE -> tokenizeCode(language, text, bodyStart, bodyEnd, out)
                        LanguageMode.MARKUP -> tokenizeMarkup(text, bodyStart, bodyEnd, out)
                        else -> out.add(bodyStart, bodyEnd, TokenType.STRING)
                    }
                    if (bodyEnd < text.length) out.add(bodyEnd, closeEnd, TokenType.META)
                    lineStart = closeEnd + 1
                    continue
                }
                heading.containsMatchIn(line) -> out.add(lineStart, lineEndAt, TokenType.HEADING)
                rule.matches(line) -> out.add(lineStart, lineEndAt, TokenType.PUNCTUATION)
                line.trimStart().startsWith(">") -> out.add(lineStart, lineEndAt, TokenType.COMMENT)
                else -> {
                    var inlineStart = lineStart
                    listMarker.find(line)?.let { match ->
                        out.add(lineStart + match.range.first, lineStart + match.range.last + 1, TokenType.KEYWORD)
                        inlineStart = lineStart + match.range.last + 1
                    }
                    tokenizeInline(text, inlineStart, lineEndAt, out)
                }
            }
            lineStart = lineEndAt + 1
        }
    }

    private fun tokenizeInline(text: String, from: Int, to: Int, out: SpanBuffer) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end in (i + 1) until to) { out.add(i, end + 1, TokenType.STRING); i = end + 1; continue }
                }
                (c == '*' || c == '_') && i + 1 < to && text[i + 1] == c -> {
                    val end = text.indexOf("$c$c", i + 2)
                    if (end in (i + 2) until to) { out.add(i, end + 2, TokenType.STRONG); i = end + 2; continue }
                }
                (c == '*' || c == '_') && i + 1 < to && !text[i + 1].isWhitespace() &&
                    (c == '*' || i == from || !text[i - 1].isLetterOrDigit()) -> {
                    val end = text.indexOf(c, i + 1)
                    if (end in (i + 1) until to && !text[end - 1].isWhitespace()) { out.add(i, end + 1, TokenType.EMPHASIS); i = end + 1; continue }
                }
                c == '[' || (c == '!' && i + 1 < to && text[i + 1] == '[') -> {
                    val open = if (c == '!') i + 1 else i
                    val close = text.indexOf(']', open + 1)
                    if (close in (open + 1) until to - 1 && text[close + 1] == '(') {
                        val paren = text.indexOf(')', close + 2)
                        if (paren in (close + 2) until to) {
                            out.add(i, close + 1, TokenType.LINK)
                            out.add(close + 1, paren + 1, TokenType.STRING)
                            i = paren + 1; continue
                        }
                    }
                }
                c == 'h' && (text.startsWith("https://", i) || text.startsWith("http://", i)) -> {
                    var j = i
                    while (j < to && !text[j].isWhitespace() && text[j] != ')' && text[j] != '>') j++
                    out.add(i, j, TokenType.LINK); i = j; continue
                }
                c == '<' && i + 1 < to && (text[i + 1].isLetter() || text[i + 1] == '/') -> {
                    val end = text.indexOf('>', i)
                    if (end in (i + 1) until to) { tokenizeMarkup(text, i, end + 1, out); i = end + 1; continue }
                }
                c == '|' -> out.add(i, i + 1, TokenType.PUNCTUATION)
            }
            i++
        }
    }
}
