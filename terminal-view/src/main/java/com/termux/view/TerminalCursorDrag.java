package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Long-press scrubbing sends normal terminal arrows, without modifying the remote screen locally. */
final class TerminalCursorDrag {
    private final TerminalView view;
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF lens = new RectF();
    private TerminalTextLine line;
    private float sourceX, sourceY;
    private int lastCell, sentStep;
    private boolean enabled;
    private boolean alternateBuffer;

    TerminalCursorDrag(TerminalView view) { this.view = view; }
    boolean isActive() { return line != null; }
    void setEnabled(boolean enabled) { this.enabled = enabled; if (!enabled) finish(); }

    boolean start(MotionEvent event) {
        if (!enabled || view.mTermSession == null || view.mTermSession.isReadOnly() ||
            view.getTopRow() != 0 || view.hasRemoteHistory() ||
            !view.mEmulator.isCursorEnabled() || event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return false;
        int row = view.textRowAt(event.getY());
        TerminalTextLine current = TerminalTextLine.at(view.mEmulator, view.mEmulator.getCursorRow());
        if (row < current.firstRow || row > current.lastRow) return false;
        line = current;
        alternateBuffer = view.mEmulator.isAlternateBufferActive();
        int initialCell = line.cellAt(view.mEmulator.getCursorCol(), view.mEmulator.getCursorRow());
        lastCell = Math.max(initialCell, line.contentEndCell());
        sentStep = line.cursorStepAt(initialCell);
        view.requestFocus();
        if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
        moveTo(event);
        return true;
    }

    boolean onTouch(MotionEvent event) {
        if (!isActive()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() != 1) { finish(); return true; }
                moveTo(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                finish();
                return true;
            default:
                return true;
        }
    }

    private void moveTo(MotionEvent event) {
        int row = Math.max(Math.max(0, line.firstRow), Math.min(line.lastRow, view.textRowAt(event.getY())));
        int column = Math.max(0, Math.min(line.columns - 1, Math.round(event.getX() / view.mRenderer.mFontWidth)));
        int targetCell = Math.max(0, Math.min(lastCell, line.cellAt(column, row)));
        int step = line.cursorStepAt(targetCell);
        int delta = step - sentStep;
        // Use the gesture's last requested position, not delayed SSH echo, to avoid repeated arrows.
        sentStep = step;
        for (int i = 0; i < Math.abs(delta); i++)
            view.handleKeyCode(delta < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, 0);
        sourceX = (targetCell % line.columns) * view.mRenderer.mFontWidth;
        sourceY = rowCenter(line.firstRow + targetCell / line.columns);
        view.invalidate();
    }

    private float rowCenter(int row) {
        return view.mRenderer.mFontLineSpacingAndAscent + (row - view.getTopRow() + 0.5f) * view.mRenderer.mFontLineSpacing;
    }

    void finish() {
        if (line == null) return;
        line = null;
        if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
        view.invalidate();
    }

    void onScreenUpdated() {
        if (line != null && (view.hasRemoteHistory() || alternateBuffer != view.mEmulator.isAlternateBufferActive() ||
            view.mEmulator.getScrollCounter() > 0 || !view.mEmulator.isCursorEnabled() ||
            view.mEmulator.getCursorRow() < line.firstRow || view.mEmulator.getCursorRow() > line.lastRow)) finish();
    }

    void draw(Canvas canvas) {
        if (!isActive()) return;
        float density = view.getResources().getDisplayMetrics().density;
        float width = Math.min(160 * density, view.getWidth() - 8 * density);
        float height = Math.min(56 * density, view.getHeight() - 8 * density);
        if (width <= 0 || height <= 0) return;
        float left = Math.max(4 * density, Math.min(view.getWidth() - width - 4 * density, sourceX - width / 2));
        float top = sourceY - height - 36 * density;
        if (top < 4 * density) top = sourceY + 36 * density;
        top = Math.max(4 * density, Math.min(view.getHeight() - height - 4 * density, top));
        lens.set(left, top, left + width, top + height);
        clip.reset(); clip.addRoundRect(lens, 14 * density, 14 * density, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.translate(lens.centerX(), lens.centerY());
        canvas.scale(1.8f, 1.8f);
        canvas.translate(-sourceX, -sourceY);
        // Render directly so the lens also works with secure windows and Android 8.
        view.mRenderer.render(view.mEmulator, canvas, view.getTopRow(), -1, -1, -1, -1);
        canvas.restore();
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * density);
        border.setColor(0xFF8E9AAF);
        canvas.drawRoundRect(lens, 14 * density, 14 * density, border);
    }
}
