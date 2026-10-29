package io.itermux.desktop

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** The UDP layer against the plugin's golden samples, and the client link against itself. */
class UdpWireTest {
    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/desktop-proto/$name")!!.readBytes()
    private val key = ByteArray(16) { it.toByte() }

    @Test fun sealedHeartbeatMatchesThePlugin() {
        val c = UdpCrypto(key)
        val sealed = golden("udp-sealed.bin")
        assertArrayEquals(sealed, c.seal(UdpWire.DIR_S2C, 7, Packet.Heartbeat(1, 2).encode()))
        val (dir, seq, plain) = c.open(sealed)!!
        assertEquals(UdpWire.DIR_S2C to 7L, dir to seq)
        assertEquals(Packet.Heartbeat(1, 2), Packet.decode(plain))
        val tampered = sealed.copyOf().also { it[0] = 0 }
        assertNull(c.open(tampered))
        assertNull(UdpCrypto(ByteArray(16)).open(sealed))
    }

    @Test fun fragmentsMatchThePluginAndRebuildWithLoss() {
        val message = ByteArray(3000) { (it * 13 % 256).toByte() }
        val golden = golden("udp-frags.bin").let { b ->
            val buf = ByteBuffer.wrap(b)
            buildList { while (buf.hasRemaining()) { val n = buf.short.toInt() and 0xFFFF; add(ByteArray(n).also { buf.get(it) }) } }
        }
        val ours = UdpWire.fragment(5, UdpWire.FLAG_VIDEO, message, 0.5f).map { it.encode() }
        assertEquals(golden.size, ours.size)
        golden.zip(ours).forEach { (g, o) -> assertArrayEquals(g, o) }
        // The first two data shards lost: parity rebuilds them.
        val r = Reassembler()
        var out: Reassembler.Delivered? = null
        golden.drop(2).forEachIndexed { i, p -> r.push(Packet.decode(p) as Packet.Frag, i.toLong())?.let { out = it } }
        assertArrayEquals(message, out!!.bytes)
        assertEquals(2, r.takeReports().single().outcome)
    }

    @Test fun fecSurvivesAnyLossUpToParity() {
        val rnd = java.util.Random(4)
        repeat(20) {
            val k = 1 + rnd.nextInt(60)
            val m = 1 + rnd.nextInt(20)
            val data = List(k) { ByteArray(50).also(rnd::nextBytes) }
            val all: MutableList<ByteArray?> = (data + Fec.encode(data, m)).toMutableList()
            (all.indices).shuffled(rnd).take(m).forEach { all[it] = null }
            Fec.decode(all, k)!!.zip(data).forEach { (a, b) -> assertArrayEquals(b, a) }
        }
    }

    @Test fun replayWindow() {
        val w = ReplayWindow()
        assertTrue(w.accept(5)); assertFalse(w.accept(5)); assertTrue(w.accept(3))
        assertTrue(w.accept(2000)); assertFalse(w.accept(900)); assertTrue(w.accept(1999))
    }

    @Test fun reliableClassification() {
        assertFalse(UdpLink.reliable(DesktopProtocol.abs(0, 1, 2)))
        assertFalse(UdpLink.reliable(DesktopProtocol.wheel(0, 120)))
        assertFalse(UdpLink.reliable(DesktopProtocol.ack(1, 2, 3)))
        assertTrue(UdpLink.reliable(DesktopProtocol.key(4, true)))
        assertTrue(UdpLink.reliable(DesktopProtocol.button(1, true)))
        assertTrue(UdpLink.reliable(DesktopProtocol.text("x")))
        assertTrue(UdpLink.reliable(DesktopProtocol.req(DesktopProtocol.REQ_KEYFRAME)))
    }

