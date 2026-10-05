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
    @Test fun arrowsAndTabKeepCommand() {
        assertTrue(SlashCommandInput().type("/he", "\u001b[A", "\u001bOB", "\t").active)
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
    @Test fun multiByteCharactersCountOnce() {
        val input = SlashCommandInput().type("/", "中", "\u007f")
        assertTrue(input.active)
        assertFalse(input.type("\u007f").active)
    }
    @Test fun bareEscEndsCommandButAltKeyDoesNot() {
        assertFalse(SlashCommandInput().type("/he", "\u001b").active)
        assertTrue(SlashCommandInput().type("/he", "\u001bb").active)
    }
    @Test fun terminalRepliesAndPasteBracketsAreSkipped() {
        assertTrue(SlashCommandInput().type("/", "\u001b[12;3R", "\u001b]11;rgb:0/0/0\u0007", "\u001b[I").active)
        assertTrue(SlashCommandInput().type("\u001b[200~/model\u001b[201~").active)
    }
}
