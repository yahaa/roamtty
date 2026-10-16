package io.itermux.app.ui

import android.os.SystemClock
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.session.HostMetrics
import io.itermux.app.session.HostMetricsReader
import io.itermux.app.session.MetricsReading
import io.itermux.app.session.PendingPrompt
import io.itermux.app.session.RemoteFolders
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Whether opening the host page can log in by itself, or needs a password typed first. */
internal val Host.needsTypedPassword: Boolean get() = auth == AuthMethod.PASSWORD.name && encryptedPassword == null

/** Stored key (host id + envelope hash) → whether it is passphrase-protected; reading it means a Keystore decrypt. */
private val keyNeedsPassphrase = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

/**
 * Whether connecting needs something typed first: an unsaved password, or the passphrase of an encrypted key.
 * A key without a passphrase logs in straight away, like a saved password.
 */
internal fun Host.needsTypedSecret(app: ItermuxApp): Boolean {
    if (auth != AuthMethod.KEY.name) return needsTypedPassword
    val envelope = encryptedKey ?: return false
    return keyNeedsPassphrase.getOrPut("$id:${envelope.contentHashCode()}") {
        runCatching {
            val key = app.vault.decrypt(envelope, "$id:key")
            try { privateKeyNeedsPassphrase(key) } finally { key.fill(0) }
        }.getOrDefault(true)
    }
}
/**
 * The credentials of one login to [host]: its saved password or key, else the [password] typed for it, and a typed key
 * [passphrase]. Saved secrets are decrypted with the Keystore, so call this off the main thread.
 */
internal fun ItermuxApp.credentialsFor(host: Host, password: String, passphrase: String) = Credentials(
    password = if (host.auth == AuthMethod.PASSWORD.name) host.encryptedPassword?.let { vault.decrypt(it, "${host.id}:password") } ?: password.toByteArray() else null,
    privateKey = host.encryptedKey?.takeIf { host.auth == AuthMethod.KEY.name }?.let { vault.decrypt(it, "${host.id}:key") },
    passphrase = passphrase.takeIf { it.isNotEmpty() }?.toByteArray())

/** A login of a page's own (no PTY); its prompts wait in [pending] for the page to answer them. */
internal fun ItermuxApp.pageLogin(host: Host, credentials: Credentials, pending: MutableStateFlow<PendingPrompt?>) =
    JschSession(host.target(SessionMode.SHELL), credentials, pins, interaction = { prompt ->
        val request = PendingPrompt(prompt)
        withContext(Dispatchers.Main.immediate) { pending.value = request }
        try { request.answer.await() }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { pending.compareAndSet(request, null) } }
    }, connectTimeoutMs = 15_000)

/**
 * What a login to this host is made of. Pages keep their login while this stays the same, so editing the
 * address, user or credentials logs in again, while a plugin probe or OS detection saving the host does not.
 */
internal val Host.loginKey: List<Any?> get() =
    listOf(id, address, port, username, auth, encryptedPassword?.contentHashCode(), encryptedKey?.contentHashCode())

/**
 * A private SSH login that samples [HostMetrics] every few seconds while the host page is visible (unless
 * [sampleMetrics] is off) and lists remote folders on request. It opens no PTY, tmux or mosh, so it never counts
 * as a session. [login] carries a password or passphrase typed for it.
 */
