package io.itermux.app.session

/** The program in front on a terminal, as far as its input area tells. */
enum class ScreenApp(val label: String) {
    CLAUDE("Claude Code"), CODEX("Codex"), GROK("Grok"), GEMINI("Gemini"), SHELL("");

    companion object {
        private val geminiModel = Regex("""\bgemini-\d""", RegexOption.IGNORE_CASE)
        private val codexContext = Regex("""\b[Cc]ontext \d+% left|\d+% context left""")
        private val rule = Regex("""^\s*─{20,}\s*$""")

        /**
         * Reads the rows around the cursor ([cursorRow] indexes [rows]): each agent keeps its prompt and footer there,
         * while a banner further up may be left on screen after the agent quits back to the shell.
         * Claude Code: `❯` between two rules, and a footer such as `? for shortcuts` or `⏵⏵ … (shift+tab to cycle)`.
         * Codex: `› ` prompt and `Context 100% left`. Grok: a box whose bottom border names the model, `Grok 4.7 … ─╯`.
         * Gemini: a `gemini-2.5-pro` model in the footer, or the `Type your message` placeholder.
         */
        fun detect(rows: List<String>, cursorRow: Int): ScreenApp {
            if (rows.isEmpty()) return SHELL
            val cursor = cursorRow.coerceIn(0, rows.lastIndex)
            val near = rows.subList((cursor - 3).coerceAtLeast(0), (cursor + 9).coerceAtMost(rows.size))
            fun anyRow(test: (String) -> Boolean) = near.any(test)
            return when {
                anyRow { geminiModel.containsMatchIn(it) || "Type your message" in it } -> GEMINI
                anyRow { codexContext.containsMatchIn(it) } -> CODEX
                anyRow { it.trimEnd().endsWith("─╯") && ("Grok" in it || "always-approve" in it) || "Ctrl+x:shortcuts" in it } -> GROK
                anyRow { line -> listOf("shift+tab to cycle", "⏵⏵", "⏸ plan mode", "? for shortcuts", "esc to interrupt").any { it in line } } ||
                    claudePrompt(rows, cursor) -> CLAUDE
                rows[cursor].trimStart().startsWith("› ") -> CODEX
                else -> SHELL
            }
        }

        /** Claude Code's prompt: a `❯` row with a full-width rule above it and another somewhere below. */
        private fun claudePrompt(rows: List<String>, cursor: Int): Boolean {
            val top = (cursor downTo (cursor - 3).coerceAtLeast(0)).firstOrNull { rule.matches(rows[it]) } ?: return false
            return top + 1 < rows.size && rows[top + 1].trimStart().startsWith("❯") &&
                (top + 2..(cursor + 6).coerceAtMost(rows.lastIndex)).any { rule.matches(rows[it]) }
        }
    }
}
