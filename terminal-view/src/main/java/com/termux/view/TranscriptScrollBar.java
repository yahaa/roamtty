package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * A draggable, fading scrollbar for local terminal history.
 *
 * Visual thumb is 6dp wide; the hit target is the rightmost 24dp. Idle 1200ms then
 * fade over 250ms. Hit-testing stays active after fade so the bar can be grabbed
 * while invisible; dragging never yields to the terminal gesture scroller.
 */
final class TranscriptScrollBar {
    static final long HIDE_DELAY_MS = 1200;
    static final long FADE_MS = 250;
    static final float BAR_WIDTH_DP = 6f;
    static final float HIT_WIDTH_DP = 24f;
    static final float MIN_THUMB_DP = 32f;
    static final float TRACK_INSET_DP = 8f;

    private final TerminalView view;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF thumb = new RectF();
    private final float density;
    private final Runnable hideTick;
    private long shownAt = -HIDE_DELAY_MS - FADE_MS;
    private int pointerId = -1;
    private float dragOffset;

    TranscriptScrollBar(TerminalView view) {
        this.view = view;
        density = view.getResources().getDisplayMetrics().density;
        hideTick = view::invalidate;
    }

    boolean isDragging() {
        return pointerId != -1;
    }

    void show() {
        shownAt = SystemClock.uptimeMillis();
        view.removeCallbacks(hideTick);
        view.invalidate();
        // Kick the fade animation once idle time elapses; draw() continues it.
        view.postDelayed(hideTick, HIDE_DELAY_MS);
    }

    /** Map transcript position to thumb location in [0, 1] (0 = oldest, 1 = live edge). */
    static float scrollFraction(int historyRows, int topRow) {
        if (historyRows <= 0) return 1f;
        return Math.max(0f, Math.min(1f, (historyRows + topRow) / (float) historyRows));
    }

    /** Inverse of {@link #scrollFraction(int, int)}; result is clamped to [-history, 0]. */
    static int topRowForFraction(int historyRows, float fraction) {
        if (historyRows <= 0) return 0;
        float clamped = Math.max(0f, Math.min(1f, fraction));
        return Math.max(-historyRows, Math.min(0, -Math.round(historyRows * (1f - clamped))));
    }

    /** Visibility multiplier: 1 while dragging / freshly shown, then linear fade to 0. */
    static float visibilityAlpha(long elapsedMs, boolean dragging) {
        if (dragging) return 1f;
        return Math.max(0f, 1f - Math.max(0L, elapsedMs - HIDE_DELAY_MS) / (float) FADE_MS);
    }

    private int historyRows() {
        return view.mEmulator == null ? 0 : view.mEmulator.getScreen().getActiveTranscriptRows();
    }

    private boolean layoutThumb() {
        int history = historyRows();
        if (history == 0 || view.getHeight() <= 2 * TRACK_INSET_DP * density) return false;
        float track = view.getHeight() - 2 * TRACK_INSET_DP * density;
        float height = Math.min(track, Math.max(MIN_THUMB_DP * density,
            track * view.mEmulator.mRows / (float) (history + view.mEmulator.mRows)));
        float fraction = scrollFraction(history, view.getTopRow());
        float top = TRACK_INSET_DP * density + fraction * (track - height);
        float right = view.getWidth() - 3 * density;
        thumb.set(right - BAR_WIDTH_DP * density, top, right, top + height);
        return true;
    }

    void draw(Canvas canvas) {
        if (!layoutThumb()) return;
        long elapsed = SystemClock.uptimeMillis() - shownAt;
        float alpha = visibilityAlpha(elapsed, isDragging());
        if (alpha == 0f) return;
        paint.setColor(0xFF8FA9BC);
        paint.setAlpha(Math.round(45 * alpha));
        canvas.drawRoundRect(thumb.left, TRACK_INSET_DP * density, thumb.right,
            view.getHeight() - TRACK_INSET_DP * density, 3 * density, 3 * density, paint);
        paint.setAlpha(Math.round(230 * alpha));
        canvas.drawRoundRect(thumb, 3 * density, 3 * density, paint);
        if (!isDragging() && elapsed >= HIDE_DELAY_MS) view.postInvalidateOnAnimation();
    }

    boolean onTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Hit-test the 24dp strip whenever history exists — even after fade-out.
            if (event.getX() < view.getWidth() - HIT_WIDTH_DP * density || !layoutThumb()) return false;
            view.mScroller.abortAnimation();
            if (view.isSelectingText()) view.stopTextSelectionMode();
            pointerId = event.getPointerId(0);
            dragOffset = event.getY() >= thumb.top && event.getY() <= thumb.bottom
                ? event.getY() - thumb.top : thumb.height() / 2f;
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            move(event.getY());
            return true;
        }
        if (!isDragging()) return false;
        int index = event.findPointerIndex(pointerId);
        if (action == MotionEvent.ACTION_MOVE && index >= 0) move(event.getY(index));
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || index < 0 ||
            (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointerId)) {
            pointerId = -1;
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
            show();
        }
        // Consume the rest of the gesture so TerminalView's gesture scroller cannot fight the thumb.
        return true;
    }

    private void move(float y) {
        if (layoutThumb()) {
            float travel = view.getHeight() - 2 * TRACK_INSET_DP * density - thumb.height();
            float fraction = travel <= 0 ? 1f
                : Math.max(0f, Math.min(1f, (y - dragOffset - TRACK_INSET_DP * density) / travel));
            view.setTopRow(topRowForFraction(historyRows(), fraction));
        }
        show();
    }
}
