package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandInputTest {
    private fun SlashCommandInput.type(vararg chunks: String) = apply { chunks.forEach { feed(it.toByteArray()) } }

    @Test fun leadingSlashStartsCommand() {
        assertTrue(SlashCommandInput().type("/").active)
        assertTrue(SlashCommandInput().type("/", "h", "e").active)
        assertFalse(SlashCommandInput().type("a", "/").active)
        assertFalse(SlashCommandInput().type("ls /tmp").active)
    }
    @Test fun keysThatLeaveTheLineKeepTheArrows() {
        listOf("\u001b", "\u001b[A", "\u001b[B", "\u001bOA", "\u001bOB", "\t", "\u001b[12;3R", "\u001b[I",
            "\u001b]11;rgb:0/0/0\u0007", "\u001b[1;2D", "\u001b[C", "\u0001", "\u0005").forEach { key ->
            val input = SlashCommandInput().type("/he")
            val line = input.line
            assertEquals(key, line, input.type(key, key).line)
        }
    }
    @Test fun escThenSlashAgainStillShows() {
        assertTrue(SlashCommandInput().type("/", "\u001b", "\u007f", "/").active)
        assertTrue(SlashCommandInput().type("/", "\u001b", "/").active)
    }
    @Test fun newLineEndsCommand() {
        assertFalse(SlashCommandInput().type("/help", "\r").active)
        assertTrue(SlashCommandInput().type("echo", "\r", "/").active)
        assertFalse(SlashCommandInput().type("/x", "\u0003").active)
        assertFalse(SlashCommandInput().type("/x", "\u0015").active)
    }
    @Test fun backspacingTheSlashEndsCommand() {
        val input = SlashCommandInput().type("/a", "\u007f")
        assertTrue(input.active)
        input.type("\u007f")
        assertFalse(input.active)
        input.type("\u007f", "/")
        assertTrue(input.active)
    }
    @Test fun editingAtTheCursorFollowsTheRealFirstCharacter() {
        assertFalse(SlashCommandInput().type("/help", "\u0001", "x").active) // x/help
        assertTrue(SlashCommandInput().type("help", "\u0001", "/").active) // /help
        assertTrue(SlashCommandInput().type("/help", "\u0001", "x", "\u0001", "\u0004").active) // x deleted
        assertTrue(SlashCommandInput().type("/help word", "\u0017").active)
        assertTrue(SlashCommandInput().type("/help", "\u001b[D", "\u0004").active) // /hel
        assertFalse(SlashCommandInput().type("/help", "\u0001", "\u001b[3~").active) // help
        assertTrue(SlashCommandInput().type("x/help", "\u001b[H", "\u001b[C", "\u0015").active) // /help
        assertFalse(SlashCommandInput().type("/help", "\u0001", "\u000b").active)
        assertTrue(SlashCommandInput().type("/a b", "\u001b\u007f").active)
    }
    @Test fun eachCommandLineIsNumbered() {
        val input = SlashCommandInput().type("/a")
        val first = input.line
        assertTrue(first != 0)
        assertTrue(input.type("b").line == first)
        assertTrue(input.type("\r").line == 0)
        assertTrue(input.type("/").line.let { it != 0 && it != first })
        assertNotEquals(input.line, input.type("x\r/y").line)
    }
    @Test fun multiByteCharactersCountOnce() {
        val input = SlashCommandInput().type("/", "中", "\u007f")
        assertTrue(input.active)
        assertFalse(input.type("\u007f").active)
    }
    @Test fun pasteIsTypedText() {
        assertTrue(SlashCommandInput().type("\u001b[200~/model\u001b[201~").active)
        assertTrue(SlashCommandInput().type("\u001b[200~/a\nb\u001b[201~").active)
    }
    @Test fun splitEscapeSequencesDoNotBecomeText() {
        assertTrue(SlashCommandInput().type("/help", "\u001b[", "D").active)
        assertTrue(SlashCommandInput().type("\u001b[", "\u0003", "/fresh").active)
    }
}
