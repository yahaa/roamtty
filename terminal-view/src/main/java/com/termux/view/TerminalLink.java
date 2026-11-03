package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;
import java.util.ArrayList;
import java.util.List;

/** A tapped web link or file path, with the cells it covers so a press can mark exactly those. */
public final class TerminalLink {
    /** The URL, or the path without its line suffix. */
    public final String text;
    /** A path's 1-based line number, or 0. */
    public final int line;
    public final boolean isUrl;
    /** Triples of external row, first column and end column (exclusive), top to bottom. */
    final int[] spans;

    TerminalLink(String text, int line, boolean isUrl, int[] spans) {
        this.text = text;
        this.line = line;
        this.isUrl = isUrl;
        this.spans = spans;
    }

    boolean coversRow(int row) {
        for (int i = 0; i < spans.length; i += 3) if (spans[i] == row) return true;
        return false;
    }

    /** Collects row fragments in reading order; {@link #build} keeps only the first characters of the text. */
    static final class Spans {
        private final TerminalEmulator emulator;
        private final List<int[]> fragments = new ArrayList<>(); // row, start offset, end offset

        Spans(TerminalEmulator emulator) { this.emulator = emulator; }

        void add(int row, int startOffset, int endOffset) {
            if (endOffset <= startOffset) return;
            int[] last = fragments.isEmpty() ? null : fragments.get(fragments.size() - 1);
            if (last != null && last[0] == row && last[2] == startOffset) last[2] = endOffset;
            else fragments.add(new int[]{row, startOffset, endOffset});
        }

        /** Fragments of a logical line's [start, end) character range. */
        void addLine(TerminalTextLine line, int start, int end) {
            for (int y = line.firstRow, rowStart = 0; y <= line.lastRow; y++) {
                int used = row(y).getSpaceUsed();
                add(y, Math.max(start, rowStart) - rowStart, Math.min(end, rowStart + used) - rowStart);
                rowStart += used;
            }
        }

        int[] build(int chars) {
            int[] spans = new int[fragments.size() * 3];
            int count = 0;
            for (int[] fragment : fragments) {
                if (chars <= 0) break;
                int end = Math.min(fragment[2], fragment[1] + chars);
                chars -= end - fragment[1];
                TerminalRow line = row(fragment[0]);
                spans[count++] = fragment[0];
                spans[count++] = cellOf(line, fragment[1]);
                spans[count++] = cellOf(line, end);
            }
            int[] result = new int[count];
            System.arraycopy(spans, 0, result, 0, count);
            return result;
        }

        private TerminalRow row(int y) {
            return emulator.getScreen().allocateFullLineIfNecessary(emulator.getScreen().externalToInternalRow(y));
        }

        private static int cellOf(TerminalRow line, int offset) {
            int cells = 0;
            for (int i = 0; i < offset && i < line.getSpaceUsed();) {
                int point = Character.codePointAt(line.mText, i, line.getSpaceUsed());
                cells += Math.max(0, WcWidth.width(point));
                i += Character.charCount(point);
            }
            return cells;
        }
    }
}
