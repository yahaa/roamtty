package io.itermux.app.session

import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxHistoryCoordinatorTest {
    private class Remote : TmuxHistoryAccess {
        override val historyConnected = true
        val calls = mutableListOf<String>()
        var active = false
        var enterGate: CompletableDeferred<Unit>? = null
        var moveGate: CompletableDeferred<Unit>? = null
        override suspend fun tmuxHistory() = TmuxHistory(100, 0, active, 24)
        override suspend fun enterTmuxHistory(): TmuxHistory {
            calls += "enter"
            enterGate?.await()
            active = true
            return tmuxHistory()
        }
        override suspend fun moveTmuxHistoryTo(position: Int): TmuxHistory {
            calls += "move"
            moveGate?.await()
            return tmuxHistory()
        }
        override suspend fun exitTmuxHistory(): TmuxHistory {
            calls += "exit"
            active = false
            return tmuxHistory()
        }
    }

    @Test fun supersededEnterNeverSendsARemoteCommand() = runTest {
        val remote = Remote()
        val history = TmuxHistoryCoordinator(true, remote, backgroundScope) { error(it) }
        history.enter()
        assertTrue(history.exitAndAwait())
        runCurrent()
        assertEquals(listOf("exit"), remote.calls)
        assertNull(history.history.value)
    }

    @Test fun exitWaitsForAnInFlightEnterAndCancelsItsRemoteMode() = runTest {
        val remote = Remote().apply { enterGate = CompletableDeferred() }
        val history = TmuxHistoryCoordinator(true, remote, backgroundScope) { error(it) }
        history.enter()
        runCurrent()
        val exit = async { history.exitAndAwait() }
        runCurrent()
        assertFalse(exit.isCompleted)
        remote.enterGate!!.complete(Unit)
        assertTrue(exit.await())
        assertFalse(remote.active)
        assertNull(history.history.value)
        assertEquals(listOf("enter", "exit"), remote.calls)
    }

    @Test fun exitWaitsForScrollAndDiscardsQueuedDrags() = runTest {
        val remote = Remote()
        val history = TmuxHistoryCoordinator(true, remote, backgroundScope) { error(it) }
        history.enter()
        runCurrent()
        remote.moveGate = CompletableDeferred()
        history.moveTo(20)
        runCurrent()
        history.moveTo(30)
        val exit = async { history.exitAndAwait() }
        runCurrent()
        assertFalse(exit.isCompleted)
        remote.moveGate!!.complete(Unit)
        assertTrue(exit.await())
        runCurrent()
        assertEquals(listOf("enter", "move", "exit"), remote.calls)
        assertFalse(remote.active)
        assertNull(history.history.value)
    }
}
