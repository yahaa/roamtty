package io.itermux.app.session

import io.itermux.ssh.HostPlugin
import io.itermux.ssh.RemoteHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class HostMetricsReaderTest {
    private class Host(var unix: Int? = 127, var windows: Int? = 0, var gpu: Int? = 0) : RemoteHost {
        val calls = mutableListOf<String>()
        var unixLines = listOf("idle=75", "kb_MemTotal=1024")
        var windowsLines = listOf("platform=windows", "cpu=50", "kb_MemTotal=16777216")
        override suspend fun probePlugins(): Set<HostPlugin>? = null
        override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int? {
            calls += "unix"
            unixLines.forEach(onLine)
            return unix
        }
        override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int? {
            val script = String(Base64.getDecoder().decode(command.substringAfter("-EncodedCommand ")), Charsets.UTF_16LE)
            if (script.contains("Win32_OperatingSystem")) {
                calls += "windows"
                windowsLines.forEach(onLine)
                return windows
            }
            calls += "gpu"
            onLine("gpu=0, Test GPU, 37, 512, 8192, 40, 50, 100")
            return gpu
        }
    }

    @Test fun unknownNativeWindowsFallsBackThenCachesWorkingCollector() = runBlocking {
        val host = Host()
        val reader = HostMetricsReader(host)
        assertEquals(50.0, reader.sample().cpuPercent!!, 0.001)
        assertEquals(37.0, reader.sample().gpus.single().utilPercent!!, 0.001)
        assertEquals(listOf("unix", "windows", "gpu", "windows", "gpu"), host.calls)
    }
    @Test fun knownWindowsDoesNotProbePosixOnEveryRefresh() = runBlocking {
        val host = Host()
        HostMetricsReader(host, preferWindows = true).sample()
        assertEquals(listOf("windows", "gpu"), host.calls)
    }
    @Test fun unixHostsKeepTheirExistingCollector() = runBlocking {
        val host = Host(unix = 0)
        val reader = HostMetricsReader(host)
        assertEquals(25.0, reader.sample().cpuPercent!!, 0.001)
        reader.sample()
        assertEquals(listOf("unix", "unix"), host.calls)
    }
    @Test fun wrongStoredOsCanFallBackToPosix() = runBlocking {
        val host = Host(unix = 0, windows = 127)
        assertEquals(25.0, HostMetricsReader(host, preferWindows = true).sample().cpuPercent!!, 0.001)
        assertEquals(listOf("windows", "unix"), host.calls)
    }
    @Test fun driverFailureCannotDiscardCoreMetricsOrAcceptPartialGpuOutput() = runBlocking {
        for (status in listOf(null, 1)) {
            val sample = HostMetricsReader(Host(gpu = status)).sample()
            assertEquals(50.0, sample.cpuPercent!!, 0.001)
            assertTrue(sample.gpus.isEmpty())
        }
    }
    @Test fun emptyOrFailedCommandsAreNotHealthySamples() = runBlocking {
        val failed = Host(unix = 3, windows = 3)
        assertEquals(MetricsReading.Unavailable, HostMetricsReader(failed).read())
        val empty = Host(unix = 0, windows = 0).apply { unixLines = listOf("hostname=only"); windowsLines = emptyList() }
        assertEquals(MetricsReading.Unavailable, HostMetricsReader(empty).read())
    }
    @Test fun totalMemoryAloneCannotMarkEmptyGaugesHealthy() = runBlocking {
        val host = Host().apply { windowsLines = listOf("kb_MemTotal=16777216") }
        assertEquals(MetricsReading.Unavailable, HostMetricsReader(host).read())
        host.windowsLines = listOf("kb_MemTotal=0", "kb_MemAvailable=0")
        assertEquals(MetricsReading.Unavailable, HostMetricsReader(host).read())
    }
    @Test fun timeoutReturnsForReconnectWithoutAnotherProbe() = runBlocking {
        val host = Host(unix = null)
        assertEquals(MetricsReading.Lost, HostMetricsReader(host).read())
        assertEquals(listOf("unix"), host.calls)
    }
    @Test fun timeoutAfterDiscoveryIsLostNotUnavailable() = runBlocking {
        val host = Host(windows = 0)
        val reader = HostMetricsReader(host, preferWindows = true)
        reader.sample()
        host.windows = null
        assertEquals(MetricsReading.Lost, reader.read())
    }
    @Test fun missingAndRolledBackNetworkCountersClearRates() {
        val last = HostMetrics(netRxBytes = 100, netTxBytes = 200)
        assertEquals(100.0 to 200.0, HostMetrics(netRxBytes = 300, netTxBytes = 600).networkRateSince(last, 2.0))
        assertNull(HostMetrics(netRxBytes = 300).networkRateSince(last, 2.0))
        assertNull(HostMetrics(netRxBytes = 50, netTxBytes = 600).networkRateSince(last, 2.0))
        assertNull(last.networkRateSince(last, 0.0))
    }

    private suspend fun HostMetricsReader.sample() = (read() as MetricsReading.Sample).metrics
}
