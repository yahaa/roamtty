package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostMetricsTest {
    private val linux = """
        hostname=build-box
        kernel=Linux 6.8.0-45-generic
        arch=x86_64
        os=Ubuntu 24.04.1 LTS
        cpus=8
        model=Intel(R) Xeon(R)   CPU @ 2.20GHz
        cpu1=cpu  100 0 100 700 100 0 0 0 0 0
        cpu2=cpu  150 0 150 780 120 0 0 0 0 0
        load=0.52 0.40 0.31 2/431
        uptime=356400.27
        kb_MemTotal=8000000
        kb_MemAvailable=6000000
        kb_SwapTotal=2000000
        kb_SwapFree=1500000
        net=1000 2000
        disk=100000 40000 60000 /
        disk=200000 50000 150000 /home/dev data
        users=2
        proc= 12.5  3.1 node
        proc=  0.4  0.2 /usr/sbin/sshd
        proc=bad row
    """.trimIndent().lines()

    @Test fun parsesLinuxSnapshot() {
        val m = HostMetrics.parse(linux)
        assertEquals("build-box", m.hostname)
        assertEquals("Ubuntu 24.04.1 LTS", m.os)
        assertEquals("Intel(R) Xeon(R) CPU @ 2.20GHz", m.cpuModel)
        assertEquals(8, m.cpus)
        // 200 ticks elapsed, 100 of them idle or iowait.
        assertEquals(50.0, m.cpuPercent!!, 0.001)
        assertEquals(listOf(0.52, 0.40, 0.31), m.load)
        assertEquals("2/431", m.tasks)
        assertEquals(356400L, m.uptimeSeconds)
        assertEquals(25.0, m.memPercent!!, 0.001)
        assertEquals(512_000_000L, m.swapUsedBytes)
        assertEquals(listOf("/", "/home/dev data"), m.disks.map { it.mount })
        assertEquals(40.0, m.disks[0].percent, 0.001)
        assertEquals(1000L, m.netRxBytes)
        assertEquals(2000L, m.netTxBytes)
        assertEquals(2, m.users)
        assertEquals(listOf(HostMetrics.Process("node", 12.5, 3.1), HostMetrics.Process("sshd", 0.4, 0.2)), m.processes)
    }

    @Test fun macOsUsesIdleShareAndByteCounts() {
        val m = HostMetrics.parse(listOf("idle=91.5", "load= 1.20 1.10 1.00 ", "bytes_MemTotal=17179869184", "bytes_MemAvailable=4294967296"))
        assertEquals(8.5, m.cpuPercent!!, 0.001)
        assertEquals(listOf(1.2, 1.1, 1.0), m.load)
        assertNull(m.tasks)
        assertEquals(75.0, m.memPercent!!, 0.001)
    }

    @Test fun missingValuesStayUnknown() {
        val m = HostMetrics.parse(listOf("hostname=", "cpus=", "garbage"))
        assertNull(m.hostname)
        assertNull(m.cpus)
        assertNull(m.cpuPercent)
        assertNull(m.memPercent)
    }
}
