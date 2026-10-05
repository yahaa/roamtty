package io.itermux.app.ui

import android.os.SystemClock
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.session.HostMetrics
import io.itermux.app.session.PendingPrompt
import io.itermux.app.session.RemoteFolders
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Whether opening the host page can log in by itself, or needs a password typed first. */
internal val Host.needsTypedPassword: Boolean get() = auth == AuthMethod.PASSWORD.name && encryptedPassword == null

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
    /** Network throughput between the last two samples, bytes per second. */
    val netRate = MutableStateFlow<Pair<Double, Double>?>(null)
    val cpuHistory = MutableStateFlow<List<Double>>(emptyList())
    val memHistory = MutableStateFlow<List<Double>>(emptyList())
    /** Sampling pauses while the app is in the background. */
    val visible = MutableStateFlow(true)
    private var session: JschSession? = null
    @Volatile private var remote: RemoteHost? = null
    /** True while the login is up and [listFolders] can run. */
    val ready = MutableStateFlow(false)
    private var job: Job? = null

    fun start() {
        job?.cancel()
        session?.disconnect()
        connection.value = ConnectionState(Phase.CONNECTING)
        job = scope.launch {
            var credentials: Credentials? = null
            try {
                credentials = withContext(Dispatchers.IO) {
                    Credentials(
                        password = if (host.auth == AuthMethod.PASSWORD.name) host.encryptedPassword?.let { app.vault.decrypt(it, "${host.id}:password") } ?: login.password.toByteArray() else null,
                        privateKey = host.encryptedKey?.takeIf { host.auth == AuthMethod.KEY.name }?.let { app.vault.decrypt(it, "${host.id}:key") },
                        passphrase = login.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
                }
                val ssh = JschSession(host.target(SessionMode.SHELL), credentials, app.pins, interaction = { prompt ->
                    val request = PendingPrompt(prompt)
                    withContext(Dispatchers.Main.immediate) { pending.value = request }
                    try { request.answer.await() }
                    finally { withContext(NonCancellable + Dispatchers.Main.immediate) { pending.compareAndSet(request, null) } }
                }, connectTimeoutMs = 15_000)
                session = ssh
                val state = launch { ssh.state.collect { connection.value = it } }
                ssh.administer { remote ->
                    this@HostMonitor.remote = remote
                    ready.value = true
                    try { if (sampleMetrics) sample(remote) else awaitCancellation() }
                    finally { this@HostMonitor.remote = null; ready.value = false }
                }
                state.cancel()
                connection.value = ssh.state.value
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                credentials?.clear()
                connection.value = ConnectionState(Phase.FAILED, "无法读取凭据，请重新导入密钥或保存密码。")
            }
        }
    }

    private suspend fun sample(remote: RemoteHost) {
        var previous: Pair<HostMetrics, Long>? = null
        while (currentCoroutineContext().isActive) {
            visible.first { it }
            val lines = mutableListOf<String>()
            remote.run(HostMetrics.script, SAMPLE_TIMEOUT_MS) { lines += it } ?: return
            val now = SystemClock.elapsedRealtime()
            val current = HostMetrics.parse(lines)
            previous?.let { (last, at) ->
                val seconds = (now - at) / 1000.0
                val rx = current.netRxBytes; val tx = current.netTxBytes
                val lastRx = last.netRxBytes; val lastTx = last.netTxBytes
                if (seconds > 0 && rx != null && tx != null && lastRx != null && lastTx != null && rx >= lastRx && tx >= lastTx)
                    netRate.value = (rx - lastRx) / seconds to (tx - lastTx) / seconds
            }
            current.cpuPercent?.let { cpuHistory.value = (cpuHistory.value + it).takeLast(MONITOR_HISTORY) }
            current.memPercent?.let { memHistory.value = (memHistory.value + it).takeLast(MONITOR_HISTORY) }
            metrics.value = current
            previous = current to now
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    /** Subfolders of [directory] (`~`, `~/x` or absolute), or null when it cannot be read or the login is not up. */
    suspend fun listFolders(directory: String): RemoteFolders? {
        val host = remote ?: return null
        val lines = mutableListOf<String>()
        val status = host.run(RemoteFolders.script(directory), LIST_TIMEOUT_MS) { lines += it }
        return if (status == 0) RemoteFolders.parse(lines) else null
    }

    fun close() {
        pending.value?.answer?.complete(null)
        session?.disconnect()
        job?.cancel()
    }
}

internal const val SAMPLE_INTERVAL_MS = 3_000L
private const val SAMPLE_TIMEOUT_MS = 10_000L
private const val LIST_TIMEOUT_MS = 10_000L
internal const val MONITOR_HISTORY = 40
