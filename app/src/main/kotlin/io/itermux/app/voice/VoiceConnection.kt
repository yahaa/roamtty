package io.itermux.app.voice

import android.util.Log
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.data.credentialsFor
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

/**
 * One host's `roamtty-desktop voice` bridge: launches the headless `voice` subcommand over SSH
 * (without OpenH264 or GUI/LaunchAgent setup), negotiates `UdpLink` with SSH `exec` stdin/stdout
 * fallback, injects `ANTIGRAVITY_MIC=127.0.0.1:<mic_port>` into `agy` launchers and tmux sessions,
 * bridges PCM frames with [VoiceAudioEngine], and supports `--resume` reconnects across network drops.
 */
internal class VoiceConnection(
    val host: Host,
    val sessionId: String,
    private val app: ItermuxApp,
    private val scope: CoroutineScope,
    private var password: String,
    private var passphrase: String,
    private val tmuxSessions: () -> List<String>,
    private val onReady: (VoiceMode, Int) -> Unit,
    private val onEnded: (VoiceConnection) -> Unit,
) {
    private val settings = DesktopSettings(app)
    private val manifest by lazy { DesktopSettings.manifest(app) }
    private val setup by lazy { DesktopPluginSetup(manifest, ::line) }

    private val mutableUi = MutableStateFlow(VoiceUiState())
    val ui = mutableUi.asStateFlow()
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())

    private val engine = VoiceAudioEngine(
        context = app,
        onMicChunk = ::sendMicPcm,
        onMicLevel = { level -> dispatch(VoiceEvent.MicLevel(level)) },
        onSpeakerLevel = { level -> dispatch(VoiceEvent.SpeakerLevel(level)) },
    )

    @Volatile private var outgoing: Channel<ByteArray>? = null
    private val sshQueued = AtomicInteger()
    @Volatile private var link: UdpLink? = null
    @Volatile private var stream: RemoteStream? = null
    @Volatile private var ssh: JschSession? = null
    @Volatile private var closing = false
    @Volatile private var kicked = false
    @Volatile private var everConnected = false
    @Volatile private var readyNotified = false
    @Volatile private var windowsHost = false
    @Volatile private var remoteSessionId: String? = null
    @Volatile private var resumeToken: String? = null
    @Volatile private var activeMicPort: Int? = null
    private val cleanedEnv = AtomicBoolean(false)

    private var job: Job? = null
    private var timerJob: Job? = null

    val active: Boolean get() = mutableUi.value.inCall
    val micPort: Int? get() = activeMicPort

    fun isBluetoothAvailable(): Boolean = engine.isBluetoothAvailable()

    fun start(mode: VoiceMode, route: AudioRoute) {
        if (job?.isActive == true) return
        closing = false
        kicked = false
        everConnected = false
        readyNotified = false
        cleanedEnv.set(false)
        val bt = engine.isBluetoothAvailable()
        dispatch(VoiceEvent.Start(mode, route, bluetoothAvailable = bt))
        engine.muted = false
        engine.beginSession(route)
        startTimer()
        job = scope.launch(Dispatchers.IO) { runLoop(mode) }
    }

    fun toggleMute(): Boolean {
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

    fun networkChanged() {
        if (!everConnected || closing) return
        val l = link
        if (l != null && l.alive) {
            Log.i(TAG, "${host.name}: network changed, rebinding voice UDP")
            l.rebind()
            return
        }
        Log.i(TAG, "${host.name}: network changed, reconnecting voice with --resume")
        stream?.close()
        ssh?.disconnect()
    }

    fun hangup() {
        closing = true
        timerJob?.cancel()
        pending.value?.answer?.complete(null)
        engine.release()
        val hadStream = stream != null
        stream?.close()
        link?.close()
        // If still logging in (no active stream), drop SSH immediately; otherwise let runStream exit and clear env first.
        if (!hadStream) ssh?.disconnect()
        dispatch(VoiceEvent.Hangup)
        if (job?.isActive != true) {
            onEnded(this)
        }
    }

    fun release() {
        password = ""
        passphrase = ""
        timerJob?.cancel()
        engine.release()
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
                        dispatch(VoiceEvent.Hangup)
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
                        dispatch(VoiceEvent.Resuming(true))
                        delay(1000)
                    }
                    Outcome.Streamed -> {
                        recoveringSince = null
                        if (!closing) {
                            dispatch(VoiceEvent.Resuming(true))
                            delay(500)
                        }
                    }
                }
            }
        } finally {
            timerJob?.cancel()
            engine.release()
            activeMicPort = null
            if (closing && mutableUi.value.phase !is VoiceCallPhase.Failed) {
                dispatch(VoiceEvent.Hangup)
            }
            onEnded(this)
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
        val credentials = try {
            app.vault.credentialsFor(host, password, passphrase)
        } catch (_: Exception) {
            return Outcome.Failed(tr("无法读取凭据，请重新导入密钥或保存密码。"))
        }
        val login = pageLogin(host, credentials, app.pins, pending)
        ssh = login
        var outcome: Outcome? = null
        try {
            login.administer { remote -> outcome = loggedIn(remote, mode) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "voice login failed", e)
        } finally {
            login.disconnect()
            ssh = null
        }
        if (closing) return Outcome.Closed
        return outcome ?: login.state.value.let { s ->
            val msg = s.message.takeIf { it.isNotBlank() }?.let(::tr)
            if (everConnected || s.phase == Phase.CONNECTING) Outcome.Dropped(msg)
            else Outcome.Failed(msg ?: tr("无法连接服务器"))
        }
    }

    private suspend fun loggedIn(remote: RemoteHost, mode: VoiceMode): Outcome {
        val probe = remote.probeDesktop()
        if (closing) return Outcome.Closed
        if (probe == null || probe.uname.isBlank()) {
            return Outcome.Failed(tr("无法检测这台主机的系统环境"))
        }
        val target = PluginManifest.targetFor(probe.uname)?.let { manifest.targets[it]?.let { t -> it to t } }
            ?: return Outcome.Failed(tr("暂不支持这台主机的系统"))
        windowsHost = target.first.startsWith("windows-")
        // Pure voice mode installs headless: skips OpenH264 download and GUI/LaunchAgent/service setup.
        var installedThisAttempt = false
        if (probe.version != manifest.version) {
            dispatch(VoiceEvent.StepChanged(VoiceStep.INSTALL))
            val failure = setup.install(remote, target.first, target.second, headless = true)
            if (failure != null) return Outcome.Failed(failure)
            installedThisAttempt = true
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
                    val failure = setup.install(remote, target.first, target.second, headless = true)
                    if (failure != null) return Outcome.Failed(failure)
                    installedThisAttempt = true
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
                            dispatch(VoiceEvent.StepChanged(VoiceStep.INSTALL))
                            val failure = setup.install(remote, target.first, target.second, headless = true)
                            if (failure != null) return Outcome.Failed(failure)
                        }
                        else -> return Outcome.Failed(trf("语音插件出错：%s", start.detail ?: start.code))
                    }
                }
                is VoiceStart.Ready -> {
                    val result = runStream(remote, s, start.hello, mode)
                    if (closing && cleanedEnv.compareAndSet(false, true)) {
                        withTimeoutOrNull(2_000) {
                            runCatching { VoiceEnvironment.clear(remote, windowsHost, tmuxSessions()) }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runStream(remote: RemoteHost, s: RemoteStream, hello: VoiceHello, mode: VoiceMode): Outcome {
        remoteSessionId = hello.session.ifEmpty { remoteSessionId }
        resumeToken = hello.resumeToken.ifEmpty { resumeToken }
        activeMicPort = hello.micPort
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        sshQueued.set(0)
        outgoing = channel

        startUdp(hello)
        // Inject ANTIGRAVITY_MIC into ~/.roamtty/agy (or Windows agy.ps1) and tmux/psmux sessions before sending F5/F6.
        VoiceEnvironment.inject(remote, windowsHost, hello.micPort, tmuxSessions())

        everConnected = true
        val initialLink = if (link?.alive == true) "UDP" else "SSH"
        dispatch(VoiceEvent.Ready(hello.micPort, initialLink))
        if (!readyNotified) {
            readyNotified = true
            withContext(Dispatchers.Main.immediate) { onReady(mode, hello.micPort) }
        }

        coroutineScope {
            val writer = launch(Dispatchers.IO) {
                val out = BufferedOutputStream(s.output, 64 * 1024)
                try {
                    for (bytes in channel) {
                        sshQueued.decrementAndGet()
                        out.write(bytes)
                        if (channel.isEmpty) out.flush()
                    }
                } catch (_: Exception) {
                    s.close()
                }
            }
            val ticker = launch {
                while (true) {
                    val l = link
                    val transportName = if (l != null && l.alive) "UDP" else "SSH"
                    if (mutableUi.value.link != transportName) dispatch(VoiceEvent.LinkChanged(transportName))
                    delay(500)
                }
            }
            try {
                withContext(Dispatchers.IO) { readFrames(s) }
            } finally {
                outgoing = null
                channel.close()
                writer.cancel()
                ticker.cancel()
                s.close()
                stream = null
                link?.close()
                link = null
                engine.stopMic()
                engine.stopSpeaker()
            }
        }
        return when {
            kicked -> Outcome.Kicked
            closing -> Outcome.Closed
            else -> Outcome.Streamed
        }
    }

    private suspend fun readFrames(s: RemoteStream) {
        val input = BufferedInputStream(s.input, 64 * 1024)
        while (!closing) {
            val frame = try { DesktopProtocol.read(input) } catch (_: Exception) { null } ?: break
            val message = try { VoiceProtocol.decode(frame) } catch (e: Exception) { Log.w(TAG, "bad voice frame", e); null } ?: continue
            handle(message)
            if (kicked) break
        }
        // Keep running over UDP alone if SSH exec closes while UDP remains alive.
        if (!closing && !kicked && link?.alive == true) {
            Log.i(TAG, "${host.name}: voice SSH channel closed, staying on UDP")
            outgoing = null
            while (!closing && !kicked && link?.alive == true) delay(200)
        }
    }

    private suspend fun startUdp(hello: VoiceHello) {
        val offer = hello.udp ?: return
        val override = settings.udpHostOverride
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
        val loss = settings.testLoss
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
        when (message) {
            is VoiceServerMessage.MicStart -> {
                Log.i(TAG, "${host.name}: MIC_START stream=${message.streamId} rate=${message.sampleRate} ch=${message.channels}")
                dispatch(VoiceEvent.MicStarted(message.streamId))
                engine.startMic(message.streamId, message.sampleRate, message.channels)
            }
            is VoiceServerMessage.MicStop -> {
                Log.i(TAG, "${host.name}: MIC_STOP stream=${message.streamId}")
                engine.stopMic(message.streamId)
                dispatch(VoiceEvent.MicStopped(message.streamId))
                if (!mutableUi.value.inCall) {
                    // Dictation finished cleanly on host FIN -> tear down voice stream and restore pet keyboard.
                    closing = true
                    stream?.close()
                    link?.close()
                }
            }
            is VoiceServerMessage.SpkStart -> {
                Log.i(TAG, "${host.name}: SPK_START stream=${message.streamId} rate=${message.sampleRate} ch=${message.channels}")
                val err = engine.startSpeaker(message.streamId, message.sampleRate, message.channels)
                send(VoiceProtocol.spkAck(message.streamId, ok = err == null, error = err.orEmpty()))
                if (err == null) {
                    dispatch(VoiceEvent.SpkStarted(message.streamId))
                } else {
                    Log.w(TAG, "${host.name}: SPK_START failed: $err")
                }
            }
            is VoiceServerMessage.SpkAudio -> {
                if (mutableUi.value.activeSpkStreamId != message.streamId) return
                engine.writeSpeaker(message.streamId, message.data, message.offset, message.length)
                val lvl = VoiceAudioMath.rmsLevel(message.data, message.offset, message.length)
                dispatch(VoiceEvent.SpkAudio(message.streamId, lvl))
            }
            is VoiceServerMessage.SpkClear -> {
                Log.i(TAG, "${host.name}: SPK_CLEAR stream=${message.streamId}")
                engine.clearSpeaker(message.streamId)
                dispatch(VoiceEvent.SpkCleared(message.streamId))
            }
            is VoiceServerMessage.SpkStop -> {
                Log.i(TAG, "${host.name}: SPK_STOP stream=${message.streamId}")
                engine.stopSpeaker(message.streamId)
                dispatch(VoiceEvent.SpkStopped(message.streamId))
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
            is VoiceServerMessage.Pong -> Unit
        }
    }

    /** Slices a 40 ms capture read into 20 ms (`<= 640 B`) frames so each fits 1 UDP shard with 1.0 FEC parity. */
    private fun sendMicPcm(streamId: Long, pcm: ByteArray, offset: Int, length: Int) {
        if (closing || mutableUi.value.isMuted) return
        val slices = VoiceAudioMath.sliceSampleAligned(offset, length, VoiceProtocol.MIC_FRAME_CHUNK_BYTES)
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
        val channel = outgoing ?: return
        if (sshQueued.get() >= SSH_SEND_CAP && !VoiceProtocol.isReliable(bytes)) return
        if (channel.trySend(bytes).isSuccess) sshQueued.incrementAndGet()
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
        private const val SSH_SEND_CAP = 128
        private const val MIC_UDP_FEC_RATIO = 1.0f
        internal const val EXIT_UNSUPPORTED_SUBCOMMAND = 2

        /** User-facing failure message when `roamtty-desktop voice` exits before writing `hello`. */
        internal fun voiceStartExitMessage(status: Int): String = if (status == EXIT_UNSUPPORTED_SUBCOMMAND) {
            tr("主机插件版本过旧，不支持语音模式，请更新插件")
        } else {
            trf("语音插件启动失败（退出码 %d）", status)
        }
    }
}
