package io.itermux.app.desktop

import io.itermux.desktop.DesktopProtocol

/**
 * Pings the host, one at a time, and keeps what the answers say of the link: the round trip, smoothed, and how many
 * pings in a row went unanswered. Ticked from the stream's ticker; answers come on either transport's thread.
 */
internal class DesktopPing(private val send: (ByteArray) -> Unit) {
    @Volatile private var sentUs = 0L
    /** The last ping got its pong; only an unanswered one counts as waiting. */
    @Volatile private var answered = true
    /** Pings in a row that went unanswered for [DesktopConnection.POOR_MS]; on UDP a single one may simply be lost. */
    @Volatile var missed = 0
        private set
    /** RTT smoothed like TCP's SRTT (1/4 new sample): one slow answer is not a slow network. */
    @Volatile private var smoothedRtt = -1.0
    /** RTT samples since the stream started: the first answers race the first keyframe's encode. */
    @Volatile private var samples = 0

    /** The smoothed round trip in ms; 0 until three answers came. */
    val rttMs: Int get() = smoothedRtt.takeIf { it >= 0 && samples >= 3 }?.toInt() ?: 0

    /** A new stream: nothing is known of it yet. */
    fun reset() {
        sentUs = 0L
        answered = true
        missed = 0
        smoothedRtt = -1.0
        samples = 0
    }

    /** Sends the next ping when one is due; one unanswered past 300 ms is sent again (UDP may have dropped it). */
    fun tick() {
        if (sentUs == 0L) {
            answered = false
            sentUs = System.nanoTime() / 1000
            send(DesktopProtocol.ping(sentUs))
        }
        val waiting = if (!answered && sentUs != 0L) (System.nanoTime() / 1000 - sentUs) / 1000 else 0
        if (waiting > DesktopConnection.POOR_MS) {
            missed++
            sentUs = System.nanoTime() / 1000
            send(DesktopProtocol.ping(sentUs))
        }
    }

    /** A pong for the ping sent at [t1]: its round trip in ms when it answers the ping in flight, else null. */
    fun answer(t1: Long): Int? {
        if (t1 != sentUs || answered) return null
        answered = true
        missed = 0
        val rtt = ((System.nanoTime() / 1000 - t1) / 1000).toInt()
        smoothedRtt = if (smoothedRtt < 0) rtt.toDouble() else smoothedRtt * 0.75 + rtt * 0.25
        samples++
        return rtt
    }

    /** Lets the next ping go. */
    fun next() {
        sentUs = 0L
    }
}
