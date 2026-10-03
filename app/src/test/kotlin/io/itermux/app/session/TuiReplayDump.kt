package io.itermux.app.session

import com.termux.terminal.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** Manual harness: ITERMUX_REPLAY=/path/a.frames,... (4-byte length + read) dumps the history beside each file. */
class TuiReplayDump {
    @Test fun dump() {
        val paths = System.getenv("ITERMUX_REPLAY")
        assumeTrue(!paths.isNullOrBlank())
        val quiet = System.getenv("ITERMUX_QUIET_MS")?.toLongOrNull() ?: -1L
        for (path in paths!!.split(',')) File("$path.$quiet.history.txt").writeText(replay(File(path).readBytes(), quietMillis = quiet, timed = path.endsWith(".timed"))
            .joinToString("\n") { it.text.trimEnd() })
    }

    companion object {
        /** Timed recordings (8-byte seconds + 4-byte length + read) capture after [quietMillis] without output. */
        internal fun replay(recording: ByteArray, columns: Int = 90, rows: Int = 30, quietMillis: Long = -1, timed: Boolean = quietMillis >= 0) =
            OutputHistory().also { replayInto(it, recording, columns, rows, quietMillis, timed) }.snapshot()

        internal fun replayInto(history: OutputHistory, recording: ByteArray, columns: Int = 90, rows: Int = 30, quietMillis: Long = -1, timed: Boolean = quietMillis >= 0, maxDelay: Long = 120) {
            lateinit var emulator: TerminalEmulator
            val output = object : TerminalOutput() {
                override fun write(data: ByteArray, offset: Int, count: Int) = Unit
                override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
                override fun onCopyTextToClipboard(text: String?) = Unit
                override fun onPasteTextFromClipboard() = Unit
                override fun onBell() = Unit
                override fun onColorsChanged() = Unit
                override fun onBeforeScreenChange() = history.captureBeforeChange(emulator)
            }
            emulator = TerminalEmulator(output, columns, rows, 8, 16, 2000, SilentTerminalClient())
            val input = ByteBuffer.wrap(recording)
            var pendingSince = 0L
            var last = 0L
            while (input.remaining() >= 4) {
                val now = if (timed) (input.double * 1000).toLong() else 0L
                // Same policy as TerminalConnection: capture after a quiet gap, or every maxDelay ms.
                if (quietMillis >= 0 && pendingSince != 0L && now - last >= quietMillis) { history.captureWhenIdle(emulator); pendingSince = 0L }
                val read = ByteArray(input.int).also { input.get(it) }
                emulator.append(read, read.size)
                if (quietMillis < 0) { history.captureWhenIdle(emulator); continue }
                if (pendingSince == 0L) pendingSince = now
                last = now
                if (now - pendingSince >= maxDelay) { history.captureWhenIdle(emulator); pendingSince = 0L }
            }
            history.captureWhenIdle(emulator)
        }
    }
}
