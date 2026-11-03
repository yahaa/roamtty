package com.termux.view;

import com.termux.terminal.SilentTerminalClient;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalPathTest {
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

    @Test public void compilerErrorOpensAtItsLine() {
        TerminalEmulator t = terminal(80, 4, "src/Post.tsx:42:7 - error TS2322: Type");
        for (int col = 0; col < 17; col++) {
            TerminalLink link = TerminalPath.find(t, col, 0);
            assertEquals("src/Post.tsx", link.text);
            assertEquals(42, link.line);
            assertFalse(link.isUrl);
            assertArrayEquals(new int[]{0, 0, 17}, link.spans);
        }
        assertNull(TerminalPath.find(t, 17, 0));
        assertNull(TerminalPath.find(t, 20, 0));
    }

    @Test public void stackTracesAndQuotesKeepOnlyThePath() {
        TerminalEmulator t = terminal(80, 4, "    at run (/home/v/app/index.js:10:5)\r\n  File \"/srv/a.py\", line 3");
        TerminalLink trace = TerminalPath.find(t, 20, 0);
        assertEquals("/home/v/app/index.js", trace.text);
        assertEquals(10, trace.line);
        assertArrayEquals(new int[]{0, 12, 37}, trace.spans);
        TerminalLink python = TerminalPath.find(t, 10, 1);
        assertEquals("/srv/a.py", python.text);
        assertEquals(0, python.line);
    }

    @Test public void bareNamesNeedAnExtension() {
        TerminalEmulator t = terminal(80, 4, "edit README.md, Makefile and v2.0 or 1.2.3 then ./run done.");
        assertEquals("README.md", TerminalPath.find(t, 6, 0).text);
        assertArrayEquals(new int[]{0, 5, 14}, TerminalPath.find(t, 6, 0).spans);
        assertNull(TerminalPath.find(t, 17, 0));
        assertNull(TerminalPath.find(t, 30, 0));
        assertNull(TerminalPath.find(t, 37, 0));
        assertEquals("./run", TerminalPath.find(t, 50, 0).text);
        assertNull(TerminalPath.find(t, 0, 0));
    }

    @Test public void urlsAndFoldersAreNotFiles() {
        TerminalEmulator t = terminal(80, 4, "see http://x.test/a.js and ~/code/ and 12:30");
        assertNull(TerminalPath.find(t, 10, 0));
        assertNull(TerminalPath.find(t, 30, 0));
        assertNull(TerminalPath.find(t, 41, 0));
    }

    @Test public void softWrappedPathMarksEveryRow() {
        TerminalEmulator t = terminal(10, 4, "x app/src/main/Long.kt:9 y");
        TerminalLink link = TerminalPath.find(t, 3, 1);
        assertEquals("app/src/main/Long.kt", link.text);
        assertEquals(9, link.line);
        assertArrayEquals(new int[]{0, 2, 10, 1, 0, 10, 2, 0, 4}, link.spans);
        assertTrue(link.coversRow(2));
        assertFalse(link.coversRow(3));
    }

    @Test public void pathATuiBrokeAcrossRowsIsJoinedWithThePieceAsFallback() {
        // Like a coding agent's output: a newline and indentation inside a long path, no soft wrap.
        // The first row fills all 30 columns, the second stops one short at the TUI's margin.
        TerminalEmulator t = terminal(30, 6, "  app/src/main/kotlin/io/iterm\r\n  ux/app/ui/MobileTerminalCli\r\n  ent.kt:51:5 done\r\nnext");
        assertFalse(t.getScreen().getLineWrap(0));
        assertFalse(t.getScreen().getLineWrap(1));
        for (int row = 0; row < 3; row++) {
            TerminalLink link = TerminalPath.find(t, 4, row);
            assertEquals("app/src/main/kotlin/io/itermux/app/ui/MobileTerminalClient.kt", link.text);
            assertEquals(51, link.line);
            assertArrayEquals(new int[]{0, 2, 30, 1, 2, 29, 2, 2, 13}, link.spans);
            assertNotNull(link.fallback);
        }
        assertEquals("app/src/main/kotlin/io/iterm", TerminalPath.find(t, 4, 0).fallback.text);
        assertEquals("ux/app/ui/MobileTerminalCli", TerminalPath.find(t, 4, 1).fallback.text);
        assertNull(TerminalPath.find(t, 15, 2));
    }

    @Test public void wordWrappedPathIsNotGluedToTheWordBeforeIt() {
        TerminalEmulator t = terminal(40, 4, "  Edited the files and then\r\n  docs/design/dev-bridge-145-v2.html\r\n");
        TerminalLink link = TerminalPath.find(t, 5, 1);
        assertEquals("docs/design/dev-bridge-145-v2.html", link.text);
        assertNull(link.fallback);
        assertArrayEquals(new int[]{1, 2, 36}, link.spans);
    }

    @Test public void separateShortLinesAreNotJoined() {
        TerminalEmulator t = terminal(40, 4, "src/a.ts\r\nsrc/b.ts\r\n");
        assertEquals("src/b.ts", TerminalPath.find(t, 2, 1).text);
        assertNull(TerminalPath.find(t, 2, 1).fallback);
    }

    @Test public void wideTextBeforeAPathShiftsItsCells() {
        TerminalEmulator t = terminal(40, 4, "修改了 src/a.ts 文件");
        TerminalLink link = TerminalPath.find(t, 9, 0);
        assertEquals("src/a.ts", link.text);
        assertArrayEquals(new int[]{0, 7, 15}, link.spans);
    }

    @Test public void urlSpansFollowTheLink() {
        TerminalEmulator t = terminal(100, 4, "see https://one.test/a. end");
        assertArrayEquals(new int[]{0, 4, 22}, TerminalUrl.find(t, 6, 0).spans);
        TerminalEmulator wrapped = terminal(40, 5,
            "  PR: \033[4m#127\033[0m (\033[4mhttps://github.com/yahaa/\033[0m\r\n" +
            "  \033[4mroamtty/pull/127\033[0m)\r\n  APK: ready");
        assertArrayEquals(new int[]{0, 12, 37, 1, 2, 18}, TerminalUrl.find(wrapped, 2, 1).spans);
    }
}
