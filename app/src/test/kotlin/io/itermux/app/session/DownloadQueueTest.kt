package io.itermux.app.session

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadQueueTest {
    @Test fun incompleteKnownLengthRemainsFailedWithoutInflatingBytesAndLaterFilesContinue() = runBlocking {
        val result = runDownloadQueue(listOf(DownloadItem("short"), DownloadItem("normal")), { _, _ -> }) { file, progress ->
            if (file.path == "short") {
                progress(32, 100)
                throw IOException("远端文件大小已变化或下载不完整，请重试。")
            }
            progress(32, 32)
        }
        assertEquals(DownloadPhase.FAILED, result[0].phase)
        assertEquals(32L, result[0].bytes)
        assertEquals(100L, result[0].total)
        assertEquals(DownloadPhase.COMPLETE, result[1].phase)
        assertEquals(32L, result[1].bytes)
    }

    @Test fun publishedSnapshotsNeverChangeAsLaterFilesProgress() = runBlocking {
        val snapshots = mutableListOf<List<DownloadItem>>()
        val frozen = mutableListOf<List<DownloadItem>>()
        val input = listOf(DownloadItem("one"), DownloadItem("two"))
        val result = runDownloadQueue(input, { items, _ ->
            snapshots += items
            frozen += items.map { it.copy() }
        }) { _, progress ->
            progress(0, 10)
            progress(10, 10)
        }
        assertEquals(frozen, snapshots)
        assertEquals(listOf(DownloadPhase.RUNNING, DownloadPhase.WAITING), snapshots.first().map { it.phase })
        assertTrue(result.all { it.phase == DownloadPhase.COMPLETE })
        assertTrue(input.all { it.phase == DownloadPhase.WAITING && it.bytes == 0L })
    }

    @Test fun failedTransferRetainsLastUnpublishedProgress() = runBlocking {
        val result = runDownloadQueue(listOf(DownloadItem("partial")), { _, _ -> }) { _, progress ->
            progress(7, -1)
            throw IOException("interrupted")
        }
        assertEquals(7L, result.single().bytes)
        assertEquals(DownloadPhase.FAILED, result.single().phase)
    }

    @Test fun duplicateNamingMatchesOriginalWithCaseAndSuffixCollisions() {
        val random = kotlin.random.Random(515)
        repeat(100) {
            val files = List(100) { index -> DownloadItem("/$index/" +
                listOf("a.txt", "A.TXT", "a (2).txt", "a (3).txt", ".env", "archive.tar.gz")[random.nextInt(6)]) }
            val reserved = files.map { it.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
            val used = mutableSetOf<String>()
            val expected = files.associate { file ->
                var name = file.name
                if (!used.add(name.lowercase(java.util.Locale.ROOT))) {
                    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                    val stem = name.substring(0, dot)
                    val extension = name.substring(dot)
                    var number = 2
                    do { name = "$stem (${number++})$extension" }
                    while (!reserved.add(name.lowercase(java.util.Locale.ROOT)))
                    used.add(name.lowercase(java.util.Locale.ROOT))
                }
                file.path to name
            }
            assertEquals(expected, downloadNames(files))
        }
    }

    @Test fun duplicateBasenamesKeepTheirExtensionsAndAvoidReservedNames() {
        val files = listOf("/a/report.pdf", "/b/report.pdf", "/c/report (2).pdf", "/d/REPORT.PDF", "/e/.env", "/f/.env")
            .map { DownloadItem(it) }
        assertEquals(listOf("report.pdf", "report (3).pdf", "report (2).pdf", "REPORT (4).PDF", ".env", ".env (2)"),
            downloadNames(files).values.toList())
    }

    @Test fun cancellationStillStopsQueueWhenCleanupThrowsIOException() = runBlocking {
        val visited = mutableListOf<String>()
        var snapshot = emptyList<DownloadItem>()
        val worker = launch {
            runDownloadQueue(listOf(DownloadItem("one"), DownloadItem("two")), { items, _ -> snapshot = items }) { file, _ ->
                visited += file.path
                currentCoroutineContext().cancel()
                throw IOException("Cleanup failed after cancellation")
            }
        }
        worker.join()
        assertEquals(listOf("one"), visited)
        assertTrue(snapshot.all { it.phase == DownloadPhase.CANCELLED })
    }

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
