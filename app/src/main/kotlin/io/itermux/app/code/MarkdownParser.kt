package io.itermux.app.code

/** Inline styles over [MdText.text]; [MdSpan.url] is set for links and images. */
enum class MdStyle { BOLD, ITALIC, STRIKE, CODE, LINK, IMAGE }

data class MdSpan(val start: Int, val end: Int, val style: MdStyle, val url: String? = null)

data class MdText(val text: String, val spans: List<MdSpan> = emptyList())

enum class MdAlign { START, CENTER, END }

sealed interface MdBlock {
    data class Heading(val level: Int, val text: MdText) : MdBlock
    data class Paragraph(val text: MdText) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    /** Lists are flat; [depth] carries nesting so long documents stay cheap to lay out lazily. */
    data class ListItem(val depth: Int, val marker: String, val checked: Boolean?, val text: MdText) : MdBlock
    data class Table(val header: List<MdText>, val alignments: List<MdAlign>, val rows: List<List<MdText>>) : MdBlock
    data class Image(val alt: String, val source: String) : MdBlock
    data object Rule : MdBlock
}

/**
 * A pragmatic CommonMark/GFM subset for reading READMEs and notes on a phone:
 * ATX and setext headings, paragraphs, fenced and indented code, quotes, nested and
 * task lists, tables, rules, images, reference links, and simple HTML blocks.
 */
