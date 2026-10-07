package com.termux.view;

/** Current remote scrollback range, position, and position request callback. */
final class RemoteHistoryState {
    interface PositionListener {
        void onPositionRequested(int position);
    }

    private PositionListener listener;
    private int rows;
    private int position;

    void update(int rows, int position, PositionListener listener) {
        this.rows = listener == null ? 0 : Math.max(0, rows);
        this.position = Math.max(0, Math.min(this.rows, position));
        this.listener = listener;
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
