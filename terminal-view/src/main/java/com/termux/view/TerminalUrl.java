package com.termux.view;

import com.termux.terminal.TerminalEmulator;
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
