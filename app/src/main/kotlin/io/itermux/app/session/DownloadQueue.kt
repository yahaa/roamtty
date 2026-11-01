package io.itermux.app.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal enum class DownloadPhase { WAITING, RUNNING, COMPLETE, FAILED, CANCELLED }

internal data class DownloadItem(val path: String, val total: Long = -1, val bytes: Long = 0,
    val phase: DownloadPhase = DownloadPhase.WAITING, val error: String? = null,
    val localPath: String? = null) {
    val name: String get() = path.substringAfterLast('/')
}

/** Reserve all original basenames first so generated suffixes cannot steal another file's name. */
internal fun downloadNames(files: List<DownloadItem>): Map<String, String> {
    val reserved = files.map { it.name.lowercase(Locale.ROOT) }.toMutableSet()
    val used = mutableSetOf<String>()
    val nextSuffix = mutableMapOf<String, Int>()
    return files.associate { file ->
        var name = file.name
        if (!used.add(name.lowercase(Locale.ROOT))) {
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.substring(0, dot)
            val extension = name.substring(dot)
            val key = name.lowercase(Locale.ROOT)
            var number = nextSuffix[key] ?: 2
            do { name = "$stem (${number++})$extension" }
            while (!reserved.add(name.lowercase(Locale.ROOT)))
            nextSuffix[key] = number
            used.add(name.lowercase(Locale.ROOT))
        }
        file.path to name
    }
}

/** Serial transfers bound memory/channel use; each failure belongs to its own file. */
internal suspend fun runDownloadQueue(
    files: List<DownloadItem>,
    onUpdate: (List<DownloadItem>, Double) -> Unit,
    download: suspend (DownloadItem, (Long, Long) -> Unit) -> Unit
): List<DownloadItem> {
    val items = files.map { it.copy(bytes = 0, phase = DownloadPhase.WAITING, error = null, localPath = null) }.toMutableList()
    // Mutate only the private queue. Observers receive a frozen snapshot at publication time.
    fun publish(speed: Double = 0.0) = onUpdate(items.toList(), speed)
    try {
        for (index in items.indices) {
            currentCoroutineContext().ensureActive()
            items[index] = items[index].copy(phase = DownloadPhase.RUNNING)
            publish()
            val started = System.nanoTime()
            var lastReport = started
            try {
                download(items[index]) { bytes, total ->
                    items[index] = items[index].copy(bytes = bytes, total = total)
                    val now = System.nanoTime()
                    if (now - lastReport >= 100_000_000L || bytes == total || bytes == 0L) {
                        publish(bytes / ((now - started) / 1e9).coerceAtLeast(0.001))
                        lastReport = now
                    }
                }
                currentCoroutineContext().ensureActive()
                items[index] = items[index].copy(phase = DownloadPhase.COMPLETE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                items[index] = items[index].copy(phase = DownloadPhase.FAILED, error = e.message)
            }
            publish()
        }
    } catch (e: CancellationException) {
        for (index in items.indices) {
            val item = items[index]
            if (item.phase == DownloadPhase.RUNNING || item.phase == DownloadPhase.WAITING)
                items[index] = item.copy(phase = DownloadPhase.CANCELLED)
        }
        publish()
        throw e
    }
    return items.toList()
}
