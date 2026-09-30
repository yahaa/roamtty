package com.termux.view;

/**
 * Defensive planning for IME {@code deleteSurroundingText*} calls.
 * WeChat / Sogou "clear all" often sends {@link Integer#MAX_VALUE} on one or both sides;
 * looping that many DEL key events ANRs the UI thread.
 */
public final class ImeDeleteGuard {
    /** Above this, treat as one-shot clear rather than emitting that many DEL keys. */
    public static final int BULK_CLEAR_THRESHOLD = 256;
    /** Hard cap for any remaining per-call DEL / FORWARD_DEL emissions. */
    public static final int MAX_EMITTED_DELETES = 256;

    public enum Plan { REJECT, CLEAR_ALL, PROCEED }

    private ImeDeleteGuard() {}

    public static Plan plan(int leftLength, int rightLength) {
        if (leftLength < 0 || rightLength < 0) return Plan.REJECT;
        if (leftLength > BULK_CLEAR_THRESHOLD || rightLength > BULK_CLEAR_THRESHOLD) return Plan.CLEAR_ALL;
        return Plan.PROCEED;
    }

    public static int clampEmit(int length) {
        if (length <= 0) return 0;
        return Math.min(length, MAX_EMITTED_DELETES);
    }

    /** Clamp a selection/cursor index into {@code [0, textLength]}. */
    public static int clampIndex(int index, int textLength) {
        if (textLength <= 0) return 0;
        if (index < 0) return 0;
        return Math.min(index, textLength);
    }

    public static int clampLength(int length, int available) {
        if (length <= 0 || available <= 0) return 0;
        return Math.min(length, available);
    }
}
