package io.itermux.desktop

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The UDP datagram format, as the plugin's `udp.rs`: `u8 dir | u64 seq` in the clear, then
 * AES-128-GCM (nonce = dir, 0, 0, 0, seq) of one packet with the header as associated data.
 */
object UdpWire {
    const val DIR_C2S = 0
    const val DIR_S2C = 1
    const val HEADER = 9
    const val SHARD = 1150
    const val MAX_K = 160
    const val FRAG = 1
    const val RACK = 2
    const val HEARTBEAT = 3
    const val FEEDBACK = 4
    const val NACK = 5
    const val FLAG_RELIABLE = 1
    const val FLAG_VIDEO = 2

    fun parityFor(k: Int, ratio: Float): Int =
        if (ratio <= 0f) 0 else Math.ceil((k * ratio).toDouble()).toInt().coerceAtLeast(1).coerceAtMost(Fec.MAX_SHARDS - k)

    /** One message cut into FEC blocks of equal shards. */
    fun fragment(msg: Long, flags: Int, bytes: ByteArray, ratio: Float): List<Packet.Frag> {
        val perBlock = SHARD * MAX_K
        val blocks = maxOf(1, (bytes.size + perBlock - 1) / perBlock)
        require(blocks <= 255)
        val out = ArrayList<Packet.Frag>()
        for (b in 0 until blocks) {
            val from = b * perBlock
            val to = minOf(bytes.size, from + perBlock)
            val len = to - from
            val k = maxOf(1, (len + SHARD - 1) / SHARD)
            val size = maxOf(1, (len + k - 1) / k)
            val data = List(k) { i ->
                val s = ByteArray(size)
                val start = from + minOf(len, i * size)
                val end = from + minOf(len, (i + 1) * size)
                System.arraycopy(bytes, start, s, 0, end - start)
                s
            }
            val m = parityFor(k, ratio)
            val parity = if (m > 0) Fec.encode(data, m) else emptyList()
            (data + parity).forEachIndexed { i, shard -> out += Packet.Frag(msg, flags, b, blocks, i, k, m, len, shard) }
        }
        return out
    }
}

/** A key's AES-128-GCM sealing; one Cipher reused, it is not thread safe (callers lock). */
class UdpCrypto(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun nonce(dir: Int, seq: Long) = ByteBuffer.allocate(12).put(dir.toByte()).put(0).put(0).put(0).putLong(seq).array()

    @Synchronized fun seal(dir: Int, seq: Long, plain: ByteArray): ByteArray {
        val head = ByteBuffer.allocate(UdpWire.HEADER).put(dir.toByte()).putLong(seq).array()
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce(dir, seq)))
        cipher.updateAAD(head)
        return head + cipher.doFinal(plain)
    }

    /** (dir, seq, plaintext), or null for anything not sealed with this key. */
    @Synchronized fun open(packet: ByteArray, length: Int = packet.size): Triple<Int, Long, ByteArray>? {
        if (length < UdpWire.HEADER + 16) return null
        val dir = packet[0].toInt() and 0xFF
        val seq = ByteBuffer.wrap(packet, 1, 8).long
        return try {
            cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce(dir, seq)))
            cipher.updateAAD(packet, 0, UdpWire.HEADER)
            Triple(dir, seq, cipher.doFinal(packet, UdpWire.HEADER, length - UdpWire.HEADER))
        } catch (_: java.security.GeneralSecurityException) { null }
    }
}

/** Drops a datagram whose sequence number was seen in the last 1024. */
class ReplayWindow {
    private var top = 0L
    private val bits = LongArray(16)

    fun accept(seq: Long): Boolean {
        val w = 1024L
        if (seq + w <= top) return false
        if (seq > top) {
            if (seq - top >= w) bits.fill(0) else for (s in top + 1..seq) bits[((s % w) / 64).toInt()] = bits[((s % w) / 64).toInt()] and (1L shl (s % 64).toInt()).inv()
            top = seq
        }
        val i = ((seq % w) / 64).toInt()
        val bit = 1L shl (seq % 64).toInt()
        if (bits[i] and bit != 0L) return false
        bits[i] = bits[i] or bit
        return true
    }
}

data class Report(val msg: Long, val received: Int, val total: Int, val firstUs: Long, val lastUs: Long, val outcome: Int)

