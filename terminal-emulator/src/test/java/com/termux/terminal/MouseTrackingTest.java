package com.termux.terminal;

public class MouseTrackingTest extends TerminalTestCase {

    public void testMoshAnyEventModeEnablesSgrWheels() {
        withTerminalSized(45, 45);
        // Mosh emits only the selected tracking mode, even if tmux enabled all three.
        enterString("\033[?1003l\033[?1002l\033[?1001l\033[?1000l\033[?1006l\033[?1003h\033[?1006h");
        assertTrue(mTerminal.isMouseTrackingActive());
        assertFalse(mTerminal.isAlternateBufferActive());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 20, 10, true);
        assertEquals("\033[<64;20;10M", mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, 20, 10, true);
        assertEquals("\033[<65;20;10M", mOutput.getOutputAndClear());
        enterString("\033[?1003l");
        assertFalse(mTerminal.isMouseTrackingActive());
    }

    public void testTrackingModesReplaceEachOtherWithoutStaleFlags() {
        int[] modes = {1000, 1002, 1003};
        for (int previous : modes) {
            for (int current : modes) {
                if (previous == current) continue;
                withTerminalSized(10, 10);
                enterString("\033[?" + previous + "h\033[?" + current + "h");
                assertTrue(mTerminal.isMouseTrackingActive());
                assertEnteringStringGivesResponse("\033[?" + previous + "$p", "\033[?" + previous + ";2$y");
                assertEnteringStringGivesResponse("\033[?" + current + "$p", "\033[?" + current + ";1$y");
                enterString("\033[?" + previous + "l");
                assertTrue("Resetting the previous mode must not disable the current mode", mTerminal.isMouseTrackingActive());
                enterString("\033[?" + current + "l");
                assertFalse("Disabling the current mode must not revive the previous mode", mTerminal.isMouseTrackingActive());
            }
        }
    }

    public void testAnyEventModeReportsButtonMotion() {
        withTerminalSized(10, 10).enterString("\033[?1003h\033[?1006h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 3, 4, true);
        assertEquals("\033[<32;3;4M", mOutput.getOutputAndClear());
        enterString("\033[?1000h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 3, 4, true);
        assertEquals("", mOutput.getOutputAndClear());
        enterString("\033[?1002h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 3, 4, true);
        assertEquals("\033[<32;3;4M", mOutput.getOutputAndClear());
    }

    public void testResetDisablesAnyEventTracking() {
        withTerminalSized(10, 10).enterString("\033[?1003h\033[?1006h");
        assertTrue(mTerminal.isMouseTrackingActive());
        mTerminal.reset();
        assertFalse(mTerminal.isMouseTrackingActive());
        assertEnteringStringGivesResponse("\033[?1003$p", "\033[?1003;2$y");
    }

    public void testSgrEncodingAloneDoesNotEnableTracking() {
        withTerminalSized(10, 10).enterString("\033[?1006h");
        assertFalse(mTerminal.isMouseTrackingActive());
    }
}
