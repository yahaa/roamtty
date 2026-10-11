package io.itermux.app.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.itermux.app.data.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Whether a saved host's sshd answers, with the DNS resolution and TCP connection time when it does. */
internal sealed interface HostReachability {
    data object Checking : HostReachability
    data object Offline : HostReachability
    /** Last DNS + TCP connect time, excluding the SSH greeting; null when only a live session proves it is up. */
    data class Online(val latencyMs: Long?) : HostReachability
}

private const val PROBE_TIMEOUT_MS = 3_000
private const val ONLINE_INTERVAL_MS = 60_000L
private const val OFFLINE_FIRST_RETRY_MS = 30_000L
private const val OFFLINE_MAX_INTERVAL_MS = 300_000L

/** Measures DNS + TCP connection time and separately checks SSH availability; no key exchange or login happens. */
internal suspend fun probeHost(host: Host, timeoutMs: Int = PROBE_TIMEOUT_MS): HostReachability =
    withContext(Dispatchers.IO) {
        sshTcpConnectTime(host.address, host.port, timeoutMs)?.let { HostReachability.Online(it) } ?: HostReachability.Offline
    }

/**
 * Times name resolution through successful TCP connect to [address]:[port]. This follows the socket's actual route,
 * including Tailscale; a TCP-terminating proxy can limit the measurement to the proxy leg.
 * After resolution, TCP connect and a valid SSH identification share the same [timeoutMs] budget:
 * a local proxy accepting a connection for an offline host must not make it appear online. Waiting for sshd's
 * greeting is deliberately excluded from the latency, as are key exchange and authentication.
 */
internal fun sshTcpConnectTime(address: String, port: Int, timeoutMs: Int): Long? = runCatching {
    Socket().use { socket ->
        val start = System.nanoTime()
        val target = InetSocketAddress(address, port)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        socket.connect(target, timeoutMs)
        val connectMs = (System.nanoTime() - start) / 1_000_000
        val input = socket.getInputStream()
        // RFC 4253 lets a server send other lines before its version line, so skip those (bounded).
        val line = StringBuilder()
        repeat(MAX_GREETING_BYTES) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return@runCatching null
            socket.soTimeout = remainingMs.toInt()
            when (val b = input.read()) {
                -1 -> return@runCatching null
                '\n'.code -> {
                    if (line.startsWith("SSH-")) {
                        return@runCatching connectMs.takeIf { line.length + 1 <= 255 && SSH_IDENTIFICATION.matches(line) }
                    }
                    line.clear()
                }
                else -> line.append(b.toChar())
            }
        }
        null
    }
}.getOrNull()

private const val MAX_GREETING_BYTES = 8_192
// RFC 4253: printable ASCII software name without spaces or '-', optional comments, and a complete line.
private val SSH_IDENTIFICATION = Regex("""SSH-(?:2\.0|1\.99)-[\x21-\x2C\x2E-\x7E]+(?: [^\x00\r\n]*)?\r?""")

/**
 * Wait before re-probing: online hosts every minute; offline hosts back off 30s, 1m, 2m, 4m, then
 * every 5m, so an unreachable server is not knocked on (and its sshd log not filled) twice a minute.
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
    private class Entry(val target: String, val result: HostReachability, val failures: Int, val dueAt: Long, val checkedAt: Long)
    private var entries by mutableStateOf(emptyMap<String, Entry>())

    private fun entry(host: Host) = entries[host.id]?.takeIf { it.target == host.probeTarget }

    fun now() = clock()

    fun statusOf(host: Host): HostReachability = entry(host)?.result ?: HostReachability.Checking

    /** When the host should next be probed; hosts never probed are due immediately. */
    fun dueAt(host: Host): Long = entry(host)?.dueAt ?: Long.MIN_VALUE

    /** When the shown result was measured, or null before the first probe. */
    fun checkedAt(host: Host): Long? = entry(host)?.checkedAt

    /** Probes the host again at once, keeping the last result (and its back-off count) until the new one arrives. */
    fun requestProbe(host: Host) {
        val entry = entry(host) ?: return
        entries = entries + (host.id to Entry(entry.target, entry.result, entry.failures, now(), entry.checkedAt))
    }

    fun record(host: Host, result: HostReachability) {
        val online = result is HostReachability.Online
        val failures = if (online) 0 else (entry(host)?.failures ?: 0) + 1
        val at = now()
        entries = entries + (host.id to Entry(host.probeTarget, result, failures, at + probeInterval(online, failures), at))
    }
}

/**
 * Probes due hosts while this is composed and the screen is resumed. Hosts in [connected] already show online and
 * are skipped: each probe is a connection that never logs in, and OpenSSH 9.8+ (macOS ships it) counts those
 * against the address under PerSourcePenalties until it refuses new logins from the phone.
 */
@Composable
internal fun ProbeHostReachability(tracker: HostReachabilityTracker, hosts: List<Host>, connected: Set<String> = emptySet()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentHosts by rememberUpdatedState(hosts)
    val currentConnected by rememberUpdatedState(connected)
    val targets = hosts.map { it.id to it.probeTarget }
    LaunchedEffect(tracker, targets) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Main-thread only: launched probes resume on the effect's dispatcher.
            val inFlight = mutableSetOf<String>()
            while (true) {
                currentHosts.filter { it.id !in inFlight && it.id !in currentConnected && tracker.dueAt(it) <= tracker.now() }.forEach { host ->
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
