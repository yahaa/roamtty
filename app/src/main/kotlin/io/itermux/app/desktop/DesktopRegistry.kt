package io.itermux.app.desktop

import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.desktop.DesktopPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * At most one desktop per host (plan §4.1), held by the session service so it outlives the page.
 * Every session card of a host watches the same connection; desktops are not sessions, so they
 * never enter the journal or count against the per-host session limit.
 */
internal class DesktopRegistry(private val app: ItermuxApp, private val scope: CoroutineScope, private val onChange: () -> Unit) {
    private val mutable = MutableStateFlow<Map<String, DesktopConnection>>(emptyMap())
    val connections: StateFlow<Map<String, DesktopConnection>> = mutable.asStateFlow()

    operator fun get(hostId: String): DesktopConnection? = mutable.value[hostId]

    /** The host's desktop, started if it is not running; an existing one keeps its link and secrets. */
    fun open(host: Host, password: String, passphrase: String, width: Int, height: Int, dpi: Int): DesktopConnection {
        val existing = mutable.value[host.id]
        if (existing != null && existing.host.loginTarget == host.loginTarget) {
            if (!existing.active) existing.start()
            return existing
        }
        existing?.let { it.close(); it.release() }
        val connection = DesktopConnection(host, app, scope, password, passphrase, width, height, dpi, onEnded = { onChange() })
        mutable.value = mutable.value + (host.id to connection)
        connection.start()
        onChange()
        return connection
    }

    fun close(hostId: String) {
        val c = mutable.value[hostId] ?: return
        c.close()
        c.release()
        mutable.value = mutable.value - hostId
        onChange()
    }

    /** Connected (or reconnecting) desktops, for the notification and the wake lock. */
    val live: List<DesktopConnection> get() = mutable.value.values.filter {
        it.phase.value is DesktopPhase.Connected || it.phase.value is DesktopPhase.Recovering || it.phase.value is DesktopPhase.Connecting
    }

    fun networkChanged() = mutable.value.values.forEach { it.networkChanged() }

    fun closeAll() { mutable.value.keys.toList().forEach(::close) }
}

private val Host.loginTarget get() = listOf(address, port, username, auth)
