package io.itermux.app.voice

import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds active `roamtty-desktop voice` sessions in [io.itermux.app.session.SessionService],
 * keyed by host id so at most one voice bridge runs per host at a time.
 */
internal class VoiceRegistry(
    private val app: ItermuxApp,
    private val scope: CoroutineScope,
    private val tmuxSessionsForHost: (String) -> List<String>,
    private val onChange: () -> Unit,
) {
    private val mutable = MutableStateFlow<Map<String, VoiceConnection>>(emptyMap())
    val connections: StateFlow<Map<String, VoiceConnection>> = mutable.asStateFlow()

    operator fun get(hostId: String): VoiceConnection? = mutable.value[hostId]

    /** Active `ANTIGRAVITY_MIC` endpoint (`127.0.0.1:<port>`) for [hostId], or null when idle. */
    fun activeMicEndpoint(hostId: String): String? =
        mutable.value[hostId]?.takeIf { it.active }?.micPort?.let(VoiceEnvironment::endpoint)

    val live: List<VoiceConnection> get() = mutable.value.values.filter { it.active }

    fun start(
        host: Host,
        sessionId: String,
        password: String,
        passphrase: String,
        mode: VoiceMode,
        route: AudioRoute,
        onReady: (VoiceMode, Int) -> Unit,
    ): VoiceConnection {
        // Close any previous voice bridge on this host cleanly before opening a new one.
        mutable.value[host.id]?.let { old ->
            mutable.value = mutable.value - host.id
            old.hangup()
            old.release()
        }
        val connection = VoiceConnection(
            host = host,
            sessionId = sessionId,
            app = app,
            scope = scope,
            password = password,
            passphrase = passphrase,
            tmuxSessions = { tmuxSessionsForHost(host.id) },
            onReady = onReady,
            onEnded = ::onEnded,
        )
        mutable.value = mutable.value + (host.id to connection)
        connection.start(mode, route)
        onChange()
        return connection
    }

    private fun onEnded(connection: VoiceConnection) {
        if (mutable.value[connection.host.id] !== connection) return
        if (!connection.active) {
            mutable.value = mutable.value - connection.host.id
            connection.release()
        }
        onChange()
    }

    fun close(hostId: String) {
        val c = mutable.value[hostId] ?: return
        mutable.value = mutable.value - hostId
        c.hangup()
        c.release()
        onChange()
    }

    fun networkChanged() = mutable.value.values.forEach { it.networkChanged() }

    fun closeAll() {
        mutable.value.keys.toList().forEach(::close)
    }
}
