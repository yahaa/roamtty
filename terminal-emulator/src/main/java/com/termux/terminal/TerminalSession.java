/* Copyright 2026 iTermux contributors. Licensed under Apache-2.0. */
package com.termux.terminal;

/** Transport-neutral contract replacing the upstream JNI/local-process TerminalSession. */
public interface TerminalSession {
    TerminalEmulator getEmulator();
    void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
    void write(String data);
    void writeCodePoint(boolean prependEscape, int codePoint);
    void onCopyTextToClipboard(String text);
    void onPasteTextFromClipboard();
    void reset();
}
