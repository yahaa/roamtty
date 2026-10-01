package io.itermux.app.session

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/** A physical terminal row, including its cell styles. Never reflow this as a paragraph. */
internal class OutputHistoryLine(val text: String, val columns: Int, private val styles: LongArray, val wrapped: Boolean) {
    val cost get() = text.length + 1
    val blank get() = text.isBlank() && styles.all { it == TextStyle.NORMAL }

    fun copyTo(target: TerminalRow) {
        var column = 0
        var lastColumn = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = WcWidth.width(codePoint)
            val at = if (width > 0) column else lastColumn
            target.setChar(at, codePoint, styles[at])
            if (width > 0) { lastColumn = column; column += width }
            index += Character.charCount(codePoint)
        }
    }

    override fun equals(other: Any?) = other is OutputHistoryLine && columns == other.columns &&
        wrapped == other.wrapped && text == other.text && styles.contentEquals(other.styles)
    override fun hashCode() = 31 * (31 * text.hashCode() + columns) + styles.contentHashCode()

    companion object {
        fun capture(emulator: TerminalEmulator, row: Int): OutputHistoryLine {
            val source = emulator.screen.allocateFullLineIfNecessary(emulator.screen.externalToInternalRow(row))
            val styles = LongArray(emulator.mColumns) { column ->
                val style = source.getStyle(column)
                if (TextStyle.isTerminalBitmap(style)) TextStyle.NORMAL else {
                    var effect = TextStyle.decodeEffect(style)
                    var foreground = TextStyle.decodeForeColor(style)
                    val background = TextStyle.decodeBackColor(style)
                    if (foreground in 0..7 && effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0)
                        foreground += 8
                    if (emulator.isReverseVideo) effect = effect xor TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                    // Freeze remote OSC overrides; the standard ANSI palette still follows the app theme.
                    TextStyle.encode(emulator.mColors.snapshotColor(foreground),
                        emulator.mColors.snapshotColor(background), effect)
                }
            }
            return OutputHistoryLine(String(source.mText, 0, source.spaceUsed), emulator.mColumns, styles,
                emulator.screen.getLineWrap(row))
        }
    }
}

/** In-memory output only. Keep overwritten TUI rows without relying on application key bindings. */
internal class OutputHistory(private val maxChars: Int = 2 * 1024 * 1024, private val maxLines: Int = 50_000) {
    private val lines = ArrayDeque<OutputHistoryLine>()
    private var previous = emptyList<OutputHistoryLine>()
    private var chars = 0

    fun capture(emulator: TerminalEmulator) {
        val next = (0 until emulator.mRows).map { OutputHistoryLine.capture(emulator, it) }
            .dropLastWhile { it.blank }
        if (next == previous || next.isEmpty()) return
        val overlap = (minOf(previous.size, next.size) downTo 1).firstOrNull { count ->
            previous.takeLast(count) == next.take(count)
        } ?: 0
        val added = if (overlap > 0) next.drop(overlap) else {
            val prefix = previous.zip(next).takeWhile { (a, b) -> a == b }.size
            val suffix = previous.drop(prefix).asReversed().zip(next.drop(prefix).asReversed())
                .takeWhile { (a, b) -> a == b }.size
            next.subList(prefix, next.size - suffix)
        }
        for (line in added) {
            if (line.cost > maxChars) continue // Keep complete rows, never cut a wide/combining character.
            lines.addLast(line)
            chars += line.cost
            while (lines.size > maxLines || chars > maxChars) chars -= lines.removeFirst().cost
        }
        previous = next
    }

    fun snapshot(): List<OutputHistoryLine> = lines.toList()
}
