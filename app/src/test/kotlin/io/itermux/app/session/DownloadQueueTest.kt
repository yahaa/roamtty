package io.itermux.app.session

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadQueueTest {
    @Test fun failuresDoNotStopLaterFilesAndSameNamesStayDistinct() = runBlocking {
        val visited = mutableListOf<String>()
        val files = listOf(DownloadItem("/a/report.pdf", 42), DownloadItem("/b/report.pdf"), DownloadItem("/empty", 0))
        val result = runDownloadQueue(files, { _, _ -> }) { file, progress ->
            visited += file.path
            if (file.path == "/b/report.pdf") throw IOException("Permission denied")
            progress(file.total, file.total)
        }
        assertEquals(files.map { it.path }, visited)
        assertEquals(listOf(DownloadPhase.COMPLETE, DownloadPhase.FAILED, DownloadPhase.COMPLETE), result.map { it.phase })
        assertEquals(42L, result[0].bytes)
        assertEquals("Permission denied", result[1].error)
        assertEquals(0L, result[2].bytes)
    }

    @Test fun cancelKeepsCompletedFilesAndStopsQueuedFiles() = runBlocking {
        var snapshot = emptyList<DownloadItem>()
        val startedSecond = CompletableDeferred<Unit>()
        val worker = launch {
            runDownloadQueue(listOf("one", "two", "three").map { DownloadItem(it) }, { items, _ -> snapshot = items }) { file, progress ->
                if (file.path == "two") {
                    startedSecond.complete(Unit)
                    awaitCancellation()
                }
                progress(100, 100)
            }
        }
        startedSecond.await()
        worker.cancelAndJoin()
        assertEquals(listOf(DownloadPhase.COMPLETE, DownloadPhase.CANCELLED, DownloadPhase.CANCELLED), snapshot.map { it.phase })
        assertEquals(100L, snapshot.first().bytes)
    }

    @Test fun progressRetainsExactByteCountEvenBelowThrottleThreshold() = runBlocking {
        var snapshot = emptyList<DownloadItem>()
        runDownloadQueue(listOf(DownloadItem("small")), { files, _ -> snapshot = files }) { _, progress ->
            progress(7, -1)
        }
        assertEquals(7L, snapshot.single().bytes)
        assertEquals(-1L, snapshot.single().total)
        assertEquals(DownloadPhase.COMPLETE, snapshot.single().phase)
    }
}
