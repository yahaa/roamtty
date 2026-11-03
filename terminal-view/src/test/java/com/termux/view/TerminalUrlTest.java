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

    @Test public void agentWrappedUrlEndsAtItsShortLastRowWhateverLongLineFollows() {
        // Claude Code: the PR link fills its row, "5" goes below, then a long line starts.
        TerminalEmulator t = terminal(46, 8,
            "  PR: \033[4;33mhttps://github.com/yahaa/roamtty/pull/27\033[0m\r\n  \033[4;33m5\033[0m\r\n" +
            "  安装包：Roamtty-tap-path-20261103-release-de\r\n  bugsigned.apk\r\n");
        assertEquals("https://github.com/yahaa/roamtty/pull/275", TerminalUrl.at(t, 20, 0));
        assertEquals("https://github.com/yahaa/roamtty/pull/275", TerminalUrl.at(t, 2, 1));
        assertArrayEquals(new int[]{0, 6, 46, 1, 2, 3}, TerminalUrl.find(t, 2, 1).spans);
        assertNull(TerminalUrl.at(t, 10, 2));
    }

    @Test public void agentWrappedUrlStopsAtFullWidthTextGluedToItsTail() {
        TerminalEmulator t = terminal(46, 8, "  新版本已可安装：http://100.64.0.2:18780/#and\r\n  roid（77c9886）\r\n");
        assertEquals("http://100.64.0.2:18780/#android", TerminalUrl.at(t, 30, 0));
        assertEquals("http://100.64.0.2:18780/#android", TerminalUrl.at(t, 3, 1));
        assertNull(TerminalUrl.at(t, 8, 1));
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

    @Test public void multipleStyledLinksKeepTheirOwnParenthesesAndCellPositions() {
        TerminalEmulator t = terminal(100, 4,
            "中文 e\u0301 (note) (\033[4mhttps://one.test/a_(b)\033[0m) " +
            "(\033[4mhttps://two.test/c\033[0m)");
        assertEquals("https://one.test/a_(b)", TerminalUrl.at(t, 17, 0));
        assertEquals("https://two.test/c", TerminalUrl.at(t, 48, 0));
        assertNull(TerminalUrl.at(t, 14, 0));
        assertNull(TerminalUrl.at(t, 39, 0));
    }

    @Test public void tuiWrappedLinkLimitsNeverOpenItsPrefix() {
        TerminalEmulator t = terminal(80, 200,
            "(\033[4mhttps://one.test/" + "\033[0m\r\n  \033[4ma".repeat(130) + "\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
        t = terminal(100, 200, "(\033[4mhttps://one.test/" + "a".repeat(8200) + "\033[0m)");
        assertNull(TerminalUrl.at(t, 5, 0));
    }

    @Test public void hardWrappedBareUrlIsWholeFromEitherRow() {
        for (String style : new String[] {"", "\033[4m"}) {
            TerminalEmulator t = terminal(40, 5,
                "  PR: " + style + "https://github.com/yahaa/\033[0m\r\n  " + style + "roamtty/pull/135\033[0m\r\n");
            for (int col = 6; col < 31; col++)
                assertEquals("https://github.com/yahaa/roamtty/pull/135", TerminalUrl.at(t, col, 0));
            for (int col = 2; col < 18; col++)
                assertEquals("https://github.com/yahaa/roamtty/pull/135", TerminalUrl.at(t, col, 1));
            assertNull(TerminalUrl.at(t, 18, 1));
        }
    }

    @Test public void tuiIndentedUnderlinedUrlIsWholeFromEveryRow() {
        // Captured from `agy` login at 44 columns: every row is indented one cell and underlined.
        String[] rows = {
            "https://accounts.google.com/o/oauth2/auth?",
            "access_type=offline&client_id=107100606059",
            "1-tmhssin2h21lcre235vtolojh4g403ep.apps.go",
            "ogleusercontent.com&code_challenge=5h3QeZa",
            "lJ95XrRbFJvWmYgE63kAuG-gHDursxB9rAB4&code_",
            "challenge_method=S256&prompt=consent&redir",
            "ect_uri=https%3A%2F%2Fantigravity.google%2",
            "Foauth-callback&response_type=code&scope=h",
            "ttps%3A%2F%2Fwww.googleapis.com%2Fauth%2Fc",
            "loud-platform+https%3A%2F%2Fwww.googleapis",
            ".com%2Fauth%2Fuserinfo.email+https%3A%2F%2",
            "Fwww.googleapis.com%2Fauth%2Fuserinfo.prof",
            "ile+https%3A%2F%2Fwww.googleapis.com%2Faut",
            "h%2Fcclog+https%3A%2F%2Fwww.googleapis.com",
            "%2Fauth%2Fexperimentsandconfigs+https%3A%2",
            "F%2Fwww.googleapis.com%2Fauth%2Faicode+ope",
            "nid&state=jyiNUdcGeMnVnfmq9LF7Zg"};
        StringBuilder sb = new StringBuilder("\r\n Your browser should open automatically.\r\n\r\n");
        for (String row : rows) sb.append(" \033[4m\033[34m").append(row).append("\033[0m\033[39m\033[49m\r\n");
        sb.append("\r\n If you aren't automatically redirected,\r\n");
        TerminalEmulator t = terminal(44, 40, sb.toString());
        String url = String.join("", rows);
        for (int r = 0; r < rows.length; r++)
            for (int col : new int[] {1, 10, rows[r].length()})
                assertEquals("row " + r + " col " + col, url, TerminalUrl.at(t, col, r + 3));
    }

    @Test public void hardWrappedUrlStopsAtTuiRuleBelowIt() {
        String rule = "\u2500".repeat(44);
        TerminalEmulator t = terminal(44, 8,
            "\033[4mhttps://accounts.google.com/o/oauth2/auth?a\033[0m\r\n\033[4mccess_type=offline&state=abc\033[0m\r\n" + rule + "\r\n");
        for (int row = 0; row < 2; row++)
            assertEquals("https://accounts.google.com/o/oauth2/auth?access_type=offline&state=abc", TerminalUrl.at(t, 3, row));
    }

    @Test public void fullWidthUrlRowJoinsAShortIndentedContinuation() {
        // tmux redraws a Claude Code reply at 45 columns: the URL row fills every cell, so no erase
        // follows it, then a cursor move places the "71" of ".../pull/171" on the next row. The row
        // held soft-wrapped text before (an earlier frame), which must not mark the new row wrapped.
        TerminalEmulator t = terminal(45, 6, "x".repeat(50) + "\033[H" +
            "\u25cf\033[1X\033[CPR:\033[1X\033[Chttps://github.com/yahaa/roamtty/pull/1\033[2;1H \033[1X\033[C71\033[K\r\n\r\n* Brewed");
        for (int row = 0; row < 2; row++)
            assertEquals("https://github.com/yahaa/roamtty/pull/171", TerminalUrl.at(t, row == 0 ? 20 : 2, row));
    }

    @Test public void hardWrappedUrlGluedToALabelIsWholeFromEitherRow() {
        // "PR：" ends in a full-width colon, so no space separates the label from the URL.
        TerminalEmulator t = terminal(45, 4, "  PR\uff1ahttps://github.com/yahaa/roamtty/pull/1\033[2;1H  71\033[K\r\n");
        assertEquals("https://github.com/yahaa/roamtty/pull/171", TerminalUrl.at(t, 20, 0));
        assertEquals("https://github.com/yahaa/roamtty/pull/171", TerminalUrl.at(t, 2, 1));
        assertNull(TerminalUrl.at(t, 2, 0));
    }

    @Test public void hardWrappedUrlIgnoresBlankCellsAfterItsFirstFragment() {
        TerminalEmulator t = terminal(40, 5, "  PR: https://github.com/yahaa/\r\n  roamtty/pull/135\r\n");
        assertNull(TerminalUrl.at(t, 35, 0));
    }

    @Test public void hardWrapFollowedByASoftWrapKeepsTheWholeUrl() {
        String url = "https://github.com/yahaa/roamtty/pull/135/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        TerminalEmulator t = terminal(40, 6, "  PR: https://github.com/yahaa/\r\n  roamtty/pull/135/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
        assertTrue(t.getScreen().getLineWrap(1));
        for (int row = 0; row < 3; row++) assertEquals("row " + row, url, TerminalUrl.at(t, row == 0 ? 10 : 3, row));
    }

    @Test public void hardWrappedUrlJoinsWideContinuationsByCellWidth() {
        TerminalEmulator t = terminal(20, 4, "https://x.test/a/b/c\r\n\u4e2d\u6587");
        // Two free cells and a four-cell continuation: it would not have fitted, so it belongs to the URL.
        assertEquals("https://x.test/a/b/c\u4e2d\u6587", TerminalUrl.at(t, 3, 0));
    }

    @Test public void overlongHardWrappedUrlNeverOpensItsFirstFragment() {
        StringBuilder sb = new StringBuilder("see https://one.test/\r\n");
        for (int i = 0; i < 100; i++) sb.append("  ").append("a".repeat(100)).append("\r\n");
        TerminalEmulator t = terminal(105, 140, sb.toString());
        assertNull(TerminalUrl.at(t, 8, 0));
    }

    @Test public void urlFollowedByShortProseRowIsNotExtended() {
        TerminalEmulator t = terminal(40, 5, "see https://one.test/a\r\nnext line\r\n");
        assertEquals("https://one.test/a", TerminalUrl.at(t, 6, 0));
        assertNull(TerminalUrl.at(t, 1, 1));
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
