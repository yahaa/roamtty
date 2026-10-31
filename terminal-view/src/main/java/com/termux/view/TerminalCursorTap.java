package com.termux.view;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.termux.terminal.TerminalEmulator;

/**
 * Roamtty: a tap on typed text moves the cursor there with arrow keys, like tapping into a text field.
 * Within the cursor's own line that is a run of ←/→. In a multi-line input box (see {@link #inputRows}) a tap on
 * another row first sends ↑/↓, waits for the app to redraw the cursor on that row, then sends ←/→ from wherever
 * the app put it, since every editor keeps its own column memory.
 */
final class TerminalCursorTap {
    /** A box taller than this is more likely output than an input box. */
    static final int MAX_INPUT_ROWS = 24;
    private static final long ECHO_TIMEOUT_MS = 1500;

    private final TerminalView view;
    private boolean enabled;
    private int pendingRow = -1;
    private float pendingX;
    private long pendingDeadline;

    TerminalCursorTap(TerminalView view) { this.view = view; }

    void setEnabled(boolean enabled) { this.enabled = enabled; cancel(); }

    void cancel() { pendingRow = -1; }

    /** Returns whether the tap landed on the input and arrows were or will be sent. */
    boolean tapTo(MotionEvent event) {
        cancel();
        TerminalEmulator emulator = view.mEmulator;
        if (!enabled || view.mTermSession == null || emulator == null || view.getTopRow() != 0 ||
            !emulator.isCursorEnabled() || event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int row = view.textRowAt(event.getY());
        int cursorRow = emulator.getCursorRow();
        TerminalTextLine current = TerminalTextLine.at(emulator, cursorRow);
        if (row >= current.firstRow && row <= current.lastRow) {
            moveAlong(current, emulator.getCursorCol(), cursorRow, row, event.getX());
            return true;
        }
        int[] input = inputRows(emulator, cursorRow);
        if (input == null || row < input[0] || row > input[1]) return false;
        sendArrows(row - cursorRow, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN);
        pendingRow = row;
        pendingX = event.getX();
        pendingDeadline = SystemClock.uptimeMillis() + ECHO_TIMEOUT_MS;
        return true;
    }

    /** Finishes a pending row change once the app has drawn the cursor on the tapped row. */
    void onScreenUpdated() {
        if (pendingRow < 0) return;
        TerminalEmulator emulator = view.mEmulator;
        if (emulator == null || emulator.getScrollCounter() > 0 || !emulator.isCursorEnabled() ||
            SystemClock.uptimeMillis() > pendingDeadline) { cancel(); return; }
        int row = pendingRow;
        if (emulator.getCursorRow() != row) return;
        cancel();
        moveAlong(TerminalTextLine.at(emulator, row), emulator.getCursorCol(), row, row, pendingX);
    }

    /** Sends ←/→ from the cursor at ({@code cursorColumn}, {@code cursorRow}) to x on {@code row}, never past the text's end. */
    private void moveAlong(TerminalTextLine line, int cursorColumn, int cursorRow, int row, float x) {
        int cursorCell = line.cellAt(cursorColumn, cursorRow);
        int column = Math.max(0, Math.min(line.columns - 1, Math.round(x / view.mRenderer.mFontWidth)));
        int target = Math.max(0, Math.min(Math.max(cursorCell, line.contentEndCell()), line.cellAt(column, row)));
        sendArrows(line.cursorStepAt(target) - line.cursorStepAt(cursorCell), KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    private void sendArrows(int delta, int negative, int positive) {
        for (int i = 0; i < Math.abs(delta); i++) view.handleKeyCode(delta < 0 ? negative : positive, 0);
    }

    /**
     * The rows {top, bottom} of the multi-line input box holding the cursor, or null when the cursor is not in one.
     * Agent TUIs fence their input: Claude Code between ─ rules, Codex between blank rows, with a status line below.
     * So the box is the run of text rows around the cursor that has a rule or blank row on both sides and something
     * drawn just below. A shell prompt has nothing below it, so ↑ there (history) is never sent.
     */
    static int[] inputRows(TerminalEmulator emulator, int cursorRow) {
        int rows = emulator.mRows;
        int top = cursorRow, bottom = cursorRow;
        while (top > 0 && isText(emulator, top - 1)) if (cursorRow - --top >= MAX_INPUT_ROWS) return null;
        while (bottom < rows - 1 && isText(emulator, bottom + 1)) if (++bottom - top >= MAX_INPUT_ROWS) return null;
        if (top == 0 || bottom == rows - 1) return null;
        boolean fencedBelow = isRule(emulator, bottom + 1) ||
            (bottom + 2 < rows && !isBlank(emulator, bottom + 2)) || (bottom + 3 < rows && !isBlank(emulator, bottom + 3));
        return top < bottom && fencedBelow ? new int[] { top, bottom } : null;
    }

    private static String rowText(TerminalEmulator emulator, int row) {
        return emulator.getScreen().getSelectedText(0, row, emulator.mColumns, row);
    }

    private static boolean isBlank(TerminalEmulator emulator, int row) { return rowText(emulator, row).trim().isEmpty(); }

    private static boolean isText(TerminalEmulator emulator, int row) { return !isBlank(emulator, row) && !isRule(emulator, row); }

    /** A row drawn mostly with box-drawing characters (U+2500-U+257F), like ──── or ╭────╮. */
    static boolean isRule(TerminalEmulator emulator, int row) {
        String text = rowText(emulator, row);
        int drawn = 0, box = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            drawn++;
            if (c >= '─' && c <= '╿') box++;
        }
        return drawn >= 8 && box * 5 >= drawn * 4;
    }
}
