package io.itermux.app

import com.termux.view.ImeBackspaceTracker
import com.termux.view.ImeBackspaceTracker.Action.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeBackspaceTrackerTest {
    @Test fun heldKeyClearsOnceAndSuppressesRemainingRepeats() {
        val tracker = ImeBackspaceTracker()
        assertEquals(DELETE, tracker.onDelete(0))
        // The IME starts repeating after its own hold delay.
        for (time in 400L..1350L step 50) assertEquals(DELETE, tracker.onDelete(time))
        assertEquals(CLEAR, tracker.onDelete(1400))
        for (time in 1450L..4000L step 50) assertEquals(IGNORE, tracker.onDelete(time))
        assertEquals(DELETE, tracker.onDelete(4400))
    }

    @Test fun separatedTapsDoNotClear() {
        val tracker = ImeBackspaceTracker()
        for (time in 0L..5000L step 300) assertEquals(DELETE, tracker.onDelete(time))
    }

    @Test fun rapidTapsDoNotClear() {
        val tracker = ImeBackspaceTracker()
        for (time in 0L..5000L step 160) assertEquals(DELETE, tracker.onDelete(time))
    }

    @Test fun aTapRightAfterAShortHoldDoesNotClear() {
        val tracker = ImeBackspaceTracker()
        for (time in 0L..800L step 50) assertEquals(DELETE, tracker.onDelete(time))
        assertEquals(DELETE, tracker.onDelete(1000))
        assertEquals(DELETE, tracker.onDelete(1200))
    }

    @Test fun otherInputResetsACompletedHold() {
        val tracker = ImeBackspaceTracker()
        for (time in 0L..950L step 50) tracker.onDelete(time)
        assertEquals(CLEAR, tracker.onDelete(1000))
        tracker.reset()
        assertEquals(DELETE, tracker.onDelete(1100))
    }

    @Test fun aPauseRestartsTheHoldThreshold() {
        val tracker = ImeBackspaceTracker()
        for (time in 0L..950L step 50) tracker.onDelete(time)
        for (time in 1200L..2150L step 50) assertEquals(DELETE, tracker.onDelete(time))
        assertEquals(CLEAR, tracker.onDelete(2200))
    }
}
