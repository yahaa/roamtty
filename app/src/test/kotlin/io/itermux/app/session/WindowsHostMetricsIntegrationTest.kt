package io.itermux.app.session

import io.itermux.ssh.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in native Windows OpenSSH test. Use only a disposable Windows test host. */
class WindowsHostMetricsIntegrationTest {
    @Test fun nativeWindowsProvidesCoreMetricsAndSuccessiveNetworkCounters() = runBlocking {
        val passwordFile = System.getenv("ROAMTTY_WINDOWS_PASSWORD_FILE")
        assumeTrue("Requires native Windows SSH test host", !passwordFile.isNullOrBlank())
        val target = Target(System.getenv("ROAMTTY_WINDOWS_HOST") ?: "127.0.0.1",
            System.getenv("ROAMTTY_WINDOWS_PORT")?.toInt() ?: 22249,
            System.getenv("ROAMTTY_WINDOWS_USER") ?: "vagrant", AuthMethod.PASSWORD, SessionMode.SHELL)
        val pins = object : PinStore {
            private var pin: HostPin? = null
            override suspend fun get(endpoint: String) = pin
            override suspend fun putIfAbsent(pin: HostPin): HostPin = this.pin ?: pin.also { this.pin = it }
        }
        val ssh = JschSession(target, Credentials(File(passwordFile!!).readText().trim().toByteArray()), pins,
            { if (it is Prompt.VerifyHost) listOf("trust") else null })
        val samples = mutableListOf<HostMetrics>()
        try {
            withTimeout(60_000) { ssh.administer { remote ->
                // Exercise first-login detection on a host that does not have /bin/sh.
                val reader = HostMetricsReader(remote)
                repeat(2) {
                    val reading = reader.read()
                    assertTrue("Native Windows returned no sample: $reading", reading is MetricsReading.Sample)
                    samples += (reading as MetricsReading.Sample).metrics
                    delay(1000)
                }
            } }
        } finally { ssh.disconnect() }
        assertEquals("SSH state: ${ssh.state.value}", 2, samples.size)
        for (m in samples) {
            assertTrue(m.os.orEmpty().contains("Windows"))
            assertTrue(m.cpuPercent!! in 0.0..100.0)
            assertTrue(m.memPercent!! in 0.0..100.0)
            assertTrue(m.disks.any { it.mount.matches(Regex("[A-Z]:/")) && it.totalBytes > 0 })
            assertTrue(m.processes.isNotEmpty())
            assertTrue(m.uptimeSeconds!! > 0)
        }
        assertNotNull(samples[1].networkRateSince(samples[0], 1.0))
    }
}
