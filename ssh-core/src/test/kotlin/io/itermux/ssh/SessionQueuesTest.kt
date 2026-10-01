package io.itermux.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionQueuesTest {
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
        assertTrue(oldInput.tryReceive().isFailure)
        assertTrue(freshInput.tryReceive().isFailure)
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
    }
}
