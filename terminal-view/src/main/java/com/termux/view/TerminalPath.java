package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a file path, optionally followed by ":line" or ":line:column", at a terminal cell. Only
 * text that looks like a path qualifies; whether the file exists is for the client to check.
 */
final class TerminalPath {
    /** A path's position after its first colon, as compilers, linters, grep and agents print it. */
    private static final Pattern LOCATION = Pattern.compile(":(\\d{1,9})(?::\\d{1,9})?(?=:|$)");
    /** A bare file name needs an extension with a letter, so "1.2.3" or "v2.0" do not qualify. */
    private static final Pattern FILE_NAME = Pattern.compile("[^/]*[\\p{L}\\d_\\-]\\.[A-Za-z\\d]*[A-Za-z][A-Za-z\\d]*");
    private static final int MAX_PATH_LENGTH = 4096;

    /** Rows a hard-wrapped path may span; paths longer than this are not joined. */
    private static final int MAX_JOINED_ROWS = 8;

    static TerminalLink find(TerminalEmulator emulator, int column, int row) {
        if (column < 0 || column >= emulator.mColumns ||
            row < -emulator.getScreen().getActiveTranscriptRows() || row >= emulator.mRows) return null;
        TerminalTextLine line = TerminalTextLine.at(emulator, row);
        String text = line.text;
        int offset = line.offsetAt(line.cellAt(column, row));
        if (offset >= text.length() || !isPathChar(text.codePointAt(offset))) return null;
        int start = offset, end = offset;
        while (start > 0 && isPathChar(text.codePointBefore(start))) start -= Character.charCount(text.codePointBefore(start));
        while (end < text.length() && isPathChar(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end));
        // The logical-line reader is bounded: a token cut off by its limit is not a whole path.
        if (end == text.length() && emulator.getScreen().getLineWrap(line.lastRow)) return null;
        TerminalLink.Spans spans = new TerminalLink.Spans(emulator);
        spans.addLine(line, start, end);
        TerminalLink alone = parse(text.substring(start, end), offset - start, spans);

        // TUIs such as coding agents break a long path with a newline and indentation, which carries
        // no soft-wrap flag. Join the pieces the layout says belong together, like a hard-wrapped URL.
        StringBuilder joined = new StringBuilder(text.substring(start, end));
        TerminalLink.Spans joinedSpans = new TerminalLink.Spans(emulator);
        joinedSpans.addLine(line, start, end);
        int tapped = offset - start;
        int rows = line.lastRow - line.firstRow + 1;
        TerminalTextLine first = line;
        for (int pieceStart = start; pieceStart == indentEnd(first.text) && rows < MAX_JOINED_ROWS; rows++) {
            int y = first.firstRow - 1;
            if (y < -emulator.getScreen().getActiveTranscriptRows() || !continues(emulator, y)) break;
            TerminalTextLine previous = TerminalTextLine.at(emulator, y);
            int pieceEnd = contentEnd(previous.text);
            pieceStart = pieceEnd;
            while (pieceStart > 0 && isPathChar(previous.text.codePointBefore(pieceStart)))
                pieceStart -= Character.charCount(previous.text.codePointBefore(pieceStart));
            if (pieceStart == pieceEnd) break;
            joined.insert(0, previous.text, pieceStart, pieceEnd);
            joinedSpans.prependLine(previous, pieceStart, pieceEnd);
            tapped += pieceEnd - pieceStart;
            first = previous;
        }
        TerminalTextLine last = line;
        for (int pieceEnd = end; pieceEnd == contentEnd(last.text) && rows < MAX_JOINED_ROWS; rows++) {
            int y = last.lastRow;
            if (y + 1 >= emulator.mRows || !continues(emulator, y)) break;
            TerminalTextLine next = TerminalTextLine.at(emulator, y + 1);
            int pieceStart = indentEnd(next.text);
            pieceEnd = pieceStart;
            while (pieceEnd < next.text.length() && isPathChar(next.text.codePointAt(pieceEnd)))
                pieceEnd += Character.charCount(next.text.codePointAt(pieceEnd));
            if (pieceEnd == pieceStart) break;
            joined.append(next.text, pieceStart, pieceEnd);
            joinedSpans.addLine(next, pieceStart, pieceEnd);
            last = next;
        }
        if (joined.length() == end - start) return alone;
        TerminalLink whole = parse(joined.toString(), tapped, joinedSpans);
        if (whole == null) return alone;
        return alone == null || alone.text.equals(whole.text) && alone.line == whole.line ? whole : whole.withFallback(alone);
    }

