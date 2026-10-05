package io.itermux.app.session

import io.itermux.app.tr
import io.itermux.app.trf

import com.termux.terminal.*
import io.itermux.app.data.Host
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val onRename: () -> Unit = {},
    private val onHostSystemDetected: suspend (HostSystem) -> Unit = {},
    /** Carry the terminal over mosh when the host has it; SSH remains for files and history. */
    preferMosh: Boolean = false,
    private val onPluginsDetected: suspend (Set<HostPlugin>) -> Unit = {}
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
    // Every row kept costs ~0.5 KB of heap at phone widths, for each open session.
    private val parser = TerminalEmulator(this, 80, 24, 8, 16, 10000, SilentTerminalClient())
    private val outputHistory = OutputHistory()
    internal fun outputHistorySnapshot(): List<OutputHistoryLine> {
        if (recordsOutput) captureIdleOutput.run()
        return outputHistory.snapshot()
    }
    override fun onBeforeScreenChange() { if (recordsOutput) outputHistory.captureBeforeChange(parser) }
    // Browsing remote copy-mode redraws old text; it is not new application output.
    private val recordsOutput get() = mode != SessionMode.TMUX || tmuxHistory.value?.source != HistorySource.TMUX
    // Full-screen programs repaint a frame over several reads; record the screen once output pauses.
    private val captureHandler = Handler(Looper.getMainLooper())
    private var outputPendingSince = 0L
    private val captureIdleOutput = Runnable {
        captureHandler.removeCallbacksAndMessages(null)
        outputPendingSince = 0L
        if (recordsOutput) outputHistory.captureWhenIdle(parser)
    }
    private fun scheduleOutputCapture() {
        val now = SystemClock.uptimeMillis()
        if (outputPendingSince == 0L) outputPendingSince = now
        // Continuous output still records regularly, so a scrolling transcript never skips a screen.
        if (now - outputPendingSince >= OUTPUT_CAPTURE_MAX_DELAY_MS) { captureIdleOutput.run(); return }
        captureHandler.removeCallbacksAndMessages(null)
        captureHandler.postDelayed(captureIdleOutput, OUTPUT_QUIET_MS)
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
        remoteSessionName = remoteSessionName,
        preferMosh = preferMosh,
        elapsedMillis = SystemClock::elapsedRealtime,
        log = { android.util.Log.i("RoamttyConn", "${host.name}#$sessionNumber $it") }
    )
    val state = if (credentials != null) ssh.state else MutableStateFlow(ConnectionState(
        Phase.DISCONNECTED, "连接已中断，请手动重连。会话记录已恢复，未发送的输入不会重发。" )).asStateFlow()
    val detectedSystem = ssh.detectedSystem
    /** SSH or mosh once connected; null before the first connection. */
    val transport = ssh.transport
    private val systemWatcher = if (credentials == null) null else scope.launch {
        launch {
            ssh.detectedPlugins.filterNotNull().collect { plugins ->
                try { onPluginsDetected(plugins) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Metadata persistence must never tear down a terminal. */ }
            }
        }
        launch { ssh.moshFallback.filterNotNull().collect { inputWarning.value = it } }
        detectedSystem.filterNotNull().collect { system ->
            try { onHostSystemDetected(system) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Metadata persistence must never tear down a terminal. */ }
        }
    }
    private val tmux = TmuxHistoryCoordinator(credentials != null && mode == SessionMode.TMUX, ssh, scope) { inputWarning.value = it }
    val tmuxHistory = tmux.history
    private val mutableCommonCommands = MutableStateFlow<CommonCommandsState>(CommonCommandsState.Idle)
    val commonCommands = mutableCommonCommands.asStateFlow()
    private var commonCommandsJob: Job? = null
    private var recentCommands = emptyList<String>()
    private var savedCommands: CommonCommandsState = CommonCommandsState.Idle
    private val job = if (credentials == null) null else scope.launch {
        try {
            var lastOutput = 0L
            ssh.connect { bytes ->
                val now = SystemClock.elapsedRealtime()
                if (lastOutput != 0L && now - lastOutput > 10_000)
                    android.util.Log.i("RoamttyConn", "${host.name}#$sessionNumber output resumed after ${now - lastOutput} ms, view bound=${onScreenChanged != null}")
                lastOutput = now
                withContext(Dispatchers.Main.immediate) {
                    parser.append(bytes, bytes.size)
                    if (recordsOutput) scheduleOutputCapture()
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
        if (tmuxHistory.value?.active == true && !exitHistoryAndAwait()) return
        paste(text)
    }
    /** tmux browses copy-mode or recorded output; a plain shell browses recorded output (full-screen programs). */
    fun enterTmuxHistory() {
        if (mode == SessionMode.TMUX) tmux.enter()
        else tmux.history.value = TmuxHistory(0, 0, true, parser.mRows, parser.isAlternateBufferActive, HistorySource.OUTPUT)
    }
    fun moveTmuxHistoryTo(position: Int) = tmux.moveTo(position)
    fun exitTmuxHistory() { if (mode == SessionMode.TMUX) tmux.exit() else tmux.history.value = null }
    private suspend fun exitHistoryAndAwait(): Boolean {
        if (mode == SessionMode.TMUX) return tmux.exitAndAwait()
        tmux.history.value = null
        return true
    }
    /** Explicit image insertion must not be lost to copy-mode or a disappearing CLI banner. */
    suspend fun insertImagePath(path: String) {
        if (!exitHistoryAndAwait()) throw java.io.IOException(tr("无法返回 tmux 实时画面，请检查远端会话。"))
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
    suspend fun makeRemoteDirectory(path: String) = ssh.makeDirectory(path)
    suspend fun isRemoteDirectory(path: String) = ssh.isDirectory(path)

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
        if (!exitHistoryAndAwait()) return@launch
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

    private var startupJob: Job? = null
    /** Types [command] once, after the first connection is up and the shell has finished drawing its prompt. */
    fun runWhenConnected(command: String) {
        startupJob?.cancel()
        startupJob = scope.launch {
            ssh.state.first { it.phase == Phase.CONNECTED }
            // rc files and tmux's first pane print for a moment; wait for output to go quiet so the line lands at a prompt.
            val deadline = SystemClock.uptimeMillis() + STARTUP_SETTLE_MAX_MS
            var seen = -1L
            while (SystemClock.uptimeMillis() < deadline) {
                val current = revision.value
                if (current == seen && current > 0) break
                seen = current
                delay(STARTUP_QUIET_MS)
            }
            if (!ssh.write("$command\r".toByteArray(Charsets.UTF_8))) inputWarning.value = tr("未发送：连接未就绪或输入队列已满。")
        }
    }

    /** Detects a connection that died while the phone slept or changed networks. */
    fun verifyAlive() { if (job != null) scope.launch { ssh.verifyAlive() } }

    override fun reset() { parser.reset(); onScreenChanged?.invoke() }
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String) { copyToClipboard?.invoke(text) }
    override fun onPasteTextFromClipboard() { pasteFromClipboard?.invoke() }
    override fun onBell() = Unit
    override fun onColorsChanged() { onScreenChanged?.invoke() }
    val transfers = FileTransferController(scope)
    val fileBrowser = RemoteBrowserState(scope)
    fun disconnect() {
        captureHandler.removeCallbacksAndMessages(null)
        startupJob?.cancel()
        fileBrowser.cancel()
        transfers.cancel()
        commonCommandsJob?.cancel()
        commonCommandsWatcher?.cancel()
        systemWatcher?.cancel()
        pending.value?.answer?.complete(null)
        tmux.disconnect()
        ssh.disconnect()
        job?.cancel()
    }
}

private const val OUTPUT_QUIET_MS = 15L
private const val OUTPUT_CAPTURE_MAX_DELAY_MS = 120L
private const val STARTUP_QUIET_MS = 350L
private const val STARTUP_SETTLE_MAX_MS = 4000L
