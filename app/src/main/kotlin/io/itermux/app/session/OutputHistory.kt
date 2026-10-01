package io.itermux.app.session

import com.termux.terminal.TerminalEmulator

/** In-memory output only. Keep overwritten TUI rows without relying on application key bindings. */
internal class OutputHistory(private val maxChars: Int = 2 * 1024 * 1024, private val maxLines: Int = 50_000) {
    private val lines = ArrayDeque<OutputHistoryLine>()
    private var previous = emptyList<OutputHistoryLine>()
    private var chars = 0

    fun capture(emulator: TerminalEmulator) {
        val next = ArrayList<OutputHistoryLine>(emulator.mRows)
        for (row in 0 until emulator.mRows) next.add(OutputHistoryLine.capture(emulator, row))
        while (next.lastOrNull()?.blank == true) next.removeAt(next.lastIndex)
        if (next == previous || next.isEmpty()) return
        val overlap = longestHistoryOverlap(previous, next)
        var start = overlap
        var end = next.size
        if (overlap == 0) {
            val commonSize = minOf(previous.size, next.size)
            while (start < commonSize && previous[start] == next[start]) start++
            var suffix = 0
            while (suffix < commonSize - start && previous[previous.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
            end -= suffix
        }
        for (index in start until end) {
            val line = next[index]
            if (line.cost > maxChars) continue // Keep complete rows, never cut a wide/combining character.
            lines.addLast(line)
            chars += line.cost
            while (lines.size > maxLines || chars > maxChars) chars -= lines.removeFirst().cost
        }
        previous = next
    }

    fun snapshot(): List<OutputHistoryLine> = lines.toList()
}

/** Longest old suffix matching the new prefix, in linear comparisons even for repeated rows. */
internal fun <T> longestHistoryOverlap(previous: List<T>, next: List<T>): Int {
    val limit = minOf(previous.size, next.size)
    if (limit == 0) return 0
    // KMP failure links let a partial match fall back without rescanning or copying rows.
    val fallback = IntArray(limit)
    var matched = 0
    for (index in 1 until limit) {
        while (matched > 0 && next[index] != next[matched]) matched = fallback[matched - 1]
        if (next[index] == next[matched]) matched++
        fallback[index] = matched
    }
    matched = 0
    for (index in previous.size - limit until previous.size) {
        while (matched > 0 && previous[index] != next[matched]) matched = fallback[matched - 1]
        if (previous[index] == next[matched]) matched++
    }
    return matched
}
