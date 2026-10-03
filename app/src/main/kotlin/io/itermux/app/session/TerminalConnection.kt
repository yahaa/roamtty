package io.itermux.app.session

import io.itermux.app.tr
import io.itermux.app.trf

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
    val sessionNumber: Int = 1,
    /** Unique remote tmux session for this local entry; legacy restorations use [Tmux.LEGACY_SESSION]. */
    val remoteSessionName: String = if (mode == SessionMode.TMUX) Tmux.newSessionName() else Tmux.LEGACY_SESSION,
    private val onRename: () -> Unit = {}
) : TerminalOutput(), TerminalSession {
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val inputWarning = MutableStateFlow<String?>(null)
    val revision = MutableStateFlow(0L)
    private val mutableTitle = MutableStateFlow(initialTitle)
    val title = mutableTitle.asStateFlow()
    val defaultTitle: String get() = trf("%s · 会话 %d", host.name, sessionNumber)
    val displayTitle: String get() = titleForDisplay(title.value)
    fun titleForDisplay(title: String): String = if (title.isBlank()) defaultTitle else "${host.name} · $title"
    fun rename(title: String) { mutableTitle.value = clipSessionTitle(title.trim()); onRename() }
    var draft: String = "" // Memory only; terminal text and prompt drafts are never saved to disk.
    var onScreenChanged: (() -> Unit)? = null
    var copyToClipboard: ((String) -> Unit)? = null
    var pasteFromClipboard: (() -> Unit)? = null
    private val parser = TerminalEmulator(this, 80, 24, 8, 16, 50000, SilentTerminalClient())
    private val outputHistory = OutputHistory()
    internal fun outputHistorySnapshot(): List<OutputHistoryLine> = outputHistory.snapshot()
    override fun onBeforeScreenChange() = captureOutput()
    private fun captureOutput() {
        // Browsing remote copy-mode redraws old text; it is not new application output.
        if (mode == SessionMode.TMUX && tmuxHistory.value?.source != HistorySource.TMUX)
            outputHistory.capture(parser)
    }
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
    private var recentCommands = emptyList<String>()
    private var savedCommands: CommonCommandsState = CommonCommandsState.Idle
    private val job = if (credentials == null) null else scope.launch {
        try {
            ssh.connect { bytes ->
                withContext(Dispatchers.Main.immediate) {
                    parser.append(bytes, bytes.size)
                    captureOutput()
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
            if (!connected && wasConnected) tmux.onConnectionEnded()
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
    /** Explicit paste exits remote history before writing, without sending application shortcuts. */
    suspend fun pasteIntoLiveTerminal(text: String) {
        if (tmuxHistory.value?.active == true && !tmux.exitAndAwait()) return
        paste(text)
    }
    fun enterTmuxHistory() = tmux.enter()
    fun moveTmuxHistoryTo(position: Int) = tmux.moveTo(position)
    fun exitTmuxHistory() = tmux.exit()
    /** Explicit image insertion must not be lost to copy-mode or a disappearing CLI banner. */
    suspend fun insertImagePath(path: String) {
        if (!tmux.exitAndAwait()) throw java.io.IOException(tr("无法返回 tmux 实时画面，请检查远端会话。"))
        val text = CodexImageAttach.pathForPaste(path)
        val input = if (parser.isBracketedPasteEnabled) "\u001b[200~$text\u001b[201~" else text
        if (!ssh.write(input.toByteArray(Charsets.UTF_8))) {
            throw java.io.IOException(tr("未发送：连接未就绪或输入队列已满。"))
        }
    }
    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) = ssh.upload(remotePath, source, onProgress)
    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {}, onSize: (Long) -> Unit = {}) = ssh.download(remotePath, destination, onProgress, onSize)
    suspend fun listRemoteDirectory(remotePath: String = "") = ssh.listRemoteDirectory(remotePath)
    suspend fun readFileContent(remotePath: String, maxBytes: Int) = ssh.readFileContent(remotePath, maxBytes)
    private var reportedDirectory: String? = null
    override fun onWorkingDirectoryChanged(uri: String) {
        reportedDirectory = runCatching { java.net.URI(uri) }.getOrNull()?.takeIf { it.scheme == "file" }
            ?.path?.takeIf(::isValidRemoteDirectory)
    }
    suspend fun currentWorkingDirectory(): String? = ssh.currentWorkingDirectory() ?: reportedDirectory

    fun openTerminalDownloads() = transfers.openDownloads { currentWorkingDirectory().orEmpty() }
    /** Rename a remote path; distinct from [rename] which only changes the local session title. */
    suspend fun renameRemoteFile(oldPath: String, newPath: String) = ssh.rename(oldPath, newPath)
    suspend fun deleteRemoteFile(path: String) = ssh.deleteFile(path)

    fun refreshCommonCommands() {
        commonCommandsJob?.cancel()
        commonCommandsJob = scope.launch {
            mutableCommonCommands.value = CommonCommandsState.Loading
            try {
                savedCommands = withContext(Dispatchers.IO) { loadCommonCommands { ssh.readRemoteText(it) } }
                publishCommonCommands()
            } catch (_: CancellationException) {
                // superseded refresh or disconnect
            } catch (_: Exception) {
                savedCommands = CommonCommandsState.Unavailable("无法读取 shell 历史")
                publishCommonCommands()
            }
        }
    }

    fun executeHistoryCommand(command: String) = scope.launch {
        val fullScreen = (mode == SessionMode.TMUX && ssh.tmuxHistory()?.alternateScreen != false) ||
            (mode == SessionMode.SHELL && parser.isAlternateBufferActive)
        // Complete all suspending probes before the exit barrier. Nothing may reopen
        // copy-mode between a successful exit and the immediate, Main-thread write.
        if (!tmux.exitAndAwait()) return@launch
        // Exiting a transcript never turns an interactive program into a shell prompt.
        if (fullScreen) {
            inputWarning.value = tr("请先退出全屏程序，再执行 shell 历史命令。")
            return@launch
        }
        val input = ShellHistoryCommands.executionInput(command, parser.isBracketedPasteEnabled)
        if (!ssh.write(input.toByteArray(Charsets.UTF_8))) {
            inputWarning.value = tr("未发送：连接未就绪或输入队列已满。")
            return@launch
        }
        recentCommands = ShellHistoryCommands.mergeRecent(listOf(command), recentCommands)
        publishCommonCommands()
    }

    private fun publishCommonCommands() {
        val saved = savedCommands as? CommonCommandsState.Ready
        mutableCommonCommands.value = if (recentCommands.isEmpty() && saved == null) savedCommands
        else CommonCommandsState.Ready(ShellHistoryCommands.mergeRecent(recentCommands, saved?.commands.orEmpty()),
            saved?.source.orEmpty())
    }

    override fun reset() { parser.reset(); onScreenChanged?.invoke() }
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String) { copyToClipboard?.invoke(text) }
    override fun onPasteTextFromClipboard() { pasteFromClipboard?.invoke() }
    override fun onBell() = Unit
    override fun onColorsChanged() { onScreenChanged?.invoke() }
    val transfers = FileTransferController(scope)
    val fileBrowser = RemoteBrowserState(scope)
    fun disconnect() {
        fileBrowser.cancel()
        transfers.cancel()
        commonCommandsJob?.cancel()
        commonCommandsWatcher?.cancel()
        pending.value?.answer?.complete(null)
        tmux.disconnect()
        ssh.disconnect()
        job?.cancel()
    }
}
