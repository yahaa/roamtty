package io.itermux.app.ui

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.itermux.app.data.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Whether a saved host answers ping, with the round trip when it does. */
internal sealed interface HostReachability {
    data object Checking : HostReachability
    data object Offline : HostReachability
    /** [latencyMs] is null when the host is known to be up (a live session) but has not answered a probe. */
    data class Online(val latencyMs: Long?) : HostReachability
}

private const val PROBE_TIMEOUT_MS = 3_000
private const val ONLINE_INTERVAL_MS = 60_000L
private const val OFFLINE_FIRST_RETRY_MS = 30_000L
private const val OFFLINE_MAX_INTERVAL_MS = 300_000L

private const val ICMP_ECHO_REQUEST = 8
private const val ICMP_ECHO_REPLY = 0
private const val ICMPV6_ECHO_REQUEST = 128
private const val ICMPV6_ECHO_REPLY = 129
private val pingSequence = AtomicInteger()

/** Thrown when this device does not let apps open ICMP sockets. */
private class PingUnavailable(cause: Throwable) : Exception(cause)

/**
 * Pings the host once (ICMP echo) and returns the round trip in milliseconds. Devices that refuse
 * unprivileged ICMP sockets fall back to timing a TCP connect to the SSH port.
 */
internal suspend fun probeHost(host: Host, timeoutMs: Int = PROBE_TIMEOUT_MS): HostReachability =
    withContext(Dispatchers.IO) {
        val latency = try { pingRoundTrip(host.address, timeoutMs) }
            catch (_: PingUnavailable) { tcpRoundTrip(host.address, host.port, timeoutMs) }
        latency?.let { HostReachability.Online(it) } ?: HostReachability.Offline
    }

/** Milliseconds for a TCP connect to [address]:[port], or null if it fails; no SSH handshake happens. */
internal fun tcpRoundTrip(address: String, port: Int, timeoutMs: Int): Long? = runCatching {
    Socket().use { socket ->
        val target = InetSocketAddress(address, port)
        val start = System.nanoTime()
        socket.connect(target, timeoutMs)
        (System.nanoTime() - start) / 1_000_000
    }
}.getOrNull()

/**
 * One ICMP echo over an unprivileged ping socket (SOCK_DGRAM + IPPROTO_ICMP[V6]); the kernel fills
 * in the identifier and checksum and only delivers replies addressed to this socket.
 */
private fun pingRoundTrip(address: String, timeoutMs: Int): Long? {
    val target = runCatching { InetAddress.getAllByName(address) }.getOrNull()
        ?.let { all -> all.firstOrNull { it is Inet4Address } ?: all.firstOrNull() } ?: return null
    val v6 = target is Inet6Address
    val fd = try {
        Os.socket(if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET, OsConstants.SOCK_DGRAM,
            if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP)
    } catch (e: ErrnoException) { throw PingUnavailable(e) }
    try {
        val sequence = pingSequence.incrementAndGet() and 0xFFFF
        val request = icmpEchoRequest(v6, sequence)
        val start = System.nanoTime()
        Os.sendto(fd, request, 0, request.size, 0, target, 0)
        val deadline = start + timeoutMs * 1_000_000L
        val reply = ByteArray(1500)
        while (true) {
            val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (left <= 0 || !awaitReadable(fd, left)) return null
            val length = Os.recvfrom(fd, reply, 0, reply.size, 0, null)
            if (isIcmpEchoReply(reply, length, v6, sequence)) return (System.nanoTime() - start) / 1_000_000
        }
    } catch (_: ErrnoException) {
        return null // unreachable network or host, interrupted poll, …
    } finally {
        runCatching { Os.close(fd) }
    }
}

private fun awaitReadable(fd: FileDescriptor, timeoutMs: Int): Boolean {
    val poll = StructPollfd().also { it.fd = fd; it.events = OsConstants.POLLIN.toShort() }
    return Os.poll(arrayOf(poll), timeoutMs) > 0
}

/** Echo request header (type, code, checksum, identifier, sequence) plus an 8-byte payload. */
internal fun icmpEchoRequest(v6: Boolean, sequence: Int): ByteArray = ByteArray(16).also {
    it[0] = (if (v6) ICMPV6_ECHO_REQUEST else ICMP_ECHO_REQUEST).toByte()
    it[6] = (sequence shr 8).toByte()
    it[7] = sequence.toByte()
    "iTermux!".toByteArray().copyInto(it, 8)
}

internal fun isIcmpEchoReply(packet: ByteArray, length: Int, v6: Boolean, sequence: Int): Boolean =
    length >= 8 && (packet[0].toInt() and 0xFF) == (if (v6) ICMPV6_ECHO_REPLY else ICMP_ECHO_REPLY) &&
        ((packet[6].toInt() and 0xFF) shl 8 or (packet[7].toInt() and 0xFF)) == sequence

/**
 * Wait before re-probing: online hosts every minute; offline hosts back off 30s, 1m, 2m, 4m, then
 * every 5m, so an unreachable server is not pinged twice a minute for as long as the list is open.
 */
internal fun probeInterval(online: Boolean, failures: Int): Long =
    if (online) ONLINE_INTERVAL_MS
    else (OFFLINE_FIRST_RETRY_MS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(OFFLINE_MAX_INTERVAL_MS)

private val Host.probeTarget get() = "$address:$port"

/**
 * Last probe result per host, kept above the host page so switching pages neither forgets the
 * status nor re-probes a host whose next check is not yet due. An edited address or port starts over.
 */
@Stable
internal class HostReachabilityTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Entry(val target: String, val result: HostReachability, val failures: Int, val dueAt: Long)
    private var entries by mutableStateOf(emptyMap<String, Entry>())

    private fun entry(host: Host) = entries[host.id]?.takeIf { it.target == host.probeTarget }

    fun now() = clock()

    fun statusOf(host: Host): HostReachability = entry(host)?.result ?: HostReachability.Checking

    /** When the host should next be probed; hosts never probed are due immediately. */
    fun dueAt(host: Host): Long = entry(host)?.dueAt ?: Long.MIN_VALUE

    fun record(host: Host, result: HostReachability) {
        val online = result is HostReachability.Online
        val failures = if (online) 0 else (entry(host)?.failures ?: 0) + 1
        entries = entries + (host.id to Entry(host.probeTarget, result, failures, now() + probeInterval(online, failures)))
    }
}

/** Probes due hosts while this is composed and the screen is resumed. */
@Composable
internal fun ProbeHostReachability(tracker: HostReachabilityTracker, hosts: List<Host>) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentHosts by rememberUpdatedState(hosts)
    val targets = hosts.map { it.id to it.probeTarget }
    LaunchedEffect(tracker, targets) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Main-thread only: launched probes resume on the effect's dispatcher.
            val inFlight = mutableSetOf<String>()
            while (true) {
                currentHosts.filter { it.id !in inFlight && tracker.dueAt(it) <= tracker.now() }.forEach { host ->
                    inFlight += host.id
                    launch {
                        try { tracker.record(host, probeHost(host)) } finally { inFlight -= host.id }
                    }
                }
                delay(1_000)
            }
        }
    }
}
