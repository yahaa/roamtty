package io.itermux.app.session

/**
 * Follows the bytes sent to the remote and tells whether the line being typed starts with `/`,
 * i.e. the user is picking a slash command (Claude Code, Codex, …).
 *
 * Only a local estimate. Cursor movement, completion and ambiguous editing invalidate it until
 * Enter or Ctrl+C starts a known line. Ctrl+U clears a line only while its cursor is still known
 * to be at the end. Terminal replies and paste framing do not change the estimate.
 */
internal class SlashCommandInput {
    private enum class State { TEXT, ESC, CSI, SS3, OSC, OSC_ESC }
    private var state = State.TEXT
    private var length = 0
    private var known = true
    private var pasting = false
    private val sequence = StringBuilder()
    private val cursorReport = Regex("[?]?[0-9]+;[0-9]+R")
    private var lines = 0
    @Volatile var active = false
        private set
    /** 0 unless [active]; otherwise numbers the slash-command line, so each new one is distinct. */
    val line: Int get() = if (active) lines else 0

    @Synchronized fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size) {
        for (i in offset until offset + count) {
            val b = data[i].toInt() and 0xff
            if (!pasting && state != State.OSC && state != State.OSC_ESC &&
                (b == '\r'.code || b == '\n'.code || b == 0x03)) {
                reset()
                state = State.TEXT
                continue
            }
            when (state) {
                State.TEXT -> text(b)
                State.ESC -> state = when (b) {
                    '['.code -> { sequence.setLength(0); State.CSI }
                    ']'.code -> State.OSC
                    'O'.code -> State.SS3
                    0x1b -> { invalidate(); State.ESC }
                    else -> { invalidate(); State.TEXT }
                }
                State.CSI -> {
                    if (sequence.length < 64) sequence.append(b.toChar()) else invalidate()
                    if (b in 0x40..0x7e) {
                        when (val completed = sequence.toString()) {
                            "200~" -> pasting = true
                            "201~" -> pasting = false
                            "I", "O" -> Unit // focus replies
                            else -> if (!completed.matches(cursorReport)) invalidate()
                        }
                        state = State.TEXT
                    }
                }
                State.SS3 -> { invalidate(); state = State.TEXT }
                State.OSC -> when (b) {
                    0x07 -> state = State.TEXT
                    0x1b -> state = State.OSC_ESC
                }
                State.OSC_ESC -> state = if (b == '\\'.code) State.TEXT else State.OSC
            }
        }
        // A lone Esc dismisses the menu, but does not prove that the remote input line is empty.
        // Keep the parser state so a split escape sequence cannot become printable input.
        if (state == State.ESC) {
            invalidate()
        }
    }

    private fun text(b: Int) {
        when {
            b == 0x1b -> state = State.ESC
            b == '\r'.code || b == '\n'.code || b == 0x03 -> if (pasting) invalidate() else reset()
            b == 0x15 -> if (known && !pasting) reset() else invalidate()
            b == 0x7f || b == 0x08 -> if (known && length > 0 && --length == 0) active = false
            b < 0x20 -> invalidate() // completion, movement, word/forward deletion, vi-mode controls
            b in 0x80..0xbf || !known -> Unit // UTF-8 continuation bytes, or an unknown input line
            else -> {
                if (length == 0) {
                    active = b == '/'.code
                    if (active) lines++
                }
                length++
            }
        }
    }

    private fun reset() {
        length = 0
        known = true
        active = false
    }

    private fun invalidate() {
        length = 0
        known = false
        active = false
    }
}
