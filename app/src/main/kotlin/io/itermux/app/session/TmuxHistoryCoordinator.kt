package io.itermux.app.session

import io.itermux.app.tr

import android.os.SystemClock
import io.itermux.ssh.TmuxHistoryAccess
import io.itermux.ssh.TmuxHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps tmux history requests, polling, and optimistic scroll position in one place. */
internal class TmuxHistoryCoordinator(
    private val enabled: Boolean,
    private val ssh: TmuxHistoryAccess,
    private val scope: CoroutineScope,
    private val warn: (String) -> Unit
) {
    val history = MutableStateFlow<TmuxHistory?>(null)
    private data class Position(val generation: Int, val value: Int)
    private val commands = Mutex()
    private val positions = Channel<Position>(Channel.CONFLATED)
    private var requested = false
    private var latestPosition = 0
    private var positionPending = false
    private var requestGeneration = 0
    private var lastInteraction = 0L
    private var poller: Job? = null
    private val worker = if (enabled) scope.launch {
        for (position in positions) commands.withLock {
            if (!requested || position.generation != requestGeneration) return@withLock
            val result = ssh.moveTmuxHistoryTo(position.value)
            if (requested && position.generation == requestGeneration && position.value == latestPosition) {
                positionPending = false
                if (result == null) warn(tr("无法滚动 tmux 历史，请检查远端会话。"))
                else history.value = result.takeIf { it.active }
            }
        }
    } else null

    fun enter() {
        if (!enabled) return
        val generation = ++requestGeneration
        requested = true
        scope.launch {
            commands.withLock {
                if (!requested || generation != requestGeneration) return@withLock
                val result = ssh.enterTmuxHistory()
                if (generation != requestGeneration) return@withLock
                if (result == null) { requested = false; warn(tr("无法打开 tmux 历史，请检查远端会话。")) }
                else {
                    requested = true
                    latestPosition = result.position
                    positionPending = false
                    history.value = result
                    poller?.cancel()
                    poller = scope.launch {
                        while (isActive && requested && ssh.historyConnected) {
                            delay(1500)
                            if (positionPending || SystemClock.elapsedRealtime() - lastInteraction < 1000) continue
                            commands.withLock poll@ {
                                if (!requested || generation != requestGeneration) return@poll
                                val latest = ssh.tmuxHistory() ?: return@poll
                                if (!requested || generation != requestGeneration) return@poll
                                history.value = latest.takeIf { it.active }
                                if (!latest.active) requested = false
                            }
                        }
                    }
                }
            }
        }
    }

    fun moveTo(position: Int) {
        val current = history.value?.takeIf { it.active } ?: return
        val target = position.coerceIn(0, current.rows)
        lastInteraction = SystemClock.elapsedRealtime()
        latestPosition = target
        positionPending = true
        history.value = current.copy(position = target)
        positions.trySend(Position(requestGeneration, target))
    }

    fun exit() {
        if (!enabled) return
        scope.launch { exitAndAwait() }
    }

    suspend fun exitAndAwait(): Boolean {
        if (!enabled) return true
        val generation = ++requestGeneration
        requested = false
        positionPending = false
        poller?.cancel()
        return commands.withLock {
            if (generation != requestGeneration) return@withLock false
            val result = ssh.exitTmuxHistory()
            if (generation != requestGeneration) return@withLock false
            if (result == null) warn(tr("无法返回 tmux 实时画面，请检查远端会话。"))
            else history.value = null
            result != null && !result.active
        }
    }

    fun onConnectionEnded() {
        ++requestGeneration
        requested = false
        positionPending = false
        poller?.cancel()
        history.value = null
    }

    fun disconnect() {
        ++requestGeneration
        requested = false
        positionPending = false
        poller?.cancel()
        history.value = null
        positions.close()
        worker?.cancel()
    }
}
