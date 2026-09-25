package com.termux.view;

/** Current remote scrollback range, position, and position request callback. */
final class RemoteHistoryState {
    interface PositionListener {
        void onPositionRequested(int position);
    }

    private PositionListener listener;
    private int rows;
    private int position;
    private Integer reportedWhileDragging;

    /** Keep the displayed position stable while the user drags the scrollbar. */
    boolean update(int rows, int position, PositionListener listener, boolean dragging) {
        int nextRows = listener == null ? 0 : Math.max(0, rows);
        int nextPosition = Math.max(0, Math.min(nextRows, position));
        boolean changed = (this.listener == null) != (listener == null) ||
            this.rows != nextRows || this.position != nextPosition;

        this.listener = listener;
        this.rows = nextRows;
        if (dragging) reportedWhileDragging = nextPosition;
        else {
            this.position = nextPosition;
            reportedWhileDragging = null;
        }
        return changed;
    }

    /** Apply the most recent remote position once the thumb is released. */
    void finishDrag() {
        if (reportedWhileDragging != null) {
            position = reportedWhileDragging;
            reportedWhileDragging = null;
        }
    }

    boolean isActive() { return listener != null; }
    int rows() { return rows; }
    int position() { return position; }

    boolean requestPosition(int requestedPosition) {
        if (listener == null) return false;
        int target = Math.max(0, Math.min(rows, requestedPosition));
        if (target == position) return false;
        position = target;
        listener.onPositionRequested(target);
        return true;
    }
}
