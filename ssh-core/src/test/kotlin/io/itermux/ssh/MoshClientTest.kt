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
                client.run(Channel(), Channel(), intArrayOf(80, 24), 8_000, 600_000,
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

    @Test fun clientGivesUpOneMinuteBeforeItsServer() {
        // A tmux session resumes seamlessly after up to 14 minutes of sleep, and reattaches after that.
        assertEquals(14 * 60_000L, JschSession.moshSilenceLimitMs(SessionMode.TMUX))
        for (mode in SessionMode.entries)
            assertEquals(JschSession.moshServerTimeoutSeconds(mode) * 1000 - 60_000, JschSession.moshSilenceLimitMs(mode))
    }
}
