package io.itermux.app.session

import io.itermux.app.tr

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
    credentials: Credentials?,
    pins: PinStore,
    private val scope: CoroutineScope,
    val id: String = UUID.randomUUID().toString(),
    initialTitle: String = "",
    /** Unique remote tmux session for this local entry; legacy restorations use [Tmux.LEGACY_SESSION]. */
    val remoteSessionName: String = if (mode == SessionMode.TMUX) Tmux.newSessionName() else Tmux.LEGACY_SESSION,
    private val onRename: () -> Unit = {}
) : TerminalOutput(), TerminalSession {
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val inputWarning = MutableStateFlow<String?>(null)
    val revision = MutableStateFlow(0L)
    private val mutableTitle = MutableStateFlow(initialTitle)
    val title = mutableTitle.asStateFlow()
    val displayTitle: String get() = title.value.ifBlank { host.name }
    fun rename(title: String) { mutableTitle.value = clipSessionTitle(title.trim()); onRename() }
    var draft: String = "" // Memory only; terminal text and prompt drafts are never saved to disk.
    var onScreenChanged: (() -> Unit)? = null
    var copyToClipboard: ((String) -> Unit)? = null
    var pasteFromClipboard: (() -> Unit)? = null
    private val parser = TerminalEmulator(this, 80, 24, 8, 16, 5000, SilentTerminalClient())
    private val ssh = JschSession(
        host.target(mode),
        credentials ?: Credentials(),
        pins,
        interaction = { prompt ->
            val request = PendingPrompt(prompt)
            pending.value = request
            try { request.answer.await() } finally { pending.compareAndSet(request, null) }
        },
        remoteSessionName = remoteSessionName
    )
    val state = if (credentials != null) ssh.state else MutableStateFlow(ConnectionState(
        Phase.DISCONNECTED, "连接已中断，请手动重连。会话记录已恢复，未发送的输入不会重发。" )).asStateFlow()
    private val tmux = TmuxHistoryCoordinator(credentials != null && mode == SessionMode.TMUX, ssh, scope) { inputWarning.value = it }
    val tmuxHistory = tmux.history
    private val mutableCommonCommands = MutableStateFlow<CommonCommandsState>(CommonCommandsState.Idle)
    val commonCommands = mutableCommonCommands.asStateFlow()
    private var commonCommandsJob: Job? = null
    private val job = if (credentials == null) null else scope.launch {
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
    private val commonCommandsWatcher = if (credentials == null) null else scope.launch {
        var wasConnected = false
        ssh.state.collect { connection ->
            val connected = connection.phase == Phase.CONNECTED
            if (connected && !wasConnected) refreshCommonCommands()
            wasConnected = connected
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
        if (!ssh.write(data.copyOfRange(offset, offset + count))) inputWarning.value = tr("未发送：连接未就绪或输入队列已满。")
    }
    override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (!Character.isValidCodePoint(codePoint)) return
        write((if (prependEscape) "\u001b" else "") + String(Character.toChars(codePoint)))
    }
    fun paste(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 128 * 1024) {
            inputWarning.value = tr("单次发送上限为 128 KiB，请分段发送。")
            return
        }
        parser.paste(text)
    }
    fun enterTmuxHistory() = tmux.enter()
    fun moveTmuxHistoryTo(position: Int) = tmux.moveTo(position)
    fun exitTmuxHistory() = tmux.exit()
    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) = ssh.upload(remotePath, source, onProgress)
    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {}, onSize: (Long) -> Unit = {}) = ssh.download(remotePath, destination, onProgress, onSize)
    suspend fun listRemoteDirectory(remotePath: String = "") = ssh.listRemoteDirectory(remotePath)
    /** Rename a remote path; distinct from [rename] which only changes the local session title. */
    suspend fun renameRemoteFile(oldPath: String, newPath: String) = ssh.rename(oldPath, newPath)
    suspend fun deleteRemoteFile(path: String) = ssh.deleteFile(path)

    fun refreshCommonCommands() {
        commonCommandsJob?.cancel()
        commonCommandsJob = scope.launch {
            mutableCommonCommands.value = CommonCommandsState.Loading
            try {
                mutableCommonCommands.value = withContext(Dispatchers.IO) { loadCommonCommands() }
            } catch (_: CancellationException) {
                // superseded refresh or disconnect
            } catch (_: Exception) {
                mutableCommonCommands.value = CommonCommandsState.Unavailable("无法读取 shell 历史")
            }
        }
    }

    /** Inject command text into the PTY and send CR (execute), not bracketed paste. */
    fun executeCommonCommand(command: String) {
        if (command.isEmpty()) return
        write(command + "\r")
    }

    private suspend fun loadCommonCommands(): CommonCommandsState {
        val bash = runCatching { ssh.readRemoteText(".bash_history") }.getOrElse { throw it }
        if (bash != null) {
            val (raw, truncated) = bash
            if (raw.isNotBlank()) {
                val ranked = ShellHistoryCommands.rankByFrequencyThenRecency(
                    ShellHistoryCommands.extractCommands(raw, zshStyle = false, skipFirstLine = truncated)
                )
                if (ranked.isNotEmpty()) return CommonCommandsState.Ready(ranked, "~/.bash_history")
            }
        }
        val zsh = ssh.readRemoteText(".zsh_history")
        if (zsh != null) {
            val (raw, truncated) = zsh
            if (raw.isNotBlank()) {
                val ranked = ShellHistoryCommands.rankByFrequencyThenRecency(
                    ShellHistoryCommands.extractCommands(raw, zshStyle = true, skipFirstLine = truncated)
                )
                if (ranked.isNotEmpty()) return CommonCommandsState.Ready(ranked, "~/.zsh_history")
            }
        }
        return CommonCommandsState.Unavailable("未找到 ~/.bash_history 或 ~/.zsh_history，或历史为空")
    }

    override fun reset() { parser.reset(); onScreenChanged?.invoke() }
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String) { copyToClipboard?.invoke(text) }
    override fun onPasteTextFromClipboard() { pasteFromClipboard?.invoke() }
    override fun onBell() = Unit
    override fun onColorsChanged() { onScreenChanged?.invoke() }
    val transfers = FileTransferController(scope)
    fun disconnect() {
        transfers.cancel()
        commonCommandsJob?.cancel()
        commonCommandsWatcher?.cancel()
        pending.value?.answer?.complete(null)
        tmux.disconnect()
        ssh.disconnect()
        job?.cancel()
    }
}
