package io.itermux.app.session

import com.termux.terminal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in JVM microbenchmarks, not device frame-rate measurements or timing assertions. */
class HotPathBenchmarkTest {
    private fun measure(name: String, operation: () -> Unit) {
        // Android's compile classpath omits java.management; this test runs only on the host JVM.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val type = Class.forName("com.sun.management.ThreadMXBean")
        type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val allocatedBytes = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val thread = Thread.currentThread().id
        fun allocated() = allocatedBytes.invoke(bean, thread) as Long
        repeat(3) { operation() }
        val samples = List(5) {
            val before = allocated()
            val started = System.nanoTime()
            operation()
            (System.nanoTime() - started) / 1e6 to (allocated() - before)
        }
        println("BENCH $name median_ms=${samples.map { it.first }.sorted()[2]} " +
            "median_allocated_bytes=${samples.map { it.second }.sorted()[2]}")
    }

    @Test fun measureHotPaths() {
        assumeTrue("Set ITERMUX_BENCHMARK=1 to run", System.getenv("ITERMUX_BENCHMARK") == "1")
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        val emulator = TerminalEmulator(output, 160, 100, 8, 16, 400, SilentTerminalClient())
        val initial = (0 until 100).joinToString("\r\n") { "row $it " + "x".repeat(140) }.toByteArray()
        emulator.append(initial, initial.size)
        val frames = List(300) { "\u001b[99;1Hframe $it".toByteArray() }
        measure("history-300-frames-160x100") {
            val history = OutputHistory()
            for (frame in frames) {
                emulator.append(frame, frame.size)
                history.capture(emulator)
            }
            check(history.snapshot().isNotEmpty())
        }
        val files = List(400) { DownloadItem("/file-$it") }
        measure("download-400-files-20000-callbacks") {
            runBlocking {
                runDownloadQueue(files, { _, _ -> }) { file, progress ->
                    if (file.path == "/file-0") repeat(20_000) { progress(it.toLong() + 1, -1) }
                }
            }
        }
        val duplicates = List(2000) { DownloadItem("/$it/report.pdf") }
        measure("download-2000-duplicate-names") { check(downloadNames(duplicates).size == duplicates.size) }
        val raw = (0 until 10_000).joinToString("\n") { "#12345\necho $it" }
        measure("shell-history-20000-lines") {
            check(ShellHistoryCommands.rankByRecency(ShellHistoryCommands.extractCommands(raw, false)).size == 200)
        }
    }
}
