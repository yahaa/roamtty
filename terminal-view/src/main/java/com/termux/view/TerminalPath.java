package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a file path, optionally followed by ":line" or ":line:column", at a terminal cell. Only
 * text that looks like a path qualifies; whether the file exists is for the client to check.
 */
final class TerminalPath {
    /** A path token and its optional position, as compilers, linters, grep and agents print it. */
    private static final Pattern TOKEN = Pattern.compile("(.+?)(?::(\\d{1,9}))?(?::\\d{1,9})?:?");
    /** A bare file name needs an extension with a letter, so "1.2.3" or "v2.0" do not qualify. */
    private static final Pattern FILE_NAME = Pattern.compile("[^/]*[\\p{L}\\d_\\-]\\.[A-Za-z\\d]*[A-Za-z][A-Za-z\\d]*");
    private static final int MAX_PATH_LENGTH = 4096;

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
        // Sentence punctuation and an enclosing quote are not part of the path.
        while (end > start && ".,;'".indexOf(text.charAt(end - 1)) >= 0) end--;
        while (start < end && text.charAt(start) == '\'') start++;
        if (offset < start || offset >= end || end - start > MAX_PATH_LENGTH) return null;
        String token = text.substring(start, end);
        if (token.contains("://")) return null;
        Matcher matcher = TOKEN.matcher(token);
        if (!matcher.matches()) return null;
        String path = matcher.group(1);
        if (!looksLikePath(path)) return null;
        int lineNumber = 0;
        if (matcher.group(2) != null) {
            lineNumber = Integer.parseInt(matcher.group(2));
        }
        TerminalLink.Spans spans = new TerminalLink.Spans(emulator);
        spans.addLine(line, start, end);
        return new TerminalLink(path, lineNumber, false, spans.build(end - start));
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
