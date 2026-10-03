package io.itermux.app.code

import org.junit.Assert.*
import org.junit.Test

class SyntaxHighlighterTest {
    /** Token text paired with its type, in document order. */
    private fun tokens(source: String, language: CodeLanguage): List<Pair<String, TokenType>> =
        SyntaxHighlighter.highlight(source, language).lines.flatMap { line ->
            (0 until line.spanCount).map { s ->
                line.text.substring(line.spans[s * 3], line.spans[s * 3 + 1]) to TokenType.entries[line.spans[s * 3 + 2]]
            }
        }

    private fun List<Pair<String, TokenType>>.typeOf(text: String) = firstOrNull { it.first == text }?.second

    @Test fun kotlinKeywordsStringsCommentsAndDeclarations() {
        val result = tokens("""
            @Composable
            fun greet(name: String): Int {
                val count = 42 // answer
                println("hi ${'$'}name")
                return count.size
            }
        """.trimIndent(), Languages.KOTLIN)
        assertEquals(TokenType.META, result.typeOf("@Composable"))
        assertEquals(TokenType.KEYWORD, result.typeOf("fun"))
        assertEquals(TokenType.FUNCTION, result.typeOf("greet"))
        assertEquals(TokenType.TYPE, result.typeOf("String"))
        assertEquals(TokenType.NUMBER, result.typeOf("42"))
        assertEquals(TokenType.COMMENT, result.typeOf("// answer"))
        assertEquals(TokenType.FUNCTION, result.typeOf("println"))
        assertEquals(TokenType.STRING, result.typeOf("\"hi \$name\""))
        assertEquals(TokenType.PROPERTY, result.typeOf("size"))
    }

    @Test fun multilineBlockCommentsAndStringsCarryAcrossRows() {
        val document = SyntaxHighlighter.highlight("a /* one\ntwo */ b\n\"\"\"x\ny\"\"\"", Languages.KOTLIN)
        assertEquals(4, document.lineCount)
        val second = document.lines[1]
        assertEquals(TokenType.COMMENT.ordinal, second.spans[2])
        assertEquals("two */", second.text.substring(second.spans[0], second.spans[1]))
        val fourth = document.lines[3]
        assertEquals(TokenType.STRING.ordinal, fourth.spans[2])
        assertEquals(0, fourth.spans[0])
    }

    @Test fun pythonPrefixesDecoratorsAndTripleQuotes() {
        val result = tokens("@cache\ndef run(self):\n    return f'{x}' if True else None\n'''doc'''", Languages.PYTHON)
        assertEquals(TokenType.META, result.typeOf("@cache"))
        assertEquals(TokenType.FUNCTION, result.typeOf("run"))
        assertEquals(TokenType.CONSTANT, result.typeOf("self"))
        assertEquals(TokenType.STRING, result.typeOf("f'{x}'"))
        assertEquals(TokenType.CONSTANT, result.typeOf("True"))
        assertEquals(TokenType.STRING, result.typeOf("'''doc'''"))
    }

    @Test fun shellCommentsNeedWordBoundaryAndVariablesAreMarked() {
        val result = tokens("echo \$HOME \${PATH} a#b # note", Languages.SHELL)
        assertEquals(TokenType.FUNCTION, result.typeOf("echo"))
        assertEquals(TokenType.VARIABLE, result.typeOf("\$HOME"))
        assertEquals(TokenType.VARIABLE, result.typeOf("\${PATH}"))
        assertEquals(TokenType.COMMENT, result.typeOf("# note"))
        assertNull(result.typeOf("a#b"))
    }

    @Test fun jsonKeysAreProperties() {
        val result = tokens("{\"name\": \"app\", \"ok\": true, \"n\": -1.5e3}", Languages.JSON)
        assertEquals(TokenType.PROPERTY, result.typeOf("\"name\""))
        assertEquals(TokenType.STRING, result.typeOf("\"app\""))
        assertEquals(TokenType.CONSTANT, result.typeOf("true"))
        assertEquals(TokenType.NUMBER, result.typeOf("1.5e3"))
    }

    @Test fun yamlKeysListsAndComments() {
        val result = tokens("services:\n  - name: web # main\n    ports: [80]\nenabled: yes", Languages.YAML)
        assertEquals(TokenType.PROPERTY, result.typeOf("services"))
        assertEquals(TokenType.PROPERTY, result.typeOf("name"))
        assertEquals(TokenType.COMMENT, result.typeOf("# main"))
        assertEquals(TokenType.NUMBER, result.typeOf("80"))
        assertEquals(TokenType.CONSTANT, result.typeOf("yes"))
    }

    @Test fun markupTagsAttributesAndEmbeddedScript() {
        val result = tokens("<!-- c --><div class=\"x\">&amp;<script>let a = 1</script></div>", Languages.HTML)
        assertEquals(TokenType.COMMENT, result.typeOf("<!-- c -->"))
        assertEquals(TokenType.TAG, result.typeOf("div"))
        assertEquals(TokenType.ATTRIBUTE, result.typeOf("class"))
        assertEquals(TokenType.STRING, result.typeOf("\"x\""))
        assertEquals(TokenType.CONSTANT, result.typeOf("&amp;"))
        assertEquals(TokenType.KEYWORD, result.typeOf("let"))
    }

