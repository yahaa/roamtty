package io.itermux.app.session

import android.os.SystemClock
import io.itermux.ssh.JschSession
import io.itermux.ssh.Phase
import io.itermux.ssh.TmuxHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps tmux history requests, polling, and optimistic scroll position in one place. */
internal class TmuxHistoryCoordinator(
    private val enabled: Boolean,
    private val ssh: JschSession,
    private val scope: CoroutineScope,
    private val warn: (String) -> Unit
) {
    val history = MutableStateFlow<TmuxHistory?>(null)
    private val positions = Channel<Int>(Channel.CONFLATED)
    private var requested = false
    private var latestPosition = 0
    private var positionPending = false
    private var requestGeneration = 0
    private var lastInteraction = 0L
    private var poller: Job? = null
    private val worker = if (enabled) scope.launch {
        for (position in positions) {
            if (!requested) continue
            val result = ssh.moveTmuxHistoryTo(position)
            if (position == latestPosition) {
                positionPending = false
                if (requested) {
                    if (result == null) warn("无法滚动 tmux 历史，请检查远端会话。")
                    else history.value = result
                }
            }
        }
    } else null

    fun enter() {
        if (!enabled) return
        val generation = ++requestGeneration
        scope.launch {
            val result = ssh.enterTmuxHistory()
            if (generation != requestGeneration) return@launch
            if (result == null) warn("无法打开 tmux 历史，请检查远端会话。")
            else {
                requested = true
                latestPosition = result.position
                positionPending = false
                history.value = result
                poller?.cancel()
                poller = scope.launch {
                    while (isActive && requested && ssh.state.value.phase == Phase.CONNECTED) {
                        delay(1500)
                        if (positionPending || SystemClock.elapsedRealtime() - lastInteraction < 1000) continue
                        val latest = ssh.tmuxHistory() ?: continue
                        if (!requested) break
                        history.value = latest.takeIf { it.active }
                        if (!latest.active) requested = false
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
        positions.trySend(target)
    }

    fun exit() {
        if (!enabled) return
        val generation = ++requestGeneration
        requested = false
        positionPending = false
        poller?.cancel()
        scope.launch {
            val result = ssh.exitTmuxHistory()
            if (generation != requestGeneration) return@launch
            if (result == null) warn("无法返回 tmux 实时画面，请检查远端会话。")
            else history.value = null
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
