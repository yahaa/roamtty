package io.itermux.app.session

import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Test

class OutputHistoryTest {
    @Test fun overlapMatchesReferenceForRepeatedRowsAndUnequalFrameSizes() {
        val random = kotlin.random.Random(318)
        repeat(10_000) {
            val previous = List(random.nextInt(40)) { random.nextInt(3) }
            val next = List(random.nextInt(40)) { random.nextInt(3) }
            val expected = (minOf(previous.size, next.size) downTo 1).firstOrNull {
                previous.takeLast(it) == next.take(it)
            } ?: 0
            assertEquals("$previous -> $next", expected, longestHistoryOverlap(previous, next))
        }
    }

    @Test fun repeatedRowsRequireOnlyLinearComparisons() {
        var comparisons = 0
        class Row(val value: Int) {
            override fun equals(other: Any?): Boolean { comparisons++; return other is Row && value == other.value }
            override fun hashCode() = value
        }
        val previous = List(300) { Row(0) }
        val next = List(300) { Row(if (it == 299) 1 else 0) }
        assertEquals(299, longestHistoryOverlap(previous, next))
        assertTrue("Comparisons: $comparisons", comparisons < 6 * previous.size)
    }

    @Test fun randomFrameEditsMatchOriginalHistoryIncludingBlankFramesAndBounds() {
        val random = kotlin.random.Random(812)
        val history = OutputHistory(maxChars = 300, maxLines = 12)
        val expected = ArrayDeque<OutputHistoryLine>()
        var previous = emptyList<OutputHistoryLine>()
        repeat(1500) {
            val source = screen(List(random.nextInt(5)) {
                listOf("A", "B", "", "中 e\u0301", "\u001b[31mred\u001b[0m")[random.nextInt(5)]
            }.joinToString("\n"), columns = if (random.nextBoolean()) 16 else 20)
            val next = (0 until source.mRows).map { OutputHistoryLine.capture(source, it) }.dropLastWhile { it.blank }
            if (next != previous && next.isNotEmpty()) {
                val overlap = (minOf(previous.size, next.size) downTo 1).firstOrNull {
                    previous.takeLast(it) == next.take(it)
                } ?: 0
                val added = if (overlap > 0) next.drop(overlap) else {
                    val prefix = previous.zip(next).takeWhile { (a, b) -> a == b }.size
                    val suffix = previous.drop(prefix).asReversed().zip(next.drop(prefix).asReversed())
                        .takeWhile { (a, b) -> a == b }.size
                    next.subList(prefix, next.size - suffix)
                }
                for (line in added) {
                    expected.addLast(line)
                    while (expected.size > 12 || expected.sumOf { it.cost } > 300) expected.removeFirst()
                }
                previous = next
            }
            history.capture(source)
            assertEquals(expected.toList(), history.snapshot())
        }
    }

    @Test fun mixedCellStylesAndReverseVideoSurviveDetachedRendering() {
        val source = screen("\u001b[?5h\u001b[31mred\u001b[0m normal \u001b[44m中", 20)
        val history = OutputHistory()
        history.capture(source)
        val snapshot = OutputHistorySession(history.snapshot(), 0)
        snapshot.updateSize(20, 4, 8, 16)
        var column = 0
        // The renderer reads the leading cell's style for a wide glyph; its continuation has no glyph.
        for (codePoint in history.snapshot().first().text.codePoints().toArray()) {
            val width = WcWidth.width(codePoint)
            if (width <= 0) continue
            val original = source.screen.getStyleAt(0, column)
            val restored = snapshot.emulator.screen.getStyleAt(0, column)
            assertEquals(TextStyle.decodeForeColor(original), TextStyle.decodeForeColor(restored))
            assertEquals(TextStyle.decodeBackColor(original), TextStyle.decodeBackColor(restored))
            assertEquals("column $column, reverse=${source.isReverseVideo}",
                TextStyle.decodeEffect(original) xor TextStyle.CHARACTER_ATTRIBUTE_INVERSE, TextStyle.decodeEffect(restored))
            column += width
        }
    }

    private fun screen(text: String, columns: Int = 16, rows: Int = 4): TerminalEmulator {
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        return TerminalEmulator(output, columns, rows, 8, 16, 100, SilentTerminalClient()).apply {
            val bytes = ("\u001b[?25l" + text.replace("\n", "\r\n")).toByteArray()
            append(bytes, bytes.size)
        }
    }
    private fun OutputHistory.capture(text: String) = capture(screen(text))
    private fun List<OutputHistoryLine>.text() = map { it.text.trimEnd() }

    @Test fun preservesOverwrittenOutputAndDeduplicatesUnchangedFrames() {
        val history = OutputHistory()
        history.capture("Header\nold reply\nPrompt")
        history.capture("Header\nold reply\nPrompt")
        history.capture("Header\nnew reply\nPrompt")
        assertEquals(listOf("Header", "old reply", "Prompt", "new reply"), history.snapshot().text())
    }

