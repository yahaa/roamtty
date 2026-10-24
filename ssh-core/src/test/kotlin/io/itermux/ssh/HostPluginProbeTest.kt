package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class HostPluginProbeTest {
    @Test fun desktopUnsupportedIsAProbeResult() {
        val out = "${HostPluginProbe.DESKTOP}unsupported\n${HostPluginProbe.UNAME}Linux x86_64\n${HostPluginProbe.DESKTOP_PLUGIN}\n"
        assertEquals(HostDesktop("unsupported", "Linux x86_64", null), HostPluginProbe.parseDesktop(out))
    }

    /** The probe runs as is on this machine's shell and reports one of its results. */
    @Test fun probeScriptRuns() {
        val p = ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true).start()
        p.outputStream.use { it.write(HostPluginProbe.script.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor())
        assertEquals(out, true, HostPluginProbe.parseDesktop(out) != null)
    }
}
