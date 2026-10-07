package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenAppTest {
    private fun detect(screen: String): ScreenApp {
        val rows = screen.trimIndent().lines()
        return ScreenApp.detect(rows, rows.indexOfFirst { "<cursor>" in it }.coerceAtLeast(0))
    }

    private val rule = "─".repeat(80)

    @Test fun claudeCodePromptBetweenRules() {
        assertEquals(ScreenApp.CLAUDE, detect("""
             ▐▛███▛█   Claude Code v2.1.292
            $rule
            ❯ <cursor>
            $rule
              ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents
        """))
        // Default mode shows no footer at all; the framed prompt is enough.
        assertEquals(ScreenApp.CLAUDE, detect("""
            ⏺ Done.
            $rule
            ❯ fix the build <cursor>
            $rule
        """))
    }

    @Test fun codexFooter() {
        assertEquals(ScreenApp.CODEX, detect("""
              /review - review any changes and find issues

            › Ask Codex to do anything <cursor>

              GPT-6-Astra xhigh · Context 100% left · weekly 65% left
              ← for agents · ? for shortcuts                      ⚠ 2 warnings · f2 to view
        """))
    }

    @Test fun grokBox() {
        assertEquals(ScreenApp.GROK, detect("""
              ╭──────────────────────────────────────────────────────────────────────────╮
              │ ❯ <cursor>                                                               │
              ╰────────────────────────────────────── Grok 4.7 (xhigh) · always-approve ─╯
                                                                        Grok Build  1.0.46
        """))
    }

    @Test fun geminiFooter() {
        assertEquals(ScreenApp.GEMINI, detect("""
            ╭──────────────────────────────────────────────────────────────╮
            │ >   Type your message or @path/to/file <cursor>              │
            ╰──────────────────────────────────────────────────────────────╯
            ~/proj            no sandbox            gemini-2.5-pro (100% context left)
        """))
    }

    @Test fun shellAfterAnAgentQuit() {
        // Claude's banner stays on screen after it exits, but the cursor is on a shell prompt below.
        assertEquals(ScreenApp.SHELL, detect("""
             ▐▛███▛█   Claude Code v2.1.292
            $rule
            ❯ /exit
            $rule
              ⏵⏵ auto mode on (shift+tab to cycle)
            Resume this session with: claude --resume 1234
            (base)
            v@dev:~/work/roamtty$
            $ ls
            README.md  app  docs
            build.gradle.kts  settings.gradle.kts
            v@dev:~/work/roamtty$ <cursor>
        """))
        assertEquals(ScreenApp.SHELL, detect("v@dev:~$ <cursor>"))
    }
}
