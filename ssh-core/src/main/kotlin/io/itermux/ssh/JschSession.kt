package io.itermux.ssh

import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.SocketTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.security.Security
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class RemoteDirectory(val path: String, val entries: List<RemoteEntry>)
data class RemoteEntry(val name: String, val isDirectory: Boolean)

/** SSH transport. Blocking socket work is confined to IO; output applies backpressure to the renderer. */
class JschSession(
    private val target: Target,
    private val credentials: Credentials,
    private val pins: PinStore,
    private val interaction: suspend (Prompt) -> List<String>?,
    private val connectTimeoutMs: Int = 20_000
) : SshSession {
    private val mutableState = MutableStateFlow(ConnectionState(Phase.CONNECTING))
    override val state = mutableState.asStateFlow()
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val input = Queue<ByteArray>(64)
    private val sizes = Queue<IntArray>(Queue.CONFLATED)
    private val interactionJob = SupervisorJob()
    @Volatile private var session: Session? = null
    @Volatile private var channel: com.jcraft.jsch.Channel? = null
    @Volatile private var dimensions = intArrayOf(80, 24, 0, 0)
    @Volatile private var trustFailure: Exception? = null
    private val tmuxHistoryController = TmuxHistoryController(::runTmux)

    private fun update(phase: Phase, message: String = "") {
        if (!stopped.get()) mutableState.value = ConnectionState(phase, message)
    }

    private fun ask(prompt: Prompt): List<String>? = runBlocking(interactionJob) {
        withTimeout(120_000) { interaction(prompt) }
    }

    override suspend fun connect(onOutput: suspend (ByteArray) -> Unit): Unit = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "A connection attempt cannot be reused" }
        val jsch = JSch()
        try {
            ensureActive()
            if (stopped.get()) return@withContext
            configureCrypto()
            jsch.hostKeyRepository = repository()
            val ssh = jsch.getSession(target.username, target.hostname, target.port)
            session = ssh
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
                jsch.addIdentity("session-key", key, null, credentials.passphrase)
                credentials.privateKey.fill(0)
                credentials.passphrase?.fill(0)
            } else if (target.auth == AuthMethod.PASSWORD) {
                ssh.setPassword(credentials.password)
            }
            ssh.setServerAliveInterval(15_000)
            ssh.setServerAliveCountMax(3)
            if (stopped.get()) return@withContext
            ssh.connect(connectTimeoutMs)
            // Secrets no longer needed after authentication; reconnect always creates a new attempt.
            credentials.clear()
            jsch.removeAllIdentity()
            ensureActive()
            if (stopped.get()) return@withContext
            if (target.mode == SessionMode.TMUX && !hasTmux(ssh)) {
                update(Phase.TMUX_MISSING, "服务器未找到 tmux，可改用普通 SSH；不会自动安装软件。")
                return@withContext
            }
            val terminal: com.jcraft.jsch.Channel = if (target.mode == SessionMode.TMUX) {
                (ssh.openChannel("exec") as ChannelExec).apply {
                    setCommand(Tmux.ATTACH)
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
            update(Phase.CONNECTED)
            coroutineScope {
                val writer = launch(Dispatchers.IO) {
                    try {
                        for (bytes in input) {
                            try { if (!stopped.get() && terminal.isConnected) { stdin.write(bytes); stdin.flush() } }
                            finally { bytes.fill(0) }
                        }
                    } catch (e: Exception) { terminal.disconnect() }
                }
                val resizer = launch(Dispatchers.IO) {
                    try {
                        for (d in sizes) when (terminal) {
                            is ChannelShell -> terminal.setPtySize(d[0], d[1], d[2], d[3])
                            is ChannelExec -> terminal.setPtySize(d[0], d[1], d[2], d[3])
                        }
                    } catch (e: Exception) { terminal.disconnect() }
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
            update(Phase.DISCONNECTED, "SSH 已断开，请手动重连。未发送的输入已丢弃。")
        } catch (e: Exception) {
            val failure = trustFailure ?: e
            if (!stopped.get()) mutableState.value = when {
                failure is HostKeyChanged -> ConnectionState(Phase.HOST_KEY_CHANGED, "主机指纹发生变化，连接已阻止。请通过可信渠道核验。", failure.old.fingerprint, failure.received.fingerprint)
                failure is HostKeyRejected -> ConnectionState(Phase.DISCONNECTED, "未信任此主机")
                e is CancellationException -> ConnectionState(Phase.DISCONNECTED, "连接已取消或验证等待超时")
                e.message?.contains("Auth", ignoreCase = true) == true -> ConnectionState(Phase.AUTH_FAILED, "认证失败，请检查用户名、凭据或服务器认证配置。")
                e is SocketTimeoutException || e.message?.contains("timeout", true) == true -> ConnectionState(Phase.FAILED, "连接超时，请检查网络、VPN 和主机端口。")
                else -> ConnectionState(Phase.FAILED, "连接失败，请检查网络、地址、密钥格式及服务器算法支持。")
            }
        } finally {
            closeTransport()
            credentials.clear()
            jsch.removeAllIdentity()
            interactionJob.cancel()
            input.close(); sizes.close()
            while (true) (input.tryReceive().getOrNull() ?: break).fill(0)
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

    override fun write(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.size > 256 * 1024 || stopped.get() || state.value.phase != Phase.CONNECTED) return false
        val copy = bytes.copyOf()
        if (input.trySend(copy).isSuccess) return true
        copy.fill(0)
        return false
    }

    override fun resize(columns: Int, rows: Int, pixelWidth: Int, pixelHeight: Int) {
        dimensions = intArrayOf(columns.coerceIn(2, 500), rows.coerceIn(2, 300), pixelWidth.coerceAtLeast(0), pixelHeight.coerceAtLeast(0))
        sizes.trySend(dimensions)
    }

    /** Query the active pane of this app's fixed tmux session over a separate SSH channel. */
    suspend fun tmuxHistory(): TmuxHistory? = tmuxHistoryController.history()

    suspend fun enterTmuxHistory(): TmuxHistory? = tmuxHistoryController.enter()

    suspend fun moveTmuxHistoryTo(position: Int): TmuxHistory? = tmuxHistoryController.moveTo(position)

    suspend fun exitTmuxHistory(): TmuxHistory? = tmuxHistoryController.exit()

    /** A separate SFTP channel shares the authenticated SSH connection without touching the PTY. */
    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) = withSftp { sftp ->
        val path = literalSftpPath(validTransferPath(remotePath))
        if (sftp.exists(path)) {
            require(!sftp.lstat(path).isDir) { "目标是目录，请选择目录并指定文件名" }
            throw FileAlreadyExistsException(remotePath)
        }
        val directory = remotePath.substringBeforeLast('/', "")
        val temporary = literalSftpPath((if ('/' in remotePath) "$directory/" else "") + ".itermux-${UUID.randomUUID()}.part")
        try {
            sftp.put(source, temporary, transferMonitor(coroutineContext[Job], onProgress))
            coroutineContext.ensureActive()
            if (sftp.exists(path)) throw FileAlreadyExistsException(remotePath)
            sftp.rename(temporary, path)
        } catch (e: Exception) {
            runCatching { sftp.rm(temporary) }
            throw e
        }
    }

    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {}, onSize: (Long) -> Unit = {}) = withSftp { sftp ->
        val path = literalSftpPath(validTransferPath(remotePath))
        val attributes = sftp.stat(path)
        require(!attributes.isDir) { "不能下载文件夹" }
        onSize(attributes.size)
        sftp.get(path, destination, transferMonitor(coroutineContext[Job], onProgress))
        coroutineContext.ensureActive()
    }

    /** List a remote SFTP directory for the file picker. An empty path opens the SFTP home. */
    suspend fun listRemoteDirectory(remotePath: String = ""): RemoteDirectory = withSftp { sftp ->
        val path = if (remotePath.isBlank()) sftp.pwd() else validTransferPath(remotePath)
        val entries = sftp.ls(literalSftpPath(path)).mapNotNull { item ->
            val entry = item as ChannelSftp.LsEntry
            entry.filename.takeUnless { it.isBlank() || it == "." || it == ".." }?.let {
                RemoteEntry(it, entry.attrs.isDir)
            }
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        RemoteDirectory(sftp.realpath(path), entries)
    }

    private suspend fun <T> withSftp(block: suspend (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        val ssh = session?.takeIf { it.isConnected && !stopped.get() && state.value.phase == Phase.CONNECTED }
            ?: throw IllegalStateException("SSH 连接未就绪")
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(connectTimeoutMs)
            coroutineContext.ensureActive()
            block(sftp)
        } finally {
            sftp.disconnect()
        }
    }

    private fun transferMonitor(job: Job?, onProgress: (Long) -> Unit): SftpProgressMonitor = object : SftpProgressMonitor {
        private var transferred = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
        override fun count(count: Long): Boolean {
            transferred += count
            onProgress(transferred)
            return !stopped.get() && job?.isActive != false
        }
        override fun end() = Unit
    }

    private fun validTransferPath(path: String): String {
        require(path.isNotBlank() && path.length <= 4096 && path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "请输入有效的远端文件路径" }
        return path
    }

    // JSch's high-level operations interpret glob patterns; realpath alone takes a raw path.
    private fun literalSftpPath(path: String): String = buildString {
        path.forEach { char ->
            if (char == '\\' || char == '*' || char == '?') append('\\')
            append(char)
        }
    }

    private fun ChannelSftp.exists(path: String): Boolean = try {
        lstat(path)
        true
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    private suspend fun runTmux(command: String): String? = withContext(Dispatchers.IO) {
        val ssh = session?.takeIf { it.isConnected && !stopped.get() && state.value.phase == Phase.CONNECTED }
            ?: return@withContext null
        val exec = try { ssh.openChannel("exec") as ChannelExec } catch (_: Exception) { return@withContext null }
        try {
            exec.setCommand(command)
            val output = exec.inputStream
            exec.connect(5000)
            val bytes = java.io.ByteArrayOutputStream()
            withTimeout(5000) {
                val buffer = ByteArray(256)
                while (!exec.isClosed || output.available() > 0) {
                    while (output.available() > 0) {
                        val count = output.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 1024) return@withTimeout
                        bytes.write(buffer, 0, count)
                    }
                    if (!exec.isClosed) delay(20)
                }
            }
            if (exec.exitStatus == 0) bytes.toString(Charsets.UTF_8.name()) else null
        } catch (_: Exception) { null }
        finally { exec.disconnect() }
    }

    override fun disconnect() {
        stopped.set(true)
        credentials.clear()
        mutableState.value = ConnectionState(Phase.DISCONNECTED, "连接已关闭")
        interactionJob.cancel()
        // JSch's close can block behind an in-flight packet. Never call it on the UI thread.
        Thread({ closeTransport() }, "ssh-close").apply { isDaemon = true }.start()
        input.close(); sizes.close()
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
