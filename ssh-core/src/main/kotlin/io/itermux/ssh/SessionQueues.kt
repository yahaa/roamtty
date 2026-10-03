package io.itermux.ssh

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded PTY stdin / resize queues. Recreated on each connect attempt so a closed or
 * drained channel from a prior attempt cannot accept stale input after reattach.
 */
internal class SessionQueues {
    private val accepting = AtomicBoolean(false)
    @Volatile private var input: Channel<ByteArray> = Channel(64)
    @Volatile private var sizes: Channel<IntArray> = Channel(Channel.CONFLATED)

    @Synchronized fun setAccepting(value: Boolean) { accepting.set(value) }
    fun isAccepting(): Boolean = accepting.get()

    @Synchronized fun write(bytes: ByteArray, stopped: Boolean): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.size > 256 * 1024 || stopped || !accepting.get()) return false
        val queue = input
        val copy = bytes.copyOf()
        if (queue.trySend(copy).isSuccess) return true
        copy.fill(0)
        return false
    }

    fun trySendSize(dimensions: IntArray) {
        sizes.trySend(dimensions)
    }

    fun currentInput(): Channel<ByteArray> = input
    fun currentSizes(): Channel<IntArray> = sizes

    @Synchronized fun reopen() {
        close()
        input = Channel(64)
        sizes = Channel(Channel.CONFLATED)
    }

    /** Stop accepting and wipe any input that was never written. */
    @Synchronized fun close() {
        accepting.set(false)
        input.close()
        sizes.close()
        while (true) (input.tryReceive().getOrNull() ?: break).fill(0)
    }
}
