package io.itermux.app.session

import com.termux.terminal.*
import io.itermux.app.data.Host
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class PendingPrompt(val prompt: Prompt, val answer: CompletableDeferred<List<String>?> = CompletableDeferred())

internal fun clipSessionTitle(value: String): String = value.take(80).let {
    if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
}

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
    private val mutableTitle = MutableStateFlow("")
    val title = mutableTitle.asStateFlow()
    val displayTitle: String get() = title.value.ifBlank { host.name }
    fun rename(title: String) { mutableTitle.value = clipSessionTitle(title.trim()) }
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
    private val tmux = TmuxHistoryCoordinator(mode == SessionMode.TMUX, ssh, scope) { inputWarning.value = it }
    val tmuxHistory = tmux.history
    private val job = scope.launch {
        try {
            ssh.connect { bytes ->
                withContext(Dispatchers.Main.immediate) {
                    parser.append(bytes, bytes.size)
                    revision.value++
                    onScreenChanged?.invoke()
                }
            }
        } finally { tmux.onConnectionEnded() }
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
    fun enterTmuxHistory() = tmux.enter()
    fun moveTmuxHistoryTo(position: Int) = tmux.moveTo(position)
    fun exitTmuxHistory() = tmux.exit()
    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) = ssh.upload(remotePath, source, onProgress)
    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {}) = ssh.download(remotePath, destination, onProgress)
    override fun reset() { parser.reset(); onScreenChanged?.invoke() }
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String) { copyToClipboard?.invoke(text) }
    override fun onPasteTextFromClipboard() { pasteFromClipboard?.invoke() }
    override fun onBell() = Unit
    override fun onColorsChanged() { onScreenChanged?.invoke() }
    fun disconnect() { pending.value?.answer?.complete(null); tmux.disconnect(); ssh.disconnect(); job.cancel() }
}
