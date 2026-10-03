package io.itermux.app.session

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/** A physical terminal row, including its cell styles. Never reflow this as a paragraph. */
internal class OutputHistoryLine private constructor(
    val text: String,
    val columns: Int,
    private val firstStyle: Long,
    /** Most rows have one style. Allocate per-cell storage only for mixed styles. */
    private val styles: LongArray?,
    val wrapped: Boolean
) {
    val cost get() = text.length + 1
    val blank get() = text.isBlank() && firstStyle == TextStyle.NORMAL && styles == null
    /** Text used to follow a row between frames: no trailing padding or scrollbar thumb. */
    val key: String by lazy(LazyThreadSafetyMode.NONE) { text.trimEnd { it.isWhitespace() || it in SCROLLBAR_GLYPHS } }

    private fun styleAt(column: Int) = styles?.get(column) ?: firstStyle

    fun copyTo(target: TerminalRow) {
        var column = 0
        var lastColumn = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = WcWidth.width(codePoint)
            val at = if (width > 0) column else lastColumn
            target.setChar(at, codePoint, styleAt(at))
            if (width > 0) { lastColumn = column; column += width }
            index += Character.charCount(codePoint)
        }
    }

    override fun equals(other: Any?) = other is OutputHistoryLine && columns == other.columns &&
        wrapped == other.wrapped && text == other.text && firstStyle == other.firstStyle && styles.contentEquals(other.styles)
    override fun hashCode() = 31 * (31 * (31 * text.hashCode() + columns) + firstStyle.hashCode()) +
        styles.contentHashCode() + wrapped.hashCode()

    companion object {
        private const val SCROLLBAR_GLYPHS = "█▐▌▕▏┃║░▒▓"

        fun blank(columns: Int) = OutputHistoryLine("", columns, TextStyle.NORMAL, null, false)

        fun capture(emulator: TerminalEmulator, row: Int): OutputHistoryLine {
            val source = emulator.screen.allocateFullLineIfNecessary(emulator.screen.externalToInternalRow(row))
            var rawStyle = source.getStyle(0)
            val firstStyle = freezeStyle(emulator, rawStyle)
            var capturedStyle = firstStyle
            var styles: LongArray? = null
            for (column in 1 until emulator.mColumns) {
                val raw = source.getStyle(column)
                if (raw != rawStyle) {
                    rawStyle = raw
                    capturedStyle = freezeStyle(emulator, raw)
                }
                if (styles == null && capturedStyle != firstStyle) styles = LongArray(emulator.mColumns) { firstStyle }
                styles?.set(column, capturedStyle)
            }
            return OutputHistoryLine(String(source.mText, 0, source.spaceUsed), emulator.mColumns, firstStyle, styles,
                emulator.screen.getLineWrap(row))
        }

        private fun freezeStyle(emulator: TerminalEmulator, style: Long): Long {
            if (TextStyle.isTerminalBitmap(style)) return TextStyle.NORMAL
            var effect = TextStyle.decodeEffect(style)
            var foreground = TextStyle.decodeForeColor(style)
            val background = TextStyle.decodeBackColor(style)
            if (foreground in 0..7 && effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0)
                foreground += 8
            if (emulator.isReverseVideo) effect = effect xor TextStyle.CHARACTER_ATTRIBUTE_INVERSE
            // Freeze remote OSC overrides; the standard ANSI palette still follows the app theme.
            return TextStyle.encode(emulator.mColors.snapshotColor(foreground),
                emulator.mColors.snapshotColor(background), effect)
        }
    }
}
