package io.itermux.app.session

import com.termux.terminal.*
import io.itermux.app.data.Host
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class PendingPrompt(val prompt: Prompt, val answer: CompletableDeferred<List<String>?> = CompletableDeferred())

/** Emulator access (append, resize, key generation, paste, drawing) is serialized on Main. */
class TerminalConnection(
    val host: Host,
    val mode: SessionMode,
    credentials: Credentials,
    pins: PinStore,
    private val scope: CoroutineScope
) : TerminalOutput(), TerminalSession {
    val id: String = UUID.randomUUID().toString()
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val inputWarning = MutableStateFlow<String?>(null)
    val revision = MutableStateFlow(0L)
    var draft: String = "" // Memory only; terminal text and prompt drafts are never saved to disk.
    var onScreenChanged: (() -> Unit)? = null
    var copyToClipboard: ((String) -> Unit)? = null
    var pasteFromClipboard: (() -> Unit)? = null
    private val parser = TerminalEmulator(this, 80, 24, 8, 16, 5000, SilentTerminalClient())
    private val ssh = JschSession(host.target(mode), credentials, pins, interaction = { prompt ->
        val request = PendingPrompt(prompt)
        pending.value = request
        try { request.answer.await() } finally { pending.compareAndSet(request, null) }
    })
    val state = ssh.state
    private val job = scope.launch {
        ssh.connect { bytes ->
            withContext(Dispatchers.Main.immediate) {
                parser.append(bytes, bytes.size)
                revision.value++
                onScreenChanged?.invoke()
            }
        }
    }
    override fun getEmulator() = parser
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val cols = columns.coerceIn(2, 500)
        val lines = rows.coerceIn(2, 300)
        parser.resize(cols, lines, cellWidthPixels, cellHeightPixels)
        ssh.resize(cols, lines, cols * cellWidthPixels, lines * cellHeightPixels)
    }
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (!ssh.write(data.copyOfRange(offset, offset + count))) inputWarning.value = "未发送：连接未就绪或输入队列已满。"
    }
    override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (!Character.isValidCodePoint(codePoint)) return
        write((if (prependEscape) "\u001b" else "") + String(Character.toChars(codePoint)))
    }
    fun paste(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 128 * 1024) {
            inputWarning.value = "单次发送上限为 128 KiB，请分段发送。"
            return
        }
        parser.paste(text)
    }
    override fun reset() { parser.reset(); onScreenChanged?.invoke() }
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String) { copyToClipboard?.invoke(text) }
    override fun onPasteTextFromClipboard() { pasteFromClipboard?.invoke() }
    override fun onBell() = Unit
    override fun onColorsChanged() { onScreenChanged?.invoke() }
    fun disconnect() { pending.value?.answer?.complete(null); ssh.disconnect(); job.cancel() }
}
