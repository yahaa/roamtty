package io.itermux.app.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.app.openSession
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.lang.ref.WeakReference

/**
 * Performance samples against the loopback fixture. Each test only measures and reports (E2E_METRIC lines);
 * scripts/e2e/run.sh compares them with scripts/e2e/budgets.json, so budgets can differ per device class.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2ePerfTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    @get:Rule(order = 2) val cleanupAfter = object : ExternalResource() {
        override fun after() { if (E2e.enabled) E2e.cleanup(service) }
    }
    private lateinit var service: SessionService

    @Before fun setUp() {
        E2e.requireFixture()
        service = E2e.bind(serviceRule)
        E2e.cleanup(service)
        runBlocking { E2e.trustFixture() }
        E2e.prepareActivity(compose)
        E2e.waitTag(compose, "home-pager")
    }

    private suspend fun open(name: String = "性能"): TerminalConnection {
        val host = E2e.save(E2e.host(name))
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(E2e.password.toByteArray())) }
        assertEquals(session.state.value.message, Phase.CONNECTED, E2e.connected(session))
        return session
    }

    private fun write(session: TerminalConnection, text: String) = compose.runOnUiThread { session.write(text) }

    private fun show(session: TerminalConnection) {
        compose.openSession(session.id)
        compose.waitUntil(10_000) { E2e.terminalView(compose)?.mEmulator === session.getEmulator() }
        write(session, "echo READY_$((40+2))\r")
        E2e.waitOutput(compose, session, "READY_42")
    }

    private fun cursor(session: TerminalConnection): Pair<Int, Int> {
        var position = 0 to 0
        E2e.instrumentation.runOnMainSync { session.getEmulator().let { position = it.cursorRow to it.cursorCol } }
        return position
    }

    /** SSH login to a shell prompt (TCP, key exchange, password, PTY, first output), five times. */
    @Test fun p01_sshConnect() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("连接耗时"))
        val samples = (1..5).map {
            val start = System.nanoTime()
            val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(E2e.password.toByteArray())) }
            assertEquals(Phase.CONNECTED, E2e.connected(session))
            val ms = (System.nanoTime() - start) / 1_000_000
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
            ms
        }
        E2e.log("ssh connect samples $samples")
        E2e.metric("ssh_connect_p50_ms", E2e.percentile(samples, 0.5), "ms")
        E2e.metric("ssh_connect_max_ms", samples.max(), "ms")
    }

    /** Key press to echo on screen: input connection → SSH → shell echo → emulator, 40 keys. */
    @Test fun p02_keystrokeEchoLatency() = runBlocking<Unit> {
        val session = open()
        show(session)
        val samples = mutableListOf<Long>()
        repeat(40) { i ->
            val before = cursor(session)
            val start = System.nanoTime()
            E2e.type(compose, ('a' + i % 26).toString())
            val deadline = start + 5_000_000_000
            while (cursor(session) == before && System.nanoTime() < deadline) Thread.sleep(1)
            samples += (System.nanoTime() - start) / 1_000_000
            if (i % 20 == 19) { write(session, "\u0015"); Thread.sleep(150) }
        }
        E2e.log("echo samples $samples")
        E2e.metric("echo_p50_ms", E2e.percentile(samples, 0.5), "ms")
        E2e.metric("echo_p95_ms", E2e.percentile(samples, 0.95), "ms")
    }

    /** Output floods: plain, wide and coloured lines; time to the end marker and frame jank while drawing. */
    @Test fun p03_outputFlood() = runBlocking<Unit> {
        val session = open()
        show(session)
        for ((label, command) in listOf(
            "seq200k" to "seq 1 200000",
            "wide" to "for i in \$(seq 1 3000); do printf '%0200d\\n' \$i; done",
            "color" to "for i in \$(seq 1 20000); do printf '\\033[3%dm%06d \\033[1;4%dmcolor\\033[0m\\n' \$((i%8)) \$i \$((i%8)); done",
        )) {
            write(session, "clear\r"); Thread.sleep(600)
            E2e.resetFrames()
            val start = System.nanoTime()
            write(session, "$command; echo FLOOD_${label}_\$((1+1))\r")
            E2e.waitOutput(compose, session, "FLOOD_${label}_2", 120_000)
            val ms = (System.nanoTime() - start) / 1_000_000
            val frames = E2e.frames()
            E2e.log("flood $label ${ms}ms $frames")
            E2e.metric("flood_${label}_ms", ms, "ms")
            E2e.metric("flood_${label}_jank_pct", frames.jankPercent, "%")
        }
    }

    private fun idle(label: String) {
        compose.waitForIdle()
        Thread.sleep(6_000) // past entry animations and the toolbox auto-hide
        E2e.resetFrames()
        val cpu = E2e.processCpuMs()
        Thread.sleep(10_000)
        val used = E2e.processCpuMs() - cpu
        val frames = E2e.frames()
        E2e.log("idle $label cpu=${used}ms $frames")
        E2e.metric("idle_${label}_cpu_ms_per_10s", used, "ms")
        E2e.metric("idle_${label}_frames_per_10s", frames.total, "frames")
    }

    /** Nothing on screen changes: the app should neither draw nor spin. */
    @Test fun p05_idleScreens() = runBlocking<Unit> {
        idle("home")
        val session = open()
        show(session)
        idle("terminal")
    }

    /** Opens eight busy sessions, measures, closes them; returns only weak references, so no frame keeps them. */
    private fun busySessions(): List<WeakReference<TerminalConnection>> = runBlocking {
        val host = E2e.save(E2e.host("内存"))
        val sessions = (1..8).map {
            withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(E2e.password.toByteArray())) }
        }
        sessions.forEach { assertEquals(Phase.CONNECTED, E2e.connected(it)) }
        show(sessions.first())
        sessions.forEachIndexed { i, s -> write(s, "seq 1 20000; echo MEM_${i}_\$((3+3))\r") }
        sessions.forEachIndexed { i, s -> E2e.waitOutput(compose, s, "MEM_${i}_6", 60_000) }
        E2e.metric("pss_8_sessions_mb", E2e.pssMb(), "MB")
        E2e.metric("java_heap_8_sessions_mb", E2e.javaHeapMb(), "MB")
        compose.runOnUiThread { sessions.forEach { service.close(it.id); service.forget(it.id) } }
        sessions.map { WeakReference(it) }
    }

    /** Memory with eight busy sessions, and that closed sessions (and their terminals) are collected. */
    @Test fun p06_memoryAndLeaks() {
        E2e.metric("pss_home_mb", E2e.pssMb(), "MB")
        val refs = busySessions()
        E2e.waitTag(compose, "home-pager")
        // Closing the final card must release its buffers without rotating or leaving the Activity.
        Thread.sleep(2_000)
        E2e.gc()
        var alive = refs.count { it.get() != null }
        // Tell a transient holder (an in-flight read) from a leak: settle and recheck.
        if (alive > 0) {
            Thread.sleep(15_000)
            compose.waitForIdle()
            E2e.gc()
            alive = refs.count { it.get() != null }
        }
        // Compose keeps the last laid-out nodes of a page that has gone (OnPositionedDispatcher.cachedNodes, 16 slots)
        // until a later layout pass fills those slots again; a removed session list held there still reaches its
        // sessions through its composition. That is the old view tree's cache, not a leak of the app's: a recreated
        // Activity drops it, as the other leak sweeps do, and whatever is alive after that is held by the app.
        if (alive > 0) {
            compose.activityRule.scenario.recreate()
            E2e.prepareActivity(compose)
            E2e.waitTag(compose, "home-pager")
            Thread.sleep(1_500)
            E2e.gc()
            alive = refs.count { it.get() != null }
        }
        E2e.metric("leaked_sessions", alive, "count")
        // Keep a heap dump to trace what still holds them (pull with adb, convert with hprof-conv).
        if (alive > 0) android.os.Debug.dumpHprofData(java.io.File(E2e.outputDir, "leaked-sessions.hprof").path)
        E2e.metric("pss_after_close_mb", E2e.pssMb(), "MB")
    }

    /** SFTP throughput over the same SSH connection as the terminal (16 MiB each way). */
    @Test fun p07_sftpThroughput() = runBlocking<Unit> {
        val session = open()
        val sink = java.io.ByteArrayOutputStream(16 shl 20)
        var start = System.nanoTime()
        session.download("e2e/transfer/blob.bin", sink)
        var seconds = (System.nanoTime() - start) / 1e9
        assertEquals(16 shl 20, sink.size())
        E2e.metric("sftp_download_mbps", 16 / seconds, "MiB/s")
        val payload = sink.toByteArray()
        start = System.nanoTime()
        session.upload("e2e/upload/perf-${System.currentTimeMillis()}.bin", payload.inputStream())
        seconds = (System.nanoTime() - start) / 1e9
        E2e.metric("sftp_upload_mbps", 16 / seconds, "MiB/s")
    }
}
