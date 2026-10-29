package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * A mosh terminal client over UDP. The server sends terminal diffs as escape sequences relative to
 * the last screen state this client acknowledged, so they are applied straight to the local emulator.
 * Only the newest server state is kept: a diff from any other base is dropped and the server resends
 * from the acknowledged state. User input is resent from the last acknowledged state until acked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MoshClient(
    private val address: InetAddress,
    private val port: Int,
    key: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Connection diagnostics only: timings and state numbers, never terminal content. */
    private val log: (String) -> Unit = {},
) {
    enum class End { SERVER_SHUTDOWN, STOPPED, TIMED_OUT, NO_CONTACT }

    private val crypto = Mosh.Crypto(key)
    private val assembly = Mosh.Assembly()
    @Volatile private var socket = DatagramSocket()
    private val packets = Channel<ByteArray>(256, BufferOverflow.DROP_OLDEST)

    // Client → server state: every state is "all user events up to count".
    private val events = ArrayList<ByteArray>()
    private var eventBase = 0L
    private val totalEvents get() = eventBase + events.size
    private val sentStates = ArrayDeque<LongArray>().apply { add(longArrayOf(0, 0)) }
    private var ackedNum = 0L
    private var ackedCount = 0L
    private var lastSentCount = 0L
    private var instructionId = 0L
    private var shutdownAcked = false

    // Server → client state.
    private var remoteNum = 0L
    private var ackPending = false
    private var ackDueAt = 0L

    // Timing.
    private var lastSend = 0L
    private var lastHeard = 0L
    private var lastHop = 0L
    private var savedTimestamp = -1
    private var savedTimestampAt = 0L
    /** When the server's newest state was last accepted; packets that never apply mean a desync. */
    private var lastAccepted = 0L
    /** First packet heard since the last applied state, or -1: a frozen app hears old packets first. */
    private var unappliedSince = -1L
    /** Since when user input waits for an ack, or -1. */
    private var inputPendingSince = -1L
    /** When a requested liveness probe went out, or -1 once the server answered it. */
    private var probeSince = -1L
    @Volatile private var probeRequested = false
    private var srtt = 1000.0
    private var rttvar = 500.0
    private var heardAny = false
    private val rto get() = (srtt + 4 * rttvar).toLong().coerceIn(50, 1000)

    private sealed class Local {
        class Keys(val bytes: ByteArray) : Local()
        class Size(val columns: Int, val rows: Int) : Local()
    }

    /**
     * Asks the server for a reply now (after the app returns from sleep or the network changes).
     * The reply proves the path still works; with a stall limit, silence ends the session.
     */
    fun probe() { probeRequested = true }

    suspend fun run(
        input: ReceiveChannel<ByteArray>,
        sizes: ReceiveChannel<IntArray>,
        initialSize: IntArray,
        firstContactTimeoutMs: Long,
        silenceLimitMs: Long,
        /**
         * When set, give up after this long without a reply to a probe or to typed input instead of
         * waiting out [silenceLimitMs]: a tmux session reattaches in seconds, while a dead path would
         * otherwise leave a frozen screen that silently drops every keystroke.
         */
        stallLimitMs: Long? = null,
        isStopped: () -> Boolean,
        onConnected: () -> Unit,
        onOutput: suspend (ByteArray) -> Unit,
    ): End = coroutineScope {
        val local = Channel<Local>(Channel.UNLIMITED)
        val receiver = launch(Dispatchers.IO) { receiveLoop() }
        val keys = launch { for (bytes in input) { local.send(Local.Keys(bytes.copyOf())); bytes.fill(0) } }
        val resizes = launch { for (d in sizes) local.send(Local.Size(d[0], d[1])) }
        val start = clock()
        lastHop = start
        var shutdownByServer = false
        try {
            push(Mosh.UserEvent.Resize(initialSize[0], initialSize[1]))
            while (true) {
                if (isStopped()) return@coroutineScope End.STOPPED
                val now = clock()
                if (!heardAny && now - start > firstContactTimeoutMs) return@coroutineScope End.NO_CONTACT
                if (heardAny && now - lastHeard > silenceLimitMs) {
                    log("mosh silent ${now - lastHeard} ms, giving up")
                    return@coroutineScope End.TIMED_OUT
                }
                if (probeRequested) {
                    probeRequested = false
                    if (heardAny) {
                        // A socket from before the sleep may sit on a stale NAT/VPN mapping: hop at once.
                        if (probeSince < 0) probeSince = now
                        log("mosh probe: last heard ${now - lastHeard} ms ago, last applied ${now - lastAccepted} ms ago")
                        hopPort(now)
                        send(now)
                        continue
                    }
                }
                if (stallLimitMs != null && heardAny && stalled(now, stallLimitMs)) {
                    log("mosh stalled: probe=${if (probeSince < 0) -1 else now - probeSince} ms input=${if (inputPendingSince < 0) -1 else now - inputPendingSince} ms heard=${now - lastHeard} ms applied=${now - lastAccepted} ms")
                    return@coroutineScope End.TIMED_OUT
                }
                // Like mosh, hop to a fresh source port when the server goes quiet (NAT rebinding, roaming).
                if (heardAny && now - lastHeard > PORT_HOP_MS && now - lastHop > PORT_HOP_MS) hopPort(now)
                val due = nextSendAt(now)
                if (due <= now) { send(now); continue }
                select<Unit> {
                    packets.onReceive { packet ->
                        if (receive(packet, onConnected, onOutput)) shutdownByServer = true
                    }
                    local.onReceive { event ->
                        when (event) {
                            is Local.Keys -> { push(Mosh.UserEvent.Keys(event.bytes)); event.bytes.fill(0) }
                            is Local.Size -> push(Mosh.UserEvent.Resize(event.columns, event.rows))
                        }
                    }
                    onTimeout((due - now).coerceIn(1, 250)) {}
                }
                if (shutdownByServer) {
                    // Acknowledge the server's shutdown so it can exit at once instead of timing out.
                    repeat(3) { send(clock(), shutdown = true) }
                    return@coroutineScope End.SERVER_SHUTDOWN
                }
            }
            @Suppress("UNREACHABLE_CODE") End.STOPPED
        } finally {
            keys.cancel(); resizes.cancel()
            if (!shutdownByServer) withContext(NonCancellable) {
                // Best effort: let mosh-server exit (tmux detaches) rather than wait for a lost client.
                runCatching { repeat(2) { send(clock(), shutdown = true) } }
            }
            receiver.cancel()
            socket.close()
            events.forEach { it.fill(0) }
        }
    }

    private fun push(event: Mosh.UserEvent) {
        val encoded = event.encode()
        // Bound unacked input so a shell that never stalls cannot grow without limit while offline.
        var pending = events.sumOf { it.size } + encoded.size
        while (events.isNotEmpty() && pending > MAX_UNACKED_BYTES) {
            val dropped = events.removeAt(0)
            pending -= dropped.size
            eventBase++
            dropped.fill(0)
            log("mosh unacked input capped, dropped oldest event")
        }
        events += encoded
        if (inputPendingSince < 0) inputPendingSince = clock()
    }

    private fun stalled(now: Long, limit: Long): Boolean {
        // Waiting on a reply that never came: a probe or typed input, and nothing heard since.
        val waiting = listOf(probeSince, inputPendingSince).filter { it >= 0 }.minOrNull()
        if (waiting != null && now - waiting > limit && now - lastHeard > limit) return true
        // Packets keep arriving yet none applies: the two sides no longer agree on the screen state.
        return unappliedSince >= 0 && lastHeard - unappliedSince > DESYNC_LIMIT_MS
    }

    private fun nextSendAt(now: Long): Long = when {
        totalEvents > lastSentCount -> now
        ackPending -> ackDueAt
        totalEvents > ackedCount -> lastSend + rto
        else -> lastSend + HEARTBEAT_MS
    }

    private fun send(now: Long, shutdown: Boolean = false) {
        val newNum = if (shutdown) Mosh.SHUTDOWN_NUM else sentStates.last()[0] + 1
        val count = totalEvents
        val diff = if (count > ackedCount)
            Mosh.userMessage(events.subList((ackedCount - eventBase).toInt(), (count - eventBase).toInt()))
        else ByteArray(0)
        val instruction = Mosh.Instruction(ackedNum, newNum, remoteNum, ackedNum, diff)
        val payload = Mosh.compress(instruction.encode())
        for (fragment in Mosh.fragments(instructionId++, payload, MAX_FRAGMENT)) {
            val plain = ByteBuffer.allocate(4 + fragment.size)
                .putShort((now and 0xFFFF).toInt().toShort()).putShort(timestampReply(now).toShort())
                .put(fragment).array()
            val datagram = crypto.seal(plain)
            // No route (airplane mode, network switch) is not fatal: mosh resumes when packets flow again.
            try { socket.send(DatagramPacket(datagram, datagram.size, address, port)) } catch (_: IOException) {}
        }
        if (!shutdown) {
            sentStates.addLast(longArrayOf(newNum, count))
            // Keep the acked base; drop the oldest unacked states if acks stall for very long.
            while (sentStates.size > 256) sentStates.removeAt(1)
        }
        lastSentCount = count
        lastSend = now
        ackPending = false
    }

    private fun timestampReply(now: Long): Int {
        if (savedTimestamp < 0 || now - savedTimestampAt > 1000) return 0xFFFF
        val reply = (savedTimestamp + (now - savedTimestampAt)).toInt() and 0xFFFF
        savedTimestamp = -1
        return reply
    }

    /** Returns true once the server has shut down. */
    private suspend fun receive(packet: ByteArray, onConnected: () -> Unit, onOutput: suspend (ByteArray) -> Unit): Boolean {
        val plain = crypto.open(packet) ?: return false
        if (plain.size < 4) return false
        val now = clock()
        val buffer = ByteBuffer.wrap(plain)
        savedTimestamp = buffer.short.toInt() and 0xFFFF
        savedTimestampAt = now
        val reply = buffer.short.toInt() and 0xFFFF
        if (reply != 0xFFFF) {
            val rtt = ((now and 0xFFFF) - reply).toInt() and 0xFFFF
            if (rtt < 5000) {
                if (!heardAny) { srtt = rtt.toDouble(); rttvar = rtt / 2.0 }
                else { rttvar = 0.75 * rttvar + 0.25 * kotlin.math.abs(srtt - rtt); srtt = 0.875 * srtt + 0.125 * rtt }
            }
        }
        if (unappliedSince < 0) unappliedSince = now
        if (probeSince >= 0) log("mosh probe answered after ${now - probeSince} ms")
        if (now - lastHeard > PORT_HOP_MS && heardAny) log("mosh heard again after ${now - lastHeard} ms")
        lastHeard = now
        probeSince = -1L
        if (!heardAny) { heardAny = true; lastAccepted = now; onConnected() }
        val assembled = assembly.add(plain.copyOfRange(4, plain.size)) ?: return false
        val instruction = Mosh.Instruction.decode(Mosh.decompress(assembled))
        if (instruction.protocolVersion != Mosh.PROTOCOL_VERSION) throw IOException("mosh protocol version mismatch")
        acknowledge(instruction.ackNum)
        // A resend of the state this client holds still shows both sides agree.
        if (instruction.newNum == remoteNum) { lastAccepted = now; unappliedSince = -1L }
        if (instruction.newNum == remoteNum || instruction.oldNum != remoteNum) {
            // Duplicate, or a diff from a base this client no longer holds: re-ack the current state.
            scheduleAck(now)
            return false
        }
        for (event in Mosh.hostMessage(instruction.diff)) {
            // Size changes always arrive with a full repaint; the local emulator already follows the view.
            if (event is Mosh.HostEvent.Bytes && event.bytes.isNotEmpty()) onOutput(event.bytes)
        }
        remoteNum = instruction.newNum
        lastAccepted = now
        unappliedSince = -1L
        scheduleAck(now)
        return remoteNum == Mosh.SHUTDOWN_NUM
    }

    private fun scheduleAck(now: Long) {
        if (!ackPending) { ackPending = true; ackDueAt = now + ACK_DELAY_MS }
    }

    private fun acknowledge(num: Long) {
        if (num == Mosh.SHUTDOWN_NUM) { shutdownAcked = true; return }
        if (num <= ackedNum) return
        val index = sentStates.indexOfFirst { it[0] == num }
        if (index < 0) return
        val count = sentStates[index][1]
        repeat(index) { sentStates.removeFirst() }
        ackedNum = num
        ackedCount = count
        val drop = (count - eventBase).toInt()
        if (drop > 0) {
            events.subList(0, drop).apply { forEach { it.fill(0) }; clear() }
            eventBase = count
        }
        if (ackedCount >= totalEvents) inputPendingSince = -1L
    }

    private fun hopPort(now: Long) {
        lastHop = now
        log("mosh port hop, silent ${now - lastHeard} ms")
        val old = socket
        socket = try { DatagramSocket() } catch (_: SocketException) { return }
        old.close()
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(65_535)
        while (currentCoroutineContext().isActive) {
            val current = socket
            try {
                if (current.soTimeout != 200) current.soTimeout = 200
                val datagram = DatagramPacket(buffer, buffer.size)
                current.receive(datagram)
                if (datagram.address == address && datagram.port == port)
                    packets.trySend(buffer.copyOf(datagram.length))
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
                // Closed by a port hop or shutdown; pick up the replacement socket.
                if (current === socket) delay(50)
            }
        }
    }

    companion object {
        private const val MAX_FRAGMENT = 400
        /** Soft cap on unacknowledged user input held for resend (keys + resizes). */
        private const val MAX_UNACKED_BYTES = 256 * 1024
        private const val HEARTBEAT_MS = 3000L
        private const val ACK_DELAY_MS = 20L
        private const val PORT_HOP_MS = 10_000L
        /** mosh-server sends a new state at least every 3 s, even when the screen is idle. */
        private const val DESYNC_LIMIT_MS = 30_000L
    }
}
