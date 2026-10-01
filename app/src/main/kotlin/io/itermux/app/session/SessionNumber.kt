package io.itermux.app.session

/** Stable for a session's lifetime, including reconnect and journal restoration. */
internal fun nextSessionNumber(used: Collection<Int>): Int = generateSequence(1) { it + 1 }.first { it !in used }
