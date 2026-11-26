package io.itermux.app.voice

import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceCallPhase
import io.itermux.desktop.VoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One-shot failure notification emitted even when a failed [VoiceConnection] is immediately cleaned up. */
internal data class VoiceFailure(val hostId: String, val sessionId: String, val message: String)

/**
 * Holds active `roamtty-desktop voice` sessions in [io.itermux.app.session.SessionService],
 * keyed by host id so at most one voice bridge runs per host at a time.
 */
internal class VoiceRegistry(
    private val scope: CoroutineScope,
    private val tmuxSessionsForHost: (String) -> List<String>,
    private val onChange: () -> Unit,
    private val connectionFactory: (
        host: Host,
        sessionId: String,
        password: String,
        passphrase: String,
        tmuxSessions: () -> List<String>,
        onReady: (VoiceMode, Int) -> VoiceReadyOutcome,
        onEnded: (VoiceConnection) -> Unit,
        isCurrentOwner: () -> Boolean,
    ) -> VoiceConnection,
) {
    constructor(
        app: ItermuxApp,
        scope: CoroutineScope,
        tmuxSessionsForHost: (String) -> List<String>,
        onChange: () -> Unit,
    ) : this(
        scope = scope,
        tmuxSessionsForHost = tmuxSessionsForHost,
        onChange = onChange,
        connectionFactory = { host, sessionId, password, passphrase, tmuxSessions, onReady, onEnded, isCurrentOwner ->
            VoiceConnection(
                host = host,
                sessionId = sessionId,
                app = app,
                scope = scope,
                password = password,
                passphrase = passphrase,
                tmuxSessions = tmuxSessions,
                onReady = onReady,
                onEnded = onEnded,
                isCurrentOwner = isCurrentOwner,
            )
        },
    )

    private val mutable = MutableStateFlow<Map<String, VoiceConnection>>(emptyMap())
    val connections: StateFlow<Map<String, VoiceConnection>> = mutable.asStateFlow()

    private val mutableFailures = MutableSharedFlow<VoiceFailure>(extraBufferCapacity = 8)
    val failures: SharedFlow<VoiceFailure> = mutableFailures.asSharedFlow()

    /** Monotonic per-host generation so a superseded connection's async `VoiceEnvironment.clear()` never clobbers a new call. */
    private val hostGenerations = ConcurrentHashMap<String, AtomicLong>()

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
        onReady: (VoiceMode, Int) -> VoiceReadyOutcome,
    ): VoiceConnection {
        val gen = hostGenerations.computeIfAbsent(host.id) { AtomicLong(0L) }.incrementAndGet()
        val connection = connectionFactory(
            host,
            sessionId,
            password,
            passphrase,
            { tmuxSessionsForHost(host.id) },
            onReady,
            ::onEnded,
            { hostGenerations[host.id]?.get() == gen },
        )
        var old: VoiceConnection? = null
        mutable.update { current ->
            old = current[host.id]
            current + (host.id to connection)
        }
        old?.let { prev ->
            prev.clearSecrets()
            prev.hangup()
        }
        connection.start(mode, route)
        onChange()
        return connection
    }

    private fun onEnded(connection: VoiceConnection) {
        val failed = connection.ui.value.phase as? VoiceCallPhase.Failed
        var removed = false
        mutable.update { current ->
            removed = false
            if (current[connection.host.id] !== connection || connection.active) {
                current
            } else {
                removed = true
                current - connection.host.id
            }
        }
        if (failed != null) {
            mutableFailures.tryEmit(VoiceFailure(connection.host.id, connection.sessionId, failed.message))
        }
        if (removed) {
            connection.release()
        }
        onChange()
    }

    fun close(hostId: String) {
        var removed: VoiceConnection? = null
        mutable.update { current ->
            removed = current[hostId]
            if (removed == null) current else current - hostId
        }
        val c = removed ?: return
        c.clearSecrets()
        c.hangup()
        onChange()
    }

    /** Stops any voice connection owned by terminal session [sessionId]. */
    fun closeBySession(sessionId: String) {
        val toClose = mutableListOf<VoiceConnection>()
        mutable.update { current ->
            toClose.clear()
            val remaining = current.filterValues { conn ->
                if (conn.sessionId == sessionId) {
                    toClose += conn
                    false
                } else {
                    true
                }
            }
            if (toClose.isEmpty()) current else remaining
        }
        if (toClose.isEmpty()) return
        toClose.forEach {
            it.clearSecrets()
            it.hangup()
        }
        onChange()
    }

    fun networkChanged() = mutable.value.values.forEach { it.networkChanged() }

    fun networkLost() = mutable.value.values.forEach { it.networkLost() }

    /** Hangs up all active voice calls without blocking the caller (Main) thread on audio teardown. */
    fun closeAll() {
        val all = mutable.getAndUpdate { emptyMap() }.values.toList()
        all.forEach {
            it.clearSecrets()
            it.hangup()
        }
        onChange()
    }
}
