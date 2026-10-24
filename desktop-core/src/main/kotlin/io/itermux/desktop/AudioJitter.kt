package io.itermux.desktop

/**
 * The sound player's rules without the audio stack (iOS `AudioJitter`). The host numbers its packets
 * every 20 ms, silent ones included, so `arrival − seq × 20 ms` is each packet's transit time plus an
 * offset; how far a packet came behind the earliest of the last 3 s, the clocks' drift taken out, is
 * the network's jitter. The buffer holds its 95th percentile plus [MARGIN_MS] (60–300 ms): a phone on
 * Wi-Fi swings by 100 ms and more, a wired link by a few. Each packet is played at once. Only a start
 * (the first packet, or sound again after the host fell silent: numbers skipped, and the player ran dry
 * or nothing came for [GAP_MS]) gets a cushion of silence up to that target; after a network stall the
 * packets it held back arrive together and refill the buffer by themselves, and a cushion under them
 * kept the sound 80 ms too late for seconds. Past target + [DROP_ABOVE_MS] waiting, packets are dropped
 * down to the target; one older than the last is dropped too; and the playback rate follows the backlog,
 * at most 3% either way, so two clocks that never quite agree (a virtual machine's sink ran 1.3% fast)
 * need no drops.
 */
class AudioJitter {
    sealed interface Decision {
        /** [cushionMs] of silence first (0 for none). */
        data class Play(val cushionMs: Int) : Decision
        data object Drop : Decision
    }

    private val at = LongArray(WINDOW)
    private val offset = LongArray(WINDOW)
    private var count = 0
    private var head = 0
    private var lastArrivalMs: Long? = null
    private var lastSeq: Long? = null
    private var dropping = false
    private var smoothed = MIN_TARGET_MS.toDouble()
    /** The rate's slow part: what the clocks' difference needs, learnt over seconds. */
    private var drift = 0.0

    /** 1 plays at the host's rate; above it faster. */
    var rate = 1.0
        private set
    /** The backlog the buffer aims for, from the jitter of the last 3 s. */
    var targetMs = MIN_TARGET_MS
        private set

    /**
     * A packet numbered [seq] arrived at [nowMs]; [backlogMs] is what is written and not yet heard,
     * [ranDry] whether the player emptied since the last packet.
     */
    fun arrive(nowMs: Long, seq: Long, backlogMs: Int, ranDry: Boolean): Decision {
        val o = nowMs - seq * PACKET_MS
        // A new `serve` numbers from 1 again: the old offsets say nothing about this one.
        if (count > 0 && kotlin.math.abs(o - offset[(head - 1 + WINDOW) % WINDOW]) > 1000) { count = 0; lastSeq = null }
        at[head] = nowMs; offset[head] = o
        head = (head + 1) % WINDOW
        if (count < WINDOW) count++
        targetMs = (p95Jitter().toInt() + MARGIN_MS).coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)

        lastSeq?.let { if (seq <= it) return Decision.Drop }
        val quiet = lastArrivalMs?.let { nowMs - it > GAP_MS } ?: true
        val resumed = lastSeq?.let { seq - it > 1 && (ranDry || quiet) } ?: true
        lastArrivalMs = nowMs
        lastSeq = seq
        if (resumed) {
            dropping = false
            smoothed = targetMs.toDouble()
            return Decision.Play((targetMs - PACKET_MS - backlogMs).coerceAtLeast(0))
        }
        if (ranDry) dropping = false
        if (backlogMs > targetMs + DROP_ABOVE_MS) dropping = true
        if (dropping) {
            if (backlogMs > targetMs) return Decision.Drop
            dropping = false
        }
        smoothed += (backlogMs - smoothed) * 0.05
        val error = smoothed - targetMs
        drift = (drift + error * 0.000002).coerceIn(-0.03, 0.03)
        val want = 1 + (drift + error * 0.0005).coerceIn(-0.03, 0.03)
        if (kotlin.math.abs(want - rate) >= 0.001) rate = want
        return Decision.Play(0)
    }

    /** How far the 95th percentile of packets came behind the earliest, with the linear drift taken out. */
    private fun p95Jitter(): Double {
        var meanT = 0.0; var meanO = 0.0
        for (i in 0 until count) { meanT += at[i]; meanO += offset[i] }
        meanT /= count; meanO /= count
        var num = 0.0; var den = 0.0
        for (i in 0 until count) {
            num += (at[i] - meanT) * (offset[i] - meanO)
            den += (at[i] - meanT) * (at[i] - meanT)
        }
        val slope = if (den > 0) num / den else 0.0
        val residuals = DoubleArray(count) { offset[it] - slope * (at[it] - meanT) }
        val base = residuals.min()
        for (i in residuals.indices) residuals[i] -= base
        residuals.sort()
        return residuals[minOf(count - 1, count * 95 / 100)]
    }

    companion object {
        const val PACKET_MS = 20
        const val MIN_TARGET_MS = 60
        const val MAX_TARGET_MS = 300
        const val MARGIN_MS = 40
        const val DROP_ABOVE_MS = 120
        /** Nothing for this long after skipped numbers: the host was silent (it sends nothing then). */
        const val GAP_MS = 100L
        private const val WINDOW = 150 // 3 s of packets
    }
}
