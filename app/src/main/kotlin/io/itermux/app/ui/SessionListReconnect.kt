package io.itermux.app.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.AuthMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * While the session list is on screen ([visible]), sessions that dropped, came back from the journal or lost the network
 * reconnect by themselves; only on hosts that need no typed password, and only once the host answers again.
 * [autoReconnectedAt] maps a session id to when it last reconnected by itself; see [AUTO_RECONNECT_INTERVAL_MS].
 */
@Composable
internal fun ReconnectFromSessionList(visible: Boolean, service: SessionService?, sessions: List<TerminalConnection>,
    hosts: List<Host>, reachability: HostReachabilityTracker, autoReconnectedAt: MutableMap<String, Long>,
    hostConnected: (Host) -> Boolean, onReconnect: (host: Host, session: TerminalConnection) -> Unit) {
    /** Sessions that already retried an unreachable host during this look at the session list. */
    val retriedThisVisit = remember(visible) { mutableSetOf<String>() }
    /** Session id → when the list began waiting for its host to answer again; see [hostBackSince]. */
    val waitingForHostSince = remember { mutableMapOf<String, Long>() }
    LaunchedEffect(visible, service, sessions, hosts) {
        if (!visible || service == null) return@LaunchedEffect
        service.awaitRestoration()
        service.sessions.value.forEach { session ->
            val host = hosts.firstOrNull { it.id == session.host.id } ?: return@forEach
            if (host.auth != AuthMethod.KEY.name && (host.auth != AuthMethod.PASSWORD.name || host.encryptedPassword == null)) return@forEach
            launch {
                session.state.first { reconnectsFromList(it) && session.id !in retriedThisVisit }
                autoReconnectedAt[session.id]?.let { delay(it + AUTO_RECONNECT_INTERVAL_MS - SystemClock.elapsedRealtime()) }
                // An offline host is left alone, not retried on every visit: a fresh probe must find its sshd first.
                if (!hostConnected(host)) {
                    val since = waitingForHostSince.getOrPut(session.id) {
                        reachability.now().also { reachability.requestProbe(host) }
                    }
                    snapshotFlow {
                        hostConnected(host) || hostBackSince(reachability.statusOf(host), reachability.checkedAt(host), since)
                    }.first { it }
                }
                waitingForHostSince -= session.id
                val state = session.state.value
                if (!reconnectsFromList(state)) return@launch
                // A host still out of reach is tried once per visit, not every few seconds.
                if (!reconnectsOnOpen(state)) retriedThisVisit += session.id
                autoReconnectedAt[session.id] = SystemClock.elapsedRealtime()
                onReconnect(host, session)
            }
        }
    }
}
