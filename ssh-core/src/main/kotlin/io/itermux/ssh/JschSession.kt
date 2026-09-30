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
import java.nio.file.FileAlreadyExistsException
import java.security.Security
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class RemoteDirectory(val path: String, val entries: List<RemoteEntry>)

/**
 * One SFTP directory entry. Extra metadata fields default so existing callers
 * that only need [name]/[isDirectory] stay source-compatible.
 */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    /** Unix epoch seconds from SftpATTRS.mTime; 0 when unknown. */
    val mtimeSeconds: Long = 0L,
    /** Formatted mode from SftpATTRS.getPermissionsString(), e.g. "-rw-r--r--". */
    val permissions: String = ""
)

/** Join an SFTP directory and child name into a single path. */
fun joinRemotePath(directory: String, name: String): String {
    if (name.isEmpty()) {
        val base = directory.trimEnd('/')
        return if (base.isEmpty()) "/" else "$base/"
    }
    val base = directory.trimEnd('/')
    return if (base.isEmpty()) "/$name" else "$base/$name"
}

/** Parent of an absolute (or home-relative) remote path; root stays "/". */
fun parentRemotePath(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    val parent = trimmed.substringBeforeLast('/', missingDelimiterValue = "")
    return parent.ifEmpty { "/" }
}

/** True when [name] is a single path segment safe to delete or rename (not `.`/`..`, no separators). */
fun isSafeRemoteEntryName(name: String): Boolean =
    name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        '/' !in name &&
        '\\' !in name &&
        name.none { it == '\u0000' || it == '\n' || it == '\r' }

/**
 * True when [path] is a non-root remote path safe to delete: no blank/control chars,
 * no `.`/`..` segments, and not the filesystem root.
 */
fun isSafeRemoteDeletePath(path: String): Boolean {
    if (path.isBlank() || path.length > 4096) return false
    if (path.any { it == '\u0000' || it == '\n' || it == '\r' }) return false
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return false
    return trimmed.split('/').none { it == "." || it == ".." }
}

fun formatRemoteSize(bytes: Long): String = when {
    bytes < 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"
    bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.US, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private val remoteMtimeFormatter = ThreadLocal.withInitial {
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
}

fun formatRemoteMtime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    return remoteMtimeFormatter.get().format(java.time.Instant.ofEpochSecond(epochSeconds))
}

/** Directory-first, case-insensitive name order used by SFTP listings. */
fun remoteEntryListComparator(): Comparator<RemoteEntry> =
    compareByDescending<RemoteEntry> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** SSH transport. Blocking socket work is confined to IO; output applies backpressure to the renderer. */
