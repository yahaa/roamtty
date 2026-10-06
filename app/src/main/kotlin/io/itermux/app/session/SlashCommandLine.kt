package io.itermux.app.session

/**
 * Whether the input on the cursor row starts with `/`, i.e. the user is picking a slash command
 * (Claude Code, Codex, Grok, …).
 *
 * Read from the screen rather than guessed from keys: the agents' own menus fill, complete and
 * clear the line (Enter on a menu entry only picks it), which no keystroke estimate can follow.
 * The row's frame and prompt marker (`│ ❯ `, `› `, `> `, `$ `) come first and are skipped.
 */
internal object SlashCommandLine {
    private const val PROMPTS = "❯›>»▶➜$#%"

    fun startsWithSlash(row: String): Boolean {
        var i = skipFrame(row, 0)
        if (i < row.length && row[i] in PROMPTS && (i + 1 == row.length || isBlank(row[i + 1]))) i = skipFrame(row, i + 1)
        return i < row.length && row[i] == '/'
    }

    private fun skipFrame(row: String, from: Int): Int {
        var i = from
        while (i < row.length && (isBlank(row[i]) || row[i] in '─'..'▟')) i++
        return i
    }

    private fun isBlank(c: Char) = c.isWhitespace() || c == ' '
}
