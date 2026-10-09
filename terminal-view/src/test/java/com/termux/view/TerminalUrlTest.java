package com.termux.view;

import com.termux.terminal.SilentTerminalClient;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalUrlTest {
    private TerminalEmulator terminal(int columns, int rows, String text) {
        TerminalEmulator emulator = new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        }, columns, rows, 8, 16, 400, new SilentTerminalClient());
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        return emulator;
    }

    @Test public void onlyTheTappedLinkOpens() {
        TerminalEmulator t = terminal(100, 4, "see https://one.test/a and http://two.test:8080/b?x=1&y=2#part end");
        assertEquals("https://one.test/a", TerminalUrl.at(t, 4, 0));
        assertEquals("http://two.test:8080/b?x=1&y=2#part", TerminalUrl.at(t, 30, 0));
        assertNull(TerminalUrl.at(t, 0, 0));
        assertNull(TerminalUrl.at(t, 23, 0));
        assertNull(TerminalUrl.at(t, 90, 0));
    }

    @Test public void softWrappedUrlIsWholeFromEveryRow() {
        TerminalEmulator t = terminal(12, 6, "go https://example.test/long/path?q=hello%20world#end");
        for (int row = 0; row < 4; row++) {
            assertEquals("https://example.test/long/path?q=hello%20world#end", TerminalUrl.at(t, 5, row));
        }
    }

    @Test public void hardLineBreakDoesNotJoinUrls() {
        TerminalEmulator t = terminal(40, 4, "https://one.test\r\nhttps://two.test");
        assertEquals("https://one.test", TerminalUrl.at(t, 3, 0));
        assertEquals("https://two.test", TerminalUrl.at(t, 3, 1));
        assertNull(TerminalUrl.at(t, 20, 0));
    }

    @Test public void wideCombiningAndColoredTextKeepCellPositions() {
        TerminalEmulator t = terminal(80, 4, "中文🙂 e\u0301 \033[32mhttps://example.test/中文\033[0m done");
        assertNull(TerminalUrl.at(t, 8, 0));
        assertEquals("https://example.test/中文", TerminalUrl.at(t, 9, 0));
        assertEquals("https://example.test/中文", TerminalUrl.at(t, 33, 0));
        assertNull(TerminalUrl.at(t, 34, 0));
    }

    @Test public void punctuationAndMarkdownWrappersAreNotPartOfUrl() {
        TerminalEmulator t = terminal(100, 4, "[docs](https://example.test/a_(b)). https://two.test/path，继续");
        assertEquals("https://example.test/a_(b)", TerminalUrl.at(t, 10, 0));
        assertNull(TerminalUrl.at(t, 33, 0));
        assertEquals("https://two.test/path", TerminalUrl.at(t, 40, 0));
        assertNull(TerminalUrl.at(t, 57, 0));
    }

    @Test public void localAndIpv6UrlsAndUppercaseSchemeWork() {
        TerminalEmulator t = terminal(100, 4, "HTTP://localhost:3000/ http://127.0.0.1:8080/ http://[::1]:3000/");
        assertEquals("HTTP://localhost:3000/", TerminalUrl.at(t, 0, 0));
        assertEquals("http://127.0.0.1:8080/", TerminalUrl.at(t, 23, 0));
        assertEquals("http://[::1]:3000/", TerminalUrl.at(t, 46, 0));
    }

    @Test public void scrolledTranscriptUrlsRemainClickable() {
        TerminalEmulator t = terminal(40, 3, "https://old.test\r\none\r\ntwo\r\nthree");
        assertEquals("https://old.test", TerminalUrl.at(t, 5, -1));
        assertNull(TerminalUrl.at(t, 5, 0));
    }

    @Test public void alternateScreenUsesItsOwnContent() {
        TerminalEmulator t = terminal(40, 4, "https://old.test\033[?1049h\033[Hhttps://tui.test");
        assertEquals("https://tui.test", TerminalUrl.at(t, 5, 0));
    }

    @Test public void invalidCellsAndNonWebTextAreIgnored() {
        TerminalEmulator t = terminal(100, 4, "file:///tmp/a javascript:alert(1) https:///path https://");
        for (int col = 0; col < 100; col++) assertNull(TerminalUrl.at(t, col, 0));
        assertNull(TerminalUrl.at(t, -1, 0));
        assertNull(TerminalUrl.at(t, 100, 0));
        assertNull(TerminalUrl.at(t, 0, -1));
        assertNull(TerminalUrl.at(t, 0, 4));
    }

    @Test public void hugeWrappedOutputDoesNotOpenATruncatedUrl() {
        TerminalEmulator t = terminal(20, 200, "https://example.test/" + "a".repeat(3500));
        assertNull(TerminalUrl.at(t, 4, 0));
    }

    @Test public void ambiguousWideCharacterWrapNeverOpensATruncatedUrl() {
        TerminalEmulator t = terminal(20, 4, "https://example.co/中文");
        assertNull(TerminalUrl.at(t, 0, 0));
        assertNull(TerminalUrl.at(t, 0, 1));
    }
}
