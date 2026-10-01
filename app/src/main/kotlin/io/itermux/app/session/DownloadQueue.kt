package io.itermux.app.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class DownloadPhase { WAITING, RUNNING, COMPLETE, FAILED, CANCELLED }

internal data class DownloadItem(val path: String, val total: Long = -1, val bytes: Long = 0,
    val phase: DownloadPhase = DownloadPhase.WAITING, val error: String? = null) {
    val name: String get() = path.substringAfterLast('/')
}

/** Serial transfers bound memory/channel use; each failure belongs to its own file. */
internal suspend fun runDownloadQueue(
    files: List<DownloadItem>,
    onUpdate: (List<DownloadItem>, Double) -> Unit,
    download: suspend (DownloadItem, (Long, Long) -> Unit) -> Unit
): List<DownloadItem> {
    var items = files.map { it.copy(bytes = 0, phase = DownloadPhase.WAITING, error = null) }
    try {
        for (index in items.indices) {
            currentCoroutineContext().ensureActive()
            items = items.toMutableList().apply { this[index] = this[index].copy(phase = DownloadPhase.RUNNING) }
            onUpdate(items, 0.0)
            val started = System.nanoTime()
            var lastReport = started
            try {
                download(items[index]) { bytes, total ->
                    items = items.toMutableList().apply { this[index] = this[index].copy(bytes = bytes, total = total) }
                    val now = System.nanoTime()
                    if (now - lastReport >= 100_000_000L || bytes == total || bytes == 0L) {
                        onUpdate(items, bytes / ((now - started) / 1e9).coerceAtLeast(0.001))
                        lastReport = now
                    }
                }
                currentCoroutineContext().ensureActive()
                items = items.toMutableList().apply { this[index] = this[index].copy(phase = DownloadPhase.COMPLETE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                items = items.toMutableList().apply {
                    this[index] = this[index].copy(phase = DownloadPhase.FAILED, error = e.message)
                }
            }
            onUpdate(items, 0.0)
        }
    } catch (e: CancellationException) {
        items = items.map {
            if (it.phase == DownloadPhase.RUNNING || it.phase == DownloadPhase.WAITING)
                it.copy(phase = DownloadPhase.CANCELLED) else it
        }
        onUpdate(items, 0.0)
        throw e
    }
    return items
}
