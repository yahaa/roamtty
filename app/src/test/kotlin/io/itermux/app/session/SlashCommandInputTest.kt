package io.itermux.app.session

import org.junit.Assert.assertFalse
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
    @Test fun movementHistoryAndCompletionInvalidateTheEstimate() {
        listOf("\u001b[C", "\u001bOD", "\u001b[1~", "\t", "\u0001", "\u0005", "\u0002", "\u0006").forEach { edit ->
            val input = SlashCommandInput().type("/he", edit)
            assertFalse(edit, input.active)
            assertFalse(input.type("/another").active)
            assertTrue(input.type("\r", "/fresh").active)
        }
    }
    @Test fun upAndDownKeepTheSlashMenu() {
        listOf("\u001b[A", "\u001b[B", "\u001bOA", "\u001bOB").forEach { key ->
            val input = SlashCommandInput().type("/he")
            val line = input.line
            assertTrue(key, input.type(key, key).line == line)
        }
        // Up/Down on an ordinary line recall shell history, so the line is no longer known.
        assertFalse(SlashCommandInput().type("ls", "\u001b[A", "/x").active)
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
    @Test fun eachCommandLineIsNumbered() {
        val input = SlashCommandInput().type("/a")
        val first = input.line
        assertTrue(first != 0)
        assertTrue(input.type("b").line == first)
        assertTrue(input.type("\r").line == 0)
        assertTrue(input.type("/").line.let { it != 0 && it != first })
    }
    @Test fun multiByteCharactersCountOnce() {
        val input = SlashCommandInput().type("/", "中", "\u007f")
        assertTrue(input.active)
        assertFalse(input.type("\u007f").active)
    }
    @Test fun bareEscapeStartsANewLineButAltEditingDoesNot() {
        assertFalse(SlashCommandInput().type("/he", "\u001b").active)
        assertFalse(SlashCommandInput().type("/he", "\u001bb", "/again").active)
        assertTrue(SlashCommandInput().type("/he", "\u001b", "/again").active)
    }
    @Test fun terminalRepliesAndPasteBracketsAreSkipped() {
        assertTrue(SlashCommandInput().type("/", "\u001b[12;3R", "\u001b]11;rgb:0/0/0\u0007", "\u001b[I").active)
        assertTrue(SlashCommandInput().type("\u001b[200~/model\u001b[201~").active)
    }
    @Test fun partialDeletionDoesNotStartAFakeNewLine() {
        listOf("\u0004", "\u0017", "\u000b").forEach { edit ->
            val input = SlashCommandInput().type("/help word", edit, "/another")
            assertFalse(input.active)
            assertFalse(input.type("\u007f".repeat(30), "/still-unknown").active)
            assertTrue(input.type("\u0003", "/fresh").active)
        }
    }
    @Test fun ctrlUOnlyClearsAKnownEndOfLine() {
        assertTrue(SlashCommandInput().type("text", "\u0015", "/fresh").active)
        assertFalse(SlashCommandInput().type("/help", "\u0001", "\u0015", "/unknown").active)
    }
    @Test fun pastedNewlinesCannotResynchronizeAnUnknownLine() {
        val input = SlashCommandInput().type("text", "\u0001", "\u001b[200~\n/help\u001b[201~")
        assertFalse(input.active)
        assertTrue(input.type("\r", "/fresh").active)
    }
    @Test fun splitAndInterruptedEscapeSequencesDoNotBecomeText() {
        val input = SlashCommandInput().type("/help", "\u001b", "[", "D", "/unknown")
        assertFalse(input.active)
        assertTrue(input.type("\r", "/fresh").active)
        assertTrue(input.type("\u001b[", "\u0003", "/fresh").active)
    }
}