    @Test fun mergesScrollingFramesAndKeepsFrozenSnapshots() {
        val history = OutputHistory()
        history.capture("one\ntwo\nthree")
        val snapshot = history.snapshot()
        history.capture("two\nthree\nfour")
        assertEquals(listOf("one", "two", "three", "four"), history.snapshot().text())
        assertEquals(listOf("one", "two", "three"), snapshot.text())
    }

    @Test fun boundsMemoryAndLinesWithoutCuttingCells() {
        val history = OutputHistory(maxChars = 64, maxLines = 3)
        repeat(1000) { history.capture("Reply $it") }
        assertEquals(listOf("Reply 997", "Reply 998", "Reply 999"), history.snapshot().text())
        history.capture("x".repeat(1000))
        assertTrue(history.snapshot().sumOf { it.cost } <= 64)
        assertTrue(history.snapshot().size <= 3)
        assertTrue(history.snapshot().all { it.columns == 16 && it.text.length == 16 })
    }

    @Test fun keepsPhysicalRowsWhitespaceStylesAndWideCombiningCharacters() {
        val source = screen("\u001b[1;38;5;2m╭──────────────╮\u001b[0m\n│ 中文 e\u0301 🙂   │\n╰──────────────╯", 16)
        val history = OutputHistory()
        history.capture(source)
        val captured = history.snapshot()
        val view = OutputHistorySession(captured, 0)
        view.updateSize(8, 4, 8, 16) // A narrower viewport must not fold the recorded border.
        assertEquals(16, view.emulator.mColumns)
        for (row in captured.indices) {
            val original = source.screen.allocateFullLineIfNecessary(source.screen.externalToInternalRow(row))
            val restored = view.emulator.screen.allocateFullLineIfNecessary(view.emulator.screen.externalToInternalRow(row))
            assertEquals(String(original.mText, 0, original.spaceUsed), String(restored.mText, 0, restored.spaceUsed))
        }
        val color = TextStyle.decodeForeColor(view.emulator.screen.getStyleAt(0, 0))
        assertEquals(10, color) // Bold ANSI green keeps its bright palette index, including theme changes.
        assertTrue(TextStyle.decodeEffect(view.emulator.screen.getStyleAt(0, 0)) and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0)
        val before = view.emulator.screen.transcriptText
        val replacement = "\u001b[2Jchanged".toByteArray()
        source.append(replacement, replacement.size)
        history.capture(source)
        view.write("ignored"); view.writeCodePoint(false, 'x'.code); view.reset()
        assertTrue(view.isReadOnly)
        assertFalse(view.emulator.shouldCursorBeVisible())
        assertEquals(before, view.emulator.screen.transcriptText)
    }

    @Test fun automaticWrapRemainsTwoPhysicalRowsAndStyleOnlyChangesAreKept() {
        val source = screen("12345678ABCDEFGH", 8)
        val history = OutputHistory()
        history.capture(source)
        assertEquals(listOf("12345678", "ABCDEFGH"), history.snapshot().text())
        assertTrue(history.snapshot().first().wrapped)
        val styled = "\u001b[1;1H\u001b[31m12345678".toByteArray()
        source.append(styled, styled.size)
        history.capture(source)
        assertEquals(3, history.snapshot().size)
    }

    @Test fun freezesRemotePaletteAndPreservesColoredBlankRows() {
        val source = screen("\u001b]10;#123456\u0007\u001b[44m\u001b[2J")
        val history = OutputHistory()
        history.capture(source)
        assertEquals(4, history.snapshot().size)
        val snapshot = OutputHistorySession(history.snapshot(), 0)
        val style = snapshot.emulator.screen.getStyleAt(0, 0)
        assertEquals(0xff123456.toInt(), TextStyle.decodeForeColor(style))
        assertEquals(4, TextStyle.decodeBackColor(style))
    }

    @Test fun repeatedSeeksSkipUnchangedViewportButResizeStillFillsNewRows() {
        val history = OutputHistory()
        repeat(60) { history.capture("ROW_$it") }
        val snapshot = OutputHistorySession(history.snapshot(), 0)
        snapshot.updateSize(16, 24, 8, 16)
        var updates = 0
        snapshot.onViewportChanged = { updates++ }
        repeat(100) { snapshot.showFirstLine(-1) }
        assertEquals(0, updates)
        snapshot.showFirstLine(10)
        assertEquals(1, updates)
        assertTrue(snapshot.emulator.screen.transcriptText.startsWith("ROW_10"))
        snapshot.updateSize(16, 30, 8, 16)
        assertEquals(2, updates)
        assertTrue(snapshot.emulator.screen.transcriptText.contains("ROW_39"))
        snapshot.showFirstLine(Int.MAX_VALUE)
        assertEquals(30, snapshot.firstLine)
        assertTrue(snapshot.emulator.screen.transcriptText.contains("ROW_59"))
        repeat(100) { snapshot.showFirstLine(Int.MAX_VALUE) }
        assertEquals(3, updates)
    }
}
