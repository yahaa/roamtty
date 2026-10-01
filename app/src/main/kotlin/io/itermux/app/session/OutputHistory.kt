package io.itermux.app.session

/** In-memory output only. Keep overwritten TUI text without relying on application key bindings. */
internal class OutputHistory(private val maxChars: Int = 2 * 1024 * 1024, private val maxLines: Int = 50_000) {
    private val lines = ArrayDeque<String>()
    private var previous = emptyList<String>()
    private var chars = 0

    fun capture(screen: String) {
        val next = screen.lineSequence().map { it.trimEnd() }.toList().dropLastWhile { it.isBlank() }
        if (next == previous || next.isEmpty()) return
        // Common scrolling frames overlap; repainting headers/footers need not be repeated.
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
            val bounded = line.take((maxChars - 1).coerceAtLeast(0))
            lines.addLast(bounded)
            chars += bounded.length + 1
            while (lines.size > maxLines || chars > maxChars) chars -= lines.removeFirst().length + 1
        }
        previous = next
    }

    fun snapshot(): List<String> = lines.toList()
}
