package io.itermux.app.e2e

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.view.TerminalView
import io.itermux.app.AppLanguage
import io.itermux.app.AppLocale
import io.itermux.app.ItermuxApp
import io.itermux.app.MainActivity
import io.itermux.app.data.Host
import io.itermux.app.data.StoredPin
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.AuthMethod
import io.itermux.ssh.HostPlugin
import io.itermux.ssh.Phase
import io.itermux.ssh.Prompt
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assume
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import java.io.File
import java.util.UUID

/**
 * Shared plumbing for the end-to-end suite. scripts/e2e/run.sh starts the loopback fixture
 * (scripts/e2e/fixture.sh), forwards it with `adb reverse` and passes its details as instrumentation
 * arguments. Without them every e2e test is skipped, so a plain connectedAndroidTest run stays green.
 */
typealias Rule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

object E2e {
    private val args get() = InstrumentationRegistry.getArguments()
    val password: String get() = args.getString("e2ePassword").orEmpty()
    val port: Int get() = args.getString("e2ePort")?.toIntOrNull() ?: 22258
    /** The fixture's host key as in its .pub file: algorithm and base64 blob. */
    val hostKeyAlgorithm: String get() = args.getString("e2eHostKeyType").orEmpty()
    val hostKey: String get() = args.getString("e2eHostKey").orEmpty()
    const val ADDRESS = "127.0.0.1"
    const val USER = "roamtty_e2e"
    /** Every host the suite saves carries this prefix, so leftovers of an interrupted run are recognised. */
    const val PREFIX = "E2E "
    val endpoint get() = "[$ADDRESS]:$port"
    val enabled get() = password.isNotEmpty() && hostKey.isNotEmpty()

    private const val TAG = "E2E"
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val app get() = context.applicationContext as ItermuxApp
    val outputDir: File get() = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    fun requireFixture() = Assume.assumeTrue("Run through scripts/e2e/run.sh (fixture arguments missing)", enabled)

    fun log(message: String) { Log.i(TAG, message) }

    /** One performance sample; run.sh collects them from logcat and checks them against scripts/e2e/budgets.json. */
    fun metric(name: String, value: Number, unit: String) {
        val line = JSONObject().put("name", name).put("value", value.toDouble()).put("unit", unit).toString()
        Log.i("E2E_METRIC", line)
        File(outputDir, "metrics.jsonl").appendText(line + "\n")
    }

    fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
    }

    val isEmulator get() = Build.HARDWARE in listOf("ranchu", "goldfish")

    fun host(name: String, mode: SessionMode = SessionMode.SHELL, savePassword: Boolean = true,
        plugins: Set<HostPlugin>? = setOf(HostPlugin.TMUX), port: Int = this.port, password: String = this.password): Host {
        val id = UUID.randomUUID().toString()
        return Host(id, PREFIX + name, ADDRESS, port, USER, AuthMethod.PASSWORD.name, mode.name,
            encryptedPassword = if (savePassword) app.vault.encrypt(password.toByteArray(), "$id:password") else null,
            os = "LINUX", plugins = plugins?.let(HostPlugin::encode))
    }

    suspend fun save(host: Host): Host = host.also { app.database.hosts().save(it) }

    /** Trusts the fixture's real host key, as an earlier "信任并连接" would have. */
    suspend fun trustFixture() {
        app.database.hosts().forgetPin(endpoint)
        app.database.hosts().insertPin(StoredPin(endpoint, hostKeyAlgorithm, hostKey))
    }

    suspend fun forgetFixture() = app.database.hosts().forgetPin(endpoint)

    /** Removes this suite's hosts and sessions (and nothing else). */
    fun cleanup(service: SessionService?) = runBlocking {
        val hosts = app.database.hosts().observe().first().filter { it.name.startsWith(PREFIX) }
        withContext(Dispatchers.Main) {
            service?.sessions?.value.orEmpty().filter { session -> session.host.name.startsWith(PREFIX) }
                .forEach { service?.close(it.id); service?.forget(it.id) }
        }
        hosts.forEach { app.database.hosts().delete(it.id) }
    }

    fun bind(rule: ServiceTestRule): SessionService =
        (rule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service

    fun prepareActivity(compose: Rule) {
        compose.activityRule.scenario.onActivity {
            AppLocale.set(it, AppLanguage.ZH_CN)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Every thread's stack, for a hang in this process (the app and the test share it). */
    fun dumpThreads(name: String) {
        val text = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n\n") { (thread, stack) ->
            "\"${thread.name}\" ${thread.state}\n" + stack.joinToString("\n") { "    at $it" }
        }
        File(outputDir, "$name-threads.txt").writeText(text)
    }

    fun screenshot(name: String) = runCatching {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    fun capture(compose: Rule, name: String) {
        runCatching { compose.waitForIdle() }
        screenshot(name)
        runCatching { File(outputDir, "$name-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString()) }
    }

    fun terminalView(compose: Rule): TerminalView? {
        var found: TerminalView? = null
        fun walk(view: View) {
            if (found != null) return
            if (view is TerminalView) found = view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        compose.runOnUiThread { walk(compose.activity.window.decorView) }
        return found
    }

    /** Types through the terminal's own input connection, the way a soft keyboard commits text. */
    fun type(compose: Rule, text: String) {
        val view = requireNotNull(terminalView(compose)) { "no terminal on screen" }
        compose.runOnUiThread { view.onCreateInputConnection(EditorInfo()).commitText(text, 1) }
    }

    fun key(compose: Rule, keyCode: Int) {
        val view = requireNotNull(terminalView(compose)) { "no terminal on screen" }
        compose.runOnUiThread {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    fun enter(compose: Rule) = key(compose, KeyEvent.KEYCODE_ENTER)

    /** Types [command] and Enter. */
    fun run(compose: Rule, command: String) {
        type(compose, command)
        enter(compose)
    }

    fun transcript(session: TerminalConnection): String {
        var text = ""
        instrumentation.runOnMainSync { text = session.getEmulator().screen.transcriptText }
        return text
    }

    fun waitOutput(compose: Rule, session: TerminalConnection, marker: String,
        timeoutMs: Long = 15_000) {
        try { compose.waitUntil(timeoutMs) { marker in transcript(session) } }
        catch (e: Throwable) { throw AssertionError("'$marker' not in terminal; tail: ${transcript(session).takeLast(600)}", e) }
    }

    fun waitTag(compose: Rule, tag: String, timeoutMs: Long = 10_000) =
        waitNode(compose, hasTestTag(tag), timeoutMs, tag)

    fun waitText(compose: Rule, text: String, timeoutMs: Long = 10_000, substring: Boolean = false) =
        waitNode(compose, hasText(text, substring = substring), timeoutMs, text)

    fun waitGone(compose: Rule, matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    /** In either tree: a tag sits on the unmerged node, while a row's text is often only on the merged one. */
    fun exists(compose: Rule, matcher: SemanticsMatcher) = listOf(true, false).any { unmerged ->
        runCatching { compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }

    private fun waitNode(compose: Rule, matcher: SemanticsMatcher, timeoutMs: Long, label: String) {
        try { compose.waitUntil(timeoutMs) { exists(compose, matcher) } }
        catch (e: Throwable) { throw AssertionError("'$label' did not appear within $timeoutMs ms", e) }
    }

    suspend fun connected(session: TerminalConnection, timeoutMs: Long = 20_000): Phase =
        kotlinx.coroutines.withTimeout(timeoutMs) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase

    /** Answers fingerprint and keyboard prompts of sessions opened outside the UI. */
    fun answer(prompt: Prompt): List<String> = when (prompt) {
        is Prompt.VerifyHost -> listOf("trust")
        is Prompt.Keyboard -> prompt.labels.map { password }
    }

    fun elapsedSince(start: Long) = SystemClock.elapsedRealtime() - start

    fun gc() = repeat(4) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(100) }

    /** Proportional set size of the app process in MB, after a GC. */
    fun pssMb(): Double {
        gc()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return info.totalPss / 1024.0
    }

    fun javaHeapMb(): Double {
        gc()
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    /** CPU time (user + system) of this process in ms, from /proc. */
    fun processCpuMs(): Long {
        val stat = File("/proc/self/stat").readText()
        val fields = stat.substringAfterLast(')').trim().split(' ')
        // utime and stime are fields 14 and 15 of the full line, 12 and 13 after the command name.
        val ticks = fields[11].toLong() + fields[12].toLong()
        return ticks * 1000 / 100
    }

    data class Frames(val total: Int, val janky: Int, val p50: Int, val p90: Int, val p99: Int) {
        val jankPercent get() = if (total == 0) 0.0 else janky * 100.0 / total
    }

    fun resetFrames() { shell("dumpsys gfxinfo ${context.packageName} reset") }

    fun frames(): Frames {
        val text = shell("dumpsys gfxinfo ${context.packageName}")
        fun int(prefix: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(':')?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val total = int("Total frames rendered")
        // With no frames, gfxinfo reports its top histogram bucket as every percentile.
        if (total == 0) return Frames(0, 0, 0, 0, 0)
        return Frames(total, int("Janky frames"), int("50th percentile"), int("90th percentile"), int("99th percentile"))
    }

    fun <T> Rule.property(tag: String, key: SemanticsPropertyKey<T>): T? {
        val config = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        return if (key in config) config[key] else null
    }

    /** The text of the node tagged [tag] and everything inside it. */
    fun Rule.text(tag: String): String = runCatching {
        onAllNodes(hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> if (SemanticsProperties.Text in node.config) node.config[SemanticsProperties.Text] else emptyList() }
            .joinToString(" ") { it.text }
    }.getOrDefault("")

    fun Rule.isOn(tag: String) = property(tag, SemanticsProperties.ToggleableState) == ToggleableState.On

    fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
    }
}
