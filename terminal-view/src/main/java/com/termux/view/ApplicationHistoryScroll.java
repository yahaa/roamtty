package com.termux.view;

/** Translate finger movement to page keys without sending a page for every pixel/row. */
final class ApplicationHistoryScroll {
    private int pendingRows;

    int pages(int rowsDown, int screenRows) {
        if (rowsDown == 0) return 0;
        if (Integer.signum(rowsDown) != Integer.signum(pendingRows)) pendingRows = 0;
        pendingRows += rowsDown;
        int threshold = Math.max(1, screenRows / 3);
        int pages = pendingRows / threshold;
        pendingRows %= threshold;
        return pages;
    }

    void reset() { pendingRows = 0; }
}
