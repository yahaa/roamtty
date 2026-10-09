package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds web URLs at terminal cell positions. */
final class TerminalUrl {
    private static final Pattern WEB_URL = Pattern.compile(
        "https?://[^\\s\\p{Z}\\p{Cc}<>\"'`，。！？；：、（）【】《》“”‘’]+", Pattern.CASE_INSENSITIVE);

    static String at(TerminalEmulator emulator, int column, int row) {
        if (column < 0 || column >= emulator.mColumns ||
            row < -emulator.getScreen().getActiveTranscriptRows() || row >= emulator.mRows) return null;
        Match renderedLink = parenthesizedLinkAt(emulator, column, row);
        if (renderedLink != null) return renderedLink.url;
        TerminalTextLine line = TerminalTextLine.at(emulator, row);
        int offset = line.offsetAt(line.cellAt(column, row));
        Matcher matcher = WEB_URL.matcher(line.text);
        while (matcher.find()) {
            // The logical-line reader is bounded. Never open a URL cut off by its scan limit.
            if (matcher.end() == line.text.length() && emulator.getScreen().getLineWrap(line.lastRow)) continue;
            int end = trimEnd(line.text, matcher.start(), matcher.end());
            if (offset < matcher.start() || offset >= end) continue;
            if (endsAtAmbiguousWrap(line, matcher.end())) return null;
            String url = line.text.substring(matcher.start(), end);
            try {
                if (new URI(url).getRawAuthority() != null) return url;
            } catch (URISyntaxException ignored) {
                // Incomplete or malformed output is ordinary terminal text.
            }
        }
        return null;
    }

    private static final int MAX_LINK_ROWS = 128;
    private static final int MAX_LINK_LENGTH = 8192;

    /** A matched but incomplete rendered link must not fall back to opening its first fragment. */
    private static final class Match {
        final String url;
        Match(String url) { this.url = url; }
    }

    private static TerminalRow rowAt(TerminalEmulator emulator, int row) {
        return emulator.getScreen().allocateFullLineIfNecessary(emulator.getScreen().externalToInternalRow(row));
    }

