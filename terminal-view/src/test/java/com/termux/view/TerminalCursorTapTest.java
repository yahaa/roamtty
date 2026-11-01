package com.termux.view;

import com.termux.terminal.SilentTerminalClient;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalCursorTapTest {
    private static final String RULE = "────────────────────────────────────────";

    private TerminalEmulator screen(String text) {
        TerminalEmulator emulator = new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        }, 40, 12, 8, 16, 400, new SilentTerminalClient());
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        return emulator;
    }

    @Test public void claudeCodeInputBetweenRulesIsOneBox() {
        TerminalEmulator t = screen("● done\r\n\r\n" + RULE + "\r\n❯ first line\r\n  second\r\n  third wraps\r\n  the tail\r\n" +
            RULE + "\r\n  ⏵⏵ auto mode on\u001b[5;9H");
        assertTrue(TerminalCursorTap.isRule(t, 2));
        assertArrayEquals(new int[] { 3, 6 }, TerminalCursorTap.inputRows(t, 4));
    }

    @Test public void codexComposerBetweenBlankRowsAboveItsStatusLine() {
        TerminalEmulator t = screen("  /review - review changes\r\n\r\n› first line\r\n  second\r\n  third\r\n\r\n  model · 100% left\u001b[4;5H");
        assertArrayEquals(new int[] { 2, 4 }, TerminalCursorTap.inputRows(t, 3));
    }

    @Test public void shellPromptWithOutputAboveIsNotABox() {
        assertNull(TerminalCursorTap.inputRows(screen("\r\nfile-a\r\nfile-b\r\n$ ls"), 3));
        assertNull("Nothing below but the screen edge", TerminalCursorTap.inputRows(
            screen("x\r\n\r\na\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng\r\nh\r\ni\r\n$ ls"), 11));
    }

    @Test public void singleRowInputHasNoOtherRowToJumpTo() {
        assertNull(TerminalCursorTap.inputRows(screen("\r\n" + RULE + "\r\n❯ hi\r\n" + RULE + "\r\n  status"), 2));
    }

    @Test public void framedBoxRowsAreTextButItsBordersAreRules() {
        TerminalEmulator t = screen("╭──────────────────╮\r\n│ > hello          │\r\n╰──────────────────╯");
        assertTrue(TerminalCursorTap.isRule(t, 0));
        assertFalse(TerminalCursorTap.isRule(t, 1));
        assertTrue(TerminalCursorTap.isRule(t, 2));
    }
}
