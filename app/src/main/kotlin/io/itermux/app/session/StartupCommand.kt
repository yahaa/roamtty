package io.itermux.app.session

import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Wait for the current writer, retry only rejected input, and stop once it is accepted locally. */
internal suspend fun enqueueStartupCommand(
    state: StateFlow<ConnectionState>,
    inputEpoch: StateFlow<Long?>,
    revision: StateFlow<Long>,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    write: (Long) -> Boolean
): Boolean {
    attempts@ while (true) {
        val (connection, epoch) = combine(state, inputEpoch) { current, ready -> current to ready }
            .first { (current, ready) -> !current.active || (current.phase == Phase.CONNECTED && ready != null) }
        if (!connection.active) return false
        val generation = requireNotNull(epoch)
        fun current() = state.value.phase == Phase.CONNECTED && inputEpoch.value == generation
        val settleDeadline = clock() + 4000
        var seen = -1L
        while (clock() < settleDeadline) {
            if (!current()) continue@attempts
            val frame = revision.value
            if (frame == seen && frame > 0) break
            seen = frame
            delay(350)
        }
        val enqueueDeadline = clock() + 1000
        while (clock() < enqueueDeadline) {
            if (!current()) continue@attempts
            // The transport checks this generation under the same lock as queue insertion.
            if (write(generation)) return true
            delay(100)
        }
        if (!current()) continue@attempts
        return false
    }
}
