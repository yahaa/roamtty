package io.itermux.app.session

import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Test

class OutputHistoryTest {
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

    private fun screen(text: String, columns: Int = 16, rows: Int = 4, alternate: Boolean = false): TerminalEmulator {
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        return TerminalEmulator(output, columns, rows, 8, 16, 100, SilentTerminalClient()).apply {
            val bytes = ((if (alternate) "\u001b[?1049h" else "") + "\u001b[?25l" + text.replace("\n", "\r\n")).toByteArray()
            append(bytes, bytes.size)
        }
    }
    /** Full-screen chat programs draw on the alternate screen. */
    private fun OutputHistory.capture(text: String, rows: Int = 4) = capture(screen(text, 40, rows, alternate = true))
    private fun List<OutputHistoryLine>.text() = map { it.text.trimEnd() }

    /** A chat program frame: fixed header, a window onto [transcript] ending at [end], fixed footer. */
    private fun chat(transcript: List<String>, end: Int, footer: String, rows: Int = 8) =
        (listOf("HEADER") + transcript.subList(maxOf(0, end - (rows - 2)), end) + List(maxOf(0, rows - 2 - end)) { "" } + footer)
            .joinToString("\n")

    @Test fun rowsUpdatedInPlaceStayLiveAndUnchangedFramesAreIgnored() {
        val history = OutputHistory()
        history.capture("Header\nold reply\nPrompt")
        history.capture("Header\nold reply\nPrompt")
        history.capture("Header\nold reply 2\nPrompt")
        history.capture("Header\nnew reply 2\nPrompt")
        assertEquals(emptyList<String>(), history.recorded.text())
        assertEquals(listOf("Header", "new reply 2", "Prompt"), history.snapshot().text())
    }

    @Test fun transcriptScrollingBetweenFixedHeaderAndFooterIsRecordedOnce() {
        val transcript = List(30) { "line $it of the answer" }
        val history = OutputHistory()
        for (end in 1..30) {
            // Each line streams in two reads while the spinner in the footer ticks.
            val partial = transcript.subList(0, end - 1) + transcript[end - 1].take(9)
            history.capture(chat(partial, end, "* Working ${end}s"), rows = 8)
            history.capture(chat(transcript, end, "+ Working ${end}s"), rows = 8)
        }
        history.capture(chat(transcript, 30, "> ready"), rows = 8)
        assertEquals(transcript.subList(0, 24), history.recorded.text())
        assertEquals(listOf("HEADER") + transcript.subList(24, 30) + "> ready", history.snapshot().text().drop(24))
    }

    @Test fun framesAreRecordedOffTheCapturingThreadAndInOrder() {
        val tasks = ArrayList<Runnable>()
        val history = OutputHistory(worker = { tasks += it })
        val transcript = List(12) { "line $it of the answer" }
        for (end in 1..12) history.capture(chat(transcript, end, "* Working ${end}s"), rows = 8)
        // One pending drain covers every queued frame, and capturing records nothing itself.
        assertEquals(1, tasks.size)
        assertEquals(transcript.subList(0, 6), history.recorded.text())
        // A drain scheduled before the snapshot caught up finds nothing left to record.
        tasks.single().run()
        history.capture(chat(transcript, 12, "> ready"), rows = 8)
        assertEquals(2, tasks.size)
        assertEquals(listOf("HEADER") + transcript.subList(6, 12) + "> ready", history.snapshot().text().drop(6))
    }

    @Test fun clearedRegionIsRecordedBeforeTheNextAnswer() {
        val history = OutputHistory()
        history.capture("HEADER\nfirst answer one\nfirst answer two\nfirst answer three\n\n> footer", rows = 6)
        history.capture("HEADER\n\n\n\n\n> footer", rows = 6)
        history.capture("HEADER\nsecond answer\n\n\n\n> footer", rows = 6)
        assertEquals(listOf("first answer one", "first answer two", "first answer three"), history.recorded.text())
    }

    @Test fun scrollingTheProgramBackAndForthDoesNotDuplicateRows() {
        val transcript = List(20) { "message number $it" }
        val history = OutputHistory()
        for (end in 1..20) history.capture(chat(transcript, end, "> input"), rows = 8)
        // The user swipes the program back to the start, then down again.
        for (end in 19 downTo 6) history.capture(chat(transcript, end, "> input"), rows = 8)
        for (end in 6..20) history.capture(chat(transcript, end, "> input"), rows = 8)
        history.capture(chat(transcript, 10, "> input"), rows = 8)
        val expected = transcript.subList(0, 14)
        assertEquals(expected, history.recorded.text())
        // Rows the program shows again are not repeated after the recorded transcript.
        assertEquals(expected + "HEADER" + "> input", history.snapshot().text())
    }

    @Test fun synchronizedFrameIsRecordedOnlyWhenComplete() {
        val source = screen("A\nB\nC\nD", 16)
        val history = OutputHistory()
        history.captureWhenIdle(source)
        val partial = "\u001b[?2026h\u001b[1;1HB\u001b[2;1HC".toByteArray()
        source.append(partial, partial.size)
        history.captureWhenIdle(source)
        assertEquals(listOf("A", "B", "C", "D"), history.snapshot().text())
        val rest = "\u001b[3;1HD\u001b[4;1HE\u001b[?2026l".toByteArray()
        source.append(rest, rest.size)
        history.captureWhenIdle(source)
        assertEquals(listOf("A", "B", "C", "D", "E"), history.snapshot().text())
    }

    @Test fun lineFeedScrollingKeepsRepeatedShellOutput() {
        val history = OutputHistory()
        val source = screen("", 16)
        val lines = listOf("ok", "ok", "done 1", "ok", "done 2", "ok", "done 3", "ok")
        for (line in lines) {
            val bytes = "$line\r\n".toByteArray()
            history.captureBeforeChange(source)
            source.append(bytes, bytes.size)
            history.captureWhenIdle(source)
        }
        assertEquals(lines, history.snapshot().text().filter { it.isNotEmpty() })
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
        val history = OutputHistory(maxChars = 200, maxLines = 3)
        repeat(1000) { history.capture("Reply $it\nReply ${it + 1}") }
        assertEquals(listOf("Reply 996", "Reply 997", "Reply 998"), history.recorded.text())
        repeat(8) { history.capture(List(4) { row -> "${'a' + row + it}".repeat(1000) }.joinToString("\n")) }
        assertTrue(history.recorded.sumOf { it.cost } <= 200)
        assertTrue(history.recorded.size <= 3)
        assertTrue(history.recorded.all { it.columns == 40 })
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
        // A restyled row is the same row: the live copy shows the new style, nothing is recorded.
        assertEquals(2, history.snapshot().size)
        assertEquals(1, TextStyle.decodeForeColor(OutputHistorySession(history.snapshot(), 0).emulator.screen.getStyleAt(0, 0)))
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
        repeat(59) { history.capture("ROW_$it\nROW_${it + 1}") }
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
