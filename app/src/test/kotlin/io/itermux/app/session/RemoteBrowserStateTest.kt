package io.itermux.app.session

import io.itermux.ssh.RemoteEntry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteBrowserStateTest {
    @Test fun cancelStopsPendingLinkResolutionWithoutCancelingTheSharedScope() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = RemoteBrowserState(scope)
        val finished = CompletableDeferred<Unit>()
        try {
            state.openLink("/slow-link") {
                try { awaitCancellation() } finally { finished.complete(Unit) }
            }
            state.cancel()
            yield()
            assertTrue("A removed session must release its pending link lookup", finished.isCompleted)
            assertTrue("Other sessions still use this scope", scope.isActive)
            assertNull(state.previewPath)
        } finally { scope.cancel() }
    }

    @Test fun navigationRetracesVisitsAndKeepsRenameTargets() {
        val state = RemoteBrowserState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        state.navigate("/projects")
        state.navigate("/projects/app")
        state.showMenu("/projects/app", RemoteEntry("README.md", false))
        assertEquals("/projects/app/README.md", state.menuPath)
        state.renamePath = state.menuPath
        state.renameDraft = "NOTES.md"
        assertTrue(state.goBack())
        assertEquals("/projects", state.directory)
        assertEquals("NOTES.md", state.renameDraft)
        assertEquals("/projects/app/README.md", state.renamePath)
        assertTrue(state.goBack())
        assertEquals("", state.directory)
        assertFalse(state.goBack())
    }

    @Test fun partialFailureAndCancellationBothRefreshAndExposeFeedback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = RemoteBrowserState(scope)
        try {
            state.deletePath = "/test"
            state.delete { throw IllegalStateException("Permission denied") }
            assertEquals(1, state.revision)
            assertNotNull(state.actionError)
            assertNull(state.operation)
            state.deletePath = "/next"
            val started = CompletableDeferred<Unit>()
            state.delete { started.complete(Unit); awaitCancellation() }
            started.await()
            assertNotNull(state.operation)
            state.cancel()
            yield()
            assertEquals(2, state.revision)
            assertNull(state.operation)
            assertNotNull(state.actionError)
        } finally { scope.cancel() }
    }

    @Test fun renameRejectsTraversalAndControlCharacters() {
        listOf("", " ", ".", "..", "../name", "a/b", "a\\b", "a\u0000b", "a\nb").forEach {
            assertFalse(it, validRemoteRename(it))
        }
        assertTrue(validRemoteRename("release notes.md"))
    }

    @Test fun sessionNumbersDoNotCollideAndFillGaps() {
        assertEquals(1, nextSessionNumber(emptyList()))
        assertEquals(4, nextSessionNumber(listOf(1, 2, 3)))
        assertEquals(2, nextSessionNumber(listOf(1, 3)))
    }

    @Test fun staleLinkResolutionCannotOverrideNewerNavigation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = RemoteBrowserState(scope)
        try {
            state.navigate("/srv")
            val slow = CompletableDeferred<Boolean>()
            state.openLink("/srv/current") { slow.await() }
            state.navigate("/home")
            slow.complete(true)
            yield()
            assertEquals("/home", state.directory)
            assertNull(state.previewPath)

            // A second tap replaces the first; only the latest result applies.
            val first = CompletableDeferred<Boolean>()
            state.openLink("/home/a") { first.await() }
            state.openLink("/home/b.txt") { false }
            first.complete(true)
            yield()
            assertEquals("/home", state.directory)
            assertEquals("/home/b.txt", state.previewPath)

            // Leaving the browser cancels a pending resolution.
            state.previewPath = null
            val pending = CompletableDeferred<Boolean>()
            state.openLink("/home/c") { pending.await() }
            state.cancelPendingOpen()
            pending.complete(true)
            yield()
            assertEquals("/home", state.directory)
            assertNull(state.previewPath)
        } finally { scope.cancel() }
    }
}
