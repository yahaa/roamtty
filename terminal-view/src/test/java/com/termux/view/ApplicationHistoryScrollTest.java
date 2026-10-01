package com.termux.view;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ApplicationHistoryScrollTest {
    @Test public void smallDragEventsAccumulateIntoPages() {
        ApplicationHistoryScroll scroll = new ApplicationHistoryScroll();
        for (int i = 0; i < 9; i++) {
            assertEquals(0, scroll.pages(-1, 30));
            assertEquals(0, scroll.pages(0, 30));
        }
        assertEquals(-1, scroll.pages(-1, 30));
        assertEquals(-2, scroll.pages(-20, 30));
    }

    @Test public void reversingOrLeavingHistoryDiscardsPartialGesture() {
        ApplicationHistoryScroll scroll = new ApplicationHistoryScroll();
        assertEquals(0, scroll.pages(-9, 30));
        assertEquals(0, scroll.pages(1, 30));
        assertEquals(1, scroll.pages(9, 30));
        assertEquals(0, scroll.pages(9, 30));
        scroll.reset();
        assertEquals(0, scroll.pages(1, 30));
    }
}