sealed interface Packet {
    class Frag(val msg: Long, val flags: Int, val block: Int, val blocks: Int, val index: Int, val k: Int, val m: Int,
        val len: Int, val shard: ByteArray) : Packet
    data class Rack(val ids: List<Long>) : Packet
    data class Heartbeat(val t: Long, val echo: Long) : Packet
    data class Feedback(val reports: List<Report>) : Packet
    data class Nack(val msg: Long, val block: Int, val indices: List<Int>) : Packet

    fun encode(): ByteArray = when (this) {
        is Frag -> ByteBuffer.allocate(18 + shard.size).put(UdpWire.FRAG.toByte()).putInt(msg.toInt()).put(flags.toByte())
            .put(block.toByte()).put(blocks.toByte()).putShort(index.toShort()).putShort(k.toShort()).putShort(m.toShort())
            .putInt(len).put(shard).array()
        is Rack -> ByteBuffer.allocate(3 + 4 * ids.size).put(UdpWire.RACK.toByte()).putShort(ids.size.toShort())
            .also { b -> ids.forEach { b.putInt(it.toInt()) } }.array()
        is Heartbeat -> ByteBuffer.allocate(17).put(UdpWire.HEARTBEAT.toByte()).putLong(t).putLong(echo).array()
        is Feedback -> ByteBuffer.allocate(3 + 25 * reports.size).put(UdpWire.FEEDBACK.toByte()).putShort(reports.size.toShort()).also { b ->
            reports.forEach { r -> b.putInt(r.msg.toInt()).putShort(r.received.toShort()).putShort(r.total.toShort()).putLong(r.firstUs).putLong(r.lastUs).put(r.outcome.toByte()) }
        }.array()
        is Nack -> ByteBuffer.allocate(8 + 2 * indices.size).put(UdpWire.NACK.toByte()).putInt(msg.toInt()).put(block.toByte())
            .putShort(indices.size.toShort()).also { b -> indices.forEach { b.putShort(it.toShort()) } }.array()
    }

    companion object {
        fun decode(p: ByteArray): Packet? = try {
            val b = ByteBuffer.wrap(p)
            fun u16() = b.short.toInt() and 0xFFFF
            fun u32() = b.int.toLong() and 0xFFFFFFFFL
            fun u8() = b.get().toInt() and 0xFF
            when (u8()) {
                UdpWire.FRAG -> {
                    val msg = u32(); val flags = u8(); val block = u8(); val blocks = u8()
                    val index = u16(); val k = u16(); val m = u16(); val len = b.int
                    Frag(msg, flags, block, blocks, index, k, m, len, ByteArray(b.remaining()).also { b.get(it) })
                }
                UdpWire.RACK -> Rack(List(u16()) { u32() })
                UdpWire.HEARTBEAT -> Heartbeat(b.long, b.long)
                UdpWire.FEEDBACK -> Feedback(List(u16()) { Report(u32(), u16(), u16(), b.long, b.long, u8()) })
                UdpWire.NACK -> { val msg = u32(); val block = u8(); Nack(msg, block, List(u16()) { u16() }) }
                else -> null
            }
        } catch (_: java.nio.BufferUnderflowException) { null }
    }
}

/** Puts messages back together; each comes out once, and video messages leave a [Report]. */
class Reassembler {
    private class Block(val k: Int, val len: Int, val shards: Array<ByteArray?>) { var data: ByteArray? = null; var parity = false }
    private class Pending(val flags: Int, val blocks: Array<Block?>, val firstUs: Long) {
        var lastUs = firstUs; var received = 0; var total = 0
        /** When a shard of a later video message first arrived: everything of this one has been sent by then. */
        var overtakenUs = 0L
    }
    private val pending = HashMap<Long, Pending>()
    private val done = java.util.TreeSet<Long>()
    /** Finished video messages, kept a moment so parity arriving after the rebuild still counts. */
    private val settling = LinkedHashMap<Long, Pair<Report, Long>>()

    class Delivered(val msg: Long, val flags: Int, val bytes: ByteArray)

    fun isDone(msg: Long) = msg in done

