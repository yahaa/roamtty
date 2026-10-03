package io.itermux.ssh

import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.Base64
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
    val remoteSessionName: String = Tmux.LEGACY_SESSION
) : SshSession, TmuxHistoryAccess {
    override val historyConnected: Boolean get() = state.value.phase == Phase.CONNECTED
    private val mutableState = MutableStateFlow(ConnectionState(Phase.CONNECTING))
    override val state = mutableState.asStateFlow()
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val queues = SessionQueues()
    private val interactionJob = SupervisorJob()
    @Volatile private var session: Session? = null
    @Volatile private var channel: com.jcraft.jsch.Channel? = null
    @Volatile private var dimensions = intArrayOf(80, 24, 0, 0)
    @Volatile private var trustFailure: Exception? = null
    private val tmuxName = Tmux.sanitizeSessionName(remoteSessionName)
    private val tmuxHistoryController = TmuxHistoryController(tmuxName, ::runCommand)

    private fun update(phase: Phase, message: String = "") {
        if (!stopped.get()) mutableState.value = ConnectionState(phase, message)
    }

    private fun ask(prompt: Prompt): List<String>? = runBlocking(interactionJob) {
        withTimeout(120_000) { interaction(prompt) }
    }

    private enum class AttemptOutcome { TRANSPORT_ENDED, REMOTE_EXIT, FATAL, STOPPED }

    override suspend fun connect(onOutput: suspend (ByteArray) -> Unit): Unit = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "A connection attempt cannot be reused" }
        var attempt = 0
        try {
            while (!stopped.get()) {
                // Discard stale input before each attempt; never replay it after reattachment.
                queues.reopen()
                tmuxHistoryController.reset()
                when (runAttempt(onOutput, reattaching = attempt > 0)) {
                    AttemptOutcome.STOPPED -> break
                    AttemptOutcome.FATAL -> break
                    AttemptOutcome.REMOTE_EXIT -> {
                        update(Phase.DISCONNECTED, "连接已关闭")
                        break
                    }
                    AttemptOutcome.TRANSPORT_ENDED -> {
                        if (stopped.get()) break
                        // Prefer keepalive to prevent idle drops; only auto-reattach tmux after transient death.
                        if (target.mode != SessionMode.TMUX) {
                            if (!stopped.get()) update(Phase.DISCONNECTED, "SSH 已断开，请手动重连。未发送的输入已丢弃。")
                            break
                        }
                        attempt++
                        if (attempt > 10) {
                            update(Phase.DISCONNECTED, "SSH 已断开，请手动重连。未发送的输入已丢弃。")
                            break
                        }
                        update(Phase.CONNECTING, "连接中断，正在自动重连…")
                        delay((1_000L * attempt * attempt).coerceAtMost(30_000L))
                    }
                }
            }
        } finally {
            queues.setAccepting(false)
            closeTransport()
            credentials.clear()
            interactionJob.cancel()
            queues.close()
        }
    }

    private fun tcpKeepaliveFactory() = object : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val socket = Socket()
            try {
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                return socket
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
        override fun getInputStream(socket: Socket) = socket.getInputStream()
        override fun getOutputStream(socket: Socket) = socket.getOutputStream()
    }

    private suspend fun runAttempt(onOutput: suspend (ByteArray) -> Unit, reattaching: Boolean): AttemptOutcome {
        val jsch = JSch()
        trustFailure = null
        var reachedConnected = false
        try {
            coroutineContext.ensureActive()
            if (stopped.get()) return AttemptOutcome.STOPPED
            configureCrypto()
            jsch.hostKeyRepository = repository()
            val ssh = jsch.getSession(target.username, target.hostname, target.port)
            session = ssh
            ssh.setSocketFactory(tcpKeepaliveFactory())
            ssh.setConfig("StrictHostKeyChecking", "yes")
            ssh.setConfig("PreferredAuthentications", when (target.auth) {
                AuthMethod.PASSWORD -> "password,keyboard-interactive"
                AuthMethod.KEY -> "publickey"
                AuthMethod.INTERACTIVE -> "keyboard-interactive"
            })
            ssh.setConfig("NumberOfPasswordPrompts", "1")
            ssh.userInfo = object : UserInfo, UIKeyboardInteractive {
                override fun getPassword(): String? = credentials.password?.toString(Charsets.UTF_8)
                override fun getPassphrase(): String? = null
                // setPassword supplies the initial attempt; never silently retry a rejected password.
                override fun promptPassword(message: String?) = false
                override fun promptPassphrase(message: String?) = false
                override fun promptYesNo(message: String?) = false
                override fun showMessage(message: String?) { /* Remote messages never go to logs. */ }
                override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?): Array<String>? {
                    update(Phase.AUTHENTICATING, "等待认证输入")
                    val labels = prompt?.toList() ?: emptyList()
                    require(labels.size <= 16) { "Too many authentication prompts" }
                    val answers = ask(Prompt.Keyboard(instruction.orEmpty().take(2048), labels.map { it.take(512) }, echo?.toList() ?: List(labels.size) { false }))
                    return answers?.takeIf { it.size == labels.size }?.toTypedArray()
                }
            }
            if (target.auth == AuthMethod.KEY) {
                val key = requireNotNull(credentials.privateKey) { "Missing key" }
                // Keep originals for tmux auto-reattach; JSch receives copies.
                jsch.addIdentity("session-key", key.copyOf(), null, credentials.passphrase?.copyOf())
            } else if (target.auth == AuthMethod.PASSWORD) {
                ssh.setPassword(credentials.password?.copyOf())
            }
            // SSH-level keepalive (~15s × 4 ≈ 1 min of silence before drop).
            ssh.setServerAliveInterval(15_000)
            ssh.setServerAliveCountMax(4)
            if (stopped.get()) return AttemptOutcome.STOPPED
            ssh.connect(connectTimeoutMs)
            // Drop secrets for plain shell once authenticated; tmux may need them to reattach.
            if (target.mode != SessionMode.TMUX) {
                credentials.clear()
                jsch.removeAllIdentity()
            }
            coroutineContext.ensureActive()
            if (stopped.get()) return AttemptOutcome.STOPPED
            if (target.mode == SessionMode.TMUX && !hasTmux(ssh)) {
                update(Phase.TMUX_MISSING, "服务器未找到 tmux，可改用普通 SSH；不会自动安装软件。")
                return AttemptOutcome.FATAL
            }
            val terminal: com.jcraft.jsch.Channel = if (target.mode == SessionMode.TMUX) {
                (ssh.openChannel("exec") as ChannelExec).apply {
                    setCommand(Tmux.attachCommand(tmuxName))
                    setPty(true)
                    val d = dimensions
                    setPtyType("xterm-256color", d[0], d[1], d[2], d[3])
                }
            } else {
                (ssh.openChannel("shell") as ChannelShell).apply {
                    setPty(true)
                    val d = dimensions
                    setPtyType("xterm-256color", d[0], d[1], d[2], d[3])
                }
            }
            channel = terminal
            val stdout = terminal.inputStream
            val stdin = terminal.outputStream
            terminal.connect(connectTimeoutMs)
            // Accept input only while the PTY writer is live — not during post-EOF exit-status grace.
            queues.setAccepting(true)
            update(Phase.CONNECTED)
            reachedConnected = true
            try {
                coroutineScope {
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
                    } finally { writer.cancel(); resizer.cancel() }
                }
            } finally {
                // Stop accepting before the exit-status grace wait so UI CONNECTED cannot queue dead writes.
                queues.setAccepting(false)
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
        } catch (e: Exception) {
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
            mutableState.value = when {
                failure is HostKeyChanged -> ConnectionState(Phase.HOST_KEY_CHANGED, "主机指纹发生变化，连接已阻止。请通过可信渠道核验。", failure.old.fingerprint, failure.received.fingerprint)
                failure is HostKeyRejected -> ConnectionState(Phase.DISCONNECTED, "未信任此主机")
                e.message?.contains("Auth", ignoreCase = true) == true -> ConnectionState(Phase.AUTH_FAILED, "认证失败，请检查用户名、凭据或服务器认证配置。")
                e is SocketTimeoutException || e.message?.contains("timeout", true) == true -> ConnectionState(Phase.FAILED, "连接超时，请检查网络、VPN 和主机端口。")
                else -> ConnectionState(Phase.FAILED, "连接失败，请检查网络、地址、密钥格式及服务器算法支持。")
            }
            return AttemptOutcome.FATAL
        } finally {
            closeTransport()
            jsch.removeAllIdentity()
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

    private fun repository() = object : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            try {
                val pin = HostPin(target.endpoint, HostKey(host, key).type, Base64.getEncoder().encodeToString(key))
                runBlocking(interactionJob) {
                    TrustPolicy(pins).verify(pin) {
                        update(Phase.VERIFYING)
                        ask(Prompt.VerifyHost(it)) == listOf("trust")
                    }
                }
                update(Phase.AUTHENTICATING)
                return HostKeyRepository.OK
            } catch (e: Exception) { trustFailure = e; throw e }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "iTermux pinned hosts"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    override fun write(bytes: ByteArray): Boolean = queues.write(bytes, stopped.get())

    override fun resize(columns: Int, rows: Int, pixelWidth: Int, pixelHeight: Int) {
        dimensions = intArrayOf(columns.coerceIn(2, 500), rows.coerceIn(2, 300), pixelWidth.coerceAtLeast(0), pixelHeight.coerceAtLeast(0))
        queues.trySendSize(dimensions)
    }

    /** Query the active pane of this connection's tmux session over a separate SSH channel. */
    override suspend fun tmuxHistory(): TmuxHistory? = tmuxHistoryController.history()

    override suspend fun enterTmuxHistory(): TmuxHistory? = tmuxHistoryController.enter()

    override suspend fun moveTmuxHistoryTo(position: Int): TmuxHistory? = tmuxHistoryController.moveTo(position)

    override suspend fun exitTmuxHistory(): TmuxHistory? = tmuxHistoryController.exit()

    private val files = SftpOperations(
        connectedSession = {
            session?.takeIf { it.isConnected && !stopped.get() && state.value.phase == Phase.CONNECTED }
                ?: throw IllegalStateException("SSH 连接未就绪")
        },
        isStopped = stopped::get,
        connectTimeoutMs = connectTimeoutMs
    )

    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) =
        files.upload(remotePath, source, onProgress)

    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {},
        onSize: (Long) -> Unit = {}) = files.download(remotePath, destination, onProgress, onSize)

    suspend fun readRemoteText(remotePath: String, maxBytes: Int = 512 * 1024): Pair<String, Boolean>? =
        files.readRemoteText(remotePath, maxBytes)

    suspend fun listRemoteDirectory(remotePath: String = ""): RemoteDirectory = files.listRemoteDirectory(remotePath)

    suspend fun readFileContent(remotePath: String, maxBytes: Int): RemoteFileContent = files.readFileContent(remotePath, maxBytes)
    suspend fun currentWorkingDirectory(): String? = validWorkingDirectory(runCommand(
        if (target.mode == SessionMode.TMUX)
            "tmux display-message -p -t ${Tmux.targetFlag(tmuxName)} '#{pane_current_path}'"
        else shellWorkingDirectoryCommand))
    suspend fun rename(oldPath: String, newPath: String) = files.rename(oldPath, newPath)
    suspend fun deleteFile(path: String) = files.deleteFile(path)

    private suspend fun runCommand(command: String): String? = withContext(Dispatchers.IO) {
        val ssh = session?.takeIf { it.isConnected && !stopped.get() && state.value.phase == Phase.CONNECTED }
            ?: return@withContext null
        val exec = try { ssh.openChannel("exec") as ChannelExec } catch (_: Exception) { return@withContext null }
        try {
            exec.setCommand(command)
            val output = exec.inputStream
            exec.connect(5000)
            val bytes = java.io.ByteArrayOutputStream()
            val complete = withTimeoutOrNull(5000) {
                val buffer = ByteArray(256)
                while (!exec.isClosed || output.available() > 0) {
                    while (output.available() > 0) {
                        val count = output.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 8192) return@withTimeoutOrNull false
                        bytes.write(buffer, 0, count)
                    }
                    if (!exec.isClosed) delay(20)
                }
                true
            } ?: false
            if (complete && exec.exitStatus == 0) bytes.toString(Charsets.UTF_8.name()) else null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { exec.disconnect() }
    }

    override fun disconnect() {
        stopped.set(true)
        queues.setAccepting(false)
        credentials.clear()
        mutableState.value = ConnectionState(Phase.DISCONNECTED, "连接已关闭")
        interactionJob.cancel()
        // JSch's close can block behind an in-flight packet. Never call it on the UI thread.
        Thread({ closeTransport() }, "ssh-close").apply { isDaemon = true }.start()
        queues.close()
    }
    private fun closeTransport() { channel?.disconnect(); session?.disconnect() }

    companion object {
        @Synchronized private fun configureCrypto() {
            // Android ships a reduced BC provider. Use the full library for modern SSH algorithms.
            if (Security.getProvider("BC") !is BouncyCastleProvider) {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }
            JSch.setConfig("signature.ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("signature.ed448", "com.jcraft.jsch.bc.SignatureEd448")
        }
    }
}
