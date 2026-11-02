package io.itermux.desktop

import io.itermux.desktop.OverlayTouch.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayTouchTest {
    private fun touch() = OverlayTouch(slop = 10f)

    @Test fun offTheBoxIsThePicturesAtOnce() {
        val t = touch()
        assertEquals(Owner.PICTURE, t.down(false, 0f, 0f, 0))
        assertNull(t.holdAt)
        assertEquals(Owner.PICTURE, t.tick(1000))
        assertEquals(Owner.PICTURE, t.up(1000))
        assertEquals(Owner.NONE, t.owner)
    }

    @Test fun aTapOnTheBoxGoesToThePicture() {
        val t = touch()
        assertEquals(Owner.PENDING, t.down(true, 5f, 5f, 100))
        assertEquals(100 + OverlayTouch.HOLD_MS, t.holdAt)
        assertEquals(Owner.PENDING, t.moved(8f, 7f, 150))
        assertEquals(Owner.PICTURE, t.up(180))
    }

    @Test fun aSwipeOnTheBoxGoesToThePicture() {
        val t = touch()
        t.down(true, 5f, 5f, 0)
        assertEquals(Owner.PICTURE, t.moved(30f, 5f, 50))
        // Once the picture's, a later stillness does not take it back.
        assertEquals(Owner.PICTURE, t.tick(1000))
        assertEquals(Owner.PICTURE, t.moved(30f, 5f, 1000))
    }

    @Test fun aSecondFingerGoesToThePicture() {
        val t = touch()
        t.down(true, 5f, 5f, 0)
        assertEquals(Owner.PICTURE, t.otherFinger())
    }

    @Test fun aStillHoldTakesTheBox() {
        val t = touch()
        t.down(true, 5f, 5f, 0)
        assertEquals(Owner.PENDING, t.tick(OverlayTouch.HOLD_MS - 1))
        assertEquals(Owner.BOX, t.tick(OverlayTouch.HOLD_MS))
        // Dragging and more fingers keep it the box's.
        assertEquals(Owner.BOX, t.moved(300f, 200f, 600))
        assertEquals(Owner.BOX, t.otherFinger())
        assertEquals(Owner.BOX, t.up(700))
        assertEquals(Owner.NONE, t.owner)
    }

    @Test fun aLateTimerStillGivesTheHoldToTheBox() {
        val t = touch()
        t.down(true, 5f, 5f, 0)
        assertEquals(Owner.BOX, t.moved(6f, 6f, OverlayTouch.HOLD_MS + 5))
        // The first move after a still hold may go far at once.
        val far = touch()
        far.down(true, 5f, 5f, 0)
        assertEquals(Owner.BOX, far.moved(100f, 80f, OverlayTouch.HOLD_MS))
        val u = touch()
        u.down(true, 5f, 5f, 0)
        assertEquals(Owner.BOX, u.up(OverlayTouch.HOLD_MS + 5))
    }

    @Test fun resetForgetsTheTouch() {
        val t = touch()
        t.down(true, 5f, 5f, 0)
        t.reset()
        assertEquals(Owner.NONE, t.owner)
        assertNull(t.holdAt)
    }
}
