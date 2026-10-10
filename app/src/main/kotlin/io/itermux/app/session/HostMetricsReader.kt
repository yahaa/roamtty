package io.itermux.app.session

import io.itermux.ssh.RemoteHost

/** One metrics read. Only [Unavailable] means the host refused our queries; [Lost] is fixed by reconnecting. */
internal sealed interface MetricsReading {
    data class Sample(val metrics: HostMetrics) : MetricsReading
    /** Every collector ran but failed or printed nothing usable, e.g. CIM access denied. */
    data object Unavailable : MetricsReading
    /** A command timed out or the login dropped. */
    data object Lost : MetricsReading
}

/** Discover the usable collector once per SSH login; a failed command is never an empty healthy snapshot. */
internal class HostMetricsReader(private val remote: RemoteHost, private val preferWindows: Boolean = false) {
    private var windows: Boolean? = null

    /** Timeouts never try the other collector: the login is likely gone. */
    suspend fun read(): MetricsReading {
        for (nativeWindows in windows?.let(::listOf) ?: listOf(preferWindows, !preferWindows)) {
            val lines = mutableListOf<String>()
            val status = (if (nativeWindows) remote.runCommand(WindowsShell.command(WindowsHostMetrics.script), TIMEOUT_MS) { lines += it }
                else remote.run(HostMetrics.script, TIMEOUT_MS) { lines += it }) ?: return MetricsReading.Lost
            val sample = HostMetrics.parse(lines)
            if (status != 0 || (sample.cpuPercent == null && sample.memPercent == null && sample.disks.isEmpty())) continue
            windows = nativeWindows
            if (!nativeWindows) return MetricsReading.Sample(sample)
            val gpu = mutableListOf<String>()
            if (remote.runCommand(WindowsShell.command(WindowsHostMetrics.gpuScript), GPU_TIMEOUT_MS) { gpu += it } != 0)
                return MetricsReading.Sample(sample)
            return MetricsReading.Sample(HostMetrics.parse(lines + gpu))
        }
        return MetricsReading.Unavailable
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val GPU_TIMEOUT_MS = 4_000L
    }
}
