package io.itermux.app.session

import io.itermux.app.tr
import io.itermux.app.trf

import com.termux.terminal.*
import io.itermux.app.data.Host
import io.itermux.app.login.PendingPrompt
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.UUID

/** Logcat tag of the connection lifecycle: connects, drops, network changes and the activity in front. */
internal const val CONNECTION_LOG_TAG = "RoamttyConn"

/** 128 KiB: the most UTF-8 bytes one paste may send, and the most characters the editor's draft holds. */
internal const val MAX_PASTE_SIZE = 128 * 1024

/** What a session restored from the journal says until it is reconnected: it has no login to connect with. */
internal const val RESTORED_MESSAGE = "连接已中断。会话记录已恢复，未发送的输入不会重发。"

private const val INPUT_NOT_SENT = "未发送：连接未就绪或输入队列已满。"

/** [value] as a card's name: on one line, without the spaces around it, at most as long as a title. */
internal fun sessionName(value: String): String = clipSessionTitle(value.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim())

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
    /** The name the user gave the card; blank lets the list name it. */
    initialName: String = "",
    val sessionNumber: Int = 1,
    /** Unique remote tmux session for this local entry; legacy restorations use [Tmux.LEGACY_SESSION]. */
    val remoteSessionName: String = if (mode == SessionMode.TMUX) Tmux.newSessionName() else Tmux.LEGACY_SESSION,
    /** [remoteSessionName] was already on the server: the app attaches to it beside its other clients and never ends it. */
    val external: Boolean = false,
    private val onRename: () -> Unit = {},
    private val onHostSystemDetected: suspend (HostSystem) -> Unit = {},
    /** Carry the terminal over mosh when the host has it; SSH remains for files. */
    preferMosh: Boolean = false,
    private val onPluginsDetected: suspend (Set<HostPlugin>) -> Unit = {},
    /** The read-only desktop probe answered (it runs with the plugin probe). */
    private val onDesktopDetected: suspend (HostDesktop) -> Unit = {},
    /** What the agent reported last time, from the journal. */
    lastAgentStatus: AgentStatus? = null,
    /** The agent reported something new, which the journal keeps. */
    private val onAgentReport: () -> Unit = {},
    /** Active `ANTIGRAVITY_MIC` endpoint for this host while a voice channel is open, or null. */
    private val antigravityMic: () -> String? = { null },
) : TerminalOutput(), TerminalSession {
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val inputWarning = MutableStateFlow<String?>(null)
    val revision = MutableStateFlow(0L)
    private val mutableTitle = MutableStateFlow(initialTitle)
    val title = mutableTitle.asStateFlow()
    val defaultTitle: String get() = trf("%s · 会话 %d", host.name, sessionNumber)
    private val mutableName = MutableStateFlow(initialName)
    /** What the user named the card, on this device only; blank for the name the list derives. [title] stays as launched. */
    val name = mutableName.asStateFlow()
    val displayTitle: String get() = titleForDisplay(title.value, name.value)
    fun titleForDisplay(title: String, name: String): String = when {
        name.isNotBlank() -> "${host.name} · $name"
        title.isBlank() -> defaultTitle
        else -> "${host.name} · $title"
    }
    fun rename(title: String) { mutableTitle.value = clipSessionTitle(title.trim()); onRename() }
    /** Names the card [name], one line; blank goes back to the name the list derives. */
    fun setName(name: String) { mutableName.value = sessionName(name); onRename() }
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
        attachExisting = external,
        preferMosh = preferMosh,
        elapsedMillis = SystemClock::elapsedRealtime,
        log = { Log.i(CONNECTION_LOG_TAG, "${host.name}#$sessionNumber $it") }
    )
    val state = if (credentials != null) ssh.state else MutableStateFlow(ConnectionState(
        Phase.DISCONNECTED, RESTORED_MESSAGE)).asStateFlow()
    val detectedSystem = ssh.detectedSystem
    /** SSH or mosh once connected; null before the first connection. */
    val transport = ssh.transport
    /** tmux or psmux once a resumable session attached; null before that and for plain shells. */
    val multiplexer = ssh.multiplexer
    private val systemWatcher = if (credentials == null) null else scope.launch {
        launch { ssh.detectedPlugins.persistEach(onPluginsDetected) }
        launch { ssh.detectedDesktop.persistEach(onDesktopDetected) }
        launch { ssh.moshFallback.filterNotNull().collect { inputWarning.value = it } }
        detectedSystem.persistEach(onHostSystemDetected)
    }
    /** Hands each detected value to [save]; metadata persistence must never tear down a terminal. */
    private suspend fun <T : Any> Flow<T?>.persistEach(save: suspend (T) -> Unit) = filterNotNull().collect { value ->
        try { save(value) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    /**
     * The agent this session was started to run, for sessions the app launched (told by their title once restored);
     * others show it once hooks report.
     */
    val launchedAgent = MutableStateFlow(CodingAgent.fromTitle(initialTitle))
    private val mutableAgentStatus = MutableStateFlow<AgentStatus?>(null)
    /** What an agent started with hooks reports in this tmux session; null for none, or while the host is out of reach. */
    val agentStatus = mutableAgentStatus.asStateFlow()
    private val mutableLastAgentStatus = MutableStateFlow(lastAgentStatus)
    /**
     * What the agent reported last, kept across connections and launches: the session list shows it while the session
     * comes back, until the host answers again.
     */
    val lastAgentStatus = mutableLastAgentStatus.asStateFlow()
    private val mutableAgentPolled = MutableStateFlow(false)
    /** Whether the host has answered a status poll since the connection came up. */
    val agentPolled = mutableAgentPolled.asStateFlow()
    private val mutableAgentUsage = MutableStateFlow<AgentSessionUsage?>(null)
    /** Live context-window, remaining quota and session token telemetry for the active AI agent; null when none. */
    val agentUsage = mutableAgentUsage.asStateFlow()
    private val mutableAgentUsageLoading = MutableStateFlow(false)
    val agentUsageLoading = mutableAgentUsageLoading.asStateFlow()
    private var agentUsageJob: Job? = null
    private var compactRefreshJob: Job? = null
    @Volatile private var lastUsageAtMs = 0L
    @Volatile private var lastUsageState: AgentStatus.State? = null
    private val mutableDirectory = MutableStateFlow("")
    /**
     * The folder the tmux pane was last seen in, agent or not, so every session can say where it runs; kept while the
     * host is out of reach, as the tmux session stays where it was. Empty until the first poll answers.
     */
    val directory = mutableDirectory.asStateFlow()
    private val agentStatusWatcher = if (credentials == null || mode != SessionMode.TMUX) null else scope.launch {
        ssh.state.map { it.phase == Phase.CONNECTED }.distinctUntilChanged().collectLatest { connected ->
            if (!connected) { mutableAgentStatus.value = null; mutableAgentPolled.value = false; return@collectLatest }
            while (true) {
                val status = pollAgentStatus()
                // An agent shows up within seconds of starting; without one the poll can be lazy.
                delay(if (status != null) 3_000 else 10_000)
            }
        }
    }
    /** Asks the host what the agent is doing now and shows it; a poll that got no answer keeps what was shown. */
    private suspend fun pollAgentStatus(): AgentStatus? {
        val output = ssh.agentStatus()
        // Only an answer without an agent clears it.
        val status = if (output == null) mutableAgentStatus.value else AgentStatus.parse(output)
        mutableAgentStatus.value = status
        if (output != null) {
            // Its age moves on with every poll; only a new report is worth writing down.
            if (status?.copy(elapsedMillis = 0) != mutableLastAgentStatus.value?.copy(elapsedMillis = 0)) {
                mutableLastAgentStatus.value = status
                onAgentReport()
            }
            mutableAgentPolled.value = true
        }
        AgentStatus.directory(output).takeIf { it.isNotEmpty() }?.let { mutableDirectory.value = it }
        maybeRefreshAgentUsage(status)
        return status
    }

    private fun maybeRefreshAgentUsage(status: AgentStatus?) {
        val kind = CodingAgent.fromCommand(status?.agent) ?: launchedAgent.value?.takeIf { !mutableAgentPolled.value }
        if (kind == null) {
            if (mutableAgentPolled.value && status == null) mutableAgentUsage.value = null
            lastUsageState = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val stateChanged = status?.state != lastUsageState
        lastUsageState = status?.state
        val due = lastUsageAtMs == 0L || (stateChanged && status?.state == AgentStatus.State.IDLE) || (now - lastUsageAtMs) >= 12_000L
        if (due) {
            refreshAgentUsage(force = false)
        }
    }

    /** Reads current session context, quota and token counts from local transcripts and passive statusLine caches. */
    fun refreshAgentUsage(force: Boolean = true) {
        if (state.value.phase != Phase.CONNECTED) return
        if (!force && agentUsageJob?.isActive == true) return
        val status = mutableAgentStatus.value
        val kind = CodingAgent.fromCommand(status?.agent) ?: mutableAgentUsage.value?.agent ?: launchedAgent.value
        if (kind == null) return
        val dir = status?.directory?.ifEmpty { mutableDirectory.value } ?: mutableDirectory.value
        val tmuxName = remoteSessionName.takeIf { mode == SessionMode.TMUX }
        agentUsageJob?.cancel()
        agentUsageJob = scope.launch {
            if (force) mutableAgentUsageLoading.value = true
            try {
                val cmd = AgentUsageCollector.sessionCommand(
                    agent = kind,
                    tmuxSession = tmuxName,
                    directory = dir,
                )
                val out = withContext(Dispatchers.IO) { ssh.runHostOutput(cmd, AgentUsageCollector.script, 15_000L) }
                lastUsageAtMs = SystemClock.elapsedRealtime()
                AgentUsageCollector.parseSession(out)?.let { mutableAgentUsage.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep last reading on transient SSH hiccups.
            } finally {
                mutableAgentUsageLoading.value = false
            }
        }
    }

    /** Sends the active agent's native context compaction command (e.g. `/compact`) and refreshes usage afterwards. */
    suspend fun compactContext(): Boolean {
        if (state.value.phase != Phase.CONNECTED) return false
        val kind = CodingAgent.fromCommand(mutableAgentStatus.value?.agent) ?: launchedAgent.value ?: return false
        val cmd = kind.compactCommand ?: return false
        val beforeCtx = mutableAgentUsage.value?.contextUsed
        if (!ssh.write(pasteInput(cmd).toByteArray(Charsets.UTF_8))) return false
        delay(120)
        val sent = ssh.write("\r".toByteArray())
        if (sent) {
            compactRefreshJob?.cancel()
            compactRefreshJob = scope.launch {
                // Compaction typically finishes in 2-20s; re-poll at staggered intervals and stop once context drops.
                for (waitMs in longArrayOf(3_000L, 4_000L, 7_000L, 10_000L)) {
                    delay(waitMs)
                    if (state.value.phase != Phase.CONNECTED) break
                    refreshAgentUsage(force = false)
                    agentUsageJob?.join()
                    val afterCtx = mutableAgentUsage.value?.contextUsed
                    if (afterCtx != null && (beforeCtx == null || afterCtx < beforeCtx)) break
                }
            }
        }
        return sent
    }

    /**
     * Answers the agent's request for permission with its default choice (Enter: "Yes" in Claude Code, Codex and Grok)
     * from the session list. Asks the host first, so a request already answered on another screen gets no stray Enter;
     * false when the agent no longer waits or the key could not be sent.
     */
    suspend fun approveAgent(): Boolean {
        if (state.value.phase != Phase.CONNECTED || pollAgentStatus()?.state != AgentStatus.State.WAITING) return false
        return ssh.write("\r".toByteArray())
    }

    /**
     * Sends [text] to an agent that finished its turn, as if typed at its prompt and submitted, from the session list.
     * Asks the host first: only an idle agent takes a new prompt. The text goes as a paste when the agent accepts one,
     * so its characters are not read as keys, and Enter follows a moment later: sent together, the agents take it as
     * part of the paste. False when the agent was not idle or the text could not be sent.
     */
    suspend fun followUpAgent(text: String): Boolean {
        val line = text.replace(WHITESPACE_RUN, " ").trim()
        if (line.isEmpty() || state.value.phase != Phase.CONNECTED) return false
        if (pollAgentStatus()?.state != AgentStatus.State.IDLE) return false
        if (!ssh.write(pasteInput(line).toByteArray(Charsets.UTF_8))) return false
        delay(150)
        return ssh.write("\r".toByteArray())
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
                Log.i(CONNECTION_LOG_TAG, "${host.name}#$sessionNumber output resumed after ${now - lastOutput} ms, view bound=${onScreenChanged != null}")
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

    /** Whether Antigravity CLI (`agy`) is currently active in this terminal (must be called on Main). */
    fun isAgyActive(): Boolean = isAgyActive(agentStatus.value, launchedAgent.value, agentPolled.value, screenApp())
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (!ssh.write(data.copyOfRange(offset, offset + count))) inputWarning.value = tr(INPUT_NOT_SENT)
    }
    override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (!Character.isValidCodePoint(codePoint)) return
        write((if (prependEscape) "\u001b" else "") + String(Character.toChars(codePoint)))
    }
    fun paste(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_PASTE_SIZE) {
            inputWarning.value = tr("单次发送上限为 128 KiB，请分段发送。")
            return
        }
        parser.paste(text)
    }
    /** Explicit image insertion must not be lost to a disappearing CLI banner. */
    fun insertImagePath(path: String) {
        val input = pasteInput(CodexImageAttach.pathForPaste(path))
        if (!ssh.write(input.toByteArray(Charsets.UTF_8))) throw IOException(tr(INPUT_NOT_SENT))
    }
    /** [text] as the program reads a paste: wrapped in bracketed-paste marks when it asked for them. */
    private fun pasteInput(text: String) = if (parser.isBracketedPasteEnabled) "\u001b[200~$text\u001b[201~" else text
    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) = ssh.upload(remotePath, source, onProgress)
    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {}, onSize: (Long) -> Unit = {}) = ssh.download(remotePath, destination, onProgress, onSize)
    suspend fun listRemoteDirectory(remotePath: String = "") = ssh.listRemoteDirectory(remotePath)
    suspend fun readFileContent(remotePath: String, maxBytes: Int) = ssh.readFileContent(remotePath, maxBytes)
    private var reportedDirectory: String? = null
    override fun onWorkingDirectoryChanged(uri: String) {
        reportedDirectory = runCatching { URI(uri) }.getOrNull()?.takeIf { it.scheme == "file" }
            ?.path?.takeIf(::isValidRemoteDirectory)
    }
    suspend fun currentWorkingDirectory(): String? = ssh.currentWorkingDirectory() ?: reportedDirectory
    /** A connection the server makes to [host]:[port] for the phone, over this session's SSH login (see [JschSession.openSocket]). */
    suspend fun openSocket(host: String, port: Int): RemoteSocket = ssh.openSocket(host, port)

    fun openTerminalDownloads() = transfers.openDownloads { currentWorkingDirectory().orEmpty() }
    /** Rename a remote path; distinct from [rename] which only changes the local session title. */
    suspend fun renameRemoteFile(oldPath: String, newPath: String) = ssh.rename(oldPath, newPath)
    suspend fun deleteRemoteFile(path: String) = ssh.deleteFile(path)
    suspend fun makeRemoteDirectory(path: String) = ssh.makeDirectory(path)
    suspend fun isRemoteDirectory(path: String) = ssh.isDirectory(path)
    /** The file a path printed in this terminal names, or null when it names no regular file. */
    suspend fun terminalFile(path: String): String? = try {
        // Absolute and home paths need no round trip for the shell's directory.
        val resolved = resolveTerminalPath(null, path) ?: resolveTerminalPath(currentWorkingDirectory(), path)
        resolved?.takeUnless { ssh.isDirectory(it) }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

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
            inputWarning.value = tr(INPUT_NOT_SENT)
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
                    inputWarning.value = tr(INPUT_NOT_SENT)
            } finally { input.fill(0) }
        }
    }

    /** Puts the hook script and [agent]'s hook file on the host before it starts; a failure only costs the status. */
    private suspend fun installAgentHooks(agent: CodingAgent) {
        ssh.state.first { !it.active || it.phase == Phase.CONNECTED }
        try {
            val activeMic = if (agent == CodingAgent.AGY) antigravityMic() else null
            // Keep ~/.roamtty/agy on the dynamic template so ephemeral fallback ports never get hardcoded.
            val (name, content, executable) = AgentHooks.hostFile(agent)
            val written = ssh.writeHostFile(AgentHooks.SCRIPT_PATH.substringAfterLast('/'), AgentHooks.script) &&
                ssh.writeHostFile(name, content, executable)
            if (written && agent == CodingAgent.CLAUDE && mode == SessionMode.TMUX) {
                val (sessionName, sessionContent) = AgentHooks.hostFile(agent, remoteSessionName)
                if (sessionName != name) ssh.writeHostFile(sessionName, sessionContent)
                ssh.runHostCommand(AgentHooks.pruneSettings)
            }
            if (activeMic != null && mode == SessionMode.TMUX) {
                val setEnv = if (multiplexer.value == HostPlugin.PSMUX) {
                    Psmux.setEnvironmentCommand(remoteSessionName, "ANTIGRAVITY_MIC", activeMic)
                } else {
                    Tmux.setEnvironmentCommand(remoteSessionName, "ANTIGRAVITY_MIC", activeMic)
                }
                ssh.runHostCommand(setEnv)
            }
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

    suspend fun killRemoteSession() = ssh.killTmuxSession()
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
        startupJob?.cancel()
        fileBrowser.cancel()
        transfers.cancel()
        commonCommandsJob?.cancel()
        commonCommandsWatcher?.cancel()
        agentUsageJob?.cancel()
        compactRefreshJob?.cancel()
        agentStatusWatcher?.cancel()
        systemWatcher?.cancel()
        pending.value?.answer?.complete(null)
        ssh.disconnect()
        job?.cancel()
    }
}