internal class HostMonitor(private val app: ItermuxApp, private val login: PluginTarget, private val scope: CoroutineScope,
    private val sampleMetrics: Boolean = true) {
    val host get() = login.host
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val connection = MutableStateFlow(ConnectionState(Phase.CONNECTING))
    val metrics = MutableStateFlow<HostMetrics?>(null)
    val samplingFailed = MutableStateFlow(false)
    /** Network throughput between the last two samples, bytes per second. */
    val netRate = MutableStateFlow<Pair<Double, Double>?>(null)
    val cpuHistory = MutableStateFlow<List<Double>>(emptyList())
    val memHistory = MutableStateFlow<List<Double>>(emptyList())
    /** Utilization history per GPU index. */
    val gpuHistory = MutableStateFlow<Map<Int, List<Double>>>(emptyMap())
    /** Sampling pauses while the app is in the background. */
    val visible = MutableStateFlow(true)
    private var session: JschSession? = null
    @Volatile private var remote: RemoteHost? = null
    /** True while the login is up and [listFolders] can run. */
    val ready = MutableStateFlow(false)
    private var job: Job? = null
    /** Whether the current login has read at least one sample. */
    @Volatile private var sampled = false

    fun start() {
        job?.cancel()
        session?.disconnect()
        connection.value = ConnectionState(Phase.CONNECTING)
        samplingFailed.value = false
        netRate.value = null
        job = scope.launch {
            var retry = 0
            while (true) {
                sampled = false
                val ended = try { login() } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    connection.value = ConnectionState(Phase.FAILED, "无法读取凭据，请重新导入密钥或保存密码。")
                    return@launch
                }
                // A sample since the last retry means the host was fine until now: start the backoff over.
                if (sampled) retry = 0
                val wait = monitorRetryDelayMs(ended, ++retry, host.auth == AuthMethod.INTERACTIVE.name)
                if (wait == null) { connection.value = ended; return@launch }
                connection.value = ConnectionState(Phase.CONNECTING, MONITOR_RECONNECTING, detail = ended.detail)
                delay(wait)
            }
        }
    }

    /** One SSH login that samples until it ends; returns how it ended. Throws when the credentials cannot be read. */
    private suspend fun login(): ConnectionState = coroutineScope {
        var credentials: Credentials? = null
        try {
            credentials = withContext(Dispatchers.IO) { app.credentialsFor(host, login.password, login.passphrase) }
            val ssh = app.pageLogin(host, credentials, pending)
            session = ssh
            // Keep showing the reconnect notice until this login gets past connecting.
            val state = launch { ssh.state.collect { if (it.phase != Phase.CONNECTING || !connection.value.active) connection.value = it } }
            ssh.administer { remote ->
                this@HostMonitor.remote = remote
                ready.value = true
                try { if (sampleMetrics) sample(remote) else awaitCancellation() }
                finally { this@HostMonitor.remote = null; ready.value = false }
            }
            state.cancel()
            ssh.state.value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { credentials?.clear(); throw e }
    }

    private suspend fun sample(remote: RemoteHost) {
        val reader = HostMetricsReader(remote, preferWindows = host.os == HostSystem.WINDOWS.name)
        var previous: Pair<HostMetrics, Long>? = null
        while (currentCoroutineContext().isActive) {
            visible.first { it }
            val current = when (val reading = reader.read()) {
                is MetricsReading.Sample -> reading.metrics
                // The login loop reconnects and shows its own notice; this is not a permissions problem.
                MetricsReading.Lost -> return
                MetricsReading.Unavailable -> {
                    samplingFailed.value = true
                    metrics.value = null
                    netRate.value = null
                    return
                }
            }
            samplingFailed.value = false
            val now = SystemClock.elapsedRealtime()
            netRate.value = previous?.let { (last, at) -> current.networkRateSince(last, (now - at) / 1000.0) }
            current.cpuPercent?.let { cpuHistory.value = (cpuHistory.value + it).takeLast(MONITOR_HISTORY) }
            current.memPercent?.let { memHistory.value = (memHistory.value + it).takeLast(MONITOR_HISTORY) }
            if (current.gpus.isNotEmpty() || gpuHistory.value.isNotEmpty()) gpuHistory.value = current.gpus.associate { gpu ->
                val past = gpuHistory.value[gpu.index].orEmpty()
                gpu.index to (gpu.utilPercent?.let { (past + it).takeLast(MONITOR_HISTORY) } ?: past)
            }
            metrics.value = current
            sampled = true
            previous = current to now
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    /** Subfolders of [directory] (`~`, `~/x` or absolute), or null when it cannot be read or the login is not up. */
    suspend fun listFolders(directory: String): RemoteFolders? = remote?.let { RemoteFolders.load(it, directory) }

    fun close() {
        pending.value?.answer?.complete(null)
        session?.disconnect()
        job?.cancel()
    }
}

/**
 * Wait before logging the monitor back in after it [ended], or null to stop and show the failure. Network failures,
 * dropped connections (a Mac going to sleep, sshd penalising this address) and failed samples back off
 * 2, 4, 8, 16, 30 s; rejected credentials, host keys and logins that need typed answers never retry.
 */
internal fun monitorRetryDelayMs(ended: ConnectionState, retry: Int, interactive: Boolean): Long? {
    if (interactive || retry > MONITOR_RETRIES) return null
    val retryable = ended.phase == Phase.FAILED || (ended.phase == Phase.DISCONNECTED && ended.message == "连接已关闭")
    return if (retryable) (1_000L shl retry).coerceAtMost(30_000L) else null
}

private const val MONITOR_RETRIES = 5
internal const val MONITOR_RECONNECTING = "连接中断，正在自动重连…"
internal const val SAMPLE_INTERVAL_MS = 3_000L
internal const val MONITOR_HISTORY = 40
