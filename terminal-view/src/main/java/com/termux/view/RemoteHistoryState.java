package com.termux.view;

/** Current remote scrollback range, position, and position request callback. */
final class RemoteHistoryState {
    interface PositionListener {
        void onPositionRequested(int position);
    }

    private PositionListener listener;
    private int rows;
    private int position;

    boolean update(int rows, int position, PositionListener listener) {
        int nextRows = listener == null ? 0 : Math.max(0, rows);
        int nextPosition = Math.max(0, Math.min(nextRows, position));
        boolean changed = (this.listener == null) != (listener == null) ||
            this.rows != nextRows || this.position != nextPosition;

        this.listener = listener;
        this.rows = nextRows;
        this.position = nextPosition;
        return changed;
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
