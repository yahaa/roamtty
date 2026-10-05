package com.termux.view;

/**
 * IMEs may send ordinary down/up pairs even while held, so use arrival time, not KeyEvent flags.
 * Auto-repeat arrives every ~30-100ms while even fast finger taps are slower, so only a dense stream counts as a hold.
 */
public final class ImeBackspaceTracker {
    public enum Action { DELETE, CLEAR, IGNORE }
    static final long REPEAT_GAP_MS = 150;
    static final long HOLD_MS = 1000;
    private long started = -1;
    private long last = -1;
    private boolean cleared;

    public Action onDelete(long now) {
        // A released key, a tap, and the initial long-press delay all break the repeat stream.
        if (last < 0 || now - last > REPEAT_GAP_MS || now < last) {
            started = now;
            cleared = false;
        }
        last = now;
        if (cleared) return Action.IGNORE;
        if (now - started >= HOLD_MS) {
            cleared = true;
            return Action.CLEAR;
        }
        return Action.DELETE;
    }

    public void reset() {
        started = last = -1;
        cleared = false;
    }
}
