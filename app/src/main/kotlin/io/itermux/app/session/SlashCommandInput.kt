package io.itermux.app.session

/**
 * Follows the bytes sent to the remote and tells whether the line being typed starts with `/`,
 * i.e. the user is picking a slash command (Claude Code, Codex, …).
 *
 * Only a local estimate: Enter, Ctrl+C/D/U/W and a bare Esc start a new line, Backspace removes a
 * character, escape sequences (arrows, paste brackets, terminal replies) are skipped.
 */
internal class SlashCommandInput {
    private enum class State { TEXT, ESC, CSI, SS3, OSC, OSC_ESC }
    private var state = State.TEXT
    private var length = 0
    @Volatile var active = false
        private set

    @Synchronized fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size) {
        for (i in offset until offset + count) {
            val b = data[i].toInt() and 0xff
            when (state) {
                State.TEXT -> text(b)
                State.ESC -> state = when (b) {
                    '['.code -> State.CSI
                    ']'.code -> State.OSC
                    'O'.code -> State.SS3
                    0x1b -> { reset(); State.ESC }
                    else -> State.TEXT // Alt+key
                }
                State.CSI -> if (b in 0x40..0x7e) state = State.TEXT
                State.SS3 -> state = State.TEXT
                State.OSC -> when (b) {
                    0x07 -> state = State.TEXT
                    0x1b -> state = State.OSC_ESC
                }
                State.OSC_ESC -> state = if (b == '\\'.code) State.TEXT else State.OSC
            }
        }
        // A lone Esc key press arrives on its own and dismisses the command menu.
        if (state == State.ESC) {
            reset()
            state = State.TEXT
        }
    }

    private fun text(b: Int) {
        when {
            b == 0x1b -> state = State.ESC
            b == '\r'.code || b == '\n'.code || b == 0x03 || b == 0x04 || b == 0x15 || b == 0x17 -> reset()
            b == 0x7f || b == 0x08 -> if (length > 0 && --length == 0) active = false
            b < 0x20 || b in 0x80..0xbf -> Unit // other controls, UTF-8 continuation bytes
            else -> {
                if (length == 0) active = b == '/'.code
                length++
            }
        }
    }

    private fun reset() {
        length = 0
        active = false
    }
}