object MarkdownParser {
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^`]*)$")
    private val heading = Regex("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$")
    private val rule = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val listItem = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])(?:\\s+(.*))?$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val setext = Regex("^ {0,3}(=+|-+)\\s*$")
    private val tableDelimiter = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val reference = Regex("^ {0,3}\\[([^\\]]+)]:\\s*<?(\\S+?)>?(?:\\s+[\"'(].*[\"')])?\\s*$")
    private val htmlImage = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlAttribute = Regex("(\\w+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val htmlTag = Regex("</?[A-Za-z][^>]*>")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val references = HashMap<String, String>()
        val content = lines.filter { line ->
            reference.matchEntire(line)?.let { references.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2]); false } ?: true
        }
        return Parser(content, InlineParser(references)).blocks()
    }

    private class Parser(private val lines: List<String>, private val inline: InlineParser) {
        private var index = 0
        private val out = ArrayList<MdBlock>()
        private val listIndents = ArrayList<Int>()

        fun blocks(): List<MdBlock> {
            while (index < lines.size) block()
            return out
        }

        private fun startsBlock(line: String): Boolean = fence.containsMatchIn(line) || heading.matches(line) ||
            rule.matches(line) || line.trimStart().startsWith(">") || listItem.matches(line) && listItem.find(line)!!.groupValues[3].isNotEmpty() ||
            isHtml(line)

        private fun isHtml(line: String) = line.trimStart().let { it.startsWith("<!--") || it.startsWith("<") && htmlTag.containsMatchIn(it) }

        private fun block() {
            val line = lines[index]
            if (line.isBlank()) { index++; listIndents.clear(); return }
            fence.matchEntire(line)?.let { match ->
                val marker = match.groupValues[1]
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith(marker)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index]); index++
                }
                index++
                out += MdBlock.Code(match.groupValues[2].trim().substringBefore(' '), code.toString())
                return
            }
            heading.matchEntire(line)?.let {
                out += MdBlock.Heading(it.groupValues[1].length, inline.parse(it.groupValues[2]))
                index++; return
            }
            if (rule.matches(line) && listIndents.isEmpty()) { out += MdBlock.Rule; index++; return }
            if (line.trimStart().startsWith(">")) { quote(); return }
            listItem.matchEntire(line)?.let { listItem(it); return }
            if (line.startsWith("    ") || line.startsWith("\t")) { indentedCode(); return }
            if (index + 1 < lines.size && '|' in line && tableDelimiter.matches(lines[index + 1]) && lines[index + 1].contains('-')) {
                table(); return
            }
            if (isHtml(line)) { html(); return }
            paragraph()
        }

        private fun quote() {
            val inner = ArrayList<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                val current = lines[index]
                val trimmed = current.trimStart()
                if (trimmed.startsWith(">")) inner += trimmed.removePrefix(">").removePrefix(" ")
                else if (startsBlock(current)) break
                else inner += current
                index++
            }
            out += MdBlock.Quote(Parser(inner, inline).blocks())
        }

        private fun listItem(match: MatchResult) {
            val indent = match.groupValues[1].replace("\t", "    ").length
            while (listIndents.isNotEmpty() && listIndents.last() > indent) listIndents.removeAt(listIndents.lastIndex)
            if (listIndents.isEmpty() || listIndents.last() < indent) listIndents += indent
            val depth = listIndents.size - 1
            var body = match.groupValues[3]
            index++
            // Lazy continuation lines belong to the item until a blank line or a new block.
            while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines[index]) && !listItem.matches(lines[index])) {
                body += " " + lines[index].trim(); index++
            }
            val taskMatch = task.matchEntire(body)
            val rawMarker = match.groupValues[2]
            val marker = if (rawMarker[0].isDigit()) rawMarker.dropLast(1) + "." else "•"
            out += MdBlock.ListItem(depth, marker, taskMatch?.let { it.groupValues[1] != " " },
                inline.parse(taskMatch?.groupValues?.get(2) ?: body))
        }

        private fun indentedCode() {
            val code = ArrayList<String>()
            while (index < lines.size && (lines[index].startsWith("    ") || lines[index].startsWith("\t") || lines[index].isBlank())) {
                code += lines[index].removePrefix("\t").let { if (it.startsWith("    ")) it.substring(4) else it }; index++
            }
            while (code.isNotEmpty() && code.last().isBlank()) code.removeAt(code.lastIndex)
            out += MdBlock.Code("", code.joinToString("\n"))
        }

        private fun cells(line: String): List<String> {
            val trimmed = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
            val cells = ArrayList<String>()
            val cell = StringBuilder()
            var code = false
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                when {
                    c == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|' -> { cell.append('|'); i++ }
                    c == '`' -> { code = !code; cell.append(c) }
                    c == '|' && !code -> { cells += cell.toString().trim(); cell.clear() }
                    else -> cell.append(c)
                }
                i++
            }
            cells += cell.toString().trim()
            return cells
        }

        private fun table() {
            val header = cells(lines[index])
            val alignments = cells(lines[index + 1]).map {
                when {
                    it.startsWith(':') && it.endsWith(':') -> MdAlign.CENTER
                    it.endsWith(':') -> MdAlign.END
                    else -> MdAlign.START
                }
            }
            index += 2
            val rows = ArrayList<List<MdText>>()
            while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
                val row = cells(lines[index])
                rows += List(header.size) { inline.parse(row.getOrElse(it) { "" }) }
                index++
            }
            out += MdBlock.Table(header.map(inline::parse), List(header.size) { alignments.getOrElse(it) { MdAlign.START } }, rows)
        }

        /** README-style HTML: keep images and readable text, drop the markup. */
        private fun html() {
            val chunk = StringBuilder()
            var inComment = false
            while (index < lines.size && lines[index].isNotBlank()) {
                val current = lines[index]
                if (current.contains("<!--")) inComment = true
                if (!inComment) chunk.append(current).append('\n')
                if (current.contains("-->")) inComment = false
                index++
            }
            val html = chunk.toString()
            htmlImage.findAll(html).forEach { tag ->
                val attributes = htmlAttribute.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim('"', '\'') }
                attributes["src"]?.let { out += MdBlock.Image(attributes["alt"].orEmpty(), it) }
            }
            val text = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(htmlImage, "")
                .replace(htmlTag, "").lines().joinToString(" ") { it.trim() }.trim()
            if (text.isNotEmpty()) out += MdBlock.Paragraph(inline.parse(text))
        }

        private fun paragraph() {
            val raw = ArrayList<String>()
            while (index < lines.size) {
                val current = lines[index]
                if (raw.isNotEmpty() && setext.matches(current)) {
                    out += MdBlock.Heading(if (current.trim().startsWith("=")) 1 else 2, inline.parse(raw.joinToString(" ") { it.trim() }))
                    index++; return
                }
                if (current.isBlank() || raw.isNotEmpty() && startsBlock(current)) break
                raw += current; index++
            }
            // Two trailing spaces or a trailing backslash is a hard line break.
            val text = StringBuilder()
            raw.forEachIndexed { n, line ->
                val hard = line.endsWith("  ") || line.trimEnd().endsWith("\\")
                text.append(line.trim().removeSuffix("\\"))
                if (n < raw.lastIndex) text.append(if (hard) '\n' else ' ')
            }
            val only = text.toString()
            // A paragraph that is just one image renders as a picture instead of inline alt text.
            Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)$").matchEntire(only)?.let {
                out += MdBlock.Image(it.groupValues[1], it.groupValues[2]); return
            }
            out += MdBlock.Paragraph(inline.parse(only))
        }
    }

    internal class InlineParser(private val references: Map<String, String>) {
        fun parse(source: String): MdText {
            val out = StringBuilder()
            val spans = ArrayList<MdSpan>()
            parseInto(source, out, spans)
            return MdText(out.toString(), spans)
        }

        private fun closingIndex(src: String, delimiter: String, from: Int): Int {
            var at = src.indexOf(delimiter, from)
            while (at > from && (src[at - 1].isWhitespace() || src[at - 1] == '\\')) at = src.indexOf(delimiter, at + 1)
            return if (at > from) at else -1
        }

        private fun linkTarget(src: String, open: Int): Pair<String, Int>? {
            if (open >= src.length || src[open] != '(') return null
            var depth = 0
            for (j in open until src.length) {
                when (src[j]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return src.substring(open + 1, j).trim().substringBefore(' ').trim('<', '>') to j + 1
                }
            }
            return null
        }

        private fun parseInto(src: String, out: StringBuilder, spans: MutableList<MdSpan>) {
            var i = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\\' && i + 1 < src.length && src[i + 1] in "\\`*_{}[]()#+-.!|~<>\"'" -> { out.append(src[i + 1]); i += 2; continue }
                    c == '`' -> {
                        var ticks = 1
                        while (i + ticks < src.length && src[i + ticks] == '`') ticks++
                        val fence = "`".repeat(ticks)
                        val close = src.indexOf(fence, i + ticks)
                        if (close > 0) {
                            val start = out.length
                            out.append(src.substring(i + ticks, close).let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it })
                            spans += MdSpan(start, out.length, MdStyle.CODE)
                            i = close + ticks; continue
                        }
                    }
                    c == '!' && i + 1 < src.length && src[i + 1] == '[' -> {
                        val close = src.indexOf(']', i + 2)
                        val target = if (close > 0) linkTarget(src, close + 1) else null
                        if (target != null) {
                            val start = out.length
                            out.append(src.substring(i + 2, close).ifEmpty { "image" })
                            spans += MdSpan(start, out.length, MdStyle.IMAGE, target.first)
                            i = target.second; continue
                        }
                    }
                    c == '[' -> {
                        val close = matchingBracket(src, i)
                        if (close > 0) {
                            val label = src.substring(i + 1, close)
                            val inlineTarget = linkTarget(src, close + 1)
                            val referenceTarget = if (inlineTarget == null && close + 1 < src.length && src[close + 1] == '[') {
                                val end = src.indexOf(']', close + 2)
                                if (end > 0) references[src.substring(close + 2, end).ifEmpty { label }.lowercase()]?.let { it to end + 1 } else null
                            } else null
                            val shortcut = if (inlineTarget == null && referenceTarget == null) references[label.lowercase()]?.let { it to close + 1 } else null
                            val target = inlineTarget ?: referenceTarget ?: shortcut
                            if (target != null) {
                                val start = out.length
                                parseInto(label, out, spans)
                                spans += MdSpan(start, out.length, MdStyle.LINK, target.first)
                                i = target.second; continue
                            }
                        }
                    }
                    c == '<' -> {
                        val close = src.indexOf('>', i + 1)
                        if (close > 0) {
                            val inner = src.substring(i + 1, close)
                            when {
                                inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:") -> {
                                    val start = out.length
                                    out.append(inner.removePrefix("mailto:"))
                                    spans += MdSpan(start, out.length, MdStyle.LINK, inner)
                                    i = close + 1; continue
                                }
                                inner.matches(Regex("br\\s*/?", RegexOption.IGNORE_CASE)) -> { out.append('\n'); i = close + 1; continue }
                                inner.matches(Regex("/?[A-Za-z][\\w-]*(\\s[^>]*)?/?")) -> { i = close + 1; continue }
                            }
                        }
                    }
                    c == 'h' && (src.startsWith("https://", i) || src.startsWith("http://", i)) && (i == 0 || !src[i - 1].isLetterOrDigit()) -> {
                        var j = i
                        while (j < src.length && !src[j].isWhitespace() && src[j] != '<') j++
                        while (j > i && src[j - 1] in ".,;:!?)'\"") j--
                        val start = out.length
                        out.append(src, i, j)
                        spans += MdSpan(start, out.length, MdStyle.LINK, src.substring(i, j))
                        i = j; continue
                    }
                    c == '~' && src.startsWith("~~", i) -> if (emphasis(src, i, "~~", MdStyle.STRIKE, out, spans)?.also { i = it } != null) continue
                    (c == '*' || c == '_') && i + 1 < src.length && src[i + 1] == c -> {
                        if (emphasis(src, i, "$c$c", MdStyle.BOLD, out, spans)?.also { i = it } != null) continue
                    }
                    (c == '*' || c == '_') && (c == '*' || i == 0 || !src[i - 1].isLetterOrDigit()) -> {
                        if (emphasis(src, i, "$c", MdStyle.ITALIC, out, spans)?.also { i = it } != null) continue
                    }
                }
                out.append(c)
                i++
            }
        }

        private fun matchingBracket(src: String, open: Int): Int {
            var depth = 0
            for (j in open until src.length) {
                when (src[j]) {
                    '\\' -> continue
                    '[' -> depth++
                    ']' -> if (--depth == 0) return j
                }
            }
            return -1
        }

        /** Returns the index after the closing delimiter, or null when [delimiter] does not open emphasis here. */
        private fun emphasis(src: String, i: Int, delimiter: String, style: MdStyle, out: StringBuilder, spans: MutableList<MdSpan>): Int? {
            val contentStart = i + delimiter.length
            if (contentStart >= src.length || src[contentStart].isWhitespace()) return null
            val close = closingIndex(src, delimiter, contentStart)
            if (close < 0) return null
            if (delimiter == "_" && close + 1 < src.length && src[close + 1].isLetterOrDigit()) return null
            val start = out.length
            parseInto(src.substring(contentStart, close), out, spans)
            spans += MdSpan(start, out.length, style)
            return close + delimiter.length
        }
    }
}
