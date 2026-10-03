package io.itermux.app.session

import com.termux.terminal.TerminalEmulator

/**
 * In-memory output only. Full-screen chat programs (Claude Code, Codex, Grok) repaint a fixed
 * header/footer around a transcript viewport with absolute cursor moves, so the remote never
 * scrolls lines into a terminal history. Rows are recorded once they leave the screen: by
 * scrolling out of the viewport, or because the whole region was replaced. Rows rewritten in
 * place (spinners, streaming lines, input boxes, menus) are transient; the latest frame is live.
 */
internal class OutputHistory(private val maxChars: Int = 2 * 1024 * 1024, private val maxLines: Int = 50_000) {
    private val lines = ArrayDeque<OutputHistoryLine>()
    private var chars = 0
    /** Absolute index of [lines]'s first row, so key positions survive trimming. */
    private var dropped = 0L
    private val positions = HashMap<String, Long>()
    /** Absolute index of the next recorded row while a program repaints rows it already showed. */
    private var replay = -1L
    private var previous = emptyList<OutputHistoryLine>()
    private var alignment = IntArray(0)
    private var deferred = 0
    private var paragraphBreak = false

    /** Before a clear or scroll; a synchronized frame is still being painted and changes as a whole. */
    fun captureBeforeChange(emulator: TerminalEmulator) {
        if (!emulator.isSynchronizedOutput) capture(emulator)
    }

    /** Once output pauses. An open synchronized update is a partly painted frame. */
    fun captureWhenIdle(emulator: TerminalEmulator) {
        if (emulator.isSynchronizedOutput && ++deferred < MAX_DEFERRED_CAPTURES) return
        deferred = 0
        capture(emulator)
    }

    fun capture(emulator: TerminalEmulator) {
        val next = ArrayList<OutputHistoryLine>(emulator.mRows)
        for (row in 0 until emulator.mRows) next.add(OutputHistoryLine.capture(emulator, row))
        while (next.lastOrNull()?.blank == true) next.removeAt(next.lastIndex)
        // A cleared screen is about to be repainted; compare the old frame with the repaint.
        if (next.isEmpty() || next == previous) return
        // A new width re-lays out the same transcript; the old rows have not left the program.
        if (previous.isNotEmpty() && previous[0].columns == next[0].columns)
            recordDisappeared(next, emulator.isAlternateBufferActive)
        previous = next
    }

    internal val recorded: List<OutputHistoryLine> get() = lines.toList()

    /**
     * Recorded rows followed by the current screen, without the screen rows already recorded (the
     * program scrolled back to them). Immutable rows keep an open view stable.
     */
    fun snapshot(): List<OutputHistoryLine> = ArrayList<OutputHistoryLine>(lines.size + previous.size).apply {
        addAll(lines)
        for (line in previous) {
            if (line.key.count { !it.isWhitespace() } >= DISTINCT_CHARS && positions[line.key]?.let { it >= dropped } == true) continue
            add(line)
        }
    }

    private fun recordDisappeared(next: List<OutputHistoryLine>, deduplicate: Boolean) {
        val old = previous
        val match = matchRows(old, next)
        val visible = HashSet<String>(next.size * 2)
        val matchedNew = BooleanArray(next.size)
        for (row in next) visible.add(row.key)
        for (index in match) if (index >= 0) matchedNew[index] = true
        for (index in old.indices) {
            val line = old[index]
            if (match[index] >= 0) continue
            val leaving = leaving(old, next, match, matchedNew, index)
            // Text still shown elsewhere was half repainted, unless it really scrolled off the top.
            if (leaving == STAYS || (leaving == LEFT && line.key.isNotEmpty() && line.key in visible)) continue
            // A paragraph break leaves before the text below it, often in an earlier frame.
            if (line.key.isEmpty()) { paragraphBreak = true; continue }
            if (paragraphBreak) record(old.getOrNull(index - 1)?.takeIf { it.key.isEmpty() } ?: BLANK_ROW, deduplicate = false)
            paragraphBreak = false
            record(line, deduplicate)
        }
    }

    private fun leaving(old: List<OutputHistoryLine>, next: List<OutputHistoryLine>, match: IntArray,
                        matchedNew: BooleanArray, index: Int): Int {
        // Blank padding that stayed in place bounds a region, but only text rows show how it moved.
        var above = index - 1
        while (above >= 0 && match[above] < 0) above--
        var below = index + 1
        while (below < old.size && (match[below] < 0 || old[below].key.isEmpty())) below++
        val belowShift = if (below < old.size) below - match[below] else 0
        if (below < old.size && belowShift > 0) {
            // Content below moved up: unless its new slot holds this row with more text, it left the
            // region (past the top, under a fixed header, or replaced by a scroll marker or blank).
            val expected = index - belowShift
            if (expected < 0) return SCROLLED_OFF
            if (above >= 0 && expected <= match[above]) return LEFT
            val slot = next[expected]
            return if (matchedNew[expected] || slot.key.isEmpty() || !similarRows(old[index].key, slot.key)) LEFT else STAYS
        }
        val counterpart = if (above >= 0) match[above] + index - above else if (below < old.size) match[below] - (below - index) else index
        // Content that moved into this slot pushed the row off the bottom (the program scrolled back
        // or a box grew); the program still shows it when scrolled there again.
        if (counterpart in next.indices && matchedNew[counterpart] && next[counterpart].key.isNotEmpty()) return STAYS
        // A similar row is the same row updated in place.
        val replacement = next.getOrNull(counterpart)
        if (replacement != null && replacement.key.isNotEmpty() && similarRows(old[index].key, replacement.key)) return STAYS
        // Small dissimilar edits are input boxes and status rows; a larger block is a cleared or replaced region.
        var rows = 0
        for (row in above + 1 until below) if (old[row].key.isNotEmpty()) rows++
        return if (rows >= 3) LEFT else STAYS
    }

