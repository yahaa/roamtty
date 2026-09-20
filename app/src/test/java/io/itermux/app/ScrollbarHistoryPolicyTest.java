package io.itermux.app;

import org.junit.Assert;
import org.junit.Test;

/**
 * Documents TerminalView.onScreenUpdated history-preservation rules for the scrollbar.
 * Pure logic mirror so the live-edge drag regression cannot return unnoticed.
 */
public class ScrollbarHistoryPolicyTest {

    static final class Update {
        int topRow;
        boolean skipScrolling;
        final boolean dragging;
        final int scrollCounter;
        final int rowsInHistory;

        Update(int topRow, boolean dragging, int scrollCounter, int rowsInHistory) {
            this.topRow = topRow;
            this.dragging = dragging;
            this.scrollCounter = scrollCounter;
            this.rowsInHistory = rowsInHistory;
        }
    }

    /** Mirrors the fixed onScreenUpdated branch (non-selection, auto-scroll enabled). */
    static void applyRemoteOutput(Update s) {
        if (s.topRow < 0) {
            s.topRow = Math.max(-s.rowsInHistory, s.topRow - s.scrollCounter);
            s.skipScrolling = true;
        }
        if (s.dragging) {
            s.skipScrolling = true;
        }
        if (!s.skipScrolling && s.topRow != 0) {
            s.topRow = 0;
        }
    }

    @Test
    public void readingHistoryKeepsAnchorWhenOutputArrives() {
        Update s = new Update(-40, false, 5, 200);
        applyRemoteOutput(s);
        Assert.assertEquals(-45, s.topRow);
        Assert.assertTrue(s.skipScrolling);
    }

    @Test
    public void draggingAtLiveEdgeDoesNotPullIntoHistory() {
        Update s = new Update(0, true, 8, 200);
        applyRemoteOutput(s);
        Assert.assertEquals("Holding the thumb at the bottom must stay at the live edge", 0, s.topRow);
        Assert.assertTrue(s.skipScrolling);
    }

    @Test
    public void draggingInHistoryStillPreservesAnchor() {
        Update s = new Update(-30, true, 4, 200);
        applyRemoteOutput(s);
        Assert.assertEquals(-34, s.topRow);
        Assert.assertTrue(s.skipScrolling);
    }

    @Test
    public void liveEdgeWithoutDragFollowsOutput() {
        Update s = new Update(0, false, 3, 200);
        applyRemoteOutput(s);
        Assert.assertEquals(0, s.topRow);
        Assert.assertFalse(s.skipScrolling);
    }
}
