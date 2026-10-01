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
        accepting.set(false)
        val previousInput = input
        val previousSizes = sizes
        input = Channel(64)
        sizes = Channel(Channel.CONFLATED)
        previousInput.close()
        previousSizes.close()
        while (true) (previousInput.tryReceive().getOrNull() ?: break).fill(0)
        while (true) previousSizes.tryReceive().getOrNull() ?: break
    }

    @Synchronized fun close() {
        accepting.set(false)
        val currentInput = input
        val currentSizes = sizes
        currentInput.close()
        currentSizes.close()
        while (true) (currentInput.tryReceive().getOrNull() ?: break).fill(0)
    }
}
