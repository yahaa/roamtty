package io.itermux.app.session

import com.termux.terminal.*

/** Detached viewport: reuses terminal drawing/selection, and has no transport or writable input. */
internal class OutputHistorySession(
    val lines: List<OutputHistoryLine>,
    private val initialFirstLine: Int = Int.MAX_VALUE,
    private val copy: (String) -> Unit = {}
) : TerminalOutput(), TerminalSession {
    val columns = (lines.maxOfOrNull { it.columns } ?: 4).coerceAtLeast(4)
    private val parser = TerminalEmulator(this, columns, 24, 8, 16, 400, SilentTerminalClient())
    var firstLine = initialFirstLine
        private set
    val lastFirstLine get() = (lines.size - parser.mRows).coerceAtLeast(0)
    var onViewportChanged: () -> Unit = {}
    private var laidOut = false

    init {
        val hideCursor = "\u001b[?25l".toByteArray()
        parser.append(hideCursor, hideCursor.size)
        showFirstLine(initialFirstLine)
    }

    fun showFirstLine(first: Int) {
        firstLine = first.coerceIn(0, lastFirstLine)
        val screen = parser.screen
        for (row in 0 until parser.mRows) {
            val target = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            target.clear(TextStyle.NORMAL)
            screen.clearLineWrap(row)
            lines.getOrNull(firstLine + row)?.let { line ->
                line.copyTo(target)
                if (line.wrapped && line.columns == columns) screen.setLineWrap(row)
            }
        }
        onViewportChanged()
    }

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        parser.resize(this.columns, rows.coerceIn(4, 300), cellWidthPixels, cellHeightPixels)
        // Resize only the viewport height; never reflow the recorded grid to the device width.
        showFirstLine(if (laidOut) firstLine else initialFirstLine)
        laidOut = true
    }
    override fun getEmulator() = parser
    override fun isReadOnly() = true
    override fun write(data: ByteArray, offset: Int, count: Int) = Unit
    override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) = Unit
    override fun onCopyTextToClipboard(text: String) = copy(text)
    override fun onPasteTextFromClipboard() = Unit
    override fun reset() = Unit
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onBell() = Unit
    override fun onColorsChanged() = Unit
}
