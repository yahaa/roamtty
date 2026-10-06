package io.itermux.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandLineTest {
    private fun slash(row: String) = SlashCommandLine.startsWithSlash(row)

    @Test fun agentPromptsWithASlashCommand() {
        assertTrue(slash("│ ❯ /model Grok 4.7                 │")) // Grok, after picking from its menu
        assertTrue(slash("› /review"))                             // Codex
        assertTrue(slash("│ > /help                              │")) // Claude Code
        assertTrue(slash("> /"))
        assertTrue(slash("❯ /compact"))
        assertTrue(slash("$ /usr/bin/env"))
    }
    @Test fun anythingElseOnTheCursorRow() {
        assertFalse(slash("│ ❯ fix the /tmp path          │"))
        assertFalse(slash("› "))
        assertFalse(slash(""))
        assertFalse(slash("v@dev:~/work$ /bin/ls"))
        assertFalse(slash("➜  roamtty git:(main) /x"))
        assertFalse(slash(">/no-space-after-prompt"))
        assertFalse(slash("Enter:send │ Alt+Enter:newline │"))
    }
}
