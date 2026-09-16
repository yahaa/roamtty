package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;

/** A draggable, fading scrollbar for local terminal history. */
final class TranscriptScrollBar {
    private static final long HIDE_DELAY_MS = 1200;
    private static final long FADE_MS = 250;
    private final TerminalView view;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF thumb = new RectF();
    private final float density;
    private long shownAt = -HIDE_DELAY_MS - FADE_MS;
    private int pointerId = -1;
    private float dragOffset;

    TranscriptScrollBar(TerminalView view) {
        this.view = view;
        density = view.getResources().getDisplayMetrics().density;
    }

    boolean isDragging() { return pointerId != -1; }

    void show() {
        shownAt = SystemClock.uptimeMillis();
        view.invalidate();
        view.postInvalidateDelayed(HIDE_DELAY_MS);
    }

    private int historyRows() {
        return view.mEmulator == null ? 0 : view.mEmulator.getScreen().getActiveTranscriptRows();
    }

    private boolean layoutThumb() {
        int history = historyRows();
        if (history == 0 || view.getHeight() <= 16 * density) return false;
        float track = view.getHeight() - 16 * density;
        float height = Math.min(track, Math.max(32 * density,
            track * view.mEmulator.mRows / (history + view.mEmulator.mRows)));
        float fraction = Math.max(0, Math.min(1, (history + view.getTopRow()) / (float) history));
        float top = 8 * density + fraction * (track - height);
        thumb.set(view.getWidth() - 9 * density, top, view.getWidth() - 3 * density, top + height);
        return true;
    }

    void draw(Canvas canvas) {
        if (!layoutThumb()) return;
        long elapsed = SystemClock.uptimeMillis() - shownAt;
        float alpha = isDragging() ? 1f : Math.max(0, 1f - Math.max(0, elapsed - HIDE_DELAY_MS) / (float) FADE_MS);
        if (alpha == 0) return;
        paint.setColor(0xFF8FA9BC);
        paint.setAlpha(Math.round(45 * alpha));
        canvas.drawRoundRect(thumb.left, 8 * density, thumb.right, view.getHeight() - 8 * density,
            3 * density, 3 * density, paint);
        paint.setAlpha(Math.round(230 * alpha));
        canvas.drawRoundRect(thumb, 3 * density, 3 * density, paint);
        if (!isDragging() && elapsed >= HIDE_DELAY_MS) view.postInvalidateOnAnimation();
    }

    boolean onTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (event.getX() < view.getWidth() - 24 * density || !layoutThumb()) return false;
            view.mScroller.abortAnimation();
            if (view.isSelectingText()) view.stopTextSelectionMode();
            pointerId = event.getPointerId(0);
            dragOffset = event.getY() >= thumb.top && event.getY() <= thumb.bottom
                ? event.getY() - thumb.top : thumb.height() / 2;
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
        return true;
    }

    private void move(float y) {
        if (layoutThumb()) {
            float travel = view.getHeight() - 16 * density - thumb.height();
            float fraction = travel <= 0 ? 1 : Math.max(0, Math.min(1, (y - dragOffset - 8 * density) / travel));
            view.setTopRow(-Math.round(historyRows() * (1 - fraction)));
        }
        show();
    }
}
