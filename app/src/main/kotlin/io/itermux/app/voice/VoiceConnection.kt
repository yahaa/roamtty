package io.itermux.app.voice

import android.util.Log
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.data.credentialsFor
import io.itermux.app.data.installedPlugins
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopPing
import io.itermux.app.desktop.DesktopPluginSetup
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.login.PendingPrompt
import io.itermux.app.login.pageLogin
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.desktop.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Result of invoking the terminal hotkey callback once the voice bridge and environment are ready. */
internal enum class VoiceReadyOutcome {
    /** Start key (`F5` / `F6`) was injected into the owning `agy` terminal session. */
    INJECTED,
    /** Owning session closed or disconnected while voice was connecting; abort silently. */
    SESSION_GONE,
    /** Owning session exited `agy` back to a plain shell while connecting; abort and inform the user. */
    AGY_EXITED,
}

/** Injectable transport / plugin boundary so [VoiceConnection] can run deterministically in unit tests. */
internal interface VoiceSessionTransport {
    val manifestVersion: String
    val udpHostOverride: String
    val testLoss: Double
    fun targetFor(uname: String): Pair<String, PluginManifest.Target>?
    suspend fun install(remote: RemoteHost, target: String, build: PluginManifest.Target, onLine: (String) -> Unit): String?
    suspend fun administer(
        host: Host,
        password: String,
        passphrase: String,
        pending: MutableStateFlow<PendingPrompt?>,
        onHandle: (disconnect: () -> Unit) -> Unit,
        block: suspend (RemoteHost) -> Unit,
    ): AdministerResult
    suspend fun markDesktopPluginInstalled(host: Host)

    data class AdministerResult(
        val credentialFailed: Boolean = false,
        val phase: Phase = Phase.DISCONNECTED,
        val message: String = "",
    )
}

internal class AppVoiceSessionTransport(private val app: ItermuxApp) : VoiceSessionTransport {
    private val settings = DesktopSettings(app)
    private val manifest by lazy { DesktopSettings.manifest(app) }

    override val manifestVersion: String get() = manifest.version
    override val udpHostOverride: String get() = settings.udpHostOverride
    override val testLoss: Double get() = settings.testLoss

    override fun targetFor(uname: String): Pair<String, PluginManifest.Target>? =
        PluginManifest.targetFor(uname)?.let { target -> manifest.targets[target]?.let { target to it } }

    override suspend fun install(remote: RemoteHost, target: String, build: PluginManifest.Target, onLine: (String) -> Unit): String? =
        DesktopPluginSetup(manifest, onLine).install(remote, target, build, headless = true)

    override suspend fun administer(
        host: Host,
        password: String,
        passphrase: String,
        pending: MutableStateFlow<PendingPrompt?>,
        onHandle: (disconnect: () -> Unit) -> Unit,
        block: suspend (RemoteHost) -> Unit,
    ): VoiceSessionTransport.AdministerResult {
        val credentials = try {
            app.vault.credentialsFor(host, password, passphrase)
        } catch (_: Exception) {
            return VoiceSessionTransport.AdministerResult(credentialFailed = true)
        }
        val login = pageLogin(host, credentials, app.pins, pending)
        onHandle { login.disconnect() }
        try {
            login.administer(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "voice login failed", e)
        } finally {
            login.disconnect()
            onHandle {}
        }
        val s = login.state.value
        return VoiceSessionTransport.AdministerResult(phase = s.phase, message = s.message)
    }

    override suspend fun markDesktopPluginInstalled(host: Host) {
        runCatching {
            val latest = app.database.hosts().find(host.id) ?: return
            val existing = latest.installedPlugins.orEmpty()
            if (HostPlugin.DESKTOP !in existing) {
                app.database.hosts().updatePlugins(
                    latest.id,
                    latest.address,
                    latest.port,
                    latest.username,
                    HostPlugin.encode(existing + HostPlugin.DESKTOP),
                )
            }
        }
    }

    private companion object {
        const val TAG = "RoamttyVoice"
    }
}

/**
 * One host's `roamtty-desktop voice` bridge: launches the headless `voice` subcommand over SSH
 * (without OpenH264 or GUI/LaunchAgent setup), negotiates `UdpLink` with SSH `exec` stdin/stdout
 * fallback, injects `ANTIGRAVITY_MIC=127.0.0.1:<mic_port>` into `agy` launchers and tmux sessions,
 * bridges PCM frames with [VoiceAudioBackend], and supports `--resume` reconnects across network drops.
 */