    private static boolean underlined(long style) {
        return (TextStyle.decodeEffect(style) & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
    }

    private static Match parenthesizedLinkAt(TerminalEmulator emulator, int column, int row) {
        // Markdown TUIs render "label (url)" with only the URL underlined. They can wrap with
        // explicit newlines/cursor moves and indentation, which carry no terminal soft-wrap flag.
        // Use both the enclosing punctuation and the link's style, never arbitrary adjacent text.
        TerminalRow tappedLine = rowAt(emulator, row);
        int styleColumn = column;
        if (column > 0 && tappedLine.findStartOfColumn(column) == tappedLine.findStartOfColumn(column - 1))
            styleColumn--; // The second cell of a wide glyph has no independent character style.
        if (!underlined(tappedLine.getStyle(styleColumn))) return null;
        int first = Math.max(-emulator.getScreen().getActiveTranscriptRows(), row - MAX_LINK_ROWS + 1);
        for (int y = row; y >= first; y--) {
            TerminalRow line = rowAt(emulator, y);
            int cell = 0;
            for (int i = 0; i < line.getSpaceUsed();) {
                int point = Character.codePointAt(line.mText, i, line.getSpaceUsed());
                int width = Math.max(0, WcWidth.width(point));
                if (point == '(' && cell + 1 < emulator.mColumns &&
                    !underlined(line.getStyle(cell)) && underlined(line.getStyle(cell + 1))) {
                    Match match = readParenthesizedLink(emulator, cell + 1, y, column, row);
                    if (match != null) return match;
                }
                cell += width;
                i += Character.charCount(point);
            }
        }
        return null;
    }

    private static Match readParenthesizedLink(TerminalEmulator emulator, int startColumn, int startRow,
                                               int tappedColumn, int tappedRow) {
        StringBuilder url = new StringBuilder();
        boolean tapped = false;
        for (int y = startRow; y < emulator.mRows && y - startRow < MAX_LINK_ROWS; y++) {
            TerminalRow line = rowAt(emulator, y);
            int cell = y == startRow ? startColumn : 0;
            int offset = line.findStartOfColumn(cell);
            if (y != startRow && !emulator.getScreen().getLineWrap(y - 1)) {
                // Layout padding is allowed only at the row boundary, not inside a URL fragment.
                while (offset < line.getSpaceUsed() && line.mText[offset] == ' ') { offset++; cell++; }
                if (offset == line.getSpaceUsed()) break;
                String fragment = new String(line.mText, offset, line.getSpaceUsed() - offset);
                if (hasWebScheme(fragment)) return tapped ? new Match(null) : null;
            }
            int baseColumn = cell;
            for (; offset < line.getSpaceUsed();) {
                int point = Character.codePointAt(line.mText, offset, line.getSpaceUsed());
                int width = Math.max(0, WcWidth.width(point));
                if (width > 0) baseColumn = cell;
                boolean linkStyle = underlined(line.getStyle(baseColumn));
                if (point == ')' && !linkStyle) {
                    if (!tapped) return null;
                    String value = url.toString();
                    try {
                        if (WEB_URL.matcher(value).matches() && new URI(value).getRawAuthority() != null)
                            return new Match(value);
                    } catch (URISyntaxException ignored) { }
                    return new Match(null);
                }
                if (point == ' ') {
                    if (emulator.getScreen().getLineWrap(y)) return tapped ? new Match(null) : null;
                    for (int i = offset; i < line.getSpaceUsed(); i++) {
                        if (line.mText[i] != ' ') return tapped ? new Match(null) : null;
                    }
                    break;
                }
                if (!linkStyle) return tapped ? new Match(null) : null;
                url.appendCodePoint(point);
                if (url.length() <= 8 && !webPrefix(url.toString())) return null;
                if (y == tappedRow && tappedColumn >= cell && tappedColumn < cell + width) tapped = true;
                if (url.length() > MAX_LINK_LENGTH) return tapped ? new Match(null) : null;
                cell += width;
                offset += Character.charCount(point);
            }
        }
        return tapped ? new Match(null) : null;
    }

    private static boolean webPrefix(String text) {
        return hasWebScheme(text) || "http://".regionMatches(true, 0, text, 0, text.length()) ||
            "https://".regionMatches(true, 0, text, 0, text.length());
    }

    private static boolean hasWebScheme(String text) {
        return text.regionMatches(true, 0, "http://", 0, 7) || text.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean endsAtAmbiguousWrap(TerminalTextLine line, int end) {
        // A wide glyph can wrap early, leaving a blank final cell. The buffer cannot distinguish
        // that padding from a real space before prose, so neither join it nor open a partial URL.
        if (end + 1 >= line.text.length() || line.text.charAt(end) != ' ' ||
            WcWidth.width(line.text.codePointAt(end + 1)) != 2) return false;
        int cells = 0;
        for (int i = 0; i < end;) {
            int point = line.text.codePointAt(i);
            cells += Math.max(0, WcWidth.width(point));
            i += Character.charCount(point);
        }
        return cells % line.columns == line.columns - 1;
    }

    private static int trimEnd(String text, int start, int end) {
        int round = 0, square = 0, curly = 0;
        for (int i = start; i < end; i++) {
            switch (text.charAt(i)) {
                case '(': round++; break;
                case ')': round--; break;
                case '[': square++; break;
                case ']': square--; break;
                case '{': curly++; break;
                case '}': curly--; break;
            }
        }
        while (end > start) {
            char last = text.charAt(end - 1);
            if (".,;:!?".indexOf(last) >= 0) end--;
            else if (last == ')' && round < 0) { end--; round++; }
            else if (last == ']' && square < 0) { end--; square++; }
            else if (last == '}' && curly < 0) { end--; curly++; }
            else break;
        }
        return end;
    }
}
