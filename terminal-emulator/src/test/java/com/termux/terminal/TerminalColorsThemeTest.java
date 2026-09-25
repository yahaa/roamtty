package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.*;

public class TerminalColorsThemeTest {
    @Test public void themeSwitchIsPerSessionAndKeepsRemoteOverrides() {
        TerminalColors first = new TerminalColors();
        TerminalColors second = new TerminalColors();
        int darkBackground = first.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
        first.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xff123456; // Remote OSC color.

        assertTrue(first.setLightTheme(true));
        assertEquals(0xff2b2a27, first.mCurrentColors[0]);
        assertEquals(second.mCurrentColors[16], first.mCurrentColors[16]);
        assertEquals(0xfff7f5f1, first.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(0xff123456, first.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND]);
        assertEquals(darkBackground, second.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertFalse(first.setLightTheme(true));

        first.reset(TextStyle.COLOR_INDEX_FOREGROUND);
        assertEquals(0xff2b2a27, first.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND]);
        assertTrue(first.setLightTheme(false));
        assertEquals(darkBackground, first.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
    }
}
