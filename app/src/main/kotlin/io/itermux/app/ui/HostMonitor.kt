package io.itermux.app.ui

import android.os.SystemClock
import io.itermux.app.ItermuxApp
import io.itermux.app.session.AgentUsageCollector
import io.itermux.app.session.HostAiUsage
import io.itermux.app.session.HostMetrics
import io.itermux.app.session.HostMetricsReader
import io.itermux.app.session.HostUsageRange
import io.itermux.app.session.MetricsReading
import io.itermux.app.login.PendingPrompt
import io.itermux.app.session.RemoteFolders
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import io.itermux.app.data.credentialsFor
import io.itermux.app.login.pageLogin

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
    val supportsAiUsage: Boolean get() = host.os != HostSystem.WINDOWS.name
    private val mutableAiUsageRange = MutableStateFlow(HostUsageRange.TODAY)
    val aiUsageRange = mutableAiUsageRange.asStateFlow()
    private val mutableAiUsage = MutableStateFlow<HostAiUsage?>(null)
    val aiUsage = mutableAiUsage.asStateFlow()
    private val mutableAiUsageLoading = MutableStateFlow(false)
    val aiUsageLoading = mutableAiUsageLoading.asStateFlow()
    private val aiUsageCache = mutableMapOf<HostUsageRange, Pair<Long, HostAiUsage>>()
    private var aiUsageJob: Job? = null
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
            credentials = withContext(Dispatchers.IO) { app.vault.credentialsFor(host, login.password, login.passphrase) }
            val ssh = pageLogin(host, credentials, app.pins, pending)
            session = ssh
            // Keep showing the reconnect notice until this login gets past connecting.
            val state = launch { ssh.state.collect { if (it.phase != Phase.CONNECTING || !connection.value.active) connection.value = it } }
            ssh.administer { remote ->
                // Opening the host page refreshes its desktop probe (read-only, one shell call).
                runCatching { remote.probeDesktop()?.let { d ->
                    app.database.hosts().updateDesktop(host.id, host.address, host.port, host.username, d.desktop, System.currentTimeMillis())
                } }
                this@HostMonitor.remote = remote
                ready.value = true
                if (sampleMetrics) refreshAiUsage(force = false)
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

    /** The tmux sessions on the server, most recently used first; [TmuxListing.Failed] when the login is not up. */
    suspend fun listTmuxSessions(): TmuxListing {
        val remote = remote ?: return TmuxListing.Failed
        val lines = mutableListOf<String>()
        return when (remote.run(Tmux.LIST_SESSIONS_SCRIPT, 10_000) { lines += it }) {
            0 -> TmuxListing.Sessions(TmuxSessionInfo.parse(lines))
            127 -> TmuxListing.Missing
            else -> TmuxListing.Failed
        }
    }

    /** Subfolders of [directory] (`~`, `~/x` or absolute), or null when it cannot be read or the login is not up. */
    suspend fun listFolders(directory: String): RemoteFolders? = remote?.let { RemoteFolders.load(it, directory) }

    /**
     * The TCP ports the host listens on, with the processes behind them; null when the login is not up or the host
     * cannot tell (Windows, or neither `ss` nor `lsof`).
     */
    suspend fun listPorts(): List<ListeningPort>? {
        if (host.os == HostSystem.WINDOWS.name) return null
        val remote = remote ?: return null
        val lines = mutableListOf<String>()
        return if (remote.run(ListeningPort.SCRIPT, 10_000) { lines += it } == 0) ListeningPort.parse(lines) else null
    }

    /** A connection the host makes to [host]:[port] for the phone, over this page's login. */
    suspend fun openSocket(host: String, port: Int): RemoteSocket =
        (remote ?: throw SshUnavailableException()).openSocket(host, port)

    fun setAiUsageRange(range: HostUsageRange) {
        mutableAiUsageRange.value = range
        aiUsageCache[range]?.let { (_, cached) -> mutableAiUsage.value = cached }
        refreshAiUsage(force = false)
    }

    /** Loads machine-wide per-agent token consumption & active quotas over the monitor's SSH connection. */
    fun refreshAiUsage(force: Boolean = true) {
        if (host.os == HostSystem.WINDOWS.name) return
        val remote = remote ?: return
        val range = mutableAiUsageRange.value
        val now = SystemClock.elapsedRealtime()
        val cached = aiUsageCache[range]
        if (!force && cached != null && now - cached.first < AI_USAGE_CACHE_MS) {
            mutableAiUsage.value = cached.second
            return
        }
        aiUsageJob?.cancel()
        aiUsageJob = scope.launch {
            mutableAiUsageLoading.value = true
            try {
                val lines = mutableListOf<String>()
                val status = withContext(Dispatchers.IO) {
                    remote.run(AgentUsageCollector.hostScript(range), 15_000) { lines += it }
                }
                if (status == 0) {
                    val parsed = AgentUsageCollector.parseHost(lines, range)
                    aiUsageCache[range] = SystemClock.elapsedRealtime() to parsed
                    if (mutableAiUsageRange.value == range) mutableAiUsage.value = parsed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep previous snapshot on transient errors.
            } finally {
                mutableAiUsageLoading.value = false
            }
        }
    }

    fun close() {
        aiUsageJob?.cancel()
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
    val retryable = ended.phase == Phase.FAILED || (ended.phase == Phase.DISCONNECTED && ended.message == ConnectionMessages.CLOSED)
    return if (retryable) (1_000L shl retry).coerceAtMost(30_000L) else null
}

internal sealed interface TmuxListing {
    data class Sessions(val sessions: List<TmuxSessionInfo>) : TmuxListing
    data object Missing : TmuxListing
    data object Failed : TmuxListing
}

private const val MONITOR_RETRIES = 5
internal const val MONITOR_RECONNECTING = "连接中断，正在自动重连…"
internal const val SAMPLE_INTERVAL_MS = 3_000L
internal const val MONITOR_HISTORY = 40
private const val AI_USAGE_CACHE_MS = 30_000L
