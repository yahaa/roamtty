package io.itermux.app.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.itermux.app.data.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Whether a saved host answers on its SSH port. */
internal enum class HostReachability { CHECKING, ONLINE, OFFLINE }

private const val PROBE_TIMEOUT_MS = 3_000
private const val PROBE_INTERVAL_MS = 30_000L

/** Opens and closes a TCP connection to the host's SSH port; no SSH handshake or login happens. */
internal suspend fun probeHost(address: String, port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean =
    withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) } }.isSuccess
    }

/**
 * Probes every host while the screen is resumed and again every [PROBE_INTERVAL_MS], so the list
 * reflects hosts going up or down without a manual refresh. Results are keyed by host id and keep
 * the last answer while a re-probe runs; an edited address or port starts over as [CHECKING].
 */
@Composable
internal fun rememberHostReachability(hosts: List<Host>): Map<String, HostReachability> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var results by remember { mutableStateOf(emptyMap<String, Pair<String, HostReachability>>()) }
    val targets = hosts.map { it.id to "${it.address}:${it.port}" }
    LaunchedEffect(targets) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                coroutineScope {
                    hosts.forEach { host ->
                        val target = "${host.address}:${host.port}"
                        launch {
                            val online = probeHost(host.address, host.port)
                            results = results + (host.id to (target to if (online) HostReachability.ONLINE else HostReachability.OFFLINE))
                        }
                    }
                }
                delay(PROBE_INTERVAL_MS)
            }
        }
    }
    return targets.associate { (id, target) ->
        id to (results[id]?.takeIf { it.first == target }?.second ?: HostReachability.CHECKING)
    }
}
