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
        "https?://[^\\s\\p{Z}\\p{Cc}<>\"`，。！？；：、（）【】《》“”‘’]+", Pattern.CASE_INSENSITIVE);

    /** The characters a web URL may contain, as {@link #WEB_URL} reads them. */
    private static final Pattern URL_CHARS = Pattern.compile("[^\\s\\p{Z}\\p{Cc}<>\"`，。！？；：、（）【】《》“”‘’]*");

    static String at(TerminalEmulator emulator, int column, int row) {
        TerminalLink link = find(emulator, column, row);
        return link == null ? null : link.text;
    }

    static TerminalLink find(TerminalEmulator emulator, int column, int row) {
        if (column < 0 || column >= emulator.mColumns ||
            row < -emulator.getScreen().getActiveTranscriptRows() || row >= emulator.mRows) return null;
        Match renderedLink = parenthesizedLinkAt(emulator, column, row);
        if (renderedLink != null) return renderedLink.link();
        Match hardWrapped = hardWrappedUrlAt(emulator, column, row);
        if (hardWrapped != null) return hardWrapped.link();
        TerminalTextLine line = TerminalTextLine.at(emulator, row);
        int offset = line.offsetAt(line.cellAt(column, row));
        Matcher matcher = WEB_URL.matcher(line.text);
        for (int next = 0; matcher.find(next);) {
            int start = matcher.start();
            if (offset < start) return null;
            int candidateEnd = quotedItemEnd(line.text, start, matcher.end());
            next = candidateEnd; // Resume after this item, including separately quoted URLs.
            if (offset >= candidateEnd) continue;
            // The logical-line reader is bounded. Never open a URL cut off by its scan limit.
            if (candidateEnd == line.text.length() && emulator.getScreen().getLineWrap(line.lastRow)) return null;
            int end = trimEnd(line.text, start, candidateEnd);
            if (offset >= end || endsAtAmbiguousWrap(line, candidateEnd)) return null;
            String url = validatedUrl(line.text.substring(start, end));
            if (url == null) return null;
            TerminalLink.Spans spans = new TerminalLink.Spans(emulator);
            spans.addLine(line, start, end);
            return new TerminalLink(url, 0, true, spans.build(url.length()));
        }
        return null;
    }

    private static String validatedUrl(String url) {
        try {
            if (WEB_URL.matcher(url).matches() && new URI(url).getRawAuthority() != null) return url;
        } catch (URISyntaxException ignored) {
            // Incomplete or malformed output is ordinary terminal text.
        }
        return null;
    }

    private static int quotedItemEnd(String text, int start, int end) {
        if (start == 0 || text.charAt(start - 1) != '\'') return end;
        for (int i = start; i + 2 < end; i++) {
            // Separately quoted list items provide an explicit boundary; internal apostrophes
            // such as Guns_N'_Roses and a trailing apostrophe in rockin' remain URL characters.
            if (text.charAt(i) == '\'' && (text.charAt(i + 1) == ',' || text.charAt(i + 1) == ';') &&
                text.charAt(i + 2) == '\'') return i + 1;
        }
        return end;
    }

    private static final int MAX_LINK_ROWS = 128;
    private static final int MAX_LINK_LENGTH = 8192;

    /** A matched but incomplete rendered link must not fall back to opening its first fragment. */
    private static final class Match {
        final String url;
        final int[] spans;
        Match(String url, int[] spans) { this.url = url; this.spans = spans; }
        TerminalLink link() { return url == null ? null : new TerminalLink(url, 0, true, spans); }
    }

    static TerminalRow rowAt(TerminalEmulator emulator, int row) {
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
            int lastOpening = line.getSpaceUsed() - 1;
            while (lastOpening >= 0 && line.mText[lastOpening] != '(') lastOpening--;
            int cell = 0;
            for (int i = 0; i <= lastOpening;) {
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

    /** A row's last whitespace-free token and whether it reaches the final column. */
    static String rowToken(TerminalEmulator emulator, int y, boolean fromIndent, int[] range) {
        TerminalRow line = rowAt(emulator, y);
        String text = new String(line.mText, 0, line.getSpaceUsed());
        int start = 0;
        if (fromIndent) while (start < text.length() && text.charAt(start) == ' ') start++;
        int end = start;
        while (end < text.length() && text.charAt(end) != ' ') end++;
        range[0] = start;
        range[1] = end;
        return text.substring(start, end);
    }

    static int cellWidth(String text) {
        int cells = 0;
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            cells += Math.max(0, WcWidth.width(point));
            i += Character.charCount(point);
        }
        return cells;
    }

    static int contentLength(TerminalRow line) {
        int end = line.getSpaceUsed();
        while (end > 0 && line.mText[end - 1] == ' ') end--;
        return end;
    }

    static int freeCells(TerminalEmulator emulator, int y) {
        TerminalRow line = rowAt(emulator, y);
        int cells = 0;
        for (int i = 0, used = contentLength(line); i < used;) {
            int point = Character.codePointAt(line.mText, i, used);
            cells += Math.max(0, WcWidth.width(point));
            i += Character.charCount(point);
        }
        return emulator.mColumns - cells;
    }

    /** The row ends in a token that the next row's first token would not have fitted behind. */
    private static boolean continues(TerminalEmulator emulator, int y) {
        if (y + 1 >= emulator.mRows || lastToken(emulator, y) == null) return false;
        boolean soft = emulator.getScreen().getLineWrap(y);
        String next = rowToken(emulator, y + 1, !soft, new int[2]);
        if (next.isEmpty() || hasWebScheme(next) || hasBoxDrawing(next)) return false;
        return soft || freeCells(emulator, y) <= cellWidth(next);
    }

    /** TUI borders and rules sit below a link; they are chrome, never part of it. */
    static boolean hasBoxDrawing(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= '\u2500' && text.charAt(i) <= '\u259f') return true;
        return false;
    }

    /**
     * TUIs hard-wrap long bare URLs with a newline plus indentation, so no soft-wrap flag marks the
     * join. A URL token that fills its row continues with the next row's first token; without this
     * only the first fragment (".../pull/1" of ".../pull/135") would open.
     */
    private static Match hardWrappedUrlAt(TerminalEmulator emulator, int column, int row) {
        int minimum = -emulator.getScreen().getActiveTranscriptRows();
        int[] range = new int[2];
        // Rows whose trailing token may continue below; find the row where the URL starts.
        for (int top = row; top >= Math.max(minimum, row - MAX_LINK_ROWS); top--) {
            String token = schemeTail(lastToken(emulator, top));
            if (token != null && continues(emulator, top)) {
                Match match = joinFrom(emulator, top, token, column, row, range);
                if (match != null) return match;
            }
            if (top < row && !continues(emulator, top)) break;
        }
        return null;
    }

    private static String lastToken(TerminalEmulator emulator, int y) {
        TerminalRow line = rowAt(emulator, y);
        String text = new String(line.mText, 0, contentLength(line));
        return text.isEmpty() ? null : text.substring(text.lastIndexOf(' ') + 1);
    }

    /** The URL inside a token, which a label can precede without a space, as in "PR：https://…". */
    private static String schemeTail(String token) {
        if (token == null) return null;
        for (int i = 0; i + 7 <= token.length(); i++)
            if (hasWebScheme(token.substring(i))) return token.substring(i);
        return null;
    }

    private static Match joinFrom(TerminalEmulator emulator, int top, String first, int column, int row,
                                  int[] range) {
        TerminalRow topLine = rowAt(emulator, top);
        int topStart = contentLength(topLine) - first.length();
        StringBuilder url = new StringBuilder(first);
        // Index of the tapped character in the joined URL, or -1.
        int tapped = -1;
        boolean hardWrapped = false;
        TerminalLink.Spans spans = new TerminalLink.Spans(emulator);
        spans.add(top, topStart, contentLength(topLine));
        if (top == row) {
            int offset = topLine.findStartOfColumn(column);
            if (offset >= topStart && offset < contentLength(topLine)) tapped = offset - topStart;
        }
        // A link broken inside itself fills each row up to the TUI's edge. A continuation row that
        // stops short of where the first row ended is the link's end, whatever long word follows it.
        int topFree = freeCells(emulator, top);
        for (int y = top; y + 1 < emulator.mRows && y - top < MAX_LINK_ROWS && continues(emulator, y) &&
            (y == top || freeCells(emulator, y) <= topFree + 1); y++) {
            String next = rowToken(emulator, y + 1, !emulator.getScreen().getLineWrap(y), range);
            // Text glued after the link on its last row, such as "（abc）", is not part of it.
            Matcher allowed = URL_CHARS.matcher(next);
            boolean cut = allowed.lookingAt() && allowed.end() < next.length();
            if (cut) {
                next = next.substring(0, allowed.end());
                range[1] = range[0] + next.length();
            }
            if (!emulator.getScreen().getLineWrap(y)) hardWrapped = true;
            if (y + 1 == row) {
                int offset = rowAt(emulator, row).findStartOfColumn(column);
                if (offset >= range[0] && offset < range[1]) tapped = url.length() + offset - range[0];
            }
            url.append(next);
            spans.add(y + 1, range[0], range[1]);
            if (url.length() > MAX_LINK_LENGTH) return tapped >= 0 ? new Match(null, null) : null;
            if (cut || range[1] < contentLength(rowAt(emulator, y + 1))) break;
        }
        // Plain terminal soft wraps are read by the ordinary matcher.
        if (!hardWrapped || tapped < 0) return null;
        String result = url.toString();
        int end = trimEnd(result, 0, result.length());
        if (tapped >= end) return null; // Trailing punctuation such as a closing parenthesis.
        String valid = validatedUrl(result.substring(0, end));
        return valid == null ? null : new Match(valid, spans.build(valid.length()));
    }

    private static Match readParenthesizedLink(TerminalEmulator emulator, int startColumn, int startRow,
                                               int tappedColumn, int tappedRow) {
        StringBuilder url = new StringBuilder();
        TerminalLink.Spans spans = new TerminalLink.Spans(emulator);
        boolean tapped = false;
        String completeUrl = null;
        link: for (int y = startRow; y < emulator.mRows && y - startRow < MAX_LINK_ROWS; y++) {
            TerminalRow line = rowAt(emulator, y);
            int cell = y == startRow ? startColumn : 0;
            int offset = line.findStartOfColumn(cell);
            if (y != startRow && !emulator.getScreen().getLineWrap(y - 1)) {
                // Layout padding is allowed only at the row boundary, not inside a URL fragment.
                while (offset < line.getSpaceUsed() && line.mText[offset] == ' ') { offset++; cell++; }
                if (offset == line.getSpaceUsed()) break;
                String fragment = new String(line.mText, offset, line.getSpaceUsed() - offset);
                if (hasWebScheme(fragment)) break;
            }
            int baseColumn = cell;
            for (; offset < line.getSpaceUsed();) {
                int point = Character.codePointAt(line.mText, offset, line.getSpaceUsed());
                int width = Math.max(0, WcWidth.width(point));
                if (width > 0) baseColumn = cell;
                boolean linkStyle = underlined(line.getStyle(baseColumn));
                if (point == ')' && !linkStyle) {
                    completeUrl = validatedUrl(url.toString());
                    break link;
                }
                if (point == ' ') {
                    if (emulator.getScreen().getLineWrap(y)) break link;
                    for (int i = offset; i < line.getSpaceUsed(); i++) {
                        if (line.mText[i] != ' ') break link;
                    }
                    break;
                }
                if (!linkStyle) break link;
                url.appendCodePoint(point);
                spans.add(y, offset, offset + Character.charCount(point));
                if (url.length() <= 8 && !webPrefix(url.toString())) return null;
                if (y == tappedRow && tappedColumn >= cell && tappedColumn < cell + width) tapped = true;
                if (url.length() > MAX_LINK_LENGTH) break link;
                cell += width;
                offset += Character.charCount(point);
            }
        }
        return tapped ? new Match(completeUrl, completeUrl == null ? null : spans.build(completeUrl.length())) : null;
    }

    private static boolean webPrefix(String text) {
        return hasWebScheme(text) || "http://".regionMatches(true, 0, text, 0, text.length()) ||
            "https://".regionMatches(true, 0, text, 0, text.length());
    }

    static boolean hasWebScheme(String text) {
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
        if (".,;:!?)]}'".indexOf(text.charAt(end - 1)) < 0) return end;
        String opening = "([{", closing = ")]}";
        int[] balance = new int[opening.length()];
        for (int i = start; i < end; i++) {
            int open = opening.indexOf(text.charAt(i)), close = closing.indexOf(text.charAt(i));
            if (open >= 0) balance[open]++;
            if (close >= 0) balance[close]--;
        }
        while (end > start) {
            char last = text.charAt(end - 1);
            int bracket = closing.indexOf(last);
            if (".,;:!?".indexOf(last) >= 0) end--;
            else if (bracket >= 0 && balance[bracket] < 0) { end--; balance[bracket]++; }
            // Apostrophes are legal URL characters. Strip only an enclosing quote paired with
            // one immediately before the scheme; punctuation inside that pair belongs to the URL.
            else if (last == '\'' && start > 0 && text.charAt(start - 1) == '\'') return end - 1;
            else break;
        }
        return end;
    }
}
