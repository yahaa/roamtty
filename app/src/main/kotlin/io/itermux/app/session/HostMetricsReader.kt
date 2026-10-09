package io.itermux.app.session

import io.itermux.ssh.RemoteHost

/** Discover the usable collector once per SSH login; a failed command is never an empty healthy snapshot. */
internal class HostMetricsReader(private val remote: RemoteHost, private val preferWindows: Boolean = false) {
    private var windows: Boolean? = null

    suspend fun read(): HostMetrics? {
        for (nativeWindows in windows?.let(::listOf) ?: listOf(preferWindows, !preferWindows)) {
            val lines = mutableListOf<String>()
            val status = if (nativeWindows) remote.runCommand(WindowsShell.command(WindowsHostMetrics.script), TIMEOUT_MS) { lines += it }
                else remote.run(HostMetrics.script, TIMEOUT_MS) { lines += it }
            if (status == null) return null
            val sample = HostMetrics.parse(lines)
            if (status == 0 && (sample.cpuPercent != null || sample.memPercent != null || sample.disks.isNotEmpty())) {
                windows = nativeWindows
                if (!nativeWindows) return sample
                val gpu = mutableListOf<String>()
                if (remote.runCommand(WindowsShell.command(WindowsHostMetrics.gpuScript), 4_000) { gpu += it } == 0)
                    lines += gpu
                return HostMetrics.parse(lines)
            }
        }
        return null
    }

    private companion object { const val TIMEOUT_MS = 15_000L }
}
