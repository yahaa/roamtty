package io.itermux.ssh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class TmuxHistory(val rows: Int, val position: Int, val active: Boolean, val paneRows: Int)

/** Serializes history commands for the fixed tmux session without owning the SSH connection. */
internal class TmuxHistoryController(private val execute: suspend (String) -> String?) {
    private val control = Mutex()

    suspend fun history(): TmuxHistory? = control.withLock { query() }

    suspend fun enter(): TmuxHistory? = control.withLock {
        if (query() == null) return@withLock null
        if (execute("tmux copy-mode -t '${Tmux.SESSION}'") == null) return@withLock null
        query()
    }

    suspend fun moveTo(position: Int): TmuxHistory? = control.withLock {
        var current = query() ?: return@withLock null
        if (!current.active) {
            if (execute("tmux copy-mode -t '${Tmux.SESSION}'") == null) return@withLock null
            current = query() ?: return@withLock null
        }
        val target = position.coerceIn(0, current.rows)
        if (target != current.position) {
            val direct = when (target) {
                0 -> "history-bottom"
                current.rows -> "history-top"
                else -> null
            }
            if (direct != null) {
                if (execute("tmux send-keys -t '${Tmux.SESSION}' -X $direct") == null) return@withLock null
            } else {
                // Page commands avoid hundreds of intermediate redraws on long drags.
                val pages = abs(target - current.position) / (current.paneRows - 2).coerceAtLeast(1)
                if (pages > 0) {
                    val pageCommand = if (target > current.position) "page-up" else "page-down"
                    if (execute("tmux send-keys -t '${Tmux.SESSION}' -N $pages -X $pageCommand") == null) return@withLock null
                    current = query() ?: return@withLock null
                }
                val remainder = target - current.position
                if (remainder != 0) {
                    val direction = if (remainder > 0) "scroll-up" else "scroll-down"
                    if (execute("tmux send-keys -t '${Tmux.SESSION}' -N ${abs(remainder)} -X $direction") == null) return@withLock null
                }
            }
        }
        query()
    }

    suspend fun exit(): TmuxHistory? = control.withLock {
        val current = query() ?: return@withLock null
        if (current.active && execute("tmux send-keys -t '${Tmux.SESSION}' -X cancel") == null) return@withLock null
        query()
    }

    private suspend fun query(): TmuxHistory? {
        val result = execute("tmux display-message -p -t '${Tmux.SESSION}' '#{history_size}:#{scroll_position}:#{pane_in_mode}:#{pane_height}'") ?: return null
        val values = result.trim().split(':')
        if (values.size != 4) return null
        val rows = values[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        // tmux leaves scroll_position empty until copy-mode is entered.
        val position = (values[1].ifEmpty { "0" }).toIntOrNull()?.coerceIn(0, rows) ?: return null
        val paneRows = values[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return TmuxHistory(rows, position, values[2] == "1", paneRows)
    }

}