    /** A backed-up sender merges pointer frames without losing movement: moves replace, deltas add. */
    @Test fun pointerFramesMerge() {
        assertArrayEquals(DesktopProtocol.abs(0, 5, 6), UdpLink.merge(DesktopProtocol.abs(0, 1, 2), DesktopProtocol.abs(0, 5, 6)))
        assertNull(UdpLink.merge(DesktopProtocol.abs(0, 1, 2), DesktopProtocol.abs(1, 5, 6)))
        assertArrayEquals(DesktopProtocol.rel(3, -1), UdpLink.merge(DesktopProtocol.rel(1, 2), DesktopProtocol.rel(2, -3)))
        assertArrayEquals(DesktopProtocol.wheel(0, 240), UdpLink.merge(DesktopProtocol.wheel(0, 120), DesktopProtocol.wheel(0, 120)))
        assertNull(UdpLink.merge(DesktopProtocol.wheel(0, 120), DesktopProtocol.rel(1, 1)))
        assertNull(UdpLink.merge(DesktopProtocol.rel(30000, 0), DesktopProtocol.rel(30000, 0)))
        assertNull(UdpLink.merge(DesktopProtocol.ack(1, 2, 3), DesktopProtocol.ack(2, 3, 4)))
    }

    /** The link against a tiny host stand-in on loopback: heartbeats answered, a reliable key resent through 30% loss. */
    @Test fun linkResendsReliableFramesThroughLoss() {
        val server = java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
        val c = UdpCrypto(key)
        val got = java.util.concurrent.LinkedBlockingQueue<Frame>()
        val serverThread = Thread {
            val buf = ByteArray(2048); val dp = java.net.DatagramPacket(buf, buf.size)
            val r = Reassembler(); var sq = 1L
            server.soTimeout = 200
            while (!Thread.currentThread().isInterrupted) {
                try { server.receive(dp) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { return@Thread }
                if (Math.random() < 0.3) continue
                val (_, _, plain) = c.open(buf, dp.length) ?: continue
                fun reply(p: Packet) { val s = c.seal(UdpWire.DIR_S2C, sq++, p.encode()); server.send(java.net.DatagramPacket(s, s.size, dp.socketAddress)) }
                when (val p = Packet.decode(plain)) {
                    is Packet.Heartbeat -> reply(Packet.Heartbeat(System.nanoTime() / 1000, p.t))
                    is Packet.Frag -> {
                        r.push(p, 0)?.let { d -> got += DesktopProtocol.read(d.bytes.inputStream())!!; reply(Packet.Rack(listOf(p.msg))) }
                            ?: if (r.isDone(p.msg)) reply(Packet.Rack(listOf(p.msg))) else Unit
                    }
                    else -> {}
                }
            }
        }.apply { isDaemon = true; start() }
        val link = UdpLink(java.net.InetAddress.getLoopbackAddress(), server.localPort, key, onFrame = {}, onVideoLost = {})
        link.start()
        val keys = (0 until 20).map { DesktopProtocol.key(4 + it, true) }
        keys.forEach { link.send(it, reliable = true) }
        val deadline = System.currentTimeMillis() + 10_000
        val seen = HashSet<Int>()
        while (seen.size < 20 && System.currentTimeMillis() < deadline) got.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { seen += (it.payload[1].toInt() shl 8) or (it.payload[2].toInt() and 0xFF) }
        assertEquals("every key arrives once despite loss", (4 until 24).toSet(), seen)
        val until = System.currentTimeMillis() + 3000
        while (!link.alive && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(link.alive)
        link.close(); serverThread.interrupt(); server.close()
    }

    /**
     * Wi-Fi holds a burst back for tens of ms at a time: a frame quiet for a while is late, not lost.
     * Its gaps are NACKed once the next frame overtakes it (the host paces video in order), or after
     * the fallback when nothing follows.
     */
    @Test fun nacksWaitForTheNextFrameNotForAQuietSpell() {
        val r = Reassembler()
        fun frag(msg: Long, index: Int) = UdpWire.fragment(msg, UdpWire.FLAG_VIDEO, ByteArray(UdpWire.SHARD * 4) { msg.toByte() }, 0f)[index]
        val ms = 1000L
        r.push(frag(2, 0), 0); r.push(frag(2, 1), 1 * ms)
        assertTrue("a 40 ms stall alone is not loss", r.missing(41 * ms, 100 * ms).isEmpty())
        r.push(frag(4, 0), 42 * ms)
        assertTrue("overtaken a moment ago: reordering grace", r.missing(43 * ms, 100 * ms).isEmpty())
        assertEquals(listOf(Triple(2L, 0, listOf(2, 3))), r.missing(48 * ms, 100 * ms))
        // The newest frame, with nothing after it, waits for the fallback.
        assertTrue(r.missing(100 * ms, 100 * ms).none { it.first == 4L })
        assertEquals(listOf(1, 2, 3), r.missing(143 * ms, 100 * ms).single { it.first == 4L }.third)
    }

    /** A reliable message that went quiet is forgotten, not done: its resend is delivered, not acknowledged unseen. */
    @Test fun anExpiredReliableMessageCanStillArrive() {
        val r = Reassembler()
        val frags = UdpWire.fragment(9, UdpWire.FLAG_RELIABLE, ByteArray(UdpWire.SHARD * 3) { 7 }, 0f)
        assertNull(r.push(frags[0], 0))
        assertTrue(r.expire(1_000_000, 300_000).isEmpty())
        assertFalse(r.isDone(9))
        var out: Reassembler.Delivered? = null
        frags.forEach { f -> r.push(f, 1_000_001)?.let { out = it } }
        assertEquals(UdpWire.SHARD * 3, out!!.bytes.size)
        assertTrue(r.isDone(9))
        // An expired video message is lost for good.
        val video = UdpWire.fragment(10, UdpWire.FLAG_VIDEO, ByteArray(UdpWire.SHARD * 3), 0f)
        r.push(video[0], 2_000_000)
        assertEquals(listOf(10L), r.expire(3_000_000, 300_000))
        assertTrue(r.isDone(10))
    }

    /** Shards of a block already rebuilt still count as received, so a lossless message reports no loss. */
    @Test fun lateShardsOfARebuiltBlockCount() {
        val r = Reassembler()
        val frags = UdpWire.fragment(3, UdpWire.FLAG_VIDEO, ByteArray(UdpWire.SHARD * 2 * UdpWire.MAX_K) { it.toByte() }, 0.25f)
        assertTrue(frags.maxOf { it.blocks } > 1)
        frags.forEach { r.push(it, 0) }
        val report = r.takeReports().single()
        assertEquals(frags.size, report.total)
        assertEquals(frags.size, report.received)
    }

    /** A host that answers heartbeats but never takes a frame: the keys come back, in order, for SSH. */
    @Test fun unacknowledgedKeysAreHandedBackInOrder() {
        val server = java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
        val c = UdpCrypto(key)
        val serverThread = Thread {
            val buf = ByteArray(2048); val dp = java.net.DatagramPacket(buf, buf.size)
            var sq = 1L
            server.soTimeout = 200
            while (!Thread.currentThread().isInterrupted) {
                try { server.receive(dp) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { return@Thread }
                val (_, _, plain) = c.open(buf, dp.length) ?: continue
                val p = Packet.decode(plain) as? Packet.Heartbeat ?: continue
                val s = c.seal(UdpWire.DIR_S2C, sq++, Packet.Heartbeat(System.nanoTime() / 1000, p.t).encode())
                server.send(java.net.DatagramPacket(s, s.size, dp.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val back = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val link = UdpLink(java.net.InetAddress.getLoopbackAddress(), server.localPort, key, onFrame = {}, onVideoLost = {},
            onGiveUp = { back += it })
        link.start()
        val until = System.currentTimeMillis() + 3000
        while (!link.alive && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(link.alive)
        val keys = listOf(DesktopProtocol.key(0xE1, true), DesktopProtocol.key(4, true), DesktopProtocol.key(4, false), DesktopProtocol.key(0xE1, false))
        keys.forEach { link.send(it, reliable = true) }
        val got = (keys.indices).mapNotNull { back.poll(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertEquals(keys.map { it.toList() }, got.map { it.toList() })
        link.close(); serverThread.interrupt(); server.close()
    }
}
