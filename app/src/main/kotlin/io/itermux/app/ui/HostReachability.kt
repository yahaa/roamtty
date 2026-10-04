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

/** Whether a saved host answers on its SSH port. */
internal enum class HostReachability { CHECKING, ONLINE, OFFLINE }

private const val PROBE_TIMEOUT_MS = 3_000
private const val ONLINE_INTERVAL_MS = 60_000L
private const val OFFLINE_FIRST_RETRY_MS = 30_000L
private const val OFFLINE_MAX_INTERVAL_MS = 300_000L

/** Opens and closes a TCP connection to the host's SSH port; no SSH handshake or login happens. */
internal suspend fun probeHost(address: String, port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean =
    withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) } }.isSuccess
    }

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
    private class Entry(val target: String, val online: Boolean, val failures: Int, val dueAt: Long)
    private var entries by mutableStateOf(emptyMap<String, Entry>())

    private fun entry(host: Host) = entries[host.id]?.takeIf { it.target == host.probeTarget }

    fun now() = clock()

    fun statusOf(host: Host): HostReachability = when (entry(host)?.online) {
        true -> HostReachability.ONLINE
        false -> HostReachability.OFFLINE
        null -> HostReachability.CHECKING
    }

    /** When the host should next be probed; hosts never probed are due immediately. */
    fun dueAt(host: Host): Long = entry(host)?.dueAt ?: Long.MIN_VALUE

    fun record(host: Host, online: Boolean) {
        val failures = if (online) 0 else (entry(host)?.failures ?: 0) + 1
        entries = entries + (host.id to Entry(host.probeTarget, online, failures, now() + probeInterval(online, failures)))
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
                        try { tracker.record(host, probeHost(host.address, host.port)) } finally { inFlight -= host.id }
                    }
                }
                delay(1_000)
            }
        }
    }
}