    @Test fun markdownHeadingsInlineCodeAndFencedLanguage() {
        val result = tokens("# Title\n- item with `code` and **bold** [link](http://x)\n```kotlin\nval x = 1\n```", Languages.MARKDOWN)
        assertEquals(TokenType.HEADING, result.typeOf("# Title"))
        assertEquals(TokenType.KEYWORD, result.typeOf("- "))
        assertEquals(TokenType.STRING, result.typeOf("`code`"))
        assertEquals(TokenType.STRONG, result.typeOf("**bold**"))
        assertEquals(TokenType.LINK, result.typeOf("[link]"))
        assertEquals(TokenType.KEYWORD, result.typeOf("val"))
        assertEquals(TokenType.META, result.typeOf("```kotlin"))
    }

    @Test fun diffAndLogLines() {
        val diff = tokens("@@ -1 +1 @@\n-old\n+new\n context", Languages.DIFF)
        assertEquals(TokenType.META, diff.typeOf("@@ -1 +1 @@"))
        assertEquals(TokenType.DELETED, diff.typeOf("-old"))
        assertEquals(TokenType.INSERTED, diff.typeOf("+new"))
        val log = tokens("2026-10-03 12:00:01 ERROR db failed, WARN retry https://example.com/x", Languages.LOG)
        assertEquals(TokenType.CONSTANT, log.typeOf("2026-10-03 12:00:01"))
        assertEquals(TokenType.ERROR, log.typeOf("ERROR"))
        assertEquals(TokenType.WARNING, log.typeOf("WARN"))
        assertEquals(TokenType.LINK, log.typeOf("https://example.com/x"))
    }

    @Test fun rustLifetimesDoNotSwallowTheLine() {
        val result = tokens("fn f<'a>(x: &'a str) -> Vec<u8> { println!(\"{}\", 'c'); }", Languages.RUST)
        assertEquals(TokenType.FUNCTION, result.typeOf("println!"))
        assertEquals(TokenType.STRING, result.typeOf("'c'"))
        assertEquals(TokenType.TYPE, result.typeOf("str"))
    }

    @Test fun longLinesSplitIntoContinuationRowsAndTabsExpand() {
        val long = "x".repeat(SyntaxHighlighter.MAX_ROW_LENGTH * 2 + 5)
        val document = SyntaxHighlighter.highlight("a\tb\n$long\r\nend", Languages.PLAIN)
        assertEquals(3, document.lineCount)
        assertEquals("a   b", document.lines[0].text)
        assertEquals(listOf(1, 2, 0, 0, 3), document.lines.map { it.number })
        assertEquals("end", document.lines.last().text)
        assertEquals(SyntaxHighlighter.MAX_ROW_LENGTH, document.maxColumns)
    }

    @Test fun wideCharactersCountTwiceForColumnWidth() {
        assertEquals(6, SyntaxHighlighter.highlight("中文ab", Languages.PLAIN).maxColumns)
    }

    @Test fun detectsLanguageFromNameExtensionAndShebang() {
        assertSame(Languages.KOTLIN, Languages.detect("Main.kt"))
        assertSame(Languages.DOCKERFILE, Languages.detect("Dockerfile"))
        assertSame(Languages.DOCKERFILE, Languages.detect("Dockerfile.prod"))
        assertSame(Languages.SHELL, Languages.detect(".bashrc"))
        assertSame(Languages.INI, Languages.detect(".env.local"))
        assertSame(Languages.PYTHON, Languages.detect("deploy", "#!/usr/bin/env python3"))
        assertSame(Languages.SHELL, Languages.detect("run", "#!/bin/bash"))
        assertSame(Languages.XML, Languages.detect("data", "<?xml version=\"1.0\"?>"))
        assertSame(Languages.PLAIN, Languages.detect("notes.txt"))
        assertSame(Languages.KOTLIN, Languages.forFence("kotlin"))
        assertNull(Languages.forFence("unknown"))
    }

    @Test fun spansNeverOverlapOrLeaveTheirRow() {
        val samples = mapOf(Languages.KOTLIN to "class A { fun b() = \"\"\"\nx\n\"\"\" /* c\n */ }",
            Languages.MARKDOWN to "# h\n```py\ndef f():\n  pass\n```\ntext *em* `c`",
            Languages.HTML to "<style>a { color: red }</style><p a=b>t</p>",
            Languages.CSS to ".a:hover, #b { margin: 0 auto; color: #fff } @media (x) {}")
        samples.forEach { (language, source) ->
            SyntaxHighlighter.highlight(source, language).lines.forEach { line ->
                var previousEnd = 0
                for (s in 0 until line.spanCount) {
                    val start = line.spans[s * 3]
                    val end = line.spans[s * 3 + 1]
                    assertTrue("${language.id}: $start..$end in '${line.text}'", start >= previousEnd && end > start && end <= line.text.length)
                    previousEnd = end
                }
            }
        }
    }

    @Test fun highlightingQuarterMegabyteStaysFast() {
        val source = buildString { while (length < 256 * 1024) append("fun f(x: Int) = \"value \$x\" // comment 123\n") }
        SyntaxHighlighter.highlight(source, Languages.KOTLIN)
        val started = System.nanoTime()
        val document = SyntaxHighlighter.highlight(source, Languages.KOTLIN)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(document.lineCount > 5000)
        assertTrue("took $elapsedMs ms", elapsedMs < 2000)
    }
}
