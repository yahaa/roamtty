package io.itermux.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionQueuesTest {
    @Test fun staleStartupGenerationCannotEnterReplacementQueue() = runBlocking {
        val queues = SessionQueues()
        val previous = queues.reopen()
        queues.setAccepting(true)
        val current = queues.reopen()
        queues.setAccepting(true)
        assertFalse(queues.write("old".toByteArray(), stopped = false, expectedGeneration = previous))
        assertNull(queues.currentInput().tryReceive())
        assertTrue(queues.write("new".toByteArray(), stopped = false, expectedGeneration = current))
        assertArrayEquals("new".toByteArray(), queues.currentInput().receive())
    }

    @Test fun writeRejectedWhenNotAccepting() {
        val queues = SessionQueues()
        assertFalse(queues.isAccepting())
        assertFalse(queues.write("x".toByteArray(), stopped = false))
        queues.setAccepting(true)
        assertTrue(queues.write("ok".toByteArray(), stopped = false))
        assertFalse(queues.write("no".toByteArray(), stopped = true))
    }

    @Test fun reopenCreatesFreshQueuesAndDrainsOldBytes() = runBlocking {
        val queues = SessionQueues()
        queues.setAccepting(true)
        assertTrue(queues.write(byteArrayOf(1, 2, 3), stopped = false))
        val oldInput = queues.currentInput()
        val oldSizes = queues.currentSizes()
        queues.reopen()
        val freshInput = queues.currentInput()
        val freshSizes = queues.currentSizes()
        assertNotSame(oldInput, freshInput)
        assertNotSame(oldSizes, freshSizes)
        assertTrue(oldInput.isClosedForSend)
        assertTrue(oldSizes.isClosedForSend)
        assertNull(oldInput.tryReceive())
        assertNull(oldInput.receive())
        assertNull(freshInput.tryReceive())
        // Reattach must explicitly enable the new writer before any input can enter it.
        assertFalse(queues.isAccepting())
        assertFalse(queues.write(byteArrayOf(8), stopped = false))
        queues.setAccepting(true)
        assertTrue(queues.write(byteArrayOf(9), stopped = false))
        val received = freshInput.receive()
        assertArrayEquals(byteArrayOf(9), received)
    }

    @Test fun closeStopsAcceptingAndClosesChannels() {
        val queues = SessionQueues()
        queues.setAccepting(true)
        assertTrue(queues.write("a".toByteArray(), stopped = false))
        queues.close()
        assertFalse(queues.isAccepting())
        assertFalse(queues.write("b".toByteArray(), stopped = false))
        assertTrue(queues.currentInput().isClosedForSend)
        assertNull(queues.currentInput().tryReceive())
    }

    @Test fun longDictationBurstCoalescesBeyondSixtyFourWrites() = runBlocking {
        val queues = SessionQueues()
        queues.setAccepting(true)
        val utterance = "你好，你能听到我说话吗？我现在在车上跟你聊天，这是一段超过六十四字的微信输入法长语音测试。".repeat(8)
        for (ch in utterance) {
            assertTrue("char '$ch' must not overflow queue", queues.write(ch.toString().toByteArray(Charsets.UTF_8), stopped = false))
        }
        val coalesced = checkNotNull(queues.currentInput().receive())
        assertEquals(utterance, String(coalesced, Charsets.UTF_8))
        assertNull(queues.currentInput().tryReceive())
    }

    @Test fun byteBudgetRejectsOverflowAndRecoversAfterDrain() = runBlocking {
        val queues = SessionQueues()
        queues.setAccepting(true)
        assertFalse(queues.write(ByteArray(SessionInputQueue.MAX_WRITE_BYTES + 1), stopped = false))
        val chunk = ByteArray(SessionInputQueue.MAX_CHUNK_BYTES) { 0x41 }
        repeat(4) { assertTrue("chunk $it fits within 1 MiB budget", queues.write(chunk, stopped = false)) }
        assertFalse("write past 1 MiB budget must be refused", queues.write(byteArrayOf(0x42), stopped = false))
        val drained = checkNotNull(queues.currentInput().receive())
        assertEquals(SessionInputQueue.MAX_CHUNK_BYTES, drained.size)
        assertTrue("draining frees budget for new input", queues.write(byteArrayOf(0x42), stopped = false))
    }
}
