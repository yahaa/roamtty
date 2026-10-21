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
}
