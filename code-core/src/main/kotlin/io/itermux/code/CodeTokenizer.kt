package io.itermux.code

import io.itermux.code.SyntaxHighlighter.MAX_TOKEN_LENGTH
import io.itermux.code.SyntaxHighlighter.isIdentPart
import io.itermux.code.SyntaxHighlighter.isIdentStart
import io.itermux.code.SyntaxHighlighter.lineEnd
import io.itermux.code.SyntaxHighlighter.nextNonSpace
import io.itermux.code.SyntaxHighlighter.scanNumber
import io.itermux.code.SyntaxHighlighter.scanString
import java.util.regex.Matcher

/**
 * One pass over [text] from [from] to [to] in a code [language], adding its tokens to [out]. At each position the
 * kinds of token are tried in a fixed order (comments, strings, patterns, annotations, variables, numbers, words);
 * the first that matches takes its characters and the scan goes on after them.
 */
internal class CodeTokenizer(private val language: CodeLanguage, private val text: String, private val from: Int,
    private val to: Int, private val out: SpanBuffer) {
    // Android's ICU-backed Matcher copies its whole input on every reset or region change, so pointing one at
    // [text] made each attempt cost the length of the file. Rules and patterns never span lines: match each
    // against a copy of the rest of its line.
    private val lineMatchers: List<Matcher> = language.lineRules.map { it.pattern.matcher("") }
    private val patternMatchers: List<Matcher> = language.patterns.map { it.pattern.matcher("") }
    private val orderedStrings = language.strings.sortedByDescending { it.open.length }
    private var i = from
    private var lineBegin = from
    private var lineHasContent = false
    private var braceDepth = 0
    /** Set by a declaring keyword (`class`, `fun`): the type of the name that follows it. */
    private var previousDeclarator: TokenType? = null
    /** The last character that was not white space, or a stand-in for the token it ended (`"`, `0`, `a`). */
    private var lastSignificant = ' '

    fun run() {
        while (i < to) {
            if (i == lineBegin && lineMatchers.isNotEmpty()) {
                lineRule()
                if (i >= to) break
            }
            val c = text[i]
            if (c == '\n') { i++; lineBegin = i; lineHasContent = false; continue }
            if (c == ' ' || c == '\t' || c == '\u000c') { i++; continue }
            if (blockComment() || lineComment() || preprocessor(c)) continue
            lineHasContent = true
            if (string() || pattern(c) || annotation(c) || variable(c) || number(c) || word(c) || cssSelector(c)) continue
            when (c) {
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
            }
            previousDeclarator = null
            lastSignificant = c
            i++
        }
    }

    /** A rule for whole lines (a diff's `+`, an INI section), tried where a line begins. */
    private fun lineRule() {
        val line = text.substring(i, lineEnd(text, i, to))
        val begin = i
        for ((index, matcher) in lineMatchers.withIndex()) {
            matcher.reset(line)
            if (matcher.lookingAt()) {
                for ((group, type) in language.lineRules[index].groups) {
                    if (matcher.start(group) >= 0) out.add(begin + matcher.start(group), begin + matcher.end(group), type)
                }
                if (matcher.end() > 0) { i = begin + matcher.end(); lineHasContent = true; lastSignificant = text[i - 1] }
                break
            }
        }
    }

    /** A token that may hold line breaks ended at [end]: the line it ends in began after the last of them. */
    private fun lineBeginAfter(end: Int) {
        lineBegin = text.lastIndexOf('\n', end - 1).let { if (it < i) lineBegin else it + 1 }
    }

    private fun blockComment(): Boolean {
        for ((open, close) in language.blockComments) {
            if (text.startsWith(open, i) && (open != "=begin" || !lineHasContent)) {
                val closeAt = text.indexOf(close, i + open.length)
                val end = if (closeAt < 0 || closeAt >= to) to else closeAt + close.length
                out.add(i, end, TokenType.COMMENT)
                lineBeginAfter(end)
                i = end; lineHasContent = true
                return true
            }
        }
        return false
    }

    private fun lineComment(): Boolean {
        for (prefix in language.lineComments) {
            if (text.startsWith(prefix, i) && (!language.commentNeedsBoundary || i == lineBegin || text[i - 1].isWhitespace())) {
                val end = lineEnd(text, i, to)
                out.add(i, end, TokenType.COMMENT)
                i = end
                return true
            }
        }
        return false
    }

    private fun preprocessor(c: Char): Boolean {
        if (!language.preprocessor || c != '#' || lineHasContent) return false
        var j = i + 1
        while (j < to && text[j] == ' ') j++
        while (j < to && text[j].isLetter()) j++
        out.add(i, j, TokenType.META)
        i = j; lineHasContent = true
        return true
    }

    private fun string(): Boolean {
        for (rule in orderedStrings) {
            if (!text.startsWith(rule.open, i)) continue
            val end = scanString(text, i, to, rule)
            if (end > i) {
                out.add(i, end, if (language.keyStrings && nextNonSpace(text, end, to) == ':') TokenType.PROPERTY else TokenType.STRING)
                lineBeginAfter(end)
                i = end; previousDeclarator = null; lastSignificant = '"'
                return true
            }
        }
        return false
    }

    private fun pattern(c: Char): Boolean {
        for ((index, matcher) in patternMatchers.withIndex()) {
            val pattern = language.patterns[index]
            if (pattern.trigger(c) && (i == from || !isIdentPart(text[i - 1], language))) {
                // A token is short: cap the copy so a minified one-line file stays linear.
                matcher.reset(text.substring(i, minOf(lineEnd(text, i, to), i + MAX_TOKEN_LENGTH)))
                if (matcher.lookingAt() && matcher.end() > 0) {
                    out.add(i, i + matcher.end(), pattern.type)
                    i += matcher.end(); lastSignificant = text[i - 1]
                    return true
                }
            }
        }
        return false
    }

    private fun annotation(c: Char): Boolean {
        if (c != language.annotationPrefix || i + 1 >= to || !isIdentStart(text[i + 1], language)) return false
        var j = i + 1
        while (j < to && (isIdentPart(text[j], language) || text[j] == '.')) j++
        out.add(i, j, TokenType.META)
        i = j
        return true
    }

    private fun variable(c: Char): Boolean {
        if (c !in language.variablePrefixes || i + 1 >= to) return false
        val next = text[i + 1]
        val end = when {
            next == '{' -> text.indexOf('}', i + 2).let { if (it < 0 || it >= lineEnd(text, i, to)) -1 else it + 1 }
            next == '(' && language.parenVariables -> text.indexOf(')', i + 2).let { if (it < 0 || it >= lineEnd(text, i, to)) -1 else it + 1 }
            isIdentStart(next, language) || next.isDigit() -> {
                var j = i + 1
                while (j < to && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                j
            }
            next in "@#?$!*-" && c == '$' -> i + 2
            else -> -1
        }
        if (end <= i) return false
        out.add(i, end, TokenType.VARIABLE)
        i = end
        return true
    }

    private fun number(c: Char): Boolean {
        if (!language.numbers || !c.isDigit() || (i != from && isIdentPart(text[i - 1], language))) return false
        val end = scanNumber(text, i, to)
        out.add(i, end, TokenType.NUMBER)
        i = end; previousDeclarator = null; lastSignificant = '0'
        return true
    }

    /** An identifier: a keyword, a string's prefix, or a name typed by what stands around it. */
    private fun word(c: Char): Boolean {
        if (!isIdentStart(c, language)) return false
        var j = i + 1
        while (j < to && isIdentPart(text[j], language)) j++
        if (prefixedString(j)) return true
        val word = text.substring(i, j)
        if (word in language.pathKeywords) {
            out.add(i, j, TokenType.KEYWORD)
            // Skip the dotted path, but let a trailing comment still be highlighted.
            var k = j
            val end = lineEnd(text, j, to)
            while (k < end && !language.lineComments.any { text.startsWith(it, k) }) k++
            i = k; previousDeclarator = null
            return true
        }
        val type = wordType(word, j)
        if (type != null) out.add(i, if (type == TokenType.FUNCTION && language.macroBang && j < to && text[j] == '!') j + 1 else j, type)
        previousDeclarator = language.declarators[word]
        lastSignificant = 'a'
        i = j
        return true
    }

    /** Python-style string prefixes such as f"..." or rb'...': the word ending at [j] and the string after it are one string. */
    private fun prefixedString(j: Int): Boolean {
        if (language.stringPrefixes.isEmpty() || j - i > 2 || j >= to || !(i until j).all { text[it] in language.stringPrefixes }) return false
        val rule = orderedStrings.firstOrNull { text.startsWith(it.open, j) }
        val end = rule?.let { scanString(text, j, to, it) } ?: -1
        if (end <= j) return false
        out.add(i, end, TokenType.STRING)
        lineBeginAfter(end)
        i = end
        return true
    }

    /** What the [word] ending at [j] is: by the language's own lists, else by what stands before and after it. */
    private fun wordType(word: String, j: Int): TokenType? {
        val next = nextNonSpace(text, j, to)
        return language.classify(word) ?: when {
            previousDeclarator != null -> previousDeclarator
            language.macroBang && j < to && text[j] == '!' -> TokenType.FUNCTION
            language.css && braceDepth == 0 -> TokenType.TAG
            language.css && next == ':' -> TokenType.PROPERTY
            language.css -> null
            next == '(' -> TokenType.FUNCTION
            lastSignificant == '.' -> TokenType.PROPERTY
            language.capitalizedTypes && word[0].isUpperCase() ->
                if (word.length > 1 && word.all { it.isUpperCase() || it.isDigit() || it == '_' }) TokenType.CONSTANT else TokenType.TYPE
            else -> null
        }
    }

    private fun cssSelector(c: Char): Boolean {
        if (!language.css || braceDepth != 0 || (c != '.' && c != '#') || i + 1 >= to || !isIdentStart(text[i + 1], language)) return false
        var j = i + 1
        while (j < to && isIdentPart(text[j], language)) j++
        out.add(i, j, TokenType.TAG)
        i = j
        return true
    }
}
