package io.itermux.app.session

/**
 * Follows the bytes sent to the remote and tells whether the line being typed starts with `/`,
 * i.e. the user is picking a slash command (Claude Code, Codex, …).
 *
 * Only a local estimate: it keeps its own copy of the input line and cursor, applying typing,
 * Backspace/Delete, Ctrl+A/E/B/F/D/K/U/W, Left/Right/Home/End and Alt+Backspace the way Readline
 * does. Enter and Ctrl+C start a new line. Esc, Tab, Up/Down, terminal replies and paste framing
 * leave the line as it is, so the arrows stay for as long as its first character is `/`.
 */
internal class SlashCommandInput {
    private enum class State { TEXT, ESC, CSI, SS3, OSC, OSC_ESC }
    private var state = State.TEXT
    private var pasting = false
    private val sequence = StringBuilder()
    /** The line as UTF-8 lead bytes: one entry per character, which is all `/` and word edits need. */
    private val chars = ArrayList<Int>()
    private var cursor = 0
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
                clearLine()
                state = State.TEXT
                continue
            }
            when (state) {
                State.TEXT -> text(b)
                State.ESC -> state = when (b) {
                    '['.code -> { sequence.setLength(0); State.CSI }
                    ']'.code -> State.OSC
                    'O'.code -> State.SS3
                    0x1b -> State.ESC
                    else -> { alt(b); State.TEXT }
                }
                State.CSI -> {
                    if (sequence.length < 64) sequence.append(b.toChar())
                    if (b in 0x40..0x7e) {
                        csi(sequence.toString())
                        state = State.TEXT
                    }
                }
                State.SS3 -> {
                    when (b) {
                        'C'.code -> moveTo(cursor + 1)
                        'D'.code -> moveTo(cursor - 1)
                        'H'.code -> moveTo(0)
                        'F'.code -> moveTo(chars.size)
                    }
                    state = State.TEXT
                }
                State.OSC -> when (b) {
                    0x07 -> state = State.TEXT
                    0x1b -> state = State.OSC_ESC
                }
                State.OSC_ESC -> state = if (b == '\\'.code) State.TEXT else State.OSC
            }
        }
        // A lone Esc key press arrives on its own; it closes a menu but leaves the line as typed.
        if (state == State.ESC) state = State.TEXT
        update()
    }

    private fun text(b: Int) {
        when (b) {
            0x1b -> state = State.ESC
            0x7f, 0x08 -> if (cursor > 0) chars.removeAt(--cursor)
            0x01 -> moveTo(0)
            0x05 -> moveTo(chars.size)
            0x02 -> moveTo(cursor - 1)
            0x06 -> moveTo(cursor + 1)
            0x04 -> if (chars.isEmpty()) clearLine() else if (cursor < chars.size) chars.removeAt(cursor)
            0x0b -> chars.subList(cursor, chars.size).clear()
            0x15 -> { chars.subList(0, cursor).clear(); cursor = 0 }
            0x17 -> deleteWordBack()
            '\r'.code, '\n'.code -> Unit // inside a bracketed paste: the line goes on
            else -> when {
                b < 0x20 || b in 0x80..0xbf -> Unit // Tab and other controls, UTF-8 continuation bytes
                chars.size < MAX_CHARS -> chars.add(cursor++, b)
            }
        }
    }

    private fun alt(b: Int) {
        when (b) {
            0x7f, 0x08 -> deleteWordBack()
            'b'.code -> moveTo(wordStartBefore(cursor))
            'f'.code -> {
                var i = cursor
                while (i < chars.size && chars[i] == ' '.code) i++
                while (i < chars.size && chars[i] != ' '.code) i++
                moveTo(i)
            }
        }
    }

    private fun csi(seq: String) {
        when (seq) {
            "200~" -> pasting = true
            "201~" -> pasting = false
            "3~" -> if (cursor < chars.size) chars.removeAt(cursor)
            "1~", "7~" -> moveTo(0)
            "4~", "8~" -> moveTo(chars.size)
            else -> when (seq.last()) {
                'C' -> moveTo(cursor + 1)
                'D' -> moveTo(cursor - 1)
                'H' -> moveTo(0)
                'F' -> moveTo(chars.size)
                else -> Unit // Up/Down pick from a menu; replies and other keys leave the line alone
            }
        }
    }

    private fun moveTo(position: Int) { cursor = position.coerceIn(0, chars.size) }

    private fun wordStartBefore(position: Int): Int {
        var i = position
        while (i > 0 && chars[i - 1] == ' '.code) i--
        while (i > 0 && chars[i - 1] != ' '.code) i--
        return i
    }

    private fun deleteWordBack() {
        val start = wordStartBefore(cursor)
        chars.subList(start, cursor).clear()
        cursor = start
    }

    private fun clearLine() {
        chars.clear()
        cursor = 0
        active = false // the next slash line gets a new number, even within one write
    }

    private fun update() {
        val slash = chars.firstOrNull() == '/'.code
        if (slash && !active) lines++
        active = slash
    }
}

private const val MAX_CHARS = 4096
