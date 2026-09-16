package com.termux.view;

/** IMEs may send ordinary down/up pairs even while held, so use arrival time, not KeyEvent flags. */
public final class ImeBackspaceTracker {
    public enum Action { DELETE, CLEAR, IGNORE }
    private long started = -1;
    private long last = -1;
    private boolean cleared;

    public Action onDelete(long now) {
        // A released key and the initial long-press delay both break the repeat stream.
        if (last < 0 || now - last > 250 || now < last) {
            started = now;
            cleared = false;
        }
        last = now;
        if (cleared) return Action.IGNORE;
        if (now - started >= 1000) {
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
