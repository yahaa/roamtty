package io.itermux.desktop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The phone end of the UDP transport (plan §7.3 P2). Frames go out as FEC'd messages; keys,
 * buttons, text and control are resent until the host acknowledges them, pointer moves are sent
 * once (the next one supersedes them). Received video is reported back for the host's congestion
 * control, and missing shards are NACKed while the round trip is short enough to help.
 * [rebind] moves to a new socket after a network change and tells the host at once.
 */
class UdpLink(
    private val host: InetAddress,
    private val port: Int,
    key: ByteArray,
    /** A whole protocol frame (`type | len | payload`) arrived. */
    private val onFrame: (Frame) -> Unit,
    /** A video message could not be rebuilt: a keyframe is due. */
    private val onVideoLost: () -> Unit,
    private val log: (String) -> Unit = {},
    /**
     * A reliable frame the host never acknowledged, handed back in order to go over SSH: after ten
     * resends, or when the link has gone quiet. Without it a lost key release left the key held down.
     */
    private val onGiveUp: (ByteArray) -> Unit = {},
) {
    private val crypto = UdpCrypto(key)
    @Volatile private var socket: DatagramSocket = DatagramSocket()
    private val seq = AtomicLong(1)
    /** Reliable messages are odd (2n + 1, n counting up) so the host can apply them in order; the rest even. */
    private val reliableSeq = AtomicLong(0)
    private val plainSeq = AtomicLong(1)
    private val replay = ReplayWindow()
    private val reasm = Reassembler()
    private val lock = Any()
    /** [frags] is null until the send thread has fragmented the frame; nothing to resend before that. */
    private class Unacked(val frame: ByteArray, @Volatile var frags: List<Packet.Frag>?, var at: Long, var tries: Int)
    private val unacked = ConcurrentHashMap<Long, Unacked>()
    /** NACKs sent per (msg, block) and when: repeated every max(2×RTT, 30 ms), three times at most. */
    private val nacked = HashMap<Pair<Long, Int>, Pair<Int, Long>>()
    @Volatile private var closed = false
    /**
     * Every datagram leaves from this one thread, in order. Callers include the main thread (touches
     * become pointer input there), where Android refuses network calls. Fragmentation and FEC also
     * run here so a multi-megabyte clipboard does not stall the UI. While the thread is behind, a
     * pointer frame merges into the one queued last of its kind (moves replace, deltas add), so the
     * order against buttons and keys holds; past [SEND_QUEUE_CAP] the remaining pointer frames go.
     */
    private sealed class Out {
        class Raw(val plain: ByteArray) : Out()
        class Plain(var frame: ByteArray, val fecRatio: Float = 0f) : Out()
        class Reliable(val id: Long, val frame: ByteArray, val unacked: Unacked) : Out()
    }
    private val queue = ArrayDeque<Out>()
    private val queueLock = Object()
    private val sender = thread(name = "udp-send", isDaemon = true) {
        while (true) {
            val next = synchronized(queueLock) {
                while (queue.isEmpty() && !closed) try { queueLock.wait() } catch (_: InterruptedException) {}
                if (closed) null else queue.removeFirst()
            } ?: break
            try { transmit(next) } catch (e: Exception) { log("udp send: $e") }
        }
    }
    @Volatile private var lastRxUs = 0L
    @Volatile private var lastServerT = 0L
    @Volatile private var lastHeartbeatUs = 0L
    @Volatile var rttMs: Int? = null
        private set
    /** Share of shards lost on the way over the last second, before FEC. */
    @Volatile var loss = 0.0
        private set
    @Volatile var fecRecovered = 0L
        private set
    @Volatile var lostMessages = 0L
        private set
    @Volatile var nacks = 0L
        private set
    /** Test hook: drop this share of datagrams in both directions. */
    @Volatile var dropRate = 0.0
    private val window = ArrayDeque<Pair<Long, Pair<Int, Int>>>()

    val localPort: Int get() = socket.localPort
    val alive: Boolean get() = !closed && lastRxUs != 0L && now() - lastRxUs < ALIVE_US
    val silentUs: Long get() = if (lastRxUs == 0L) Long.MAX_VALUE else now() - lastRxUs

    private fun now() = System.nanoTime() / 1000

    fun start() {
        receiver(socket)
        thread(name = "desktop-udp-tick", isDaemon = true) {
            while (!closed) {
                try { tick() } catch (e: Exception) { log("udp tick: $e") }
                Thread.sleep(20)
            }
        }
        heartbeat()
    }

    private fun receiver(s: DatagramSocket) = thread(name = "desktop-udp-rx", isDaemon = true) {
        val buf = ByteArray(2048)
        val dp = DatagramPacket(buf, buf.size)
        while (!closed && socket === s) {
            try { s.receive(dp) } catch (_: Exception) { if (socket !== s) return@thread else continue }
            if (dropRate > 0 && Math.random() < dropRate) continue
            val (dir, sq, plain) = crypto.open(buf, dp.length) ?: continue
            if (dir != UdpWire.DIR_S2C) continue
            synchronized(lock) { if (!replay.accept(sq)) return@synchronized null else Unit } ?: continue
            lastRxUs = now()
            when (val p = Packet.decode(plain) ?: continue) {
                is Packet.Heartbeat -> {
                    lastServerT = p.t
                    if (p.echo != 0L) rttMs = ((now() - p.echo) / 1000).toInt()
                    // The host's RTT probe: answered at once, or the host times its resends by our next heartbeat.
                    else raw(Packet.Heartbeat(now(), p.t).encode())
                }
                is Packet.Frag -> {
                    val reliable = p.flags and UdpWire.FLAG_RELIABLE != 0
                    val delivered = synchronized(lock) { reasm.push(p, now()) }
                    if (delivered != null) {
                        if (reliable) raw(Packet.Rack(listOf(p.msg)).encode())
                        DesktopProtocol.read(delivered.bytes.inputStream())?.let(onFrame)
                    } else if (reliable && synchronized(lock) { reasm.isDone(p.msg) }) raw(Packet.Rack(listOf(p.msg)).encode())
                }
                is Packet.Rack -> p.ids.forEach { unacked.remove(it) }
                else -> {}
            }
        }
    }

    private fun enqueue(item: Out) {
        synchronized(queueLock) {
            if (closed) return
            queue.addLast(item)
            queueLock.notify()
        }
    }

    private fun raw(plain: ByteArray) = enqueue(Out.Raw(plain))

    private fun transmit(item: Out) {
        when (item) {
            is Out.Raw -> emit(item.plain)
            is Out.Plain -> {
                // Ids are u32 on the wire: wrap the same way as reliable ones.
                val id = (plainSeq.getAndIncrement() * 2) and 0xFFFFFFFFL
                UdpWire.fragment(id, 0, item.frame, item.fecRatio).forEach { emit(it.encode()) }
            }
            is Out.Reliable -> {
                // Handed to SSH by [giveUp] while it waited here: the host gets it from there.
                if (unacked[item.id] !== item.unacked) return
                val frags = UdpWire.fragment(item.id, UdpWire.FLAG_RELIABLE, item.frame, if (item.frame.size > UdpWire.SHARD) 0.25f else 0f)
                item.unacked.frags = frags
                item.unacked.at = now()
                frags.forEach { emit(it.encode()) }
            }
        }
    }

    private fun emit(plain: ByteArray) {
        if (closed) return
        val packet = crypto.seal(UdpWire.DIR_C2S, seq.getAndIncrement(), plain)
        if (dropRate > 0 && Math.random() < dropRate) return
        try { socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(host, port))) } catch (_: Exception) {}
    }

    private fun heartbeat() {
        lastHeartbeatUs = now()
        raw(Packet.Heartbeat(now(), lastServerT).encode())
    }

    /** Sends a whole protocol frame; [reliable] ones are resent until the host acknowledges them. */
    fun send(frame: ByteArray, reliable: Boolean, fecRatio: Float = 0f) {
        if (reliable) {
            // The id and the resend entry exist at once, so a [giveUp] right after still hands this frame over in order.
            val id = (reliableSeq.getAndIncrement() * 2 + 1) and 0xFFFFFFFFL
            val u = Unacked(frame, null, now(), 0)
            unacked[id] = u
            enqueue(Out.Reliable(id, frame, u))
            return
        }
        synchronized(queueLock) {
            if (closed) return
            val last = queue.lastOrNull() as? Out.Plain
            val merged = last?.let { merge(it.frame, frame) }
            if (merged != null) { last.frame = merged; return }
            if (queue.size >= SEND_QUEUE_CAP && coalesceable(frame)) return
            queue.addLast(Out.Plain(frame, fecRatio))
            queueLock.notify()
        }
    }

    private fun tick() {
        val t = now()
        // Keepalive four times a second: the host counts the link down after 3 s of silence, and the
        // answers let a stall show within 700 ms even when one or two are lost.
        if (t - lastHeartbeatUs > 250_000) heartbeat()
        val rtt = (rttMs ?: 50).toLong() * 1000
        val resendAfter = maxOf(2 * rtt, 80_000L)
        if (lastRxUs != 0L && t - lastRxUs >= ALIVE_US) giveUp()
        for (id in unacked.keys.sorted()) {
            val u = unacked[id] ?: continue
            if (t - u.at < resendAfter) continue
            if (u.tries >= 10) { giveUp(); break }
            val frags = u.frags ?: continue
            u.tries++; u.at = t
            frags.forEach { raw(it.encode()) }
        }
        val (reports, missing, lost) = synchronized(lock) {
            // A retransmit only beats the next frame when the round trip is under about two frames.
            val missing = if (rtt < 40_000) reasm.missing(t, maxOf(100_000L, 3 * rtt)) else emptyList()
            Triple(reasm.takeReports(t), missing, reasm.expire(t, maxOf(300_000L, 3 * rtt)))
        }
        for ((m, block, idx) in missing) {
            val (count, at) = nacked[m to block] ?: (0 to 0L)
            if (count >= 3 || t - at < maxOf(2 * rtt, 30_000L)) continue
            nacked[m to block] = (count + 1) to t
            nacks++
            raw(Packet.Nack(m, block, idx).encode())
        }
        if (nacked.size > 2000) nacked.clear()
        if (reports.isNotEmpty()) {
            reports.chunked(40).forEach { raw(Packet.Feedback(it).encode()) }
            reports.forEach { r ->
                if (r.outcome == 2) fecRecovered++
                window.addLast(t to (r.received to r.total))
            }
            while (window.isNotEmpty() && t - window.first().first > 1_000_000) window.removeFirst()
            val (rx, tot) = window.fold(0 to 0) { a, w -> (a.first + w.second.first) to (a.second + w.second.second) }
            // Too few shards make a noisy estimate: it needs 50 in the last second to count.
            loss = if (tot < 50) 0.0 else (1.0 - rx.toDouble() / tot).coerceAtLeast(0.0)
        }
        if (lost.isNotEmpty()) { lostMessages += lost.size; onVideoLost() }
    }

    /**
     * Hands every unacknowledged reliable frame to [onGiveUp], oldest first, so a send that falls back to
     * SSH goes after them. Everything before the oldest stuck one waits too: the host applies them in order.
     */
    fun giveUp() {
        for (id in unacked.keys.sorted()) unacked.remove(id)?.let { onGiveUp(it.frame) }
    }

    /** A new network: a new socket (new local port), three heartbeats straight away. */
    fun rebind() {
        val old = socket
        socket = DatagramSocket()
        old.close()
        receiver(socket)
        repeat(3) { heartbeat() }
        log("udp rebound to port ${socket.localPort}")
    }

    fun close() {
        synchronized(queueLock) { closed = true; queue.clear(); queueLock.notifyAll() }
        socket.close()
    }

    companion object {
        const val ALIVE_US = 3_000_000L
        private const val SEND_QUEUE_CAP = 512

        /** Which client frames must arrive: everything except pointer moves, wheel, ACK, pings, and live mic audio. */
        fun reliable(frame: ByteArray): Boolean = when (frame[0].toInt() and 0xFF) {
            DesktopProtocol.ACK, DesktopProtocol.PING, DesktopProtocol.PONG, VoiceProtocol.MIC_AUDIO -> false
            DesktopProtocol.INPUT -> (frame.getOrNull(4)?.toInt() ?: 0) !in setOf(1, 2, 4)
            else -> true
        }

        private fun inputKind(frame: ByteArray): Int =
            if (frame.size > 4 && frame[0].toInt() and 0xFF == DesktopProtocol.INPUT) frame[4].toInt() and 0xFF else -1

        /** Absolute/relative moves and wheel: a later one can stand in for one still queued. */
        fun coalesceable(frame: ByteArray): Boolean = inputKind(frame) in setOf(1, 2, 4)

        /**
         * One frame doing what [queued] then [next] do, or null when they must both go: an absolute move
         * replaces one on the same display, relative moves and wheel steps add up.
         */
        fun merge(queued: ByteArray, next: ByteArray): ByteArray? {
            val kind = inputKind(next)
            if (kind != inputKind(queued) || queued.size != next.size) return null
            val a = ByteBuffer.wrap(queued, 5, queued.size - 5)
            val b = ByteBuffer.wrap(next, 5, next.size - 5)
            return when (kind) {
                1 -> if (queued.size >= 6 && queued[5] == next[5]) next else null
                2, 4 -> {
                    val dx = a.short + b.short
                    val dy = a.short + b.short
                    // Past what a frame holds the sum would be clipped: send both instead.
                    if (dx !in Short.MIN_VALUE..Short.MAX_VALUE || dy !in Short.MIN_VALUE..Short.MAX_VALUE) null
                    else if (kind == 2) DesktopProtocol.rel(dx, dy) else DesktopProtocol.wheel(dx, dy)
                }
                else -> null
            }
        }
    }
}
