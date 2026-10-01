package com.termux.view;

import static com.termux.view.ImeDeleteGuard.Plan.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImeDeleteGuardTest {
    @Test public void rejectsNegativeLengths() {
        assertEquals(REJECT, ImeDeleteGuard.plan(-1, 0));
        assertEquals(REJECT, ImeDeleteGuard.plan(0, -1));
        assertEquals(REJECT, ImeDeleteGuard.plan(-1, -1));
    }

    @Test public void weChatOneKeyClearUsesMaxValue() {
        assertEquals(CLEAR_ALL, ImeDeleteGuard.plan(Integer.MAX_VALUE, 0));
        assertEquals(CLEAR_ALL, ImeDeleteGuard.plan(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(CLEAR_ALL, ImeDeleteGuard.plan(0, Integer.MAX_VALUE));
    }

    @Test public void largeButFiniteDeletesClearInsteadOfFlooding() {
        assertEquals(CLEAR_ALL, ImeDeleteGuard.plan(ImeDeleteGuard.BULK_CLEAR_THRESHOLD + 1, 0));
        assertEquals(CLEAR_ALL, ImeDeleteGuard.plan(1000, 0));
        assertEquals(CLEAR_ALL, ImeDeleteGuard.plan(0, 1000));
    }

    @Test public void ordinarySingleAndSmallBatchDeletesProceed() {
        assertEquals(PROCEED, ImeDeleteGuard.plan(1, 0));
        assertEquals(PROCEED, ImeDeleteGuard.plan(0, 1));
        assertEquals(PROCEED, ImeDeleteGuard.plan(3, 0)); // Samsung spellcheck-style
        assertEquals(PROCEED, ImeDeleteGuard.plan(ImeDeleteGuard.BULK_CLEAR_THRESHOLD, 0));
        assertEquals(PROCEED, ImeDeleteGuard.plan(0, 0));
    }

    @Test public void clampEmitNeverExceedsCap() {
        assertEquals(0, ImeDeleteGuard.clampEmit(-5));
        assertEquals(0, ImeDeleteGuard.clampEmit(0));
        assertEquals(1, ImeDeleteGuard.clampEmit(1));
        assertEquals(ImeDeleteGuard.MAX_EMITTED_DELETES,
            ImeDeleteGuard.clampEmit(Integer.MAX_VALUE));
        assertEquals(ImeDeleteGuard.MAX_EMITTED_DELETES,
            ImeDeleteGuard.clampEmit(ImeDeleteGuard.MAX_EMITTED_DELETES + 10));
    }

    @Test public void clampIndexStaysInsideText() {
        assertEquals(0, ImeDeleteGuard.clampIndex(-1, 10));
        assertEquals(0, ImeDeleteGuard.clampIndex(5, 0));
        assertEquals(10, ImeDeleteGuard.clampIndex(99, 10));
        assertEquals(5, ImeDeleteGuard.clampIndex(5, 10));
    }

    @Test public void clampLengthRespectsAvailability() {
        assertEquals(0, ImeDeleteGuard.clampLength(5, 0));
        assertEquals(0, ImeDeleteGuard.clampLength(0, 5));
        assertEquals(3, ImeDeleteGuard.clampLength(10, 3));
        assertEquals(3, ImeDeleteGuard.clampLength(3, 10));
    }
}
