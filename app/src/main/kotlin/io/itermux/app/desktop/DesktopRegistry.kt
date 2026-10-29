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

    /**
     * The host's desktop. A live connection keeps its link; an ended/failed one is replaced so a
     * corrected password or key passphrase is used on the next login.
     */
    fun open(host: Host, password: String, passphrase: String, width: Int, height: Int, dpi: Int): DesktopConnection {
        val existing = mutable.value[host.id]
        if (existing != null && existing.host.loginTarget == host.loginTarget && existing.active) {
            return existing
        }
        if (existing != null) {
            mutable.value = mutable.value - host.id
            existing.close()
            existing.release()
        }
        val connection = DesktopConnection(host, app, scope, password, passphrase, width, height, dpi, onEnded = ::onEnded)
        mutable.value = mutable.value + (host.id to connection)
        connection.start()
        onChange()
        return connection
    }

    /**
     * A desktop that failed stays so its page can show why and offer a retry (which needs its secrets); it goes when
     * the page is left ([dropEnded]) or the next [open] replaces it. One closed outright goes at once.
     */
    private fun onEnded(connection: DesktopConnection) {
        if (mutable.value[connection.host.id] !== connection) return
        if (connection.phase.value !is DesktopPhase.Failed) {
            mutable.value = mutable.value - connection.host.id
            connection.release()
        }
        onChange()
    }

    /** Drops [hostId]'s desktop if it is no longer running (failed), with its secrets and decoder. */
    fun dropEnded(hostId: String) {
        val c = mutable.value[hostId] ?: return
        if (!c.active) close(hostId)
    }

    fun close(hostId: String) {
        val c = mutable.value[hostId] ?: return
        mutable.value = mutable.value - hostId
        c.close()
        c.release()
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