    fun push(f: Packet.Frag, nowUs: Long): Delivered? {
        if (f.msg in done) {
            settling[f.msg]?.let { (r, at) -> settling[f.msg] = r.copy(received = r.received + 1) to at }
            return null
        }
        val n = f.k + f.m
        if (f.k == 0 || n > Fec.MAX_SHARDS || f.index >= n || f.block >= f.blocks) return null
        val p = pending.getOrPut(f.msg) { Pending(f.flags, arrayOfNulls(f.blocks), nowUs) }
        if (f.flags and UdpWire.FLAG_VIDEO != 0) for ((m, o) in pending) if (m < f.msg && o.overtakenUs == 0L && o.flags and UdpWire.FLAG_VIDEO != 0) o.overtakenUs = nowUs
        if (p.blocks.size != f.blocks) return null
        p.lastUs = nowUs
        val b = p.blocks[f.block] ?: Block(f.k, f.len, arrayOfNulls(n)).also { p.blocks[f.block] = it; p.total += n }
        if (b.data != null || b.shards.size != n || b.shards[f.index] != null) return null
        b.shards[f.index] = f.shard
        p.received++
        if (b.shards.count { it != null } >= b.k) {
            b.parity = (0 until b.k).any { b.shards[it] == null }
            val data = Fec.decode(b.shards.toList(), b.k) ?: return null
            val joined = ByteArray(b.len)
            var at = 0
            for (d in data) { val c = minOf(d.size, b.len - at); if (c <= 0) break; System.arraycopy(d, 0, joined, at, c); at += c }
            b.data = joined
            b.shards.fill(null)
        }
        if (p.blocks.all { it?.data != null }) {
            pending.remove(f.msg)
            val out = java.io.ByteArrayOutputStream()
            p.blocks.forEach { out.write(it!!.data!!) }
            finish(f.msg, p, if (p.blocks.any { it!!.parity }) 2 else 1, nowUs)
            return Delivered(f.msg, p.flags, out.toByteArray())
        }
        return null
    }

    private fun finish(msg: Long, p: Pending, outcome: Int, nowUs: Long) {
        done += msg
        while (done.size > 4096) done.pollFirst()
        if (p.flags and UdpWire.FLAG_VIDEO != 0) settling[msg] = Report(msg, p.received, p.total, p.firstUs, p.lastUs, outcome) to nowUs
    }

    /**
     * Missing shards (msg, block, indices): what a NACK asks for. The host paces video out in order, so
     * a video message's gaps are lost once a later one has been arriving for [REORDER_US]. A quiet spell
     * alone is not loss: Wi-Fi holds bursts back for tens of ms, and NACKing after 10 ms of quiet
     * resent 5-30% of a phone's shards at 0% loss. Messages nothing has overtaken wait [fallbackUs].
     */
    fun missing(nowUs: Long, fallbackUs: Long): List<Triple<Long, Int, List<Int>>> = pending.flatMap { (msg, p) ->
        val overtaken = p.overtakenUs != 0L && nowUs - p.overtakenUs >= REORDER_US
        if (!overtaken && nowUs - p.lastUs < fallbackUs) emptyList() else p.blocks.withIndex().mapNotNull { (bi, b) ->
            if (b == null || b.data != null) null else {
                val need = b.k - b.shards.count { it != null }
                b.shards.indices.filter { b.shards[it] == null }.take(need).takeIf { it.isNotEmpty() }?.let { Triple(msg, bi, it) }
            }
        }
    }

    /** Gives up on messages quiet for [timeoutUs]; returns the video ones lost. */
    fun expire(nowUs: Long, timeoutUs: Long): List<Long> {
        val stale = pending.filter { nowUs - it.value.lastUs > timeoutUs }.keys.toList()
        return stale.mapNotNull { m ->
            val p = pending.remove(m)!!
            finish(m, p, 0, nowUs)
            m.takeIf { p.flags and UdpWire.FLAG_VIDEO != 0 }
        }
    }

    /** Reports of video messages finished at least [settleUs] ago. */
    fun takeReports(nowUs: Long = Long.MAX_VALUE, settleUs: Long = SETTLE_US): List<Report> {
        val out = ArrayList<Report>()
        val it = settling.entries.iterator()
        while (it.hasNext()) {
            val (r, at) = it.next().value
            if (nowUs != Long.MAX_VALUE && nowUs - at < settleUs) break
            out += r
            it.remove()
        }
        return out
    }

    companion object {
        const val SETTLE_US = 50_000L
        /** A later message's shards can pass an earlier one's last few on the way: 5 ms of grace. */
        const val REORDER_US = 5_000L
    }
}