internal class VoiceConnection(
    val host: Host,
    val sessionId: String,
    private val scope: CoroutineScope,
    private var password: String,
    private var passphrase: String,
    private val tmuxSessions: () -> List<String>,
    private val onReady: (VoiceMode, Int) -> VoiceReadyOutcome,
    private val onEnded: (VoiceConnection) -> Unit,
    private val onNotice: (String) -> Unit = {},
    private val isCurrentOwner: () -> Boolean = { true },
    private val transport: VoiceSessionTransport,
    engineFactory: (
        onMicChunk: (Long, ByteArray, Int, Int) -> Unit,
        onMicLevel: (Float) -> Unit,
        onSpeakerLevel: (Float) -> Unit,
        onMicError: (String) -> Unit,
        onBluetoothAvailableChanged: (Boolean) -> Unit,
        onRouteChanged: (AudioRoute) -> Unit,
    ) -> VoiceAudioBackend,
    private val shutdownGraceMs: Long = SHUTDOWN_GRACE_MS,
    private val remoteEndGraceMs: Long = VoiceRemoteStreamTracker.REMOTE_END_GRACE_MS,
    private val noStreamHintDelayMs: Long = VoiceRemoteStreamTracker.NO_STREAM_HINT_DELAY_MS,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    constructor(
        host: Host,
        sessionId: String,
        app: ItermuxApp,
        scope: CoroutineScope,
        password: String,
        passphrase: String,
        tmuxSessions: () -> List<String>,
        onReady: (VoiceMode, Int) -> VoiceReadyOutcome,
        onEnded: (VoiceConnection) -> Unit,
        onNotice: (String) -> Unit = {},
        isCurrentOwner: () -> Boolean = { true },
    ) : this(
        host = host,
        sessionId = sessionId,
        scope = scope,
        password = password,
        passphrase = passphrase,
        tmuxSessions = tmuxSessions,
        onReady = onReady,
        onEnded = onEnded,
        onNotice = onNotice,
        isCurrentOwner = isCurrentOwner,
        transport = AppVoiceSessionTransport(app),
        engineFactory = { onMicChunk, onMicLevel, onSpeakerLevel, onMicError, onBluetoothChanged, onRouteChanged ->
            VoiceAudioEngine(
                context = app,
                onMicChunk = onMicChunk,
                onMicLevel = onMicLevel,
                onSpeakerLevel = onSpeakerLevel,
                onMicError = onMicError,
                onBluetoothAvailableChanged = onBluetoothChanged,
                onRouteChanged = onRouteChanged,
            )
        },
    )

    /** Per-stream SSH write queue with real-time `MIC_AUDIO` cap (dropping oldest mic chunk) and shutdown coordination. */
    private class ActiveStream(val stream: RemoteStream) {
        val input = BufferedInputStream(stream.input, 64 * 1024)
        val out = BufferedOutputStream(stream.output, 64 * 1024)
        val writeLock = Any()
        private val queueLock = Any()
        private val queue = ArrayDeque<ByteArray>()
        private var micQueued = 0
        private var inputClosed = false
        val signal = Channel<Unit>(Channel.CONFLATED)
        val sshQueued = AtomicInteger(0)
        val shutdownQueued = AtomicBoolean(false)
        val shutdownFlushed = AtomicBoolean(false)
        val eofReached = CompletableDeferred<Unit>()
        @Volatile var shutdownFrameRef: ByteArray? = null
        @Volatile var shutdownStartedAtMs: Long = 0L

        fun enqueue(bytes: ByteArray): Boolean {
            synchronized(queueLock) {
                if (inputClosed) return false
                if (isMicAudio(bytes)) {
                    while (micQueued >= MIC_SSH_CAP) {
                        val oldestIdx = queue.indexOfFirst(::isMicAudio)
                        if (oldestIdx < 0) break
                        queue.removeAt(oldestIdx)
                        micQueued--
                        sshQueued.decrementAndGet()
                    }
                    queue.addLast(bytes)
                    micQueued++
                    sshQueued.incrementAndGet()
                } else {
                    if (sshQueued.get() >= SSH_SEND_CAP && !VoiceProtocol.isReliable(bytes)) return false
                    queue.addLast(bytes)
                    sshQueued.incrementAndGet()
                }
            }
            signal.trySend(Unit)
            return true
        }

        fun pollNext(): Pair<ByteArray, Boolean>? = synchronized(queueLock) {
            val next = queue.removeFirstOrNull() ?: return null
            if (isMicAudio(next)) micQueued--
            sshQueued.decrementAndGet()
            next to queue.isEmpty()
        }

        fun closeInput() {
            synchronized(queueLock) { inputClosed = true }
            signal.close()
        }
    }

    private val mutableUi = MutableStateFlow(VoiceUiState())
    val ui = mutableUi.asStateFlow()
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())

    private val engine: VoiceAudioBackend = engineFactory(
        ::sendMicPcm,
        { level -> dispatch(VoiceEvent.MicLevel(level)) },
        { level -> dispatch(VoiceEvent.SpeakerLevel(level)) },
        { err ->
            Log.w(TAG, "${host.name}: mic capture error: $err")
            failFromAudio(tr("麦克风采集异常，语音已断开"))
        },
        { available -> dispatch(VoiceEvent.BluetoothChanged(available)) },
        { route -> dispatch(VoiceEvent.SetRoute(route)) },
    )

    @Volatile private var activeStream: ActiveStream? = null
    @Volatile private var link: UdpLink? = null
    @Volatile private var stream: RemoteStream? = null
    @Volatile private var disconnectLogin: (() -> Unit)? = null
    private val closingFlag = AtomicBoolean(false)
    @Volatile private var closing = false
    @Volatile private var kicked = false
    @Volatile private var everConnected = false
    @Volatile private var readyNotified = false
    @Volatile private var windowsHost = false
    @Volatile private var remoteSessionId: String? = null
    @Volatile private var resumeToken: String? = null
    @Volatile private var activeMicPort: Int? = null
    @Volatile private var fatalFailure: String? = null
    private val cleanedEnv = AtomicBoolean(false)
    private val fallbackShutdownSent = AtomicBoolean(false)
    private val ping = DesktopPing(::send)
    @Volatile private var goodSinceMs = 0L
    @Volatile private var voiceOpusEnabled = false
    @Volatile private var lastSpkStats: VoiceStats? = null
    @Volatile private var lastUplinkStats: VoiceStats? = null
    @Volatile private var lastStallRebindMs = 0L
    private val micOpusLock = Any()
    private var micSender: VoiceRedSender? = null
    private val handleLock = Any()
    private val trackerLock = Any()
    private val streamTracker = VoiceRemoteStreamTracker()

    private var job: Job? = null
    private var timerJob: Job? = null
    private var remoteEndJob: Job? = null
    private var noStreamHintJob: Job? = null

    val active: Boolean get() = mutableUi.value.inCall
    val micPort: Int? get() = activeMicPort
    /** True once the initial `F5`/`F6` start hotkey was actually sent to the owning `agy` session. */
    val startKeySent: Boolean get() = readyNotified
    /** Visible for unit tests: current SSH queued frame count on the active stream. */
    internal val currentSshQueued: Int get() = activeStream?.sshQueued?.get() ?: 0

    fun isBluetoothAvailable(): Boolean = engine.isBluetoothAvailable()

    fun start(mode: VoiceMode, route: AudioRoute) {
        if (job?.isActive == true) return
        closingFlag.set(false)
        closing = false
        kicked = false
        everConnected = false
        readyNotified = false
        fatalFailure = null
        cleanedEnv.set(false)
        fallbackShutdownSent.set(false)
        synchronized(trackerLock) {
            remoteEndJob?.cancel()
            remoteEndJob = null
            noStreamHintJob?.cancel()
            noStreamHintJob = null
            streamTracker.reset(mode)
        }
        val bt = engine.isBluetoothAvailable()
        val effectiveRoute = if (route == AudioRoute.BLUETOOTH && !bt) AudioRoute.SPEAKER else route
        dispatch(VoiceEvent.Start(mode, effectiveRoute, bluetoothAvailable = bt))
        engine.muted = false
        // Defer AudioManager mode/focus/route acquisition until the bridge is ready in runStream()
        // so any superseded call finishes audio teardown (clearCommunicationDevice + MODE_NORMAL) first.
        engine.applyRoute(effectiveRoute)
        startTimer()
        job = scope.launch(Dispatchers.IO) { runLoop(mode) }
    }

    /** Toggles mute once connected; returns the new mute state, or null when ignored (connecting / resuming). */
    fun toggleMute(): Boolean? {
        val current = mutableUi.value
        if (!current.inCall || current.isConnecting || current.resuming) return null
        dispatch(VoiceEvent.ToggleMute)
        val nowMuted = mutableUi.value.isMuted
        engine.muted = nowMuted
        return nowMuted
    }

    fun setAudioRoute(route: AudioRoute) {
        dispatch(VoiceEvent.SetRoute(route))
        engine.applyRoute(route)
    }

    fun cycleAudioRoute(): AudioRoute {
        dispatch(VoiceEvent.BluetoothChanged(engine.isBluetoothAvailable()))
        dispatch(VoiceEvent.CycleRoute)
        val route = mutableUi.value.audioRoute
        engine.applyRoute(route)
        return route
    }

    /** Offloads UDP socket rebinding / SSH drop off the caller (Main) thread. */
    fun networkChanged() {
        if (!everConnected || closing) return
        scope.launch(Dispatchers.IO) {
            if (closing) return@launch
            val l = link
            if (l != null && l.alive) {
                Log.i(TAG, "${host.name}: network changed, rebinding voice UDP")
                runCatching { l.rebind() }.onFailure { Log.w(TAG, "${host.name}: voice UDP rebind failed", it) }
                return@launch
            }
            Log.i(TAG, "${host.name}: network changed, reconnecting voice with --resume")
            runCatching { stream?.close() }
            runCatching { disconnectLogin?.invoke() }
        }
    }

    /** Called when the active default network is lost without an immediate replacement. */
    fun networkLost() {
        if (!everConnected || closing) return
        scope.launch(Dispatchers.IO) {
            if (closing) return@launch
            Log.i(TAG, "${host.name}: default network lost, dropping stale SSH voice stream")
            // Closing the dead SSH stream lets readFrames either stay on UDP while alive or transition straight to --resume.
            runCatching { stream?.close() }
            if (link?.alive != true) runCatching { disconnectLogin?.invoke() }
        }
    }

    fun hangup() {
        hangupInternal(keepFailure = false)
    }

    private fun failFromAudio(message: String) {
        if (fatalFailure == null) fatalFailure = message
        dispatch(VoiceEvent.Fail(message))
        hangupInternal(keepFailure = true)
    }

    private fun hangupInternal(keepFailure: Boolean): Boolean {
        if (!closingFlag.compareAndSet(false, true)) return false
        closing = true
        cancelStreamTimers()
        engine.silenceForHangup()
        timerJob?.cancel()
        pending.value?.answer?.complete(null)
        val active = activeStream
        val s = active?.stream ?: stream
        val l = link
        val hadStream = s != null
        if (!keepFailure && mutableUi.value.phase !is VoiceCallPhase.Failed) {
            dispatch(VoiceEvent.Hangup)
        }
        // Step 1: Enqueue CONFIG {"shutdown":true} FIRST on UDP + SSH before closing streams or blocking on engine.release().
        val hadShutdownTarget = hadStream || (l != null && l.alive)
        if (hadShutdownTarget) {
            enqueueShutdownFrame(active, l)
        }
        val activeJob = job
        // Step 2: Run flush + grace wait + close + engine.release() on a detached IO scope immune to SessionService.scope.cancel().
        shutdownScope.launch {
            if (hadShutdownTarget) {
                if (active != null) {
                    // If SessionService.scope was cancelled (onDestroy), writer may already be cancelled: flush directly.
                    if (!scope.isActive) {
                        flushShutdownDirectIfNeeded(active)
                    } else if (!active.shutdownFlushed.get()) {
                        // Brief yield so writer can flush; fall back to direct write if writer stopped without flushing.
                        delay(minOf(15L, shutdownGraceMs))
                        if (!active.shutdownFlushed.get()) flushShutdownDirectIfNeeded(active)
                    }
                    val elapsed = System.currentTimeMillis() - active.shutdownStartedAtMs
                    val remaining = (shutdownGraceMs - elapsed).coerceAtLeast(0L)
                    if (remaining > 0L && !active.eofReached.isCompleted && !active.stream.closed) {
                        withTimeoutOrNull(remaining) { active.eofReached.await() }
                    }
                } else {
                    if (s != null) {
                        val shutdownFrame = VoiceProtocol.config(mapOf("shutdown" to true))
                        runCatching {
                            s.output.write(shutdownFrame)
                            s.output.flush()
                        }
                    }
                    delay(shutdownGraceMs)
                }
            }
            runCatching { s?.close() }
            runCatching { l?.close() }
            // If still logging in (no active stream), drop SSH immediately; otherwise let runStream exit and clear env first.
            if (!hadStream) runCatching { disconnectLogin?.invoke() }
            if (activeJob?.isActive != true) {
                clearSecrets()
                engine.release()
                onEnded(this@VoiceConnection)
            }
        }
        return true
    }

    fun clearSecrets() {
        password = ""
        passphrase = ""
    }

    fun release() {
        clearSecrets()
        cancelStreamTimers()
        timerJob?.cancel()
        engine.release()
    }

    private fun cancelStreamTimers() {
        synchronized(trackerLock) {
            remoteEndJob?.cancel()
            remoteEndJob = null
            noStreamHintJob?.cancel()
            noStreamHintJob = null
        }
    }

    private fun beginRecovery() {
        synchronized(trackerLock) {
            remoteEndJob?.cancel()
            remoteEndJob = null
            noStreamHintJob?.cancel()
            noStreamHintJob = null
            streamTracker.beginRecovery()
        }
    }

    private fun handleTrackerAction(action: VoiceRemoteStreamAction) {
        when (action) {
            VoiceRemoteStreamAction.None -> Unit
            VoiceRemoteStreamAction.StreamActive -> cancelStreamTimers()
            is VoiceRemoteStreamAction.ScheduleRemoteEnd -> {
                if (!closing && !mutableUi.value.resuming) {
                    scheduleRemoteEnd(action.reason)
                }
            }
        }
    }

    private fun scheduleRemoteEnd(reason: VoiceRemoteEndReason) {
        synchronized(trackerLock) {
            remoteEndJob?.cancel()
            remoteEndJob = scope.launch {
                if (remoteEndGraceMs > 0L) delay(remoteEndGraceMs)
                val shouldFinish = synchronized(trackerLock) {
                    !closing && active && !mutableUi.value.resuming &&
                        streamTracker.activeMicStreamId == null &&
                        streamTracker.activeSpkStreamId == null
                }
                if (shouldFinish) {
                    finishFromRemote(reason)
                }
            }
        }
    }

    private fun scheduleNoStreamHint() {
        if (noStreamHintDelayMs <= 0L) return
        synchronized(trackerLock) {
            if (closing || streamTracker.hadAnyStream) return
            noStreamHintJob?.cancel()
            noStreamHintJob = scope.launch {
                delay(noStreamHintDelayMs)
                val shouldWarn = synchronized(trackerLock) {
                    !closing && active && !mutableUi.value.resuming && !streamTracker.hadAnyStream
                }
                if (shouldWarn) {
                    Log.i(TAG, "${host.name}: no agy audio stream detected after ${noStreamHintDelayMs}ms")
                    onNotice(tr("未检测到 agy 语音流，请查看终端状态"))
                }
            }
        }
    }

    private fun finishFromRemote(reason: VoiceRemoteEndReason) {
        if (closing || !active) return
        val notice = when (reason) {
            VoiceRemoteEndReason.ABORTED_EARLY -> tr("agy 语音已中断，请查看终端输出")
            VoiceRemoteEndReason.ENDED -> tr("通话已结束")
        }
        Log.i(TAG, "${host.name}: remote agy streams closed ($reason), auto-ending live call")
        if (hangupInternal(keepFailure = false)) {
            onNotice(notice)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && !closing) {
                delay(1000)
                dispatch(VoiceEvent.TickSecond)
            }
        }
    }

    private fun dispatch(event: VoiceEvent) {
        synchronized(mutableUi) {
            mutableUi.value = VoiceStateMachine.reduce(mutableUi.value, event)
        }
    }

    private fun line(text: String) {
        log.value = (log.value + text).takeLast(120)
    }

    private suspend fun runLoop(mode: VoiceMode) {
        var recoveringSince: Long? = null
        try {
            while (!closing) {
                val outcome = attempt(mode)
                Log.i(TAG, "${host.name}: voice attempt ended $outcome")
                when (outcome) {
                    Outcome.Closed -> {
                        if (mutableUi.value.phase !is VoiceCallPhase.Failed) {
                            dispatch(VoiceEvent.Hangup)
                        }
                        break
                    }
                    Outcome.Kicked -> {
                        dispatch(VoiceEvent.Fail(tr("语音通道已在另一台设备上接管")))
                        break
                    }
                    is Outcome.Failed -> {
                        dispatch(VoiceEvent.Fail(outcome.message))
                        break
                    }
                    is Outcome.Dropped -> {
                        if (!everConnected) {
                            dispatch(VoiceEvent.Fail(outcome.message ?: tr("无法连接语音通道")))
                            break
                        }
                        val since = recoveringSince ?: System.currentTimeMillis().also { recoveringSince = it }
                        if (System.currentTimeMillis() - since > RECOVER_LIMIT_MS) {
                            dispatch(VoiceEvent.Fail(tr("语音连接中断超时，已断开")))
                            break
                        }
                        beginRecovery()
                        dispatch(VoiceEvent.Resuming(true))
                        delay(1000)
                    }
                    Outcome.Streamed -> {
                        recoveringSince = null
                        if (!closing) {
                            beginRecovery()
                            dispatch(VoiceEvent.Resuming(true))
                            delay(500)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                cancelStreamTimers()
                timerJob?.cancel()
                clearSecrets()
                engine.release()
                activeMicPort = null
                if (closing && mutableUi.value.phase !is VoiceCallPhase.Failed) {
                    val fail = fatalFailure
                    if (fail != null) dispatch(VoiceEvent.Fail(fail))
                    else dispatch(VoiceEvent.Hangup)
                }
                onEnded(this@VoiceConnection)
            }
        }
    }

    private sealed interface Outcome {
        data object Closed : Outcome
        data object Kicked : Outcome
        data class Failed(val message: String) : Outcome
        data class Dropped(val message: String? = null) : Outcome
        data object Streamed : Outcome
    }

    private suspend fun attempt(mode: VoiceMode): Outcome {
        var outcome: Outcome? = null
        val result = transport.administer(
            host = host,
            password = password,
            passphrase = passphrase,
            pending = pending,
            onHandle = { disconnect -> disconnectLogin = disconnect },
        ) { remote ->
            outcome = loggedIn(remote, mode)
        }
        if (result.credentialFailed) {
            return Outcome.Failed(tr("无法读取凭据，请重新导入密钥或保存密码。"))
        }
        fatalFailure?.let { return Outcome.Failed(it) }
        if (closing) return Outcome.Closed
        return outcome ?: run {
            val msg = result.message.takeIf { it.isNotBlank() }?.let(::tr)
            if (everConnected || result.phase == Phase.CONNECTING) Outcome.Dropped(msg)
            else Outcome.Failed(msg ?: tr("无法连接服务器"))
        }
    }

    private suspend fun loggedIn(remote: RemoteHost, mode: VoiceMode): Outcome {
        val probe = remote.probeDesktop()
        if (closing) return Outcome.Closed
        if (probe == null || probe.uname.isBlank()) {
            return Outcome.Failed(tr("无法检测这台主机的系统环境"))
        }
        val target = transport.targetFor(probe.uname)
            ?: return Outcome.Failed(tr("暂不支持这台主机的系统"))
        windowsHost = target.first.startsWith("windows-")
        // Pure voice mode installs headless: skips OpenH264 download and GUI/LaunchAgent/service setup.
        var installedThisAttempt = false
        if (probe.version != transport.manifestVersion) {
            dispatch(VoiceEvent.StepChanged(VoiceStep.INSTALL))
            val failure = transport.install(remote, target.first, target.second, ::line)
            if (failure != null) return Outcome.Failed(failure)
            installedThisAttempt = true
            transport.markDesktopPluginInstalled(host)
        }
        var tries = 0
        while (!closing && tries++ < 3) {
            dispatch(VoiceEvent.StepChanged(VoiceStep.START))
            val s = remote.openStream(voiceCommand()) ?: return Outcome.Dropped()
            stream = s
            dispatch(VoiceEvent.StepChanged(VoiceStep.HANDSHAKE))
            val first = readHandshake(s)
            if (first.line == null) {
                if (first.timedOut) return Outcome.Dropped(tr("语音插件握手超时"))
                val status = silentExit(s)
                // Exit 127 / 9009: binary missing; exit 2: older plugin lacking the `voice` subcommand.
                if (!installedThisAttempt && (status == 127 || status == WindowsDesktop.NOT_FOUND || status == EXIT_UNSUPPORTED_SUBCOMMAND)) {
                    dispatch(VoiceEvent.StepChanged(VoiceStep.INSTALL))
                    val failure = transport.install(remote, target.first, target.second, ::line)
                    if (failure != null) return Outcome.Failed(failure)
                    installedThisAttempt = true
                    transport.markDesktopPluginInstalled(host)
                    continue
                }
                return if (s.closed && status != null) Outcome.Failed(voiceStartExitMessage(status))
                else Outcome.Dropped()
            }
            when (val start = VoiceProtocol.parseStart(first.line)) {
                is VoiceStart.Failed -> {
                    s.close()
                    when (start.code) {
                        "proto_unsupported" -> {
                            if (installedThisAttempt) {
                                return Outcome.Failed(trf("语音插件出错：%s", start.detail ?: start.code))
                            }
                            dispatch(VoiceEvent.StepChanged(VoiceStep.INSTALL))
                            val failure = transport.install(remote, target.first, target.second, ::line)
                            if (failure != null) return Outcome.Failed(failure)
                            installedThisAttempt = true
                            transport.markDesktopPluginInstalled(host)
                        }
                        else -> return Outcome.Failed(trf("语音插件出错：%s", start.detail ?: start.code))
                    }
                }
                is VoiceStart.Ready -> {
                    transport.markDesktopPluginInstalled(host)
                    val result = runStream(remote, s, start.hello, mode)
                    if (closing && cleanedEnv.compareAndSet(false, true) && isCurrentOwner()) {
                        withContext(NonCancellable) {
                            withTimeoutOrNull(2_000) {
                                runCatching { VoiceEnvironment.clear(remote, windowsHost, tmuxSessions()) }
                            }
                        }
                    }
                    return result
                }
            }
        }
        return if (closing) Outcome.Closed else Outcome.Failed(tr("语音插件启动失败"))
    }

    private fun voiceCommand(): String {
        val args = VoiceProtocol.voiceArgs(
            proto = VoiceProtocol.PROTO_VERSION,
            port = VoiceProtocol.DEFAULT_MIC_PORT,
            resumeSession = remoteSessionId,
            resumeToken = resumeToken,
            idleTimeoutSec = VoiceProtocol.DEFAULT_IDLE_TIMEOUT_SEC,
        )
        return if (windowsHost) WindowsDesktop.voiceCommand(args)
        else "\"\$HOME/.roamtty/bin/roamtty-desktop\" voice $args"
    }

    private suspend fun runStream(remote: RemoteHost, s: RemoteStream, hello: VoiceHello, mode: VoiceMode): Outcome {
        val previousSession = remoteSessionId
        if (!previousSession.isNullOrEmpty() && hello.session.isNotEmpty() && hello.session != previousSession) {
            Log.w(TAG, "${host.name}: voice resume returned new session=${hello.session} (was $previousSession)")
            line("voice session changed on resume: $previousSession -> ${hello.session}")
        }
        remoteSessionId = hello.session.ifEmpty { remoteSessionId }
        resumeToken = hello.resumeToken.ifEmpty { resumeToken }
        activeMicPort = hello.micPort

        val active = ActiveStream(s)
        activeStream = active
        ping.reset()
        goodSinceMs = 0L
        lastSpkStats = null
        lastUplinkStats = null
        lastStallRebindMs = 0L
        synchronized(micOpusLock) { micSender = null }
        voiceOpusEnabled = hello.codecs.any { it.equals("opus", ignoreCase = true) }
        var writer: Job? = null
        var ticker: Job? = null
        try {
            if (voiceOpusEnabled) {
                sendSsh(VoiceProtocol.config(mapOf("voice_codec" to "opus")))
                Log.i(TAG, "${host.name}: negotiated Opus+RED voice codec")
            }
            startUdp(hello)
            if (closing) return Outcome.Closed

            // Inject ANTIGRAVITY_MIC into ~/.roamtty/agy (or Windows agy.ps1) and tmux/psmux sessions before sending F6.
            VoiceEnvironment.inject(remote, windowsHost, hello.micPort, tmuxSessions())
            if (closing) return Outcome.Closed

            // Refresh Bluetooth availability and enter MODE_IN_COMMUNICATION + communication routing right before
            // triggering F6 so any superseded call has already finished tearing down its Bluetooth SCO/BLE route.
            dispatch(VoiceEvent.BluetoothChanged(engine.isBluetoothAvailable()))
            engine.beginSession(mutableUi.value.audioRoute)
            if (closing) return Outcome.Closed

            everConnected = true
            val initialLink = if (link?.alive == true) "UDP" else "SSH"
            dispatch(VoiceEvent.Ready(hello.micPort, initialLink))
            if (!readyNotified) {
                val readyResult = withContext(mainDispatcher) {
                    if (closing) return@withContext VoiceReadyOutcome.SESSION_GONE
                    onReady(mode, hello.micPort).also {
                        if (it == VoiceReadyOutcome.INJECTED) readyNotified = true
                    }
                }
                when (readyResult) {
                    VoiceReadyOutcome.INJECTED -> scheduleNoStreamHint()
                    VoiceReadyOutcome.SESSION_GONE -> {
                        closingFlag.set(true)
                        closing = true
                        return Outcome.Closed
                    }
                    VoiceReadyOutcome.AGY_EXITED -> {
                        val msg = tr("当前终端已退出 agy，已取消语音连接")
                        fatalFailure = msg
                        closingFlag.set(true)
                        closing = true
                        return Outcome.Failed(msg)
                    }
                }
            }
            if (closing) return Outcome.Closed

            coroutineScope {
                writer = launch(Dispatchers.IO) {
                    try {
                        for (@Suppress("UNUSED_VARIABLE") ignored in active.signal) {
                            drainSshQueue(active)
                        }
                        drainSshQueue(active)
                    } catch (_: Exception) {
                        runCatching { s.close() }
                    }
                }
                ticker = launch {
                    var ticks = 0
                    while (true) {
                        val l = link
                        val transportName = if (l != null && l.alive) "UDP" else "SSH"
                        if (mutableUi.value.link != transportName) dispatch(VoiceEvent.LinkChanged(transportName))
                        if (ticks % PING_EVERY_TICKS == 0) ping.next()
                        ping.tick()
                        val nowMs = System.currentTimeMillis()
                        // Report speaker-side voice stats every 250 ms so host adapts Opus bitrate & RED depth live.
                        if (voiceOpusEnabled) {
                            mutableUi.value.activeSpkStreamId?.let { spkId ->
                                engine.speakerStats(spkId, clockMs())?.let { st ->
                                    lastSpkStats = st
                                    send(VoiceProtocol.voiceStats(st), fecRatio = 0f)
                                }
                            }
                        }
                        // Fast UDP stall probe: rebind after 700 ms silence instead of waiting full 3 s ALIVE_US.
                        if (l != null && l.alive && l.silentUs > UDP_STALL_REBIND_US && nowMs - lastStallRebindMs >= UDP_STALL_REBIND_COOLDOWN_MS) {
                            lastStallRebindMs = nowMs
                            launch(Dispatchers.IO) {
                                runCatching { l.rebind() }.onFailure { Log.w(TAG, "${host.name}: voice UDP stall rebind failed", it) }
                            }
                        }
                        updateLinkQuality(nowMs)
                        ticks++
                        delay(TICK_INTERVAL_MS)
                    }
                }
                try {
                    withContext(Dispatchers.IO) { readFrames(active) }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (closing) {
                            finishShutdownBeforeClose(active, writer)
                        }
                        active.closeInput()
                        writer?.cancel()
                        ticker?.cancel()
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (closing) {
                    finishShutdownBeforeClose(active, writer)
                }
                activeStream = null
                active.closeInput()
                writer?.cancel()
                ticker?.cancel()
                runCatching { s.close() }
                stream = null
                runCatching { link?.close() }
                link = null
                engine.stopMic()
                engine.stopSpeaker()
            }
        }
        return when {
            fatalFailure != null -> Outcome.Failed(fatalFailure!!)
            kicked -> Outcome.Kicked
            closing -> Outcome.Closed
            else -> Outcome.Streamed
        }
    }

    private fun drainSshQueue(active: ActiveStream) {
        while (true) {
            val (bytes, emptyAfter) = active.pollNext() ?: break
            val isShutdown = bytes === active.shutdownFrameRef
            synchronized(active.writeLock) {
                if (isShutdown && !active.shutdownFlushed.compareAndSet(false, true)) return@synchronized
                active.out.write(bytes)
                if (emptyAfter || isShutdown) active.out.flush()
            }
        }
    }

    /**
     * Guarantees `CONFIG {"shutdown":true}` is sent on UDP + flushed on SSH and gives the host a brief
     * grace window (exiting early on stream EOF) BEFORE closing `s` and `link`.
     */
    private suspend fun finishShutdownBeforeClose(active: ActiveStream, writer: Job?) {
        enqueueShutdownFrame(active, link)
        active.closeInput()
        if (writer != null && writer.isActive) {
            withTimeoutOrNull(minOf(60L, shutdownGraceMs)) { writer.join() }
        }
        flushShutdownDirectIfNeeded(active)
        if (!active.eofReached.isCompleted && !active.stream.closed) {
            val elapsed = System.currentTimeMillis() - active.shutdownStartedAtMs
            val remaining = (shutdownGraceMs - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) {
                val eofProbe = shutdownScope.launch {
                    try {
                        val buf = ByteArray(256)
                        while (!active.stream.closed && !active.eofReached.isCompleted) {
                            if (active.input.read(buf) < 0) {
                                active.eofReached.complete(Unit)
                                break
                            }
                        }
                    } catch (_: Exception) {
                        active.eofReached.complete(Unit)
                    }
                }
                withTimeoutOrNull(remaining) { active.eofReached.await() }
                eofProbe.cancel()
            }
        }
    }

    private fun enqueueShutdownFrame(active: ActiveStream?, l: UdpLink?): ByteArray? {
        val shouldSend = active?.shutdownQueued?.compareAndSet(false, true)
            ?: (l != null && l.alive && fallbackShutdownSent.compareAndSet(false, true))
        if (!shouldSend) return active?.shutdownFrameRef
        val shutdownFrame = VoiceProtocol.config(mapOf("shutdown" to true))
        if (active != null) {
            active.shutdownFrameRef = shutdownFrame
            active.shutdownStartedAtMs = System.currentTimeMillis()
        }
        if (l != null && l.alive) {
            runCatching { l.send(shutdownFrame, reliable = true, fecRatio = 1.0f) }
        }
        active?.enqueue(shutdownFrame)
        return shutdownFrame
    }

    private fun flushShutdownDirectIfNeeded(active: ActiveStream) {
        if (active.shutdownFlushed.get()) return
        val frame = active.shutdownFrameRef ?: VoiceProtocol.config(mapOf("shutdown" to true)).also {
            active.shutdownFrameRef = it
            if (active.shutdownStartedAtMs == 0L) active.shutdownStartedAtMs = System.currentTimeMillis()
        }
        runCatching {
            synchronized(active.writeLock) {
                if (active.shutdownFlushed.compareAndSet(false, true)) {
                    active.out.write(frame)
                    active.out.flush()
                }
            }
        }
    }

    private suspend fun readFrames(active: ActiveStream) {
        while (!closing) {
            val frame = try {
                DesktopProtocol.read(active.input)
            } catch (_: Exception) {
                null
            }
            if (frame == null) {
                active.eofReached.complete(Unit)
                break
            }
            val message = try {
                VoiceProtocol.decode(frame)
            } catch (e: Exception) {
                Log.w(TAG, "bad voice frame", e)
                null
            } ?: continue
            handle(message)
            if (kicked) break
        }
        // Keep running over UDP alone if SSH exec closes while UDP remains alive.
        if (!closing && !kicked && link?.alive == true) {
            Log.i(TAG, "${host.name}: voice SSH channel closed, staying on UDP")
            active.closeInput()
            while (!closing && !kicked && link?.alive == true) delay(200)
        }
    }

    private suspend fun startUdp(hello: VoiceHello) {
        val offer = hello.udp ?: return
        val override = transport.udpHostOverride
        val address = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getByName(override.ifEmpty { host.address }) }.getOrNull()
        } ?: return
        val l = UdpLink(
            host = address,
            port = offer.port,
            key = offer.key,
            onFrame = { frame -> runCatching { VoiceProtocol.decode(frame) }.getOrNull()?.let(::handle) },
            onVideoLost = {},
            log = { Log.i(TAG, "${host.name}: voice $it") },
            onGiveUp = { frame -> sendSsh(frame) },
        )
        val loss = transport.testLoss
        if (loss > 0) {
            l.dropRate = loss
            sendSsh(VoiceProtocol.config(mapOf("debug_loss" to loss)))
        }
        withContext(Dispatchers.IO) { l.start() }
        link = l
        sendSsh(VoiceProtocol.config(mapOf("udp_client_port" to l.localPort)))
        Log.i(TAG, "${host.name}: voice UDP offered on ${address.hostAddress}:${offer.port}")
    }

    private fun handle(message: VoiceServerMessage) {
        synchronized(handleLock) {
            if (closing) return
            when (message) {
                is VoiceServerMessage.MicStart -> {
                    Log.i(TAG, "${host.name}: MIC_START stream=${message.streamId} rate=${message.sampleRate} ch=${message.channels}")
                    synchronized(micOpusLock) {
                        micSender = if (voiceOpusEnabled && VoiceOpusMath.isOpusSampleRate(message.sampleRate)) {
                            VoiceRedSender(message.streamId, message.sampleRate, enableDtx = true)
                        } else null
                    }
                    if (!engine.startMic(message.streamId, message.sampleRate, message.channels)) {
                        if (closing) return
                        failFromAudio(tr("无法启动麦克风，请检查麦克风权限"))
                        return
                    }
                    if (closing) {
                        engine.stopMic(message.streamId)
                        return
                    }
                    dispatch(VoiceEvent.MicStarted(message.streamId))
                    val action = synchronized(trackerLock) { streamTracker.onMicStarted(message.streamId, clockMs()) }
                    handleTrackerAction(action)
                }
                is VoiceServerMessage.MicStop -> {
                    Log.i(TAG, "${host.name}: MIC_STOP stream=${message.streamId}")
                    synchronized(micOpusLock) {
                        if (micSender?.streamId == message.streamId) micSender = null
                    }
                    engine.stopMic(message.streamId)
                    dispatch(VoiceEvent.MicStopped(message.streamId))
                    val action = synchronized(trackerLock) { streamTracker.onMicStopped(message.streamId, clockMs()) }
                    handleTrackerAction(action)
                }
                is VoiceServerMessage.SpkStart -> {
                    Log.i(TAG, "${host.name}: SPK_START stream=${message.streamId} rate=${message.sampleRate} ch=${message.channels}")
                    val err = engine.startSpeaker(message.streamId, message.sampleRate, message.channels)
                    if (closing) {
                        if (err == null) engine.stopSpeaker(message.streamId)
                        return
                    }
                    if (err == null) {
                        dispatch(VoiceEvent.SpkStarted(message.streamId))
                        val action = synchronized(trackerLock) { streamTracker.onSpkStarted(message.streamId, clockMs()) }
                        handleTrackerAction(action)
                    } else {
                        Log.w(TAG, "${host.name}: SPK_START failed: $err")
                    }
                    send(VoiceProtocol.spkAck(message.streamId, ok = err == null, error = err.orEmpty()), fecRatio = 1.0f)
                }
                is VoiceServerMessage.SpkAudio -> {
                    if (mutableUi.value.activeSpkStreamId != message.streamId) return
                    // Speaker level & Speaking/Listening transitions are driven solely by VoiceAudioEngine's smoothed onSpeakerLevel.
                    engine.writeSpeaker(message.streamId, message.data, message.offset, message.length)
                    synchronized(trackerLock) { streamTracker.onSpkAudio(message.length) }
                }
                is VoiceServerMessage.SpkClear -> {
                    Log.i(TAG, "${host.name}: SPK_CLEAR stream=${message.streamId}")
                    engine.clearSpeaker(message.streamId)
                    dispatch(VoiceEvent.SpkCleared(message.streamId))
                    synchronized(trackerLock) { streamTracker.onSpkCleared() }
                }
                is VoiceServerMessage.SpkStop -> {
                    Log.i(TAG, "${host.name}: SPK_STOP stream=${message.streamId}")
                    engine.stopSpeaker(message.streamId)
                    dispatch(VoiceEvent.SpkStopped(message.streamId))
                    val action = synchronized(trackerLock) { streamTracker.onSpkStopped(message.streamId, clockMs()) }
                    handleTrackerAction(action)
                }
                is VoiceServerMessage.SpkOpus -> {
                    val pkt = message.packet
                    if (mutableUi.value.activeSpkStreamId != pkt.streamId) return
                    engine.writeSpeakerOpus(pkt.streamId, pkt)
                    synchronized(trackerLock) { streamTracker.onSpkAudio(pkt.primary.size.coerceAtLeast(1)) }
                }
                is VoiceServerMessage.VoiceStatsMsg -> {
                    lastUplinkStats = message.stats
                    synchronized(micOpusLock) {
                        micSender?.takeIf { it.streamId == message.stats.streamId }?.onPeerStats(message.stats, clockMs())
                    }
                }
                is VoiceServerMessage.State -> {
                    if (message.kicked) {
                        kicked = true
                        stream?.close()
                    }
                    message.error?.let(::line)
                }
                is VoiceServerMessage.Ping -> {
                    val now = System.nanoTime() / 1000
                    send(VoiceProtocol.pong(message.t1, now, now))
                }
                is VoiceServerMessage.Pong -> {
                    ping.answer(message.t1)
                }
            }
        }
    }

    /** Two missed pings in a row, slow answer, UDP stall, or high voice packet loss marks the link unstable; 5 clean seconds restore it. */
    private fun updateLinkQuality(now: Long) {
        val rtt = ping.rttMs
        val l = link
        val voiceLoss = maxOf(
            (lastSpkStats?.lossPermille ?: 0) / 1000.0,
            (lastUplinkStats?.lossPermille ?: 0) / 1000.0,
        )
        val bad = if (l != null && l.alive) {
            l.silentUs > 700_000 || maxOf(l.loss, voiceLoss) > 0.20 || rtt > DesktopConnection.POOR_MS
        } else {
            ping.missed >= 2 || voiceLoss > 0.20 || rtt > DesktopConnection.POOR_MS
        }
        if (bad) goodSinceMs = 0L else if (goodSinceMs == 0L) goodSinceMs = now
        val wasPoor = mutableUi.value.networkPoor
        val poor = bad || (wasPoor && now - goodSinceMs < POOR_RECOVER_MS)
        if (poor != wasPoor) {
            Log.i(TAG, "${host.name}: voice networkPoor=$poor (udp=${l?.alive} silent=${l?.silentUs?.div(1000)}ms loss=${maxOf(l?.loss ?: 0.0, voiceLoss)} rtt=$rtt missed=${ping.missed})")
            dispatch(VoiceEvent.NetworkQualityChanged(poor))
        }
    }

    /** Encodes 20 ms capture reads into Opus+RED frames when negotiated (single UDP shard, DTX), else legacy PCM16. */
    private fun sendMicPcm(streamId: Long, pcm: ByteArray, offset: Int, length: Int) {
        if (closing || mutableUi.value.isMuted) return
        val slices = VoiceAudioMath.sliceSampleAligned(offset, length, VoiceProtocol.MIC_FRAME_CHUNK_BYTES)
        if (voiceOpusEnabled) {
            for ((start, len) in slices) {
                val pkt = synchronized(micOpusLock) {
                    micSender?.takeIf { it.streamId == streamId }?.encodePcm20ms(pcm, start, len)
                }
                if (pkt != null) {
                    send(VoiceProtocol.micOpus(pkt), fecRatio = 0f)
                }
            }
            return
        }
        for ((start, len) in slices) {
            val frame = VoiceProtocol.micAudio(streamId, pcm, start, len)
            send(frame, fecRatio = MIC_UDP_FEC_RATIO)
        }
    }

    private fun send(bytes: ByteArray, fecRatio: Float = 0f) {
        val reliable = VoiceProtocol.isReliable(bytes)
        val l = link
        if (l != null && l.alive) {
            l.send(bytes, reliable, fecRatio)
        } else {
            l?.giveUp()
            sendSsh(bytes)
        }
    }

    private fun sendSsh(bytes: ByteArray) {
        activeStream?.enqueue(bytes)
    }

    private class Handshake(val line: String?, val timedOut: Boolean = false)

    private suspend fun readHandshake(s: RemoteStream): Handshake = try {
        Handshake(withTimeout(HANDSHAKE_MS) {
            val reader = async(Dispatchers.IO) { try { DesktopProtocol.readLine(s.input) } catch (_: Exception) { null } }
            try { reader.await() } catch (e: CancellationException) { s.close(); throw e }
        })
    } catch (_: TimeoutCancellationException) {
        s.close()
        line(tr("语音插件握手超时"))
        Handshake(null, timedOut = true)
    } catch (_: Exception) {
        Handshake(null)
    }

    private suspend fun silentExit(s: RemoteStream): Int? {
        val status = withTimeoutOrNull(3000) { while (!s.closed) delay(30); s.exitStatus }
        val err = ByteArray(16 * 1024)
        var errLen = 0
        withTimeoutOrNull(1000) {
            runInterruptible(Dispatchers.IO) {
                try {
                    while (errLen < err.size) {
                        val r = s.errors.read(err, errLen, err.size - errLen)
                        if (r < 0) break
                        errLen += r
                    }
                } catch (_: IOException) {}
            }
        }
        val errors = err.copyOf(errLen).toString(Charsets.UTF_8).trim()
        s.close()
        if (errors.isNotEmpty()) line(errors.takeLast(500))
        return status
    }

    companion object {
        private const val TAG = "RoamttyVoice"
        private const val HANDSHAKE_MS = 25_000L
        private const val RECOVER_LIMIT_MS = 290_000L // Within plugin's 300s default --idle-timeout
        private const val SHUTDOWN_GRACE_MS = 120L
        private const val TICK_INTERVAL_MS = 250L
        private const val PING_EVERY_TICKS = 4 // Every ~1s
        private const val POOR_RECOVER_MS = 5_000L
        private const val SSH_SEND_CAP = 128
        internal const val MIC_SSH_CAP = 6
        private const val MIC_UDP_FEC_RATIO = 1.0f
        private const val UDP_STALL_REBIND_US = 700_000L
        private const val UDP_STALL_REBIND_COOLDOWN_MS = 2_000L
        internal const val EXIT_UNSUPPORTED_SUBCOMMAND = 2

        /** Detached IO scope so `SessionService.onDestroy()` cancelling its scope cannot abort `shutdown: true` delivery. */
        private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun isMicAudio(frame: ByteArray): Boolean {
            if (frame.isEmpty()) return false
            val t = frame[0].toInt() and 0xFF
            return t == VoiceProtocol.MIC_AUDIO || t == VoiceProtocol.MIC_OPUS
        }

        /** User-facing failure message when `roamtty-desktop voice` exits before writing `hello`. */
        internal fun voiceStartExitMessage(status: Int): String = if (status == EXIT_UNSUPPORTED_SUBCOMMAND) {
            tr("主机插件版本过旧，不支持语音模式，请更新插件")
        } else {
            trf("语音插件启动失败（退出码 %d）", status)
        }
    }
}
