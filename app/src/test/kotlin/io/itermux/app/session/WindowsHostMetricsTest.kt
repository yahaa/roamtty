package io.itermux.app.session

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class WindowsHostMetricsTest {
    private fun run(script: String, env: Map<String, String> = emptyMap()): Pair<Int, List<String>> {
        val executable = System.getenv("ROAMTTY_PWSH")
        assumeTrue("Requires ROAMTTY_PWSH", !executable.isNullOrEmpty())
        val provider = javaClass.getResource("/windows-metrics-provider.ps1")!!.readText()
        val command = "[Threading.Thread]::CurrentThread.CurrentCulture=[cultureinfo]'de-DE';\n$provider\n$script"
        val process = ProcessBuilder(executable!!, "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand",
            Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))).apply {
            environment().putAll(env)
            redirectErrorStream(true)
        }.start()
        try {
            assertTrue("PowerShell collector hung", process.waitFor(15, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readLines()
        } finally { process.destroyForcibly() }
    }

    @Test fun windowsCimSampleUsesCorrectUnitsAndCulture() {
        val (status, lines) = run(WindowsHostMetrics.script)
        assertEquals(lines.toString(), 0, status)
        val m = HostMetrics.parse(lines)
        assertEquals("开发机", m.hostname)
        assertEquals("Microsoft Windows 11 专业版", m.os)
        assertEquals(8, m.cpus)
        assertEquals(50.0, m.cpuPercent!!, 0.001)
        assertEquals(25.0, m.memPercent!!, 0.001)
        assertEquals(536870912L, m.swapUsedBytes)
        assertEquals(listOf("C:/", "D:/"), m.disks.map { it.mount })
        assertEquals(107374182400L, m.disks[0].totalBytes)
        assertEquals(40.0, m.disks[0].percent, 0.001)
        assertTrue(m.uptimeSeconds!! in 3599..3605)
        assertEquals(2, m.users)
        assertEquals(listOf("compiler", "服务 工作"), m.processes.map { it.name })
        assertEquals(200.0, m.processes[0].cpuPercent, 0.001)
        assertEquals(6.25, m.processes[0].memPercent, 0.001)
        assertEquals(3.125, m.processes[1].memPercent, 0.006) // .NET versions differ at midpoint rounding.
        assertNotNull(m.netRxBytes)
        assertNotNull(m.netTxBytes)
    }

    @Test fun unavailableOptionalProvidersDoNotInventZeroOrDiscardMemory() {
        val (status, lines) = run(WindowsHostMetrics.script, mapOf("ROAMTTY_METRICS_OPTIONAL_FAILURE" to "1"))
        assertEquals(0, status)
        val m = HostMetrics.parse(lines)
        assertEquals(25.0, m.memPercent!!, 0.001)
        assertNull(m.cpuPercent)
        assertTrue(m.disks.isEmpty())
        assertTrue(m.processes.isEmpty())
    }

    @Test fun failedSystemQueryIsNotAHealthyEmptySample() {
        assertEquals(3, run(WindowsHostMetrics.script, mapOf("ROAMTTY_METRICS_FAILURE" to "1")).first)
    }

    @Test fun slowOptionalProcessQueryHasShortDeadlineAndPreservesCoreSample() {
        // The provider simulates a stalled CIM call honoring OperationTimeoutSec.
        val started = System.nanoTime()
        val (status, lines) = run(WindowsHostMetrics.script, mapOf("ROAMTTY_METRICS_SLOW_PROCESS" to "1"))
        assertEquals(lines.toString(), 0, status)
        val m = HostMetrics.parse(lines)
        assertEquals(50.0, m.cpuPercent!!, 0.001)
        assertEquals(25.0, m.memPercent!!, 0.001)
        assertEquals(2, m.disks.size)
        assertTrue(m.processes.isEmpty())
        assertTrue("Optional query exhausted the sample deadline", (System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test fun commandsFitTheWindowsCmdCommandLineLimit() {
        listOf(WindowsHostMetrics.script, WindowsHostMetrics.gpuScript).forEach {
            val size = WindowsShell.command(it).length
            assertTrue("Encoded PowerShell command is $size characters; CMD limit is 8191", size < 8191)
        }
    }

    @Test fun optionalNvidiaQueriesParseAndHungDriverIsTerminated() {
        val dir = Files.createTempDirectory("windows-gpu-").toFile()
        try {
            val exe = File(dir, "nvidia-smi.exe").apply {
                writeText("#!/usr/bin/env python3\nimport sys\n" +
                    "print('0, RTX Test, 37, 1024, 8192, 45, 80, 200' if '--query-gpu=' in sys.argv[1] else r'42, C:\\Tools\\python.exe, 512')\n")
                setExecutable(true)
            }
            val env = mapOf("PATH" to "${dir.path}:${System.getenv("PATH")}")
            val (status, lines) = run(WindowsHostMetrics.gpuScript, env)
            assertEquals(lines.toString(), 0, status)
            val m = HostMetrics.parse(listOf("platform=windows") + lines)
            assertEquals(37.0, m.gpus.single().utilPercent!!, 0.001)
            assertEquals("python.exe", m.gpuProcesses.single().name)
            exe.writeText("#!/usr/bin/env python3\nimport time\ntime.sleep(60)\n")
            val start = System.nanoTime()
            val stalled = run(WindowsHostMetrics.gpuScript, env)
            assertEquals(0, stalled.first)
            assertTrue(stalled.second.toString(), stalled.second.isEmpty())
            assertTrue("Hung GPU query exceeded its deadline", (System.nanoTime() - start) / 1_000_000 < 7000)
        } finally { dir.deleteRecursively() }
    }
}
