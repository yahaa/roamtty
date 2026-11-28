package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class MoshClientTest {
    private val key = "4NeCCgvZFe2RnPgrcU1PQw"

    /** Answers the first client datagram once, then goes silent like an expired mosh-server. */
    private fun answerOnce(server: DatagramSocket) = Thread {
        val buffer = ByteArray(65_535)
        val request = DatagramPacket(buffer, buffer.size)
        server.receive(request)
        val instruction = Mosh.Instruction(0, 1, 0, 0)
        val fragment = Mosh.fragments(0, Mosh.compress(instruction.encode()), 400).single()
        val plain = ByteBuffer.allocate(4 + fragment.size).putShort(0).putShort(-1).put(fragment).array()
        val sealed = Mosh.Crypto(key).seal(plain, toClient = true)
        server.send(DatagramPacket(sealed, sealed.size, request.socketAddress))
    }.apply { isDaemon = true; start() }

    @Test fun sleepLongerThanSilenceLimitEndsSessionAtOnce() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        server.use {
            answerOnce(server)
            // Elapsed time that, like Android's elapsedRealtime, keeps counting through device sleep.
            val clock = AtomicLong(1_000)
            val connected = CompletableDeferred<Unit>()
            val client = MoshClient(InetAddress.getLoopbackAddress(), server.localPort, key, clock::get)
            val end = async(Dispatchers.Default) {
                client.run(SessionInputQueue(), Channel(), intArrayOf(80, 24), 8_000, 600_000,
                    isStopped = { false }, onConnected = { connected.complete(Unit) }, onOutput = {})
            }
            withTimeout(5_000) { connected.await() }
            delay(300)
            assertTrue("a quiet server alone must not end the session", end.isActive)
            // The phone slept overnight; mosh-server gave up long ago.
            clock.addAndGet(8 * 3600_000L)
            assertEquals(MoshClient.End.TIMED_OUT, withTimeout(2_000) { end.await() })
        }
    }

    /** Answers every client datagram while [answering] holds; [oldNum] picks the diff's base state. */
    private fun answerEach(server: DatagramSocket, answering: () -> Boolean = { true }, oldNum: (Long) -> Long = { it - 1 }) = Thread {
        val buffer = ByteArray(65_535)
        var num = 0L
        var clientNum = 0L
        try {
            while (true) {
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                // Each small client datagram carries the next state number: acknowledge the newest.
                clientNum++
                if (!answering()) continue
                num++
                val instruction = Mosh.Instruction(oldNum(num), num, clientNum, 0)
                val fragment = Mosh.fragments(num, Mosh.compress(instruction.encode()), 400).single()
                val plain = ByteBuffer.allocate(4 + fragment.size).putShort(0).putShort(-1).put(fragment).array()
                val sealed = Mosh.Crypto(key).seal(plain, toClient = true)
                server.send(DatagramPacket(sealed, sealed.size, request.socketAddress))
            }
        } catch (_: java.io.IOException) { /* closed */ }
    }.apply { isDaemon = true; start() }

    private fun CoroutineScope.runClient(server: DatagramSocket, clock: AtomicLong, connected: CompletableDeferred<Unit>,
        input: SessionInput = SessionInputQueue(), stallLimitMs: Long? = 10_000): Pair<MoshClient, Deferred<MoshClient.End>> {
        val client = MoshClient(InetAddress.getLoopbackAddress(), server.localPort, key, clock::get)
        return client to async(Dispatchers.Default) {
            client.run(input, Channel(), intArrayOf(80, 24), 8_000, 600_000, stallLimitMs,
                isStopped = { false }, onConnected = { connected.complete(Unit) }, onOutput = {})
        }
    }

    // After a night of sleep the UDP path (often through a VPN) can be dead while nothing has
    // noticed: the wake probe must reattach tmux in seconds, not after the 14-minute silence limit.
    @Test fun unansweredWakeProbeEndsTmuxSession() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val answering = java.util.concurrent.atomic.AtomicBoolean(true)
            answerEach(server, answering::get)
            val clock = AtomicLong(1_000)
            val connected = CompletableDeferred<Unit>()
            val (client, end) = runClient(server, clock, connected)
            withTimeout(5_000) { connected.await() }
            answering.set(false)
            client.probe()
            delay(500)
            assertTrue(end.isActive)
            clock.addAndGet(11_000)
            assertEquals(MoshClient.End.TIMED_OUT, withTimeout(2_000) { end.await() })
        }
    }

    @Test fun answeredWakeProbeKeepsSession() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            answerEach(server)
            val clock = AtomicLong(1_000)
            val connected = CompletableDeferred<Unit>()
            val (client, end) = runClient(server, clock, connected)
            withTimeout(5_000) { connected.await() }
            client.probe()
            delay(500)
            clock.addAndGet(11_000)
            delay(500)
            assertTrue("a server that answers the probe keeps the session", end.isActive)
            end.cancel()
        }
    }

    @Test fun unansweredInputEndsOnlyTmuxSession() = runBlocking {
        for (stallLimit in listOf(10_000L, null)) DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val answering = java.util.concurrent.atomic.AtomicBoolean(true)
            answerEach(server, answering::get)
            val clock = AtomicLong(1_000)
            val connected = CompletableDeferred<Unit>()
            val input = SessionInputQueue()
            val (_, end) = runClient(server, clock, connected, input, stallLimit)
            withTimeout(5_000) { connected.await() }
            delay(300)
            answering.set(false)
            assertTrue(input.trySend("ls\r".toByteArray()))
            delay(300)
            clock.addAndGet(11_000)
            delay(700)
            if (stallLimit == null) {
                // A plain shell lives only in its mosh-server: keep waiting for the network.
                assertTrue(end.isActive)
                end.cancel()
            } else assertEquals(MoshClient.End.TIMED_OUT, withTimeout(2_000) { end.await() })
        }
    }

    @Test fun packetsThatNeverApplyEndTmuxSession() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            // The first state applies; every later diff names a base the client never had.
            answerEach(server, oldNum = { if (it == 1L) 0 else 999 })
            val clock = AtomicLong(1_000)
            val connected = CompletableDeferred<Unit>()
            val (_, end) = runClient(server, clock, connected)
            withTimeout(5_000) { connected.await() }
            delay(300)
            // Heartbeats keep getting answered, yet none of the answers applies.
            withTimeout(10_000) {
                while (end.isActive) { clock.addAndGet(3_000); delay(100) }
            }
            assertEquals(MoshClient.End.TIMED_OUT, end.await())
        }
    }

    // Logged on the phone: the app was frozen 42 s, and the first packet after the thaw was taken
    // for a desync. Only packets that keep failing to apply for a while mean one.
    @Test fun freezeThenFirstPacketIsNotADesync() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            answerEach(server)
            val clock = AtomicLong(1_000)
            val connected = CompletableDeferred<Unit>()
            val (client, end) = runClient(server, clock, connected)
            withTimeout(5_000) { connected.await() }
            delay(300)
            clock.addAndGet(42_000)
            client.probe()
            delay(1_000)
            assertTrue("a thawed client that hears the server again keeps the session", end.isActive)
            end.cancel()
        }
    }

    @Test fun dictationBurstDeliversAllBytesWithoutDropping() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val crypto = Mosh.Crypto(key)
            val stateBytes = HashMap<Long, ByteArray>().apply { put(0L, ByteArray(0)) }
            val done = CompletableDeferred<String>()
            val expected = "你好，这是一段很长的微信输入法语音测试，超过六十四字也不会报错。".repeat(5)
            val expectedBytes = expected.toByteArray(Charsets.UTF_8)
            var datagramCount = 0
            val listener = Thread {
                val assembly = Mosh.Assembly()
                val buf = ByteArray(65_535)
                var num = 0L
                var lastClientAck = 0L
                try {
                    while (!done.isCompleted) {
                        val req = DatagramPacket(buf, buf.size)
                        server.receive(req)
                        datagramCount++
                        val plain = crypto.open(buf.copyOf(req.length), fromClient = true) ?: continue
                        val assembled = assembly.add(plain.copyOfRange(4, plain.size)) ?: continue
                        val inst = Mosh.Instruction.decode(Mosh.decompress(assembled))
                        val base = stateBytes[inst.oldNum]
                        if (base != null && inst.newNum > lastClientAck) {
                            val next = java.io.ByteArrayOutputStream().apply {
                                write(base)
                                if (inst.diff.isNotEmpty()) {
                                    for (event in Mosh.hostMessage(inst.diff)) {
                                        if (event is Mosh.HostEvent.Bytes) write(event.bytes)
                                    }
                                }
                            }.toByteArray()
                            stateBytes[inst.newNum] = next
                            lastClientAck = inst.newNum
                            if (next.size >= expectedBytes.size) {
                                done.complete(String(next, Charsets.UTF_8))
                            }
                        }
                        num++
                        val reply = Mosh.Instruction(num - 1, num, lastClientAck, 0)
                        val frag = Mosh.fragments(num, Mosh.compress(reply.encode()), 400).single()
                        val replyPlain = ByteBuffer.allocate(4 + frag.size).putShort(0).putShort(-1).put(frag).array()
                        val sealed = crypto.seal(replyPlain, toClient = true)
                        server.send(DatagramPacket(sealed, sealed.size, req.socketAddress))
                    }
                } catch (_: java.io.IOException) { /* closed */ }
            }.apply { isDaemon = true; start() }
            val clock = AtomicLong(1_000)
            val connected = CompletableDeferred<Unit>()
            val input = SessionInputQueue()
            val (_, end) = runClient(server, clock, connected, input)
            withTimeout(5_000) { connected.await() }
            for (ch in expected) {
                assertTrue(input.trySend(ch.toString().toByteArray(Charsets.UTF_8)))
            }
            val got = withTimeout(5_000) { done.await() }
            assertEquals(expected, got)
            assertTrue("coalesced burst should not spam 1 datagram per char (got $datagramCount)", datagramCount < 30)
            end.cancel()
            listener.interrupt()
        }
    }

    @Test fun clientGivesUpOneMinuteBeforeItsServer() {
        // A tmux session resumes seamlessly after up to 14 minutes of sleep, and reattaches after that.
        assertEquals(14 * 60_000L, JschSession.moshSilenceLimitMs(SessionMode.TMUX))
        for (mode in SessionMode.entries)
            assertEquals(JschSession.moshServerTimeoutSeconds(mode) * 1000 - 60_000, JschSession.moshSilenceLimitMs(mode))
    }
}
