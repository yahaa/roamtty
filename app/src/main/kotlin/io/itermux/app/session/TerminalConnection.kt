package io.itermux.app.session

import io.itermux.app.tr
import io.itermux.app.trf

import com.termux.terminal.*
import io.itermux.app.data.Host
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /** Carry the terminal over mosh when the host has it; SSH remains for files. */
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
        Phase.DISCONNECTED, io.itermux.app.ui.RESTORED_MESSAGE)).asStateFlow()
    val detectedSystem = ssh.detectedSystem
    /** SSH or mosh once connected; null before the first connection. */
    val transport = ssh.transport
    /** tmux or psmux once a resumable session attached; null before that and for plain shells. */
    val multiplexer = ssh.multiplexer
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
    /**
     * The agent this session was started to run, for sessions the app launched (told by their title once restored);
     * others show it once hooks report.
     */
    val launchedAgent = MutableStateFlow(CodingAgent.fromTitle(initialTitle))
    private val mutableAgentStatus = MutableStateFlow<AgentStatus?>(null)
    /** What an agent started with hooks reports in this tmux session; null for none, or while the host is out of reach. */
    val agentStatus = mutableAgentStatus.asStateFlow()
    private val agentStatusWatcher = if (credentials == null || mode != SessionMode.TMUX) null else scope.launch {
        ssh.state.map { it.phase == Phase.CONNECTED }.distinctUntilChanged().collectLatest { connected ->
            if (!connected) { mutableAgentStatus.value = null; return@collectLatest }
            while (true) {
                val output = ssh.agentStatus()
                // A poll that got no answer keeps what was shown; only an answer without an agent clears it.
                val status = if (output == null) mutableAgentStatus.value else AgentStatus.parse(output)
                mutableAgentStatus.value = status
                // An agent shows up within seconds of starting; without one the poll can be lazy.
                delay(if (status != null) 3_000 else 10_000)
            }
        }
    }
    private val mutableCommonCommands = MutableStateFlow<CommonCommandsState>(CommonCommandsState.Idle)
    val commonCommands = mutableCommonCommands.asStateFlow()
    private var commonCommandsJob: Job? = null
    private var recentCommands = emptyList<String>()
    private var savedCommands: CommonCommandsState = CommonCommandsState.Idle
    private val job = if (credentials == null) null else scope.launch {
        var lastOutput = 0L
        ssh.connect { bytes ->
            val now = SystemClock.elapsedRealtime()
            if (lastOutput != 0L && now - lastOutput > 10_000)
                android.util.Log.i("RoamttyConn", "${host.name}#$sessionNumber output resumed after ${now - lastOutput} ms, view bound=${onScreenChanged != null}")
            lastOutput = now
            withContext(Dispatchers.Main.immediate) {
                parser.append(bytes, bytes.size)
                updateSlashCommand()
                revision.value++
                onScreenChanged?.invoke()
            }
        }
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
    private val slashCommand = MutableStateFlow(0)
    private var slashLines = 0
    /** Non-zero while the input on the cursor row starts with `/`, numbering each such line. */
    val slashCommandLine = slashCommand.asStateFlow()
    private fun updateSlashCommand() {
        val row = parser.cursorRow
        val slash = SlashCommandLine.startsWithSlash(parser.screen.getSelectedText(0, row, parser.mColumns, row, false, false))
        slashCommand.value = when {
            !slash -> 0
            slashCommand.value != 0 -> slashCommand.value
            else -> ++slashLines
        }
    }
    /** Which agent, if any, is in front: read from the visible screen when asked. */
    fun screenApp(): ScreenApp {
        val rows = List(parser.mRows) { row -> parser.screen.getSelectedText(0, row, parser.mColumns, row, false, false) }
        return ScreenApp.detect(rows, parser.cursorRow)
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
    /** Explicit image insertion must not be lost to a disappearing CLI banner. */
    fun insertImagePath(path: String) {
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
        val fullScreen = (mode == SessionMode.TMUX && ssh.tmuxAlternateScreen() != false) ||
            (mode == SessionMode.SHELL && parser.isAlternateBufferActive)
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
    /** Types [command] once into a ready writer; explicitly rejected input may wait for reattachment. */
    fun runWhenConnected(command: String, agentHooks: CodingAgent? = null) {
        startupJob?.cancel()
        startupJob = scope.launch {
            if (agentHooks != null) installAgentHooks(agentHooks)
            val input = "$command\r".toByteArray(Charsets.UTF_8)
            try {
                if (!enqueueStartupCommand(ssh.state, ssh.inputEpoch, revision) { epoch -> ssh.write(input, epoch) })
                    inputWarning.value = tr("未发送：连接未就绪或输入队列已满。")
            } finally { input.fill(0) }
        }
    }

    /** Puts the hook script and [agent]'s hook file on the host before it starts; a failure only costs the status. */
    private suspend fun installAgentHooks(agent: CodingAgent) {
        ssh.state.first { !it.active || it.phase == Phase.CONNECTED }
        try {
            val (name, content, executable) = AgentHooks.hostFile(agent)
            ssh.writeHostFile(AgentHooks.SCRIPT_PATH.substringAfterLast('/'), AgentHooks.script) &&
                ssh.writeHostFile(name, content, executable)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The agent still starts, without reporting. */ }
    }

    /** Restarts the tmux pane in its folder and types its foreground program again; false when tmux was not reached. */
    suspend fun restartPane(): Boolean {
        val command = ssh.restartTmuxPane() ?: return false
        if (command.isNotEmpty()) {
            delay(500) // let the fresh shell print its prompt before the command echoes
            runWhenConnected(command)
        }
        return true
    }

    /** Detects a connection that died while the phone slept or changed networks. */
    suspend fun killRemoteSession() = ssh.killTmuxSession()
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
        startupJob?.cancel()
        fileBrowser.cancel()
        transfers.cancel()
        commonCommandsJob?.cancel()
        commonCommandsWatcher?.cancel()
        agentStatusWatcher?.cancel()
        systemWatcher?.cancel()
        pending.value?.answer?.complete(null)
        ssh.disconnect()
        job?.cancel()
    }
}
