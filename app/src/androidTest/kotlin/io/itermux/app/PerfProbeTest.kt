package io.itermux.app

import android.content.Intent
import android.os.Debug
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

/**
 * Manual performance probe against the loopback SSH fixture; emulator only.
 * Prints PERF lines (filter logcat or the instrumentation output) and fails when a closed session,
 * a replaced connection or a destroyed Activity stays reachable after GC.
 */
@RunWith(AndroidJUnit4::class)
class PerfProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
    private val host = Host(name = "Perf", address = "127.0.0.1", port = 22228, username = "itermux_test")

    private fun log(message: String) { android.util.Log.i("PERF", message); println("PERF $message") }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }

    private fun gc() = repeat(6) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(150) }
    private fun memory(label: String) {
        gc()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val rt = Runtime.getRuntime()
        log("mem[$label] pss=${info.totalPss / 1024}MB javaHeap=${info.getMemoryStat("summary.java-heap").toInt() / 1024}MB " +
            "native=${info.getMemoryStat("summary.native-heap").toInt() / 1024}MB graphics=${info.getMemoryStat("summary.graphics").toInt() / 1024}MB " +
            "used=${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB")
    }
    private fun alive(refs: List<WeakReference<*>>): Int {
        gc()
        val first = refs.count { it.get() != null }
        if (first == 0) return 0
        // Tell a transient holder (an in-flight read, a layout cache) from a real leak: settle, nudge layout, recheck.
        Thread.sleep(20_000)
        compose.runOnUiThread { compose.activity.window.decorView.requestLayout() }
        compose.waitForIdle()
        gc()
        return refs.count { it.get() != null }.also { log("alive: $first right away, $it after 20s") }
    }
    /** Writes a heap dump to look up what still holds a leaked object (pull it with `run-as … cat`, then hprof-conv). */
    private fun dumpIfRetained(name: String, refs: List<WeakReference<*>>) {
        if (refs.none { it.get() != null }) return
        val file = java.io.File(context.filesDir, "$name.hprof")
        Debug.dumpHprofData(file.path)
        log("heap dump: ${file.path}")
    }

    private fun service() = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
    private fun screen(session: TerminalConnection): String {
        var text = ""
        instrumentation.runOnMainSync { text = session.getEmulator().screen.transcriptText }
        return text
    }
    private suspend fun open(service: SessionService): TerminalConnection {
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try { withTimeout(15_000) { session.state.first { it.phase == Phase.CONNECTED } } } finally { prompts.cancel() }
        return session
    }
    private fun terminalViews(): List<TerminalView> {
        val found = mutableListOf<TerminalView>()
        fun walk(view: View) {
            if (view is TerminalView) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        compose.runOnUiThread { walk(compose.activity.window.decorView) }
        return found
    }
    /** Its own frame, so no stale local in the test method keeps a view (or its Activity) reachable. */
    private fun watchTerminalViews(): List<WeakReference<*>> = terminalViews().map { WeakReference(it) }
    private fun watchActivity(): WeakReference<*> = WeakReference(compose.activity)
    private fun churnRound(service: SessionService, round: Int, views: MutableList<WeakReference<*>>): WeakReference<*> = runBlocking {
        val session = open(service)
        compose.openSession(session.id)
        compose.waitUntil(5_000) { terminalViews().isNotEmpty() }
        views += watchTerminalViews()
        session.write("seq 1 20000; echo DONE$round''_END\r")
        waitFor(session, "DONE${round}_END")
        compose.runOnUiThread { service.close(session.id) }
        compose.waitForIdle()
        WeakReference(session)
    }
    private fun waitFor(session: TerminalConnection, marker: String, timeoutMs: Long = 60_000) =
        compose.waitUntil(timeoutMs) { marker in screen(session) }
    private fun closeAll(service: SessionService) = compose.runOnUiThread {
        service.sessions.value.forEach { service.close(it.id); runCatching { service.forget(it.id) } }
    }

    @After fun forgetHost() { runBlocking { (context.applicationContext as ItermuxApp).database.hosts().delete(host.id) } }

    private fun setup(): SessionService {
        (context.applicationContext as ItermuxApp).database.hosts().also { runBlocking { it.save(host); it.forgetPin("[127.0.0.1]:22228") } }
        return service().also(::closeAll)
    }

    @Test fun closedSessionsAndTheirViewsAreCollected() = runBlocking {
        val service = setup()
        memory("start")
        val connections = mutableListOf<WeakReference<*>>()
        val views = mutableListOf<WeakReference<*>>()
        repeat(8) { round ->
            connections += churnRound(service, round, views)
            if (round % 2 == 1) memory("after close #$round")
        }
        Thread.sleep(1500)
        memory("end")
        val liveConnections = alive(connections)
        val liveViews = alive(views)
        log("churn: connections alive=$liveConnections/${connections.size}, terminal views alive=$liveViews/${views.size}")
        dumpIfRetained("churn", connections + views)
        check(liveConnections == 0 && liveViews == 0) { "leak: $liveConnections connections, $liveViews views" }
    }

    @Test fun switchingSessionsDoesNotKeepOldViews() = runBlocking {
        val service = setup()
        val a = open(service); val b = open(service)
        val views = mutableListOf<WeakReference<*>>()
        memory("switch start")
        repeat(20) { i ->
            // Back leads terminal → host page → host list, where the drawer button is.
            repeat(3) {
                if (compose.onAllNodes(androidx.compose.ui.test.hasTestTag("brand-logo")).fetchSemanticsNodes().isEmpty()) {
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
                }
            }
            compose.openSession(if (i % 2 == 0) a.id else b.id)
            compose.waitUntil(5_000) { terminalViews().isNotEmpty() }
            views += watchTerminalViews()
        }
        memory("switch end")
        val live = alive(views)
        log("switch: terminal views alive=$live/${views.size} (1 expected, the shown one)")
        closeAll(service)
        check(live <= 1) { "leak: $live terminal views" }
    }

    @Test fun reconnectReleasesReplacedConnections() = runBlocking {
        val service = setup()
        var session = open(service)
        val replaced = mutableListOf<WeakReference<*>>()
        repeat(6) {
            session.write("seq 1 5000\r")
            replaced += WeakReference(session)
            session = withContext(Dispatchers.Main) { service.reconnect(session.id, Credentials(password.toByteArray())) }
            val prompts = CoroutineScope(Dispatchers.Main).launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
            try { withTimeout(15_000) { session.state.first { it.phase == Phase.CONNECTED } } } finally { prompts.cancel() }
        }
        val live = alive(replaced)
        log("reconnect: replaced connections alive=$live/${replaced.size}")
        dumpIfRetained("reconnect", replaced)
        closeAll(service)
        check(live == 0) { "leak: $live replaced connections" }
    }

    @Test fun recreatedActivitiesAreCollected() = runBlocking {
        val service = setup()
        val session = open(service)
        compose.openSession(session.id)
        compose.waitUntil(5_000) { terminalViews().isNotEmpty() }
        val activities = mutableListOf<WeakReference<*>>()
        val views = mutableListOf<WeakReference<*>>()
        memory("recreate start")
        repeat(6) {
            activities += watchActivity()
            views += watchTerminalViews()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.waitUntil(5_000) { terminalViews().isNotEmpty() }
        }
        memory("recreate end")
        val liveActivities = alive(activities)
        val liveViews = alive(views)
        log("recreate: activities alive=$liveActivities/${activities.size}, terminal views alive=$liveViews/${views.size}")
        dumpIfRetained("recreate", activities + views)
        closeAll(service)
        check(liveActivities == 0 && liveViews == 0) { "leak: $liveActivities activities, $liveViews views" }
    }

    @Test fun floodOutputThroughputAndFrames() = runBlocking {
        val service = setup()
        val session = open(service)
        compose.openSession(session.id)
        compose.waitUntil(5_000) { terminalViews().isNotEmpty() }
        val pkg = context.packageName
        for ((label, command) in listOf(
            "seq300k" to "seq 1 300000",
            "wide-lines" to "for i in \$(seq 1 3000); do printf '%0200d\\n' \$i; done",
            "colors" to "for i in \$(seq 1 20000); do printf '\\033[3%dm%06d \\033[1;4%dmcolor\\033[0m\\n' \$((i%8)) \$i \$((i%8)); done",
        )) {
            session.write("clear\r"); Thread.sleep(500)
            shell("dumpsys gfxinfo $pkg reset")
            val cpu0 = Debug.threadCpuTimeNanos()
            val start = System.nanoTime()
            session.write("$command; echo FLOOD_$label''_END\r")
            waitFor(session, "FLOOD_${label}_END", 120_000)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val stats = shell("dumpsys gfxinfo $pkg").lineSequence().filter {
                it.trimStart().startsWith("Total frames") || it.trimStart().startsWith("Janky") ||
                    it.trimStart().startsWith("50th") || it.trimStart().startsWith("90th") || it.trimStart().startsWith("99th")
            }.take(6).joinToString(" | ") { it.trim() }
            log("flood[$label] ${elapsed}ms ; $stats ; testThreadCpu=${(Debug.threadCpuTimeNanos() - cpu0) / 1_000_000}ms")
        }
        memory("after flood")
        closeAll(service)
    }

    @Test fun idleScreensDoNotKeepDrawing() = runBlocking {
        val service = setup()
        val pkg = context.packageName
        fun idle(label: String) {
            compose.waitForIdle(); Thread.sleep(6_000) // past toolbox auto-hide and entry animations
            shell("dumpsys gfxinfo $pkg reset")
            val cpu = shell("cat /proc/${android.os.Process.myPid()}/stat").split(" ").let { it[13].toLong() + it[14].toLong() }
            Thread.sleep(10_000)
            val cpu2 = shell("cat /proc/${android.os.Process.myPid()}/stat").split(" ").let { it[13].toLong() + it[14].toLong() }
            val frames = shell("dumpsys gfxinfo $pkg").lineSequence().firstOrNull { it.trimStart().startsWith("Total frames") }?.trim()
            log("idle[$label] 10s: $frames, process cpu=${(cpu2 - cpu) * 10}ms")
        }
        idle("hosts")
        val session = open(service)
        compose.openSession(session.id)
        compose.waitUntil(5_000) { terminalViews().isNotEmpty() }
        idle("terminal-shell")
        session.write("top -d 1\r")
        idle("terminal-top-1s")
        session.write("q")
        closeAll(service)
    }
}