    private fun record(line: OutputHistoryLine, deduplicate: Boolean) {
        if (line.cost > maxChars) return // Keep complete rows, never cut a wide/combining character.
        if (line.key.isEmpty() && lines.lastOrNull()?.key?.isEmpty() != false) return
        if (deduplicate && repeatsRecorded(line.key)) return
        replay = -1L
        lines.addLast(line)
        chars += line.cost
        if (line.key.count { !it.isWhitespace() } >= DISTINCT_CHARS) positions[line.key] = dropped + lines.lastIndex
        while (lines.size > maxLines || chars > maxChars) {
            val removed = lines.removeFirst()
            chars -= removed.cost
            if (positions[removed.key] == dropped) positions.remove(removed.key)
            dropped++
        }
    }

    /** Scrolling a program back and forth shows recorded rows again; follow them instead of copying. */
    private fun repeatsRecorded(key: String): Boolean {
        if (replay >= dropped && replay < dropped + lines.size) {
            if (lines[(replay - dropped).toInt()].key == key) { replay++; return true }
            // Blank and short rows inside an already recorded passage are kept by the earlier copy.
            if (key.count { !it.isWhitespace() } < DISTINCT_CHARS) return true
        }
        if (key.count { !it.isWhitespace() } < DISTINCT_CHARS) return false
        val position = positions[key]?.takeIf { it >= dropped } ?: return false
        replay = position + 1
        return true
    }

    /**
     * Monotonic row alignment: moving transcript rows and fixed header/footer rows. Text rows weigh
     * more than blank rows, so blank padding never pulls text out of alignment.
     */
    private fun matchRows(old: List<OutputHistoryLine>, next: List<OutputHistoryLine>): IntArray {
        val match = IntArray(old.size) { -1 }
        // A line feed at the bottom moves every row up by one, even when the top rows look alike.
        if (old.size >= 2 && old.size == next.size && (1 until old.size).all { old[it].key == next[it - 1].key }) {
            for (row in 1 until old.size) match[row] = row - 1
            return match
        }
        // Rows outside the changed middle match in place; only the middle needs the quadratic table.
        var start = 0
        while (start < old.size && start < next.size && old[start].key == next[start].key) { match[start] = start; start++ }
        var oldEnd = old.size
        var newEnd = next.size
        while (oldEnd > start && newEnd > start && old[oldEnd - 1].key == next[newEnd - 1].key) match[--oldEnd] = --newEnd
        val rows = oldEnd - start
        val columns = newEnd - start
        if (rows == 0 || columns == 0) return match
        val stride = columns + 1
        val size = (rows + 1) * stride
        if (alignment.size < size) alignment = IntArray(size) else alignment.fill(0, 0, size)
        for (i in rows - 1 downTo 0) for (j in columns - 1 downTo 0) {
            val skip = maxOf(alignment[(i + 1) * stride + j], alignment[i * stride + j + 1])
            val key = old[start + i].key
            alignment[i * stride + j] = if (key == next[start + j].key)
                maxOf(skip, (if (key.isEmpty()) 1 else 2) + alignment[(i + 1) * stride + j + 1]) else skip
        }
        var i = 0
        var j = 0
        while (i < rows && j < columns) {
            val key = old[start + i].key
            val score = alignment[i * stride + j]
            if (key == next[start + j].key && score == (if (key.isEmpty()) 1 else 2) + alignment[(i + 1) * stride + j + 1]) {
                match[start + i++] = start + j++
            } else if (alignment[(i + 1) * stride + j] == score) i++
            else j++
        }
        return match
    }

    private companion object {
        const val DISTINCT_CHARS = 12
        const val STAYS = 0
        const val LEFT = 1
        const val SCROLLED_OFF = 2
        val BLANK_ROW = OutputHistoryLine.blank(1)
        /** Never wait indefinitely for a program that forgets to end a synchronized update. */
        const val MAX_DEFERRED_CAPTURES = 16
    }
}

/** The same logical row before and after a spinner tick, a status update, or more streamed text. */
internal fun similarRows(old: String, new: String): Boolean {
    if (old.startsWith(new) || new.startsWith(old)) return true
    val limit = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < limit && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return 2 * (prefix + suffix) >= maxOf(old.length, new.length)
}
