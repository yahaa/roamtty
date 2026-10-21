package io.itermux.desktop

import org.junit.Assert.*
import org.junit.Test

class HidUsageTest {
    @Test fun androidKeys() {
        assertEquals(HidUsage.A, HidUsage.fromAndroid(29))
        assertEquals(0x27, HidUsage.fromAndroid(7))
        assertEquals(0x1E, HidUsage.fromAndroid(8))
        assertEquals(HidUsage.ENTER, HidUsage.fromAndroid(66))
        assertEquals(HidUsage.f(12), HidUsage.fromAndroid(142))
        assertEquals(HidUsage.LEFT_GUI, HidUsage.fromAndroid(117))
        assertEquals(0x59, HidUsage.fromAndroid(145))
        assertNull(HidUsage.fromAndroid(4)) // back
        assertEquals(0x68, HidUsage.f(13))
    }

    @Test fun tappedModifierWrapsOneKey() {
        val m = ModifierLatch()
        m.tap(HidUsage.LEFT_CTRL)
        assertEquals(listOf(HidUsage.LEFT_CTRL to true, HidUsage.C to true, HidUsage.C to false, HidUsage.LEFT_CTRL to false),
            m.press(HidUsage.C))
        assertEquals(ModifierLatch.State.OFF, m.state(HidUsage.LEFT_CTRL))
        assertEquals(listOf(HidUsage.C to true, HidUsage.C to false), m.press(HidUsage.C))
    }

    @Test fun heldModifierStays() {
        val m = ModifierLatch()
        m.hold(HidUsage.LEFT_SHIFT)
        m.press(HidUsage.A)
        assertEquals(ModifierLatch.State.LOCKED, m.state(HidUsage.LEFT_SHIFT))
        m.tap(HidUsage.LEFT_SHIFT)
        assertEquals(ModifierLatch.State.OFF, m.state(HidUsage.LEFT_SHIFT))
    }

    @Test fun comboGathersUpToFour() {
        val m = ModifierLatch()
        m.setCombo(true)
        listOf(HidUsage.DELETE, HidUsage.LEFT_CTRL, HidUsage.LEFT_ALT, HidUsage.A, HidUsage.C).forEach { assertTrue(m.press(it).isEmpty()) }
        assertEquals(4, m.pending.size)
        val out = m.sendCombo()
        assertEquals(listOf(HidUsage.LEFT_CTRL to true, HidUsage.LEFT_ALT to true, HidUsage.DELETE to true, HidUsage.A to true),
            out.take(4))
        assertEquals(8, out.size)
        assertTrue(m.pending.isEmpty())
    }
}
