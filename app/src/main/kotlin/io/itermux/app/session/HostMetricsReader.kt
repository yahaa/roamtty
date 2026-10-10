package io.itermux.app.session

import io.itermux.ssh.RemoteHost

/** Discover the usable collector once per SSH login; a failed command is never an empty healthy snapshot. */
internal class HostMetricsReader(private val remote: RemoteHost, private val preferWindows: Boolean = false) {
    private var windows: Boolean? = null

    /** A sample, or null when no collector works or the connection is lost (timeouts never try the other one). */
    suspend fun read(): HostMetrics? {
        for (nativeWindows in windows?.let(::listOf) ?: listOf(preferWindows, !preferWindows)) {
            val lines = mutableListOf<String>()
            val status = (if (nativeWindows) remote.runCommand(WindowsShell.command(WindowsHostMetrics.script), TIMEOUT_MS) { lines += it }
                else remote.run(HostMetrics.script, TIMEOUT_MS) { lines += it }) ?: return null
            val sample = HostMetrics.parse(lines)
            if (status != 0 || (sample.cpuPercent == null && sample.memPercent == null && sample.disks.isEmpty())) continue
            windows = nativeWindows
            if (!nativeWindows) return sample
            val gpu = mutableListOf<String>()
            if (remote.runCommand(WindowsShell.command(WindowsHostMetrics.gpuScript), GPU_TIMEOUT_MS) { gpu += it } != 0) return sample
            return HostMetrics.parse(lines + gpu)
        }
        return null
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val GPU_TIMEOUT_MS = 4_000L
    }
}
