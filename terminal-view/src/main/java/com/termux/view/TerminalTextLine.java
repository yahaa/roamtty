package com.termux.view;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;

/** A bounded logical line, preserving terminal cells across soft wraps. */
final class TerminalTextLine {
    final String text;
    final int firstRow;
    final int lastRow;
    final int columns;

    private TerminalTextLine(String text, int firstRow, int lastRow, int columns) {
        this.text = text;
        this.firstRow = firstRow;
        this.lastRow = lastRow;
        this.columns = columns;
    }

    static TerminalTextLine at(TerminalEmulator emulator, int row) {
        TerminalBuffer screen = emulator.getScreen();
        int first = row, last = row;
        int minimum = -screen.getActiveTranscriptRows();
        while (first > minimum && row - first < 64 && screen.getLineWrap(first - 1)) first--;
        while (last < emulator.mRows - 1 && last - first < 128 && screen.getLineWrap(last)) last++;
        StringBuilder text = new StringBuilder();
        for (int y = first; y <= last; y++) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(y));
            text.append(line.mText, 0, line.getSpaceUsed());
        }
        return new TerminalTextLine(text.toString(), first, last, emulator.mColumns);
    }

    int cellAt(int column, int row) { return (row - firstRow) * columns + column; }

    int contentEndCell() {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        int cells = 0;
        for (int i = 0; i < end;) {
            int point = text.codePointAt(i);
            cells += Math.max(0, WcWidth.width(point));
            i += Character.charCount(point);
        }
        return cells;
    }

    int offsetAt(int cell) {
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            int size = Math.max(0, WcWidth.width(point));
            if (size > 0 && width + size > cell) return offset;
            width += size;
            offset += Character.charCount(point);
        }
        return text.length();
    }

    // Wide glyphs take one arrow press; combining marks stay with their base glyph.
    int cursorStepAt(int cell) {
        int end = offsetAt(cell), steps = 0;
        for (int i = 0; i < end;) {
            int point = text.codePointAt(i);
            if (WcWidth.width(point) > 0) steps++;
            i += Character.charCount(point);
        }
        return steps;
    }
}
