package io.itermux.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexImageAttachTest {
    @Test fun detectsCodexAndGrokTokens() {
        assertTrue(CodexImageAttach.looksLikeCodexOrGrok("OpenAI Codex v0.1"))
        assertTrue(CodexImageAttach.looksLikeCodexOrGrok("› wait for input · codex"))
        assertTrue(CodexImageAttach.looksLikeCodexOrGrok("grok> "))
        assertTrue(CodexImageAttach.looksLikeCodexOrGrok("Running Grok CLI"))
        assertTrue(CodexImageAttach.looksLikeCodexOrGrok("CODEX session"))
    }

    @Test fun rejectsUnrelatedShellOutput() {
        assertFalse(CodexImageAttach.looksLikeCodexOrGrok("user@host:~$ ls /tmp"))
        assertFalse(CodexImageAttach.looksLikeCodexOrGrok("decoding xerography kit"))
        // Prefer false-negative: substring inside a larger token should not match.
        assertFalse(CodexImageAttach.looksLikeCodexOrGrok("mycodextool"))
        assertFalse(CodexImageAttach.looksLikeCodexOrGrok("agrokfile"))
        assertFalse(CodexImageAttach.looksLikeCodexOrGrok(""))
    }

    @Test fun remotePathIsUniquePngUnderTmp() {
        val a = CodexImageAttach.newRemotePngPath()
        val b = CodexImageAttach.newRemotePngPath()
        assertTrue(a.startsWith("/tmp/itermux-img-") && a.endsWith(".png"))
        assertTrue(b.startsWith("/tmp/itermux-img-") && b.endsWith(".png"))
        assertTrue(a != b)
    }
}
