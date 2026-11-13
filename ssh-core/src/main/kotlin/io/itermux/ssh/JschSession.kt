package io.itermux.ssh

import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** SSH transport. Blocking socket work is confined to IO; output applies backpressure to the renderer. */
class JschSession(
    private val target: Target,
    private val credentials: Credentials,
    private val pins: PinStore,
    private val interaction: suspend (Prompt) -> List<String>?,
    private val connectTimeoutMs: Int = 20_000,
    /** Remote tmux session name; ignored for SHELL mode. Legacy journal entries use [Tmux.LEGACY_SESSION]. */
    val remoteSessionName: String = Tmux.LEGACY_SESSION,
    /** [remoteSessionName] was already on the server: attach it as it is, beside its other clients. */
    private val attachExisting: Boolean = false,
    /** Run the terminal over mosh (UDP) when the host has mosh-server; falls back to the SSH PTY. */
    private val preferMosh: Boolean = false,
    /**
     * Milliseconds that keep counting while the device sleeps (Android: elapsedRealtime). mosh
     * measures server silence with it; a clock paused by sleep keeps a session whose mosh-server
     * already expired looking connected while every keystroke is lost.
     */
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Connection diagnostics only: phases, timings and outcomes, never credentials or terminal content. */
    private val log: (String) -> Unit = {},
) : SshSession {
    private val mutableState = MutableStateFlow(ConnectionState(Phase.CONNECTING))
    override val state = mutableState.asStateFlow()
    private val probes = HostProbes { ssh, command, input -> runCommand(ssh, command, input) }
    val detectedSystem = probes.system
    /** tmux/mosh availability, probed with the system on every authenticated connection. */
    val detectedPlugins = probes.plugins
    /** The read-only desktop probe that runs with the plugin probe; null until it answered. */
    val detectedDesktop = probes.desktop
    /** True once a plugin probe found cmd.exe or PowerShell, not a POSIX shell; null until probed. */
    val nativeWindows = probes.nativeWindows
    private val mutableMultiplexer = MutableStateFlow<HostPlugin?>(null)
    /** Which multiplexer keeps this TMUX-mode session; found on the first attach, kept for reattaches. */
    val multiplexer = mutableMultiplexer.asStateFlow()
    private val mutableTransport = MutableStateFlow<TerminalTransport?>(null)
    /** Which transport carries the live terminal; null until connected. */
    val transport = mutableTransport.asStateFlow()
    private val mutableMoshFallback = MutableStateFlow<String?>(null)
    /** Set when mosh was preferred but the terminal had to fall back to SSH. */
    val moshFallback = mutableMoshFallback.asStateFlow()
    @Volatile private var moshUnavailable = false
    @Volatile private var remoteAddress: java.net.InetAddress? = null
    private var detectionOnly = false
    private var administration: (suspend (RemoteHost) -> Unit)? = null
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val queues = SessionQueues()
    private val channels = SshChannels(connectTimeoutMs, stopped::get)
    @Volatile private var queueGeneration = 0L
    private val mutableInputEpoch = MutableStateFlow<Long?>(null)
    /** Non-null only while this exact terminal writer accepts input. */
    val inputEpoch = mutableInputEpoch.asStateFlow()

    private fun setInputAccepting(accepting: Boolean) {
        if (!accepting) mutableInputEpoch.value = null
        queues.setAccepting(accepting)
        if (accepting) mutableInputEpoch.value = queueGeneration
    }
    private val interactionJob = SupervisorJob()
    private val login = SshLogin(target, credentials, pins, connectTimeoutMs, interactionJob, ::ask, { phase, message -> update(phase, message) },
        onTrustFailure = { trustFailure = it }, onAddress = { remoteAddress = it })
    @Volatile private var session: Session? = null
    @Volatile private var channel: com.jcraft.jsch.Channel? = null
    @Volatile private var moshClient: MoshClient? = null
    @Volatile private var dimensions = intArrayOf(80, 24, 0, 0)
    @Volatile private var trustFailure: Exception? = null
    private val tmuxName = Tmux.sanitizeSessionName(remoteSessionName)

    private fun update(phase: Phase, message: String = "", detail: String? = null) {
        if (stopped.get()) return
        if (mutableState.value.phase != phase) log("phase $phase")
        mutableState.value = ConnectionState(phase, message, detail = detail)
    }

    private fun ask(prompt: Prompt): List<String>? = runBlocking(interactionJob) {
        withTimeout(120_000) { interaction(prompt) }
    }

    /** UNREACHABLE: a first connection failed on the network before reaching the server. */
    private enum class AttemptOutcome { TRANSPORT_ENDED, UNREACHABLE, REMOTE_EXIT, FATAL, STOPPED }
    @Volatile private var unreachableFailure: Exception? = null
    /** Whether the latest attempt reached CONNECTED; a drop after that earns a fresh retry budget. */
    @Volatile private var reachedConnected = false

    override suspend fun connect(onOutput: suspend (ByteArray) -> Unit): Unit = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "A connection attempt cannot be reused" }
        var attempt = 0
        var firstConnectRetry = 0
        try {
            while (!stopped.get()) {
                // Discard stale input before each attempt; never replay it after reattachment.
                queueGeneration = queues.reopen()
                val outcome = runAttempt(onOutput, reattaching = attempt > 0)
                log("attempt ended: $outcome (attempt $attempt, reached connected $reachedConnected)")
                when (outcome) {
                    AttemptOutcome.STOPPED, AttemptOutcome.FATAL -> break
                    AttemptOutcome.REMOTE_EXIT -> {
                        update(Phase.DISCONNECTED, ConnectionMessages.CLOSED)
                        break
                    }
                    AttemptOutcome.UNREACHABLE -> {
                        if (stopped.get()) break
                        // A VPN reconnecting or a Wi-Fi handover fails the socket for a few seconds;
                        // the host list probe then shows the host online while this entry stays failed.
                        val wait = firstConnectRetryDelayMs(++firstConnectRetry)
                        if (wait == null) {
                            update(Phase.FAILED, ConnectionMessages.UNREACHABLE, unreachableFailure?.let(::failureDetail))
                            break
                        }
                        update(Phase.CONNECTING, "网络暂不可用，正在重试…")
                        delay(wait)
                    }
                    AttemptOutcome.TRANSPORT_ENDED -> {
                        if (stopped.get()) break
                        // Each sleep or network change on a long-lived session is a new drop, not a retry.
                        if (reachedConnected) attempt = 0
                        // Prefer keepalive to prevent idle drops; only auto-reattach tmux after transient death.
                        if (target.mode != SessionMode.TMUX || ++attempt > 10) {
                            update(Phase.DISCONNECTED, ConnectionMessages.DROPPED)
                            break
                        }
                        update(Phase.CONNECTING, "连接中断，正在自动重连…")
                        delay((1_000L * attempt * attempt).coerceAtMost(30_000L))
                    }
                }
            }
        } finally {
            setInputAccepting(false)
            closeTransport()
            credentials.clear()
            interactionJob.cancel()
            queues.close()
        }
    }

    /** A short, authenticated connection for the host editor; never opens a PTY or tmux. */
    suspend fun detectHostSystem(): HostSystem {
        detectionOnly = true
        connect {}
        return detectedSystem.value ?: HostSystem.UNKNOWN
    }

    /**
     * An authenticated session for one-off administration such as installing plugins. The block
     * runs while the connection stays open; it never opens a PTY, tmux or mosh.
     */
    suspend fun administer(block: suspend (RemoteHost) -> Unit) {
        administration = block
        connect {}
    }

    /** Check each fresh login: the multiplexer may have been removed while the connection was down. */
    private suspend fun findMultiplexer(ssh: Session): HostPlugin? = when {
        hasTmux(ssh) -> HostPlugin.TMUX
        Psmux.parseProbe(runCommand(ssh, Psmux.probeCommand)) == true -> HostPlugin.PSMUX
        else -> null
    }.also { mutableMultiplexer.value = it }

    private suspend fun runAttempt(onOutput: suspend (ByteArray) -> Unit, reattaching: Boolean): AttemptOutcome {
        val jsch = JSch()
        trustFailure = null
        reachedConnected = false
        try {
            coroutineContext.ensureActive()
            if (stopped.get()) return AttemptOutcome.STOPPED
            val ssh = login.newSession(jsch)
            session = ssh
            if (stopped.get()) return AttemptOutcome.STOPPED
            ssh.connect(connectTimeoutMs)
            // Drop secrets for plain shell once authenticated; tmux may need them to reattach,
            // and mosh to log the file connection back in after it drops.
            if (target.mode != SessionMode.TMUX && !preferMosh) {
                credentials.clear()
                jsch.removeAllIdentity()
            }
            coroutineContext.ensureActive()
            if (stopped.get()) return AttemptOutcome.STOPPED
            if (detectionOnly) {
                probes.probeSystem(ssh)
                return AttemptOutcome.REMOTE_EXIT
            }
            administration?.let { block ->
                update(Phase.CONNECTED)
                block(remoteHost(ssh))
                return AttemptOutcome.REMOTE_EXIT
            }
            val mux = if (target.mode == SessionMode.TMUX) findMultiplexer(ssh) ?: run {
                update(Phase.TMUX_MISSING, "服务器未找到 tmux 或 psmux，可在“安装插件”中安装，或改用普通 SSH。")
                return AttemptOutcome.FATAL
            } else null
            if (preferMosh && !moshUnavailable) {
                val outcome = runMosh(ssh, onOutput)
                if (outcome != null) return outcome
                if (stopped.get()) return AttemptOutcome.STOPPED
                // Keep this connection: the PTY below replaces mosh for the rest of the session.
                moshUnavailable = true
                if (target.mode != SessionMode.TMUX) { credentials.clear(); jsch.removeAllIdentity() }
                mutableMoshFallback.value = "mosh 无法连接（可能未放行 UDP 60000–61000），已改用 SSH。"
            }
            return runTerminal(ssh, openTerminal(ssh, mux), onOutput)
        } catch (e: Exception) {
            return attemptFailed(e, reattaching)
        } finally {
            closeTransport()
            jsch.removeAllIdentity()
        }
    }

    /** The channel the terminal runs on, not yet connected: the multiplexer's attach command for TMUX, else a login shell. */
    private fun openTerminal(ssh: Session, mux: HostPlugin?): com.jcraft.jsch.Channel {
        val size = dimensions
        return if (target.mode == SessionMode.TMUX) {
            (ssh.openChannel("exec") as ChannelExec).apply {
                setCommand(if (mux == HostPlugin.PSMUX) Psmux.attachCommand(tmuxName) else Tmux.attachCommand(tmuxName, attachExisting))
                setPty(true)
                setPtyType(PTY_TYPE, size[0], size[1], size[2], size[3])
            }
        } else {
            (ssh.openChannel("shell") as ChannelShell).apply {
                setPty(true)
                setPtyType(PTY_TYPE, size[0], size[1], size[2], size[3])
            }
        }
    }

    /** Connects [terminal] and carries it until it ends; says how it ended. */
    private suspend fun runTerminal(ssh: Session, terminal: com.jcraft.jsch.Channel, onOutput: suspend (ByteArray) -> Unit): AttemptOutcome {
        channel = terminal
        val stdout = terminal.inputStream
        val stdin = terminal.outputStream
        terminal.connect(connectTimeoutMs)
        // Accept input only while the PTY writer is live — not during post-EOF exit-status grace.
        setInputAccepting(true)
        mutableTransport.value = TerminalTransport.SSH
        update(Phase.CONNECTED)
        reachedConnected = true
        try {
            pumpTerminal(ssh, terminal, stdout, stdin, onOutput)
        } finally {
            // Stop accepting before the exit-status grace wait so UI CONNECTED cannot queue dead writes.
            setInputAccepting(false)
        }
        // EOF/closed channels can precede exit-status or JSch's transport disconnect flag.
        // Even a closed channel needs this grace period when no exit status has arrived.
        withTimeoutOrNull(1_000) {
            while (!stopped.get() && ssh.isConnected && terminal.exitStatus == -1) delay(10)
        }
        return when {
            stopped.get() -> AttemptOutcome.STOPPED
            // Normal shell/tmux end — exit-status arrived.
            terminal.exitStatus != -1 -> AttemptOutcome.REMOTE_EXIT
            // Transport dead, or channel EOF with no exit status while SSH still up:
            // prefer TRANSPORT_ENDED so TMUX can auto-reattach. Do not treat bare
            // ssh.isConnected as REMOTE_EXIT.
            else -> AttemptOutcome.TRANSPORT_ENDED
        }
    }

    /** Writes queued input and sizes to the PTY and hands its output on, until the channel reaches EOF or the session stops. */
    private suspend fun pumpTerminal(ssh: Session, terminal: com.jcraft.jsch.Channel, stdout: InputStream, stdin: OutputStream,
        onOutput: suspend (ByteArray) -> Unit) = coroutineScope {
        // Metadata must not delay the terminal, enter shell history or steal PTY output.
        val systemProbe = probes.launch(ssh)
        val writer = launch(Dispatchers.IO) {
            try {
                for (bytes in queues.currentInput()) {
                    try { if (!stopped.get() && terminal.isConnected) { stdin.write(bytes); stdin.flush() } }
                    finally { bytes.fill(0) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { terminal.disconnect() }
        }
        val resizer = launch(Dispatchers.IO) {
            try {
                for (d in queues.currentSizes()) when (terminal) {
                    is ChannelShell -> terminal.setPtySize(d[0], d[1], d[2], d[3])
                    is ChannelExec -> terminal.setPtySize(d[0], d[1], d[2], d[3])
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { terminal.disconnect() }
        }
        try {
            val buffer = ByteArray(8192)
            while (isActive && !stopped.get()) {
                val count = stdout.read(buffer)
                if (count < 0) break
                if (count > 0) onOutput(buffer.copyOf(count))
            }
        } finally { writer.cancel(); resizer.cancel(); systemProbe.cancel() }
    }

    /** An attempt threw [e]: whether to try again, and what the user is told when not. A cancellation is thrown on. */
    private fun attemptFailed(e: Exception, reattaching: Boolean): AttemptOutcome {
        val failure = trustFailure ?: e
        if (stopped.get()) return AttemptOutcome.STOPPED
        if (e is CancellationException) {
            update(Phase.DISCONNECTED, "连接已取消或验证等待超时")
            throw e
        }
        // A lost network can fail before the next PTY exists. Keep the remaining retry budget,
        // but never retry rejected credentials, host keys, or local configuration failures.
        if (trustFailure == null && (reachedConnected || reattaching) && isRetryableTransportFailure(e)) {
            return AttemptOutcome.TRANSPORT_ENDED
        }
        // Host detection and plugin installs answer the user directly, so they fail fast instead.
        if (trustFailure == null && !detectionOnly && administration == null &&
            isRetryableTransportFailure(e) && !isTimeoutFailure(e)) {
            unreachableFailure = e
            return AttemptOutcome.UNREACHABLE
        }
        val detail = failureDetail(failure)
        mutableState.value = when {
            failure is HostKeyChanged -> ConnectionState(Phase.HOST_KEY_CHANGED, "主机指纹发生变化，连接已阻止。请通过可信渠道核验。", failure.old.fingerprint, failure.received.fingerprint)
            failure is HostKeyRejected -> ConnectionState(Phase.DISCONNECTED, "未信任此主机")
            e.message?.contains("Auth", ignoreCase = true) == true -> ConnectionState(Phase.AUTH_FAILED, "认证失败，请检查用户名、凭据或服务器认证配置。")
            e is SocketTimeoutException || e.message?.contains("timeout", true) == true -> ConnectionState(Phase.FAILED, ConnectionMessages.TIMEOUT, detail = detail)
            else -> ConnectionState(Phase.FAILED, "连接失败，请检查网络、地址、密钥格式及服务器算法支持。", detail = detail)
        }
        return AttemptOutcome.FATAL
    }

    /**
     * Starts mosh-server over this SSH connection and runs the terminal over UDP. Returns null when
     * mosh cannot start or no UDP reply arrives, so the caller falls back to an SSH PTY.
     */
    private suspend fun runMosh(ssh: Session, onOutput: suspend (ByteArray) -> Unit): AttemptOutcome? {
        update(Phase.CONNECTING, "正在启动 mosh…")
        val connect = startMoshServer(ssh) ?: return null
        val address = remoteAddress ?: return null
        log("mosh-server started on port ${connect.port}")
        val client = MoshClient(address, connect.port, connect.key, elapsedMillis, log)
        moshClient = client
        val end = try {
            coroutineScope {
                val systemProbe = probes.launch(ssh)
                // Keep files usable: quietly log back in after the SSH side drops.
                // Detached: a login stuck on a dead network must not hold the terminal on a frozen
                // screen after mosh gives up; JSch's connect ignores cancellation.
                val keeper = CoroutineScope(Dispatchers.IO).launch {
                    while (true) {
                        delay(20_000)
                        if (mutableTransport.value == TerminalTransport.MOSH) auxiliarySession(interactive = false)
                    }
                }
                try {
                    client.run(queues.currentInput(), queues.currentSizes(), dimensions,
                        firstContactTimeoutMs = 8_000,
                        silenceLimitMs = moshSilenceLimitMs(target.mode),
                        stallLimitMs = moshStallLimitMs(target.mode),
                        isStopped = stopped::get,
                        onConnected = {
                            mutableTransport.value = TerminalTransport.MOSH
                            setInputAccepting(true)
                            update(Phase.CONNECTED)
                            reachedConnected = true
                        },
                        onOutput = onOutput)
                } finally { systemProbe.cancel(); keeper.cancel() }
            }
        } finally { setInputAccepting(false); moshClient = null }
        log("mosh ended: $end")
        return when (end) {
            MoshClient.End.NO_CONTACT -> null
            MoshClient.End.STOPPED -> AttemptOutcome.STOPPED
            MoshClient.End.SERVER_SHUTDOWN -> AttemptOutcome.REMOTE_EXIT
            // A tmux session is reattached over a fresh SSH login; a plain shell reports the drop.
            MoshClient.End.TIMED_OUT -> AttemptOutcome.TRANSPORT_ENDED
        }
    }

    private suspend fun startMoshServer(ssh: Session): Mosh.Connect? {
        // mosh-server only applies -l when the login environment is not UTF-8 already.
        for (locale in listOf("en_US.UTF-8", "C.UTF-8")) {
            val output = channels.execute(ssh, HostPluginProbe.command, moshStartScript(locale), 15_000)?.second ?: return null
            Mosh.parseConnect(output)?.let { return it }
            if (!output.contains("UTF-8")) return null
        }
        return null
    }

    private fun moshStartScript(locale: String): String {
        // Without a client mosh-server waits forever; tmux keeps the work, so its server may expire sooner.
        val timeout = moshServerTimeoutSeconds(target.mode)
        val command = if (target.mode == SessionMode.TMUX) " -- ${Tmux.attachShellCommand(tmuxName, attachExisting)}" else ""
        return """
            exec 2>&1
            ${HostPluginProbe.moshLookup}
            [ -n "${'$'}m" ] || { echo __ITERMUX_NO_MOSH__; exit 0; }
            MOSH_SERVER_NETWORK_TMOUT=$timeout exec "${'$'}m" new -s -c 256 -l LANG=$locale$command
        """.trimIndent()
    }

    private fun remoteHost(ssh: Session) = object : RemoteHost {
        override suspend fun probePlugins(): Set<HostPlugin>? = probes.probePlugins(ssh)
        override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int? =
            channels.execute(ssh, HostPluginProbe.command, script, timeoutMs, onLine = onLine)?.first
        override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int? =
            channels.execute(ssh, command, null, timeoutMs, onLine = onLine)?.first
        override suspend fun probeDesktop(): HostDesktop? {
            probes.probePlugins(ssh)
            return probes.desktop.value
        }
        override suspend fun upload(remotePath: String, source: InputStream) = files.upload(remotePath, source)
        override suspend fun openStream(command: String): RemoteStream? = channels.openStream(ssh, command)
        override suspend fun openSocket(host: String, port: Int): RemoteSocket = channels.openDirect(ssh, host, port)
    }

    private val auxiliaryLock = Mutex()

    /**
     * The SSH connection for files and cwd. Beside a live mosh terminal it may have
     * dropped (sleep, network change) while mosh recovered, so it is logged in again on demand.
     * [interactive] allows keyboard-interactive prompts; background refreshes never prompt.
     */
    private suspend fun auxiliarySession(interactive: Boolean = true): Session? {
        connectedSession()?.let { return it }
        if (mutableTransport.value != TerminalTransport.MOSH || stopped.get() || state.value.phase != Phase.CONNECTED) return null
        if (!interactive && target.auth == AuthMethod.INTERACTIVE) return null
        val owner = moshClient ?: return null
        return auxiliaryLock.withLock {
            connectedSession()?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val jsch = JSch()
                try {
                    val ssh = login.newSession(jsch, auxiliary = true)
                    ssh.connect(connectTimeoutMs)
                    // A login that outlived its mosh link must not replace the next attempt's session.
                    if (stopped.get() || moshClient !== owner) { ssh.disconnect(); return@withContext null }
                    val old = session
                    session = ssh
                    old?.disconnect()
                    ssh
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                finally { jsch.removeAllIdentity() }
            }
        }
    }

    private suspend fun hasTmux(ssh: Session): Boolean {
        val probe = ssh.openChannel("exec") as ChannelExec
        channel = probe
        return try {
            probe.setCommand(Tmux.PROBE)
            probe.connect(connectTimeoutMs)
            withTimeout(connectTimeoutMs.toLong()) { while (!probe.isClosed) { delay(30); if (stopped.get()) throw CancellationException() } }
            probe.exitStatus == 0
        } finally { probe.disconnect() }
    }

    override fun write(bytes: ByteArray): Boolean = queues.write(bytes, stopped.get())

    /** Startup input must never cross a writer replacement between readiness checking and insertion. */
    fun write(bytes: ByteArray, expectedEpoch: Long): Boolean = queues.write(bytes, stopped.get(), expectedEpoch)

    override fun resize(columns: Int, rows: Int, pixelWidth: Int, pixelHeight: Int) {
        dimensions = intArrayOf(columns.coerceIn(2, 500), rows.coerceIn(2, 300), pixelWidth.coerceAtLeast(0), pixelHeight.coerceAtLeast(0))
        queues.trySendSize(dimensions)
    }

    private fun display(format: String): String = if (multiplexer.value == HostPlugin.PSMUX) Psmux.displayCommand(tmuxName, format)
        else Tmux.command("display-message -p -t $tmuxName \"$format\"")

    /** Whether the active tmux pane runs a full-screen program; null when tmux cannot be asked. */
    suspend fun tmuxAlternateScreen(): Boolean? =
        when (runCommand(display("#{alternate_on}"))?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }

    /** The agent status lines of [Tmux.agentStatusCommand]; null without tmux, a reachable host or a quick answer. */
    suspend fun agentStatus(): String? {
        if (target.mode != SessionMode.TMUX || multiplexer.value != HostPlugin.TMUX) return null
        return auxiliarySession(interactive = false)?.let { runCommand(it, Tmux.agentStatusCommand(tmuxName)) }
    }

    /** Writes [content] to `~/.roamtty/[name]` on the host, replacing it; [name] is a plain file name. */
    suspend fun writeHostFile(name: String, content: String, executable: Boolean = false): Boolean {
        require(name.all { it.isLetterOrDigit() || it in "-_." } && !name.startsWith("."))
        val chmod = if (executable) " && chmod +x \"\$HOME/.roamtty/$name\"" else ""
        return auxiliarySession(interactive = false)?.let {
            runCommand(it, "/bin/sh -c 'mkdir -p \"\$HOME/.roamtty\" && cat > \"\$HOME/.roamtty/$name\"$chmod'", content)
        } != null
    }

    /** Runs [command] on the host beside the terminal; false when it could not be asked. */
    suspend fun runHostCommand(command: String): Boolean =
        auxiliarySession(interactive = false)?.let { runCommand(it, command) } != null

    /** See [Tmux.restartPaneCommand]: the ended foreground command line ("" for a bare shell), or null on failure. */
    suspend fun restartTmuxPane(): String? = runCommand(
        if (multiplexer.value == HostPlugin.PSMUX) Psmux.restartPaneCommand(tmuxName) else Tmux.restartPaneCommand(tmuxName))?.trim()

    /** Ends this session's tmux (or psmux) session on the server over the live connection; false when it could not. */
    suspend fun killTmuxSession(): Boolean {
        val ssh = auxiliarySession() ?: return false
        return if (multiplexer.value == HostPlugin.PSMUX) runCommand(ssh, Psmux.killSessionCommand(tmuxName)) != null
            else runCommand(ssh, HostPluginProbe.command, Tmux.killSessionScript(tmuxName)) != null
    }

    private fun connectedSession(): Session? =
        session?.takeIf { it.isConnected && !stopped.get() && state.value.phase == Phase.CONNECTED }

    private val files = SftpOperations(
        connectedSession = { connectedSession() ?: throw IllegalStateException("SSH 连接未就绪") },
        isStopped = stopped::get,
        connectTimeoutMs = connectTimeoutMs
    )

    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) =
        files.upload(remotePath, source, onProgress)

    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {},
        onSize: (Long) -> Unit = {}) = withFiles { files.download(remotePath, destination, onProgress, onSize) }

    suspend fun readRemoteText(remotePath: String, maxBytes: Int = 512 * 1024): Pair<String, Boolean>? =
        files.readRemoteText(remotePath, maxBytes)

    suspend fun listRemoteDirectory(remotePath: String = ""): RemoteDirectory = withFiles { files.listRemoteDirectory(remotePath) }

    suspend fun readFileContent(remotePath: String, maxBytes: Int): RemoteFileContent = withFiles { files.readFileContent(remotePath, maxBytes) }
    suspend fun currentWorkingDirectory(): String? = when {
        target.mode != SessionMode.TMUX -> validWorkingDirectory(runCommand(shellWorkingDirectoryCommand))
        multiplexer.value == HostPlugin.PSMUX -> runCommand(display("#{pane_current_path}"))?.let(Psmux::sftpPath)?.takeIf(::isValidRemoteDirectory)
        else -> validWorkingDirectory(runCommand(display("#{pane_current_path}")))
    }
    suspend fun rename(oldPath: String, newPath: String) = withFiles { files.rename(oldPath, newPath) }
    suspend fun deleteFile(path: String) = withFiles { files.deleteFile(path) }
    suspend fun makeDirectory(path: String) = withFiles { files.makeDirectory(path) }
    suspend fun isDirectory(path: String) = withFiles { files.isDirectory(path) }

    /**
     * A connection the server makes to [host]:[port] for the phone, over the terminal's SSH login (restored first for
     * mosh). Throws [java.net.ConnectException] when it was refused, [SshUnavailableException] when the login is not up.
     */
    suspend fun openSocket(host: String, port: Int): RemoteSocket =
        channels.openDirect(auxiliarySession() ?: throw SshUnavailableException(), host, port)

    /** File operations first restore the SSH side of a mosh session if it dropped. */
    private suspend fun <T> withFiles(block: suspend () -> T): T { auxiliarySession(); return block() }

    private suspend fun runCommand(command: String): String? =
        auxiliarySession()?.let { runCommand(it, command) }

    private suspend fun runCommand(ssh: Session, command: String, input: String? = null): String? =
        channels.capture(ssh, command, input)

    private val probing = AtomicBoolean(false)

    /**
     * Confirms the SSH terminal still reaches the server. After a night of sleep or a network
     * switch the socket can be dead while nothing has noticed yet: keepalives only run while the
     * CPU is awake and need a minute of silence, so the screen stays frozen and every keystroke
     * vanishes. A channel open needs a server reply; without one the transport is closed so the
     * attempt ends now — tmux reattaches, a plain shell reports the drop.
     */
    suspend fun verifyAlive(timeoutMs: Int = 10_000) {
        // mosh is UDP: its client asks the server for a reply and reattaches tmux if none comes.
        if (mutableTransport.value == TerminalTransport.MOSH) {
            log("verify: mosh probe (client ${if (moshClient == null) "absent" else "present"}, phase ${state.value.phase})")
            moshClient?.probe(); return
        }
        log("verify: transport ${mutableTransport.value}, phase ${state.value.phase}")
        if (mutableTransport.value != TerminalTransport.SSH || state.value.phase != Phase.CONNECTED) return
        val ssh = session ?: return
        if (!probing.compareAndSet(false, true)) return
        try {
            val alive = withContext(Dispatchers.IO) { probeSshAlive(ssh, timeoutMs) }
            log("verify: ssh probe ${if (alive) "answered" else "unanswered, closing transport"}")
            // A newer attempt or an explicit disconnect owns the transport now.
            if (!alive && !stopped.get() && session === ssh) withContext(Dispatchers.IO) { ssh.disconnect() }
        } finally { probing.set(false) }
    }

    override fun disconnect() {
        stopped.set(true)
        setInputAccepting(false)
        credentials.clear()
        mutableState.value = ConnectionState(Phase.DISCONNECTED, ConnectionMessages.CLOSED)
        interactionJob.cancel()
        // JSch's close can block behind an in-flight packet. Never call it on the UI thread.
        Thread({ closeTransport() }, "ssh-close").apply { isDaemon = true }.start()
        queues.close()
    }
    private fun closeTransport() { channel?.disconnect(); session?.disconnect() }

    companion object {
        private const val PTY_TYPE = "xterm-256color"

        /** mosh-server exits after this long without hearing the client. */
        internal fun moshServerTimeoutSeconds(mode: SessionMode): Long =
            if (mode == SessionMode.TMUX) 15 * 60L else 12 * 3600L + 600

        /**
         * The client gives up just before its server would, so a wake from sleep inside the limit
         * resumes seamlessly, and one past it reattaches instead of typing into a server that is gone.
         */
        internal fun moshSilenceLimitMs(mode: SessionMode): Long =
            (moshServerTimeoutSeconds(mode) - 60) * 1000

        /**
         * tmux keeps the work, so a mosh path that stops answering a wake probe or typed input is
         * reattached over a fresh login after a few seconds. A plain shell lives only in its
         * mosh-server, so it keeps waiting for the network instead.
         */
        internal fun moshStallLimitMs(mode: SessionMode): Long? =
            if (mode == SessionMode.TMUX) 10_000L else null
    }
}
