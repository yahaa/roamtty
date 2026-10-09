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

    @Test public void tuiWrappedPrUrlIsWholeFromEitherDisplayedRow() {
        TerminalEmulator t = terminal(40, 5,
            "  PR: \033[4m#127\033[0m (\033[4mhttps://github.com/yahaa/\033[0m\r\n" +
            "  \033[4mroamtty/pull/127\033[0m)\r\n  APK: ready");
        assertFalse(t.getScreen().getLineWrap(0));
        for (int col = 12; col < 37; col++)
            assertEquals("https://github.com/yahaa/roamtty/pull/127", TerminalUrl.at(t, col, 0));
        for (int col = 2; col < 18; col++)
            assertEquals("https://github.com/yahaa/roamtty/pull/127", TerminalUrl.at(t, col, 1));
        assertNull(TerminalUrl.at(t, 11, 0));
        assertNull(TerminalUrl.at(t, 37, 0));
        assertNull(TerminalUrl.at(t, 0, 1));
        assertNull(TerminalUrl.at(t, 18, 1));
    }

    @Test public void tuiCursorPositionedUrlKeepsItsQueryAndFragment() {
        TerminalEmulator t = terminal(40, 5,
            "\033[1;3HPR: (\033[4mhttps://github.com/yahaa/\033[0m" +
            "\033[2;3H\033[4mroamtty/pull/127?x=1&y=2#discussion\033[0m)");
        assertEquals("https://github.com/yahaa/roamtty/pull/127?x=1&y=2#discussion", TerminalUrl.at(t, 10, 1));
    }

    @Test public void tuiWrappedUrlAllowsColorChangesAndWideCharacters() {
        TerminalEmulator t = terminal(40, 5,
            "中文 (\033[4;31mhttps://example.test/\033[0m\r\n" +
            "  \033[4;32m中文/path\033[0m)");
        assertEquals("https://example.test/中文/path", TerminalUrl.at(t, 8, 0));
        assertEquals("https://example.test/中文/path", TerminalUrl.at(t, 2, 1));
        assertEquals("https://example.test/中文/path", TerminalUrl.at(t, 3, 1));
    }

    @Test public void tuiWrappedUrlKeepsCombiningMarksAtTheLastColumn() {
        TerminalEmulator t = terminal(17, 5,
            "(\033[4mhttps://t.test/e\u0301\033[0m\r\n  \033[4m/tail\033[0m)");
        assertEquals("https://t.test/e\u0301/tail", TerminalUrl.at(t, 16, 0));
        assertEquals("https://t.test/e\u0301/tail", TerminalUrl.at(t, 3, 1));
    }

    @Test public void tuiWrappedUrlNeverConsumesAnotherUrl() {
        TerminalEmulator t = terminal(40, 5,
            "(\033[4mhttps://one.test/\033[0m\r\n  \033[4mhttps://two.test/path\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
        assertEquals("https://two.test/path", TerminalUrl.at(t, 8, 1));
        t = terminal(40, 5,
            "(\033[4mhttps://one.test/\033[0m\r\n  \033[4mhttps://\033[0m\r\n" +
            "  \033[4mtwo.test/path\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
        assertNull(TerminalUrl.at(t, 5, 1));
    }

    @Test public void incompleteOrBrokenStyledLinkNeverOpensItsPrefix() {
        for (String tail : new String[] {
            "  \033[4mpath\033[0m", "  plain prose)", "  \033[4mpath has spaces\033[0m)",
            "\r\n  \033[4mpath\033[0m)"
        }) {
            TerminalEmulator t = terminal(40, 6, "(\033[4mhttps://one.test/\033[0m\r\n" + tail);
            assertNull(tail, TerminalUrl.at(t, 5, 0));
        }
    }

    @Test public void styledProseDoesNotHideAnIndependentUrl() {
        TerminalEmulator t = terminal(60, 4, "(\033[4msee https://one.test\033[0m)");
        assertEquals("https://one.test", TerminalUrl.at(t, 9, 0));
    }

    @Test public void tuiWrappedLinkSurvivesInScrollback() {
        TerminalEmulator t = terminal(40, 3,
            "(\033[4mhttps://github.com/\033[0m\r\n  \033[4myahaa/roamtty/\033[0m\r\n" +
            "  \033[4mpull/127\033[0m)\r\none\r\ntwo\r\nthree");
        assertEquals("https://github.com/yahaa/roamtty/pull/127", TerminalUrl.at(t, 5, -3));
        assertEquals("https://github.com/yahaa/roamtty/pull/127", TerminalUrl.at(t, 5, -1));
    }

    @Test public void styledSoftWrapDoesNotDiscardRealOrAmbiguousSpaces() {
        TerminalEmulator t = terminal(21, 5, "(\033[4mhttps://example.co/中文\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
        t = terminal(20, 5, "(\033[4mhttps://one.test/a   b\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
    }

    @Test public void closedStyledLinkDoesNotConsumeTheFollowingRow() {
        TerminalEmulator t = terminal(40, 5,
            "(\033[4mhttps://one.test/\033[0m)\r\n  \033[4mnext\033[0m)");
        assertEquals("https://one.test/", TerminalUrl.at(t, 5, 0));
        assertNull(TerminalUrl.at(t, 3, 1));
    }

    @Test public void tuiWrappedLinkLimitsNeverOpenItsPrefix() {
        TerminalEmulator t = terminal(80, 200,
            "(\033[4mhttps://one.test/" + "\033[0m\r\n  \033[4ma".repeat(130) + "\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
        t = terminal(100, 200, "(\033[4mhttps://one.test/" + "a".repeat(8200) + "\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
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

    @Test public void apostrophesRemainPartOfUrlPathsQueriesAndFragments() {
        for (String url : new String[] {
            "https://example.test/Guns_N'_Roses", "https://example.test/rockin'",
            "https://example.test/?name=O'Brien#it's-here"
        }) {
            TerminalEmulator t = terminal(100, 4, "see " + url + " end");
            for (int col = 4; col < 4 + url.length(); col++) assertEquals(url, TerminalUrl.at(t, col, 0));
            assertNull(TerminalUrl.at(t, 4 + url.length(), 0));
        }
    }

    @Test public void enclosingSingleQuotesAreExcludedButInnerApostrophesRemain() {
        String url = "https://example.test/Guns_N'_Roses";
        TerminalEmulator t = terminal(100, 4, "see '" + url + "'. end");
        for (int col = 5; col < 5 + url.length(); col++) assertEquals(url, TerminalUrl.at(t, col, 0));
        assertNull(TerminalUrl.at(t, 4, 0));
        assertNull(TerminalUrl.at(t, 5 + url.length(), 0));
        assertNull(TerminalUrl.at(t, 6 + url.length(), 0));
        t = terminal(100, 4, "'https://example.test/path!' and ('https://two.test/rockin'')");
        assertEquals("https://example.test/path!", TerminalUrl.at(t, 4, 0));
        assertEquals("https://two.test/rockin'", TerminalUrl.at(t, 39, 0));
    }

    @Test public void apostrophesSurviveAutomaticAndTuiLineWrapping() {
        TerminalEmulator t = terminal(20, 5, "https://example.test/Guns_N'_Roses");
        assertEquals("https://example.test/Guns_N'_Roses", TerminalUrl.at(t, 8, 1));
        t = terminal(40, 5, "(\033[4mhttps://example.test/Guns_N\033[0m\r\n  \033[4m'_Roses\033[0m)");
        assertEquals("https://example.test/Guns_N'_Roses", TerminalUrl.at(t, 2, 0));
        assertEquals("https://example.test/Guns_N'_Roses", TerminalUrl.at(t, 2, 1));
    }

    @Test public void adjacentQuotedItemsDoNotBecomeOneUrl() {
        for (String separator : new String[] {",", ";"}) {
            String text = "['https://one.test/a'" + separator + "'https://two.test/b']";
            TerminalEmulator t = terminal(100, 4, text);
            assertEquals("https://one.test/a", TerminalUrl.at(t, 5, 0));
            assertEquals("https://two.test/b", TerminalUrl.at(t, text.indexOf("https://two") + 5, 0));
            assertNull(TerminalUrl.at(t, text.indexOf(separator), 0));
        }
        TerminalEmulator t = terminal(100, 4, "['https://one.test/a','plain text']");
        assertEquals("https://one.test/a", TerminalUrl.at(t, 5, 0));
        assertNull(TerminalUrl.at(t, 25, 0));
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