class JschSession(
    private val target: Target,
    private val credentials: Credentials,
    private val pins: PinStore,
    private val interaction: suspend (Prompt) -> List<String>?,
    private val connectTimeoutMs: Int = 20_000,
    /** Remote tmux session name; ignored for SHELL mode. Legacy journal entries use [Tmux.LEGACY_SESSION]. */
    val remoteSessionName: String = Tmux.LEGACY_SESSION
) : SshSession {
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
    private val tmuxHistoryController = TmuxHistoryController(tmuxName, ::runTmux)

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

    /**
     * Read a remote text file (home-relative or absolute) without upload-path overwrite rules.
     * Returns null if missing. If larger than [maxBytes], keeps only the trailing bytes
     * (caller should skip the first partial line).
     */
    suspend fun readRemoteText(remotePath: String, maxBytes: Int = 512 * 1024): Pair<String, Boolean>? = withSftp { sftp ->
        require(remotePath.isNotBlank() && remotePath.length <= 4096 &&
            remotePath.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "请输入有效的远端文件路径"
        }
        val path = literalSftpPath(remotePath)
        val attributes = try {
            sftp.stat(path)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return@withSftp null else throw e
        }
        if (attributes.isDir) return@withSftp null
        val out = java.io.ByteArrayOutputStream()
        sftp.get(path, out)
        coroutineContext.ensureActive()
        val bytes = out.toByteArray()
        val truncated = bytes.size > maxBytes
        val slice = if (truncated) bytes.copyOfRange(bytes.size - maxBytes, bytes.size) else bytes
        String(slice, Charsets.UTF_8) to truncated
    }

    /** List a remote SFTP directory for the file picker. An empty path opens the SFTP home. */
    suspend fun listRemoteDirectory(remotePath: String = ""): RemoteDirectory = withSftp { sftp ->
        val path = if (remotePath.isBlank()) sftp.pwd() else validTransferPath(remotePath)
        // Collect via selector to avoid an intermediate Vector copy and to honor cancellation
        // mid-listing (BREAK). Attrs come from LsEntry — no per-entry stat.
        val entries = ArrayList<RemoteEntry>(256)
        val job = coroutineContext[Job]
        sftp.ls(literalSftpPath(path), ChannelSftp.LsEntrySelector { entry ->
            if (job?.isActive == false) return@LsEntrySelector ChannelSftp.LsEntrySelector.BREAK
            val name = entry.filename
            if (name.isNullOrBlank() || name == "." || name == "..") {
                return@LsEntrySelector ChannelSftp.LsEntrySelector.CONTINUE
            }
            val attrs = entry.attrs
            entries.add(
                RemoteEntry(
                    name = name,
                    isDirectory = attrs.isDir,
                    size = attrs.size,
                    mtimeSeconds = attrs.mTime.toLong() and 0xFFFFFFFFL,
                    permissions = attrs.permissionsString
                )
            )
            ChannelSftp.LsEntrySelector.CONTINUE
        })
        coroutineContext.ensureActive()
        entries.sortWith(remoteEntryListComparator())
        // Skip realpath RTT when we already have an absolute path (pwd / navigated).
        // Relative paths still need realpath for a stable breadcrumb.
        val resolved = if (path.startsWith("/")) path else sftp.realpath(literalSftpPath(path))
        RemoteDirectory(resolved, entries)
    }

    /** Rename a remote file or directory. Paths follow the same validation as transfers. */
    suspend fun rename(oldPath: String, newPath: String) = withSftp { sftp ->
        val from = literalSftpPath(validTransferPath(oldPath))
        val to = literalSftpPath(validTransferPath(newPath))
        sftp.rename(from, to)
    }

    /**
     * Delete a remote file or directory. Directories are removed recursively
     * (contents first, then rmdir). Empty directories are removed directly.
     * Refuses `.`/`..`/root/unsafe paths. On partial failure, aborts with a clear error.
     */
    suspend fun deleteFile(path: String) = withSftp { sftp ->
        require(isSafeRemoteDeletePath(path)) { "无效的删除路径" }
        deleteRemoteRecursive(sftp, validTransferPath(path))
    }

    private fun deleteRemoteRecursive(sftp: ChannelSftp, path: String) {
        val remote = literalSftpPath(path)
        val attrs = try {
            sftp.lstat(remote)
        } catch (e: SftpException) {
            throw IllegalStateException("无法删除：$path（${e.message}）", e)
        }
        if (attrs.isDir) {
            @Suppress("UNCHECKED_CAST")
            val listing = try {
                sftp.ls(remote) as java.util.Vector<ChannelSftp.LsEntry>
            } catch (e: SftpException) {
                throw IllegalStateException("无法列出目录：$path（${e.message}）", e)
            }
            for (item in listing) {
                val name = item.filename ?: continue
                if (name.isEmpty() || name == "." || name == "..") continue
                if (!isSafeRemoteEntryName(name)) {
                    throw IllegalArgumentException("目录含有不安全的名称，已中止删除：$name")
                }
                val child = joinRemotePath(path, name)
                deleteRemoteRecursive(sftp, child)
            }
            try {
                sftp.rmdir(remote)
            } catch (e: SftpException) {
                throw IllegalStateException("无法删除目录：$path（${e.message}）", e)
            }
        } else {
            try {
                sftp.rm(remote)
            } catch (e: SftpException) {
                throw IllegalStateException("无法删除文件：$path（${e.message}）", e)
            }
        }
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
