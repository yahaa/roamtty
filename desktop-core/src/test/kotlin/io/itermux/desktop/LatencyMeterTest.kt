package io.itermux.desktop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The phone's split of a frame's latency, against a host clock 5 s ahead of the phone's. */
class LatencyMeterTest {
    private val skew = 5_000_000L

    /** A ping at [t1] (phone) taking [up] there and [down] back, answered at once. */
    private fun LatencyMeter.ping(t1: Long, up: Long, down: Long) = onPong(t1, t1 + up + skew, t1 + up + skew, t1 + up + down)

    @Test fun splitsAFrameAcrossTheHostTheNetworkAndThePhone() {
        val m = LatencyMeter()
        m.ping(0, 3_000, 3_000)
        m.onHostTiming(JSONObject("""{"frames":30,"capture_us":4000,"convert_us":0,"encode_us":6000,"encoder":"nvenc"}"""))
        // Captured at phone time 100 ms, encoded 10 ms later, 7 ms on the way, 5 ms decoding, 9 ms to the screen.
        m.onReceived(1, 100_000 + skew, 110_000 + skew, 117_000)
        m.onDecoded(1, 122_000)
        m.onRendered(1, 131_000)
        val l = m.take()!!
        assertEquals(4.0, l.captureMs!!, 0.0)
        assertEquals(6.0, l.encodeMs!!, 0.0)
        assertEquals(7.0, l.networkMs!!, 0.01)
        assertEquals(5.0, l.decodeMs!!, 0.0)
        assertEquals(9.0, l.renderMs!!, 0.0)
        assertEquals(31.0, l.totalMs!!, 0.01)
        assertEquals("nvenc", l.encoder)
        assertNull("a fresh window", m.take())
    }

    @Test fun theQuickestPingSetsTheClock() {
        val m = LatencyMeter()
        // A slow answer whose delay was all on the way back reads the clock 20 ms wrong ...
        m.ping(0, 1_000, 41_000)
        // ... the quick symmetric one later does not.
        m.ping(1_000_000, 2_000, 2_000)
        m.onReceived(1, 0, 2_000_000 + skew, 2_010_000)
        m.onDecoded(1, 2_011_000)
        assertEquals(10.0, m.take()!!.networkMs!!, 0.01)
    }

    @Test fun withoutAPingOnlyThePhonesOwnShare() {
        val m = LatencyMeter()
        m.onReceived(1, 1, 2, 10_000)
        m.onDecoded(1, 14_000)
        m.onRendered(1, 20_000)
        val l = m.take()!!
        assertNull(l.networkMs)
        assertNull(l.totalMs)
        assertEquals(4.0, l.decodeMs!!, 0.0)
        assertEquals(6.0, l.renderMs!!, 0.0)
    }

    @Test fun withoutRenderCallbacksTheTotalEndsAtTheDecoder() {
        val m = LatencyMeter()
        m.ping(0, 3_000, 3_000)
        m.onReceived(1, 100_000 + skew, 110_000 + skew, 117_000)
        m.onDecoded(1, 122_000)
        val l = m.take()!!
        assertNull(l.renderMs)
        assertEquals(22.0, l.totalMs!!, 0.01)
    }
}
