package io.itermux.app.session

import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCommandTest {
    @Test fun outageDuringSettlingWaitsForNewWriter() = runTest {
        val state = MutableStateFlow(ConnectionState(Phase.CONNECTED))
        val epoch = MutableStateFlow<Long?>(1L)
        val revision = MutableStateFlow(1L)
        val writes = mutableListOf<Long>()
        val sent = async { enqueueStartupCommand(state, epoch, revision, { testScheduler.currentTime }) {
            writes += it; true
        } }
        runCurrent()
        advanceTimeBy(100)
        epoch.value = null
        state.value = ConnectionState(Phase.CONNECTING)
        advanceTimeBy(800)
        assertTrue(writes.isEmpty())
        epoch.value = 2L
        state.value = ConnectionState(Phase.CONNECTED)
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        assertTrue(sent.await())
        assertEquals(listOf(2L), writes)
    }

    @Test fun rejectedWriteCanRetryAfterWriterChangesButAcceptedInputIsNeverReplayed() = runTest {
        val state = MutableStateFlow(ConnectionState(Phase.CONNECTED))
        val epoch = MutableStateFlow<Long?>(1L)
        val writes = mutableListOf<Long>()
        val sent = async { enqueueStartupCommand(state, epoch, MutableStateFlow(1L), { testScheduler.currentTime }) {
            writes += it
            if (it == 1L) { epoch.value = null; state.value = ConnectionState(Phase.CONNECTING); false }
            else true
        } }
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(1L), writes)
        epoch.value = 2L
        state.value = ConnectionState(Phase.CONNECTED)
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        assertTrue(sent.await())
        epoch.value = null
        state.value = ConnectionState(Phase.CONNECTING)
        epoch.value = 3L
        state.value = ConnectionState(Phase.CONNECTED)
        advanceTimeBy(5000)
        assertEquals(listOf(1L, 2L), writes)
    }

    @Test fun terminalFailureEndsWaitingWithoutWriting() = runTest {
        for (phase in listOf(Phase.AUTH_FAILED, Phase.HOST_KEY_CHANGED, Phase.FAILED, Phase.DISCONNECTED)) {
            val state = MutableStateFlow(ConnectionState(Phase.CONNECTING))
            val sent = async { enqueueStartupCommand(state, MutableStateFlow(null), MutableStateFlow(0L),
                { testScheduler.currentTime }) { fail("No ready writer"); true } }
            runCurrent()
            state.value = ConnectionState(phase)
            assertFalse(sent.await())
        }
    }

    @Test fun fullQueueRetriesForABoundedTimeAndCancellationStopsWaiting() = runTest {
        val state = MutableStateFlow(ConnectionState(Phase.CONNECTED))
        val epoch = MutableStateFlow<Long?>(1L)
        var tries = 0
        val rejected = async { enqueueStartupCommand(state, epoch, MutableStateFlow(1L),
            { testScheduler.currentTime }) { tries++; false } }
        advanceTimeBy(2000)
        assertFalse(rejected.await())
        assertEquals(10, tries)
        epoch.value = null
        state.value = ConnectionState(Phase.CONNECTING)
        val cancelled = async { enqueueStartupCommand(state, epoch, MutableStateFlow(1L),
            { testScheduler.currentTime }) { fail("No ready writer"); true } }
        runCurrent()
        cancelled.cancel()
        advanceTimeBy(5000)
        assertTrue(cancelled.isCancelled)
    }
}
