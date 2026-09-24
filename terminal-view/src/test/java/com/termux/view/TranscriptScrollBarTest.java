package com.termux.view;

import org.junit.Assert;
import org.junit.Test;

/** Headless coverage for scrollbar mapping, clamping, and fade timing. */
public class TranscriptScrollBarTest {

    @Test
    public void scrollFractionMapsLiveEdgeAndOldestHistory() {
        Assert.assertEquals(1f, TranscriptScrollBar.scrollFraction(100, 0), 0f);
        Assert.assertEquals(0f, TranscriptScrollBar.scrollFraction(100, -100), 0f);
        Assert.assertEquals(0.5f, TranscriptScrollBar.scrollFraction(100, -50), 0.0001f);
    }

    @Test
    public void scrollFractionWithoutHistoryIsLiveEdge() {
        Assert.assertEquals(1f, TranscriptScrollBar.scrollFraction(0, 0), 0f);
        Assert.assertEquals(1f, TranscriptScrollBar.scrollFraction(-1, -5), 0f);
    }

    @Test
    public void topRowForFractionIsInverseAndClamped() {
        Assert.assertEquals(0, TranscriptScrollBar.topRowForFraction(100, 1f));
        Assert.assertEquals(-100, TranscriptScrollBar.topRowForFraction(100, 0f));
        Assert.assertEquals(-50, TranscriptScrollBar.topRowForFraction(100, 0.5f));
        Assert.assertEquals(0, TranscriptScrollBar.topRowForFraction(100, 2f));
        Assert.assertEquals(-100, TranscriptScrollBar.topRowForFraction(100, -1f));
        Assert.assertEquals(0, TranscriptScrollBar.topRowForFraction(0, 0.3f));
    }

    @Test
    public void fractionRoundTripPreservesTopRow() {
        for (int history : new int[] {1, 17, 160, 5000}) {
            for (int top = -history; top <= 0; top += Math.max(1, history / 20)) {
                float fraction = TranscriptScrollBar.scrollFraction(history, top);
                Assert.assertEquals("history=" + history + " top=" + top,
                    top, TranscriptScrollBar.topRowForFraction(history, fraction));
            }
        }
    }

    @Test
    public void visibilityStaysOpaqueWhileDraggingEvenAfterIdle() {
        Assert.assertEquals(1f,
            TranscriptScrollBar.visibilityAlpha(HIDE_PAST_FADE, true), 0f);
    }

    @Test
    public void visibilityFadesAfterIdleDelay() {
        Assert.assertEquals(1f,
            TranscriptScrollBar.visibilityAlpha(0, false), 0f);
        Assert.assertEquals(1f,
            TranscriptScrollBar.visibilityAlpha(TranscriptScrollBar.HIDE_DELAY_MS, false), 0f);
        Assert.assertEquals(0.5f,
            TranscriptScrollBar.visibilityAlpha(
                TranscriptScrollBar.HIDE_DELAY_MS + TranscriptScrollBar.FADE_MS / 2, false), 0.01f);
        Assert.assertEquals(0f,
            TranscriptScrollBar.visibilityAlpha(HIDE_PAST_FADE, false), 0f);
    }

    /** Fade must finish so a later drag can start from a fully-hidden bar. */
    @Test
    public void fullyFadedAlphaIsExactlyZero() {
        long elapsed = TranscriptScrollBar.HIDE_DELAY_MS + TranscriptScrollBar.FADE_MS;
        Assert.assertEquals(0f, TranscriptScrollBar.visibilityAlpha(elapsed, false), 0f);
        Assert.assertEquals(0f, TranscriptScrollBar.visibilityAlpha(elapsed + 10_000, false), 0f);
    }

    private static final long HIDE_PAST_FADE =
        TranscriptScrollBar.HIDE_DELAY_MS + TranscriptScrollBar.FADE_MS + 1;
}
