package io.itermux.ssh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

enum class HistorySource { TMUX, OUTPUT }

data class TmuxHistory(val rows: Int, val position: Int, val active: Boolean, val paneRows: Int,
    val alternateScreen: Boolean = false, val source: HistorySource = HistorySource.TMUX)

interface TmuxHistoryAccess {
    val historyConnected: Boolean
    suspend fun tmuxHistory(): TmuxHistory?
    suspend fun enterTmuxHistory(): TmuxHistory?
    suspend fun moveTmuxHistoryTo(position: Int): TmuxHistory?
    suspend fun exitTmuxHistory(): TmuxHistory?
}

/** Serializes history commands for one remote tmux session without owning the SSH connection. */
internal class TmuxHistoryController(
    private val sessionName: String,
    private val execute: suspend (String) -> String?
) {
    private val control = Mutex()
    private val target get() = Tmux.targetFlag(sessionName)
    private var applicationSource = HistorySource.TMUX

    suspend fun reset() = control.withLock { applicationSource = HistorySource.TMUX }

    suspend fun history(): TmuxHistory? = control.withLock { query() }

    suspend fun enter(): TmuxHistory? = control.withLock {
        val current = query() ?: return@withLock null
        if (current.source != HistorySource.TMUX && current.active) return@withLock current
        if (current.alternateScreen) {
            // Full-screen copy-mode only has the current frame. Browse captured output
            // locally, using exactly the same path for every foreground program.
            if (current.active && execute("tmux send-keys -t $target -X cancel") == null) return@withLock null
            applicationSource = HistorySource.OUTPUT
            return@withLock current.copy(active = true, source = HistorySource.OUTPUT)
        }
        if (execute("tmux copy-mode -t $target") == null) return@withLock null
        query()
    }

    suspend fun moveTo(position: Int): TmuxHistory? = control.withLock {
        var current = query() ?: return@withLock null
        if (current.source != HistorySource.TMUX) return@withLock current
        // A stale drag must not reopen copy-mode after the user returned to live output.
        if (!current.active) return@withLock current
        val targetPos = position.coerceIn(0, current.rows)
        if (targetPos != current.position) {
            val direct = when (targetPos) {
                0 -> "history-bottom"
                current.rows -> "history-top"
                else -> null
            }
            if (direct != null) {
                if (execute("tmux send-keys -t $target -X $direct") == null) return@withLock null
            } else {
                // Page commands avoid hundreds of intermediate redraws on long drags.
                val pages = abs(targetPos - current.position) / (current.paneRows - 2).coerceAtLeast(1)
                if (pages > 0) {
                    val pageCommand = if (targetPos > current.position) "page-up" else "page-down"
                    if (execute("tmux send-keys -t $target -N $pages -X $pageCommand") == null) return@withLock null
                    current = query() ?: return@withLock null
                }
                val remainder = targetPos - current.position
                if (remainder != 0) {
                    val direction = if (remainder > 0) "scroll-up" else "scroll-down"
                    if (execute("tmux send-keys -t $target -N ${abs(remainder)} -X $direction") == null) return@withLock null
                }
            }
        }
        query()
    }

    suspend fun exit(): TmuxHistory? = control.withLock {
        var current = query() ?: return@withLock null
        if (applicationSource != HistorySource.TMUX) {
            applicationSource = HistorySource.TMUX
            current = query() ?: return@withLock null
        }
        if (current.active && execute("tmux send-keys -t $target -X cancel") == null) return@withLock null
        query()
    }

    private suspend fun query(): TmuxHistory? {
        val result = execute("tmux display-message -p -t $target '#{history_size}:#{scroll_position}:#{pane_in_mode}:#{pane_height}:#{alternate_on}'") ?: return null
        val values = result.trim().split(':')
        if (values.size != 5) return null
        val rows = values[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        // tmux leaves scroll_position empty until copy-mode is entered.
        val position = (values[1].ifEmpty { "0" }).toIntOrNull()?.coerceIn(0, rows) ?: return null
        val paneRows = values[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val alternate = values[4] == "1"
        return TmuxHistory(rows, position, values[2] == "1" || applicationSource != HistorySource.TMUX,
            paneRows, alternate, applicationSource)
    }
}
