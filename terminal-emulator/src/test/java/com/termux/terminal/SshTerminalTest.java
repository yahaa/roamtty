package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/** Regressions for the SSH adapter and the local privacy changes. */
public class SshTerminalTest extends TerminalTestCase {
    public void testSplitUtf8AndWideCharacters() {
        withTerminalSized(12, 4);
        byte[] bytes = "中文🙂".getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) mTerminal.append(new byte[]{b}, 1);
        assertTrue(mTerminal.getScreen().getTranscriptText().contains("中文🙂"));
        assertEquals(6, mTerminal.getCursorCol());
    }
    public void testBracketedPasteAndControlSanitization() {
        withTerminalSized(12, 4);
        enterString("\033[?2004h");
        assertTrue(mTerminal.isBracketedPasteEnabled());
        mTerminal.paste("你好\nsecond\033[201~");
        assertEquals("\033[200~你好\rsecond[201~\033[201~", mOutput.getOutputAndClear());
        enterString("\033[?2004l");
        mTerminal.paste("hello");
        assertEquals("hello", mOutput.getOutputAndClear());
    }
    public void testRemoteOsc52CannotWriteClipboard() {
        withTerminalSized(12, 4);
        enterString("\033]52;c;aGVsbG8=\007");
        assertTrue(mOutput.clipboardPuts.isEmpty());
    }
    public void testTranscriptRemainsBounded() {
        mOutput = new MockTerminalOutput();
        mTerminal = new TerminalEmulator(mOutput, 20, 4, 8, 16, 100, null);
        for (int i = 0; i < 2000; i++) enterString("line " + i + "\r\n");
        assertTrue(mTerminal.getScreen().getActiveTranscriptRows() <= 100);
        assertTrue(mTerminal.getScreen().getTranscriptText().contains("1999"));
        assertFalse(mTerminal.getScreen().getTranscriptText().contains("line 1\n"));
    }
}
