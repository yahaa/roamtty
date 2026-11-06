package io.itermux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chinese text is its own translation key, so a key missing from [EnglishText], or one left behind when the Chinese
 * changes, shows Chinese in the English interface without any error. Reads the sources for every `tr(…)` and `trf(…)`.
 */
class TranslationCoverageTest {
    private val call = Regex("""(?<![\w.])trf?\(""")
    private val literal = """"(?:[^"\\\n]|\\.)*""""
    private val joined = Regex("""$literal(?:\s*\+\s*$literal)*""")
    private val part = Regex(literal)
    private val template = Regex("""(?<!\\)\$[\w{]""")
    private val chinese = Regex("""[一-鿿]""")

    /** The source of the first argument of the call whose `(` ends at [start]: `if`, `when` and `?:` are all inside. */
    private fun firstArgument(source: String, start: Int): String {
        var depth = 1
        var inString = false
        var i = start
        while (i < source.length) {
            val c = source[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> {}
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> if (--depth == 0) return source.substring(start, i)
                c == ',' && depth == 1 -> return source.substring(start, i)
            }
            i++
        }
        return source.substring(start)
    }

    private fun unescape(text: String) = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(text) {
        val escape = it.groupValues[1]
        when (escape[0]) {
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            'u' -> escape.drop(1).toInt(16).toChar().toString()
            else -> escape
        }
    }

    /** The Chinese keys passed to `tr` in [source]; text with a `$` template is not a key and cannot be looked up. */
    private fun keysIn(source: String): List<String> = call.findAll(source).flatMap { match ->
        joined.findAll(firstArgument(source, match.range.last + 1)).map { it.value }
    }.filterNot { group -> part.findAll(group).any { template.containsMatchIn(it.value) } }
        .map { group -> part.findAll(group).joinToString("") { unescape(it.value.drop(1).dropLast(1)) } }
        .filter { chinese.containsMatchIn(it) }.toList()

    @Test fun readsEveryFormAKeyIsWrittenIn() {
        assertEquals(listOf("甲"), keysIn("""tr("甲")"""))
        assertEquals(listOf("甲乙"), keysIn("tr(\n  \"甲\" +\n  \"乙\"\n)"))
        assertEquals(listOf("甲", "乙"), keysIn("""tr(if (a(1)) "甲" else "乙")"""))
        assertEquals(listOf("甲"), keysIn("""tr(e.message ?: "甲")"""))
        assertEquals(listOf("甲 %d"), keysIn("""trf("甲 %d", count("乙"))"""))
        assertEquals(listOf("甲\"乙\""), keysIn("""tr("甲\"乙\"")"""))
        assertEquals(emptyList<String>(), keysIn("""tr("甲 ${'$'}name"); str("乙"); x.tr("丙")"""))
    }

    @Test fun everyChineseKeyHasAnEnglishTranslation() {
        val root = File("src/main/kotlin")
        val keys = root.walkTopDown().filter { it.extension == "kt" && it.name != "AppLocale.kt" }
            .flatMap { file -> keysIn(file.readText()).map { it to file.name } }.toList()
        assertTrue("Only ${keys.size} keys found: the scan is not reading the sources", keys.size > 500)
        val missing = keys.filterNot { (key, _) -> key in EnglishText.values }.distinct()
            .joinToString("\n") { (key, file) -> "$file: $key" }
        assertEquals("Missing from EnglishText:\n$missing", "", missing)
    }
}
