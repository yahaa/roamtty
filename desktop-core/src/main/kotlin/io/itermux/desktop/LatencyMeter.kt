package io.itermux.desktop

import org.json.JSONObject

/**
 * Where a frame's time goes, from the host grabbing the pixels to the phone showing them, averaged
 * over each [take] window. The host reports its own share (STATE `timing`); the phone measures the
 * rest against the host's clock, whose offset comes from the ping that took the least time: on a
 * path as slow one way as the other it is exact, otherwise off by at most half that round trip.
 */
class LatencyMeter {
    /**
     * All in milliseconds; null where the window had nothing to measure it with. [totalMs] runs to the screen,
     * or to the decoder's output where no render times come ([renderMs] null).
     */
    data class Latency(val captureMs: Double?, val convertMs: Double?, val encodeMs: Double?, val networkMs: Double?,
        val decodeMs: Double?, val renderMs: Double?, val totalMs: Double?, val encoder: String)

    private class Times(val captureUs: Long, val encodedUs: Long, val recvUs: Long, var decodedUs: Long = 0)

    private val frames = HashMap<Long, Times>()
    /** host clock − phone clock, µs, with the round trip it was measured over. */
    private val offsets = ArrayDeque<Pair<Long, Long>>()
    private var host: JSONObject? = null
    private val network = Sum()
    private val decode = Sum()
    private val render = Sum()
    private val total = Sum()
    /** Capture to decoded: the total where the decoder never says when a frame reached the screen (the emulator's). */
    private val toDecoded = Sum()

    private class Sum(var us: Long = 0, var n: Int = 0) {
        fun add(v: Long) { if (v >= 0) { us += v; n++ } }
        fun ms() = if (n == 0) null else us / n / 1000.0
    }

    /** A pong: [t1] and [t4] on the phone's clock, [t2] and [t3] on the host's. */
    @Synchronized fun onPong(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt < 0) return
        offsets.addLast(((t2 - t1) + (t3 - t4)) / 2 to rtt)
        while (offsets.size > 8) offsets.removeFirst()
    }

    private fun offset(): Long? = offsets.minByOrNull { it.second }?.first

    @Synchronized fun onReceived(frame: Long, captureUs: Long, encodedUs: Long, recvUs: Long) {
        frames[frame] = Times(captureUs, encodedUs, recvUs)
        if (frames.size > 240) frames.keys.filter { it < frame - 120 }.forEach(frames::remove)
    }

    @Synchronized fun onDecoded(frame: Long, decodedUs: Long) {
        val t = frames[frame] ?: return
        t.decodedUs = decodedUs
        decode.add(decodedUs - t.recvUs)
        offset()?.let {
            network.add(t.recvUs - (t.encodedUs - it))
            toDecoded.add(decodedUs - (t.captureUs - it))
        }
    }

    /** The frame reached the display (MediaCodec.OnFrameRenderedListener); the last step. */
    @Synchronized fun onRendered(frame: Long, renderedUs: Long) {
        val t = frames.remove(frame) ?: return
        if (t.decodedUs != 0L) render.add(renderedUs - t.decodedUs)
        offset()?.let { total.add(renderedUs - (t.captureUs - it)) }
    }

    /** STATE `timing`. */
    @Synchronized fun onHostTiming(json: JSONObject) { host = json }

    /** The averages since the last call, then a fresh window; null when nothing came in. */
    @Synchronized fun take(): Latency? {
        val h = host
        fun hostMs(key: String) = h?.takeIf { it.has(key) }?.optLong(key)?.div(1000.0)
        val l = Latency(hostMs("capture_us"), hostMs("convert_us"), hostMs("encode_us"), network.ms(), decode.ms(), render.ms(),
            total.ms() ?: toDecoded.ms(), h?.optString("encoder").orEmpty())
        host = null
        listOf(network, decode, render, total, toDecoded).forEach { it.us = 0; it.n = 0 }
        return l.takeIf { listOf(it.captureMs, it.networkMs, it.decodeMs, it.totalMs).any { v -> v != null } }
    }

    /** A new stream: the host's clock starts over with its process. */
    @Synchronized fun reset() {
        frames.clear()
        offsets.clear()
        host = null
        listOf(network, decode, render, total, toDecoded).forEach { it.us = 0; it.n = 0 }
    }
}
