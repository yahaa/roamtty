package io.itermux.ssh

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface SessionInput {
    suspend fun receive(): ByteArray?
}

/**
 * Coalescing, byte-bounded PTY stdin queue. Adjacent writes waiting for the transport join one
 * chunk (up to [maxChunkBytes]) so long IME dictation commits or delete bursts never hit an
 * item-count cap, while unread memory stays bounded by [maxQueuedBytes].
 */
internal class SessionInputQueue(
    private val maxWriteBytes: Int = MAX_WRITE_BYTES,
    private val maxChunkBytes: Int = MAX_CHUNK_BYTES,
    private val maxQueuedBytes: Int = MAX_QUEUED_BYTES,
) : SessionInput {
    private class Chunk(initial: ByteArray) {
        var data: ByteArray = initial
        var length: Int = initial.size

        fun append(bytes: ByteArray, limit: Int): Boolean {
            if (length + bytes.size > limit) return false
            if (length + bytes.size > data.size) {
                val nextSize = maxOf(data.size * 2, length + bytes.size).coerceAtMost(limit)
                val next = ByteArray(nextSize)
                data.copyInto(next, 0, 0, length)
                data.fill(0)
                data = next
            }
            bytes.copyInto(data, length)
            length += bytes.size
            return true
        }

        fun takeAndWipe(): ByteArray = try { data.copyOf(length) } finally { data.fill(0); length = 0 }
        fun wipe() { data.fill(0); length = 0 }
    }

    private val lock = Any()
    private val chunks = ArrayDeque<Chunk>()
    private var queuedBytes = 0
    private var closed = false
    private val signal = Channel<Unit>(Channel.CONFLATED)

    val isClosedForSend: Boolean get() = synchronized(lock) { closed }

    fun trySend(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.size > maxWriteBytes) return false
        synchronized(lock) {
            if (closed || queuedBytes + bytes.size > maxQueuedBytes) return false
            val tail = chunks.lastOrNull()
            if (tail == null || !tail.append(bytes, maxChunkBytes)) {
                chunks.addLast(Chunk(bytes.copyOf()))
            }
            queuedBytes += bytes.size
            signal.trySend(Unit)
            return true
        }
    }

    fun tryReceive(): ByteArray? = synchronized(lock) {
        val head = chunks.removeFirstOrNull() ?: return null
        queuedBytes -= head.length
        head.takeAndWipe()
    }

    override suspend fun receive(): ByteArray? {
        while (true) {
            synchronized(lock) {
                val head = chunks.removeFirstOrNull()
                if (head != null) {
                    queuedBytes -= head.length
                    return head.takeAndWipe()
                }
                if (closed) return null
            }
            if (signal.receiveCatching().isClosed) return tryReceive()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            while (true) (chunks.removeFirstOrNull() ?: break).wipe()
            queuedBytes = 0
            signal.close()
        }
    }

    companion object {
        const val MAX_WRITE_BYTES = 256 * 1024
        const val MAX_CHUNK_BYTES = 256 * 1024
        const val MAX_QUEUED_BYTES = 1024 * 1024
    }
}

/**
 * Bounded PTY stdin / resize queues. Recreated on each connect attempt so a closed or
 * drained queue from a prior attempt cannot accept stale input after reattach.
 */
internal class SessionQueues {
    private val accepting = AtomicBoolean(false)
    @Volatile private var input: SessionInputQueue = SessionInputQueue()
    @Volatile private var sizes: Channel<IntArray> = Channel(Channel.CONFLATED)
    private var generation = 0L

    @Synchronized fun setAccepting(value: Boolean) { accepting.set(value) }
    fun isAccepting(): Boolean = accepting.get()

    @Synchronized fun write(bytes: ByteArray, stopped: Boolean, expectedGeneration: Long? = null): Boolean {
        if (expectedGeneration != null && expectedGeneration != generation) return false
        if (bytes.isEmpty()) return true
        if (bytes.size > SessionInputQueue.MAX_WRITE_BYTES || stopped || !accepting.get()) return false
        return input.trySend(bytes)
    }

    fun trySendSize(dimensions: IntArray) {
        sizes.trySend(dimensions)
    }

    fun currentInput(): SessionInputQueue = input
    fun currentSizes(): Channel<IntArray> = sizes

    @Synchronized fun reopen(): Long {
        close()
        input = SessionInputQueue()
        sizes = Channel(Channel.CONFLATED)
        return ++generation
    }

    /** Stop accepting and wipe any input that was never written. */
    @Synchronized fun close() {
        accepting.set(false)
        input.close()
        sizes.close()
    }
}