    /** A path, its line and its cells from a run of path characters, if the tapped character belongs to it. */
    private static TerminalLink parse(String run, int tapped, TerminalLink.Spans spans) {
        int start = 0, end = run.length();
        // Sentence punctuation and an enclosing quote are not part of the path.
        while (end > start && ".,;'".indexOf(run.charAt(end - 1)) >= 0) end--;
        while (start < end && run.charAt(start) == '\'') start++;
        if (tapped < start || tapped >= end || end - start > MAX_PATH_LENGTH) return null;
        if (run.substring(start, end).contains("://")) return null;
        // The path ends at its first colon. "grep -n" glues the matched source text on after the
        // location, as in "src/a.ts:42:const x", so whatever follows "path:line[:column]" is not it.
        int colon = run.indexOf(':', start);
        int lineNumber = 0;
        if (colon >= 0 && colon < end) {
            Matcher location = LOCATION.matcher(run).region(colon, end);
            if (location.lookingAt()) lineNumber = Integer.parseInt(location.group(1));
            int pathEnd = colon;
            end = lineNumber > 0 ? location.end() : colon;
            if (tapped >= end) return null;
            String path = run.substring(start, pathEnd);
            if (!looksLikePath(path)) return null;
            return new TerminalLink(path, lineNumber, false, spans.build(start, end - start));
        }
        String path = run.substring(start, end);
        if (!looksLikePath(path)) return null;
        return new TerminalLink(path, 0, false, spans.build(start, end - start));
    }

    /**
     * Row y ends a logical line whose last token the next row's first token would not have fitted
     * behind, so a TUI that breaks lines itself moved it down. Borders and links are never continuations.
     */
    private static boolean continues(TerminalEmulator emulator, int y) {
        if (y + 1 >= emulator.mRows || emulator.getScreen().getLineWrap(y)) return false;
        String next = TerminalUrl.rowToken(emulator, y + 1, true, new int[2]);
        if (next.isEmpty() || TerminalUrl.hasWebScheme(next) || TerminalUrl.hasBoxDrawing(next)) return false;
        int free = TerminalUrl.freeCells(emulator, y);
        // A path broken inside itself fills the row up to the TUI's own margin; a word wrap that
        // moved a whole word down usually leaves more room, and joining it would glue on that word.
        return free <= MAX_RIGHT_MARGIN && free <= TerminalUrl.cellWidth(next);
    }

    /** Cells a TUI may leave unused at the right edge of a row it filled. */
    private static final int MAX_RIGHT_MARGIN = 4;

    private static int indentEnd(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) == ' ') i++;
        return i;
    }

    private static int contentEnd(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        return end;
    }

    static boolean looksLikePath(String path) {
        if (path.isEmpty() || path.indexOf(':') >= 0 || path.contains("//")) return false;
        int slash = path.indexOf('/');
        if (slash < 0) return FILE_NAME.matcher(path).matches();
        // "/", "./", "~/" and "a/" alone name a folder, not a file to show.
        if (path.endsWith("/")) return false;
        String name = path.substring(path.lastIndexOf('/') + 1);
        for (int i = 0; i < name.length(); i++) if (Character.isLetter(name.charAt(i))) return true;
        return false;
    }

    private static boolean isPathChar(int point) {
        return Character.isLetterOrDigit(point) || "._-/~+@:".indexOf(point) >= 0;
    }
}
