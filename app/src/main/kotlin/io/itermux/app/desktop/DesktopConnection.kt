package io.itermux.app.desktop

import android.graphics.Bitmap
import android.util.Log
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.login.PendingPrompt
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.desktop.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import io.itermux.app.data.credentialsFor
import io.itermux.app.login.pageLogin

/**
 * One host's desktop (plan §4.1): its own SSH login, the plugin's `serve` on an exec channel, frames
 * to the [decoder], input from the page. It outlives the page: [pause] keeps it for the keep-alive time
 * without pictures, and a dropped link is resumed with the session token for up to 30 s.
 */
internal class DesktopConnection(
    val host: Host,
    private val app: ItermuxApp,
    private val scope: CoroutineScope,
    /** Typed secrets, kept in memory so a dropped link logs in again by itself; cleared in [release]. */
    private var password: String,
    private var passphrase: String,
    /** The phone's screen: its long edge caps the encode size. */
    private val clientWidth: Int,
    private val clientHeight: Int,
    private val clientDpi: Int,
    private val onEnded: (DesktopConnection) -> Unit,
) {
    private val settings = DesktopSettings(app)
    private val manifest by lazy { DesktopSettings.manifest(app) }
    private val setup by lazy { DesktopPluginSetup(manifest, ::line) }
    val phase = MutableStateFlow<DesktopPhase>(DesktopPhase.Idle)
    val pending = MutableStateFlow<PendingPrompt?>(null)
    private val mutableHello = MutableStateFlow<Hello?>(null)
    val hello = mutableHello.asStateFlow()
    val display = MutableStateFlow(0)
    val cursor = MutableStateFlow<RemoteCursor?>(null)
    val stats = MutableStateFlow(DesktopStats())
    /** The install and start log shown under the progress. */
    val log = MutableStateFlow<List<String>>(emptyList())
    /** Text the host's clipboard held when asked. */
    val remoteClipboard = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** One-off messages for the page, e.g. "请在本机解锁". */
    val notice = MutableStateFlow<String?>(null)
    val viewOnly = MutableStateFlow(false)
    val lockOnClose = MutableStateFlow(settings.lockOnClose(host.id))
    val quality = MutableStateFlow(settings.quality(host.id))
    val fps = MutableStateFlow(settings.fps(host.id))
    /** The host's sound: wanted by the user (this host's setting), and only sent by plugins that can. */
    val audio = MutableStateFlow(settings.audio(host.id))
    /** Null until the host said; false when it has no sound to send (an older plugin, or no audio server). */
    val audioAvailable = MutableStateFlow<Boolean?>(null)
    /** The video size of the last frame: the page's surface is fixed to it. */
    val videoSize = MutableStateFlow(0 to 0)

    val decoder = VideoDecoder(onDecoded = ::decoded, onRendered = { frame, us -> latency.onRendered(frame, us) },
        onNeedKeyframe = { askKeyframe() })
    private val latency = LatencyMeter()
    private val player = AudioPlayer()

    @Volatile private var outgoing: Channel<ByteArray>? = null
    /** Frames waiting in [outgoing]: past [SSH_SEND_CAP] pointer moves and other droppable frames are dropped. */
    private val sshQueued = AtomicInteger()
    /** The UDP transport once the host offered one; frames go over it while it is alive (plan §7.3 P2). */
    @Volatile private var link: UdpLink? = null
    @Volatile private var lastVideoFrame = -1L
    /** A frame went missing: later ones are dropped until the next keyframe. */
    @Volatile private var brokenUntilKey = false
    @Volatile private var lastKeyAsk = 0L
    @Volatile private var stream: RemoteStream? = null
    @Volatile private var ssh: JschSession? = null
    private var job: Job? = null
    private var keepAlive: Job? = null
    @Volatile private var closing = false
    @Volatile private var kicked = false
    @Volatile private var everConnected = false
    private var sessionId: String? = null
    private var resumeToken: String? = null
    private val received = ConcurrentHashMap<Long, Long>()
    /**
     * Cursor images by hash for the current stream. The plugin sends each shape once per stream, so none can be
     * evicted while it runs; a new stream sends them again, so the cache starts empty each time. Bitmaps are not
     * recycled: [cursor] and the page's draw may still hold one.
     */
    private val shapes = ConcurrentHashMap<Long, CursorImage>()
    private val ping = DesktopPing(::send)
    /** When the link was last found good after being poor; 0 while it is poor. */
    @Volatile private var goodSince = 0L
    private var frameWindow = 0
    private var byteWindow = 0L
    private var statsAt = System.currentTimeMillis()
    private var audioPacketsSeen = 0L
    /** A keyframe request came within a second of the last one and waits for the next tick; see [askKeyframe]. */
    @Volatile private var keyAskPending = false
    /** Per stream, for the log: inputs sent, and those view-only held back. */
    @Volatile private var inputsSent = 0
    @Volatile private var inputsDropped = 0
    /** The macOS agent was reloaded once for this connection after serve found it not running. */
    @Volatile private var agentReloaded = false
    /** One forced install per connection when Windows says nobody is signed in (see the not_logged_in case). */
    private var serviceInstallTried = false
    /** The host's shell is cmd.exe or PowerShell, a native Windows host (known once the probe answered). */
    @Volatile var windowsHost = false
        private set

    val active: Boolean get() = phase.value !is DesktopPhase.Idle && phase.value !is DesktopPhase.Failed

    fun start() {
        if (job?.isActive == true) return
        closing = false
        kicked = false
        agentReloaded = false
        serviceInstallTried = false
        phase.value = DesktopPhase.Connecting(DesktopPhase.Step.LOGIN)
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    private suspend fun runLoop() {
        var recoveringSince: Long? = null
        try {
            while (!closing) {
                val outcome = attempt()
                Log.i(TAG, "${host.name}: attempt ended $outcome")
                when (outcome) {
                    is Outcome.Closed -> { phase.value = DesktopPhase.Idle; break }
                    is Outcome.Kicked -> { phase.value = DesktopPhase.Failed(tr("已在另一台设备上打开")); break }
                    is Outcome.Failed -> { phase.value = DesktopPhase.Failed(outcome.message); break }
                    is Outcome.Dropped -> {
                        if (!everConnected) { phase.value = DesktopPhase.Failed(outcome.message ?: tr("无法连接桌面")); break }
                        val since = recoveringSince ?: System.currentTimeMillis().also { recoveringSince = it }
                        if (System.currentTimeMillis() - since > RECOVER_LIMIT_MS) {
                            phase.value = DesktopPhase.Failed(tr("连接中断超过 30 秒，桌面已断开"))
                            break
                        }
                        phase.value = DesktopPhase.Recovering(since)
                        delay(1000)
                    }
                    is Outcome.Streamed -> recoveringSince = null
                    // loggedIn keeps asking while the host needs the user; this only happens if that login dropped.
                    is Outcome.Needs -> delay(1500)
                }
                if (outcome is Outcome.Streamed && !closing) {
                    // The stream ran and ended by itself: try to get it back right away.
                    val since = System.currentTimeMillis()
                    recoveringSince = since
                    phase.value = DesktopPhase.Recovering(since)
                }
            }
        } finally {
            if (closing && phase.value !is DesktopPhase.Failed) phase.value = DesktopPhase.Idle
            keepAlive?.cancel()
            if (!active) onEnded(this)
        }
    }

    private sealed interface Outcome {
        data object Closed : Outcome
        data object Kicked : Outcome
        data class Failed(val message: String) : Outcome
        data class Dropped(val message: String? = null) : Outcome
        /** A stream ran and ended; the loop resumes it. */
        data object Streamed : Outcome
        /** The host needs the user first (macOS permissions); asked again shortly. */
        data object Needs : Outcome
    }

    private suspend fun attempt(): Outcome {
        val credentials = try { app.vault.credentialsFor(host, password, passphrase) } catch (e: Exception) {
            return Outcome.Failed(tr("无法读取凭据，请重新导入密钥或保存密码。"))
        }
        val login = pageLogin(host, credentials, app.pins, pending)
        ssh = login
        var outcome: Outcome? = null
        try {
            login.administer { remote -> outcome = loggedIn(remote) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "desktop login", e) }
        finally {
            login.disconnect()
            ssh = null
        }
        if (closing) return Outcome.Closed
        return outcome ?: login.state.value.let { s ->
            val message = s.message.takeIf { it.isNotBlank() }?.let(::tr)
            // A login that never got through is the host saying no, unless a live desktop is recovering.
            if (everConnected || s.phase == Phase.CONNECTING) Outcome.Dropped(message) else Outcome.Failed(message ?: tr("无法连接服务器"))
        }
    }

    private fun line(text: String) { log.value = (log.value + text).takeLast(200) }

    private suspend fun loggedIn(remote: RemoteHost): Outcome {
        val probe = awaitDesktop(remote)
        if (closing) return Outcome.Closed
        if (probe == null) return Outcome.Failed(tr("无法检测这台主机的桌面"))
        if (probe.desktop == "no") return Outcome.Failed(tr("这台主机没有图形桌面"))
        if (probe.desktop == "unsupported") return Outcome.Failed(tr("暂不支持 GNOME / KDE 的 Wayland 桌面，请改用 X11 会话"))
        val target = PluginManifest.targetFor(probe.uname)?.let { manifest.targets[it]?.let { t -> it to t } }
            ?: return Outcome.Failed(tr("暂不支持这台主机的系统"))
        windowsHost = target.first.startsWith("windows-")
        if (probe.version != manifest.version) install(remote, target.first, target.second)?.let { return it }
        var tries = 0
        while (!closing && tries++ < 3) {
            // While waiting on the user's permission, the guide stays up between tries.
            if (!everConnected && phase.value !is DesktopPhase.NeedsAction) phase.value = DesktopPhase.Connecting(DesktopPhase.Step.START)
            val s = remote.openStream(serveCommand()) ?: return Outcome.Dropped()
            stream = s
            val first = readHandshake(s)
            if (first.line == null) {
                // Closed by us: no exit status to report, and a later try may well get through.
                if (first.timedOut) return Outcome.Dropped(tr("桌面插件握手超时"))
                val status = silentExit(s)
                if (status == 127 || status == WindowsDesktop.NOT_FOUND) { install(remote, target.first, target.second)?.let { return it }; continue }
                return if (s.closed && status != null) Outcome.Failed(trf("桌面插件启动失败（退出码 %d）", status)) else Outcome.Dropped()
            }
            when (val start = DesktopProtocol.parseStart(first.line)) {
                is Start.Failed -> {
                    s.close()
                    when (start.code) {
                        "encoder_failed", "proto_unsupported" -> { install(remote, target.first, target.second, force = true)?.let { return it } }
                        "no_desktop" -> return Outcome.Failed(tr("这台主机没有图形桌面"))
                        "not_logged_in" -> {
                            // Someone is at the Mac's console, so the agent itself is gone (an upgrade
                            // that raced launchd unloaded it): load it again once instead of waiting.
                            if (target.first.startsWith("macos-") && probe.desktop == "yes" && !agentReloaded) {
                                agentReloaded = true
                                line(tr("重新启动桌面代理…"))
                                val ok = setup.registerAgent(remote)
                                Log.i(TAG, "${host.name}: agent not running, reloaded: $ok")
                                if (ok) { tries--; continue }
                            }
                            // Windows: the plugin is current but runs as a logon task. An install over an
                            // administrator's login sets the service up, which serves the sign-in screen.
                            if (windowsHost && !serviceInstallTried) {
                                serviceInstallTried = true
                                install(remote, target.first, target.second, force = true)?.let { return it }
                                tries--; continue
                            }
                            phase.value = DesktopPhase.NeedsAction(listOf("not-logged-in")); delay(1000)
                        }
                        else -> return Outcome.Failed(trf("桌面插件出错：%s", start.detail ?: start.code))
                    }
                }
                is Start.Ready -> {
                    val outcome = stream(s, start.hello)
                    if (outcome != Outcome.Needs) return outcome
                    // Every 1.5 s until the user allowed it on the host (plan §4.3: then it connects by itself).
                    tries = 0
                    delay(1500)
                }
            }
        }
        return if (closing) Outcome.Closed else Outcome.Failed(tr("桌面插件启动失败"))
    }

    /** What the host says of its desktop, asked again every second while nobody is signed in; null when it does not answer. */
    private suspend fun awaitDesktop(remote: RemoteHost): HostDesktop? {
        var probe = remote.probeDesktop()
        probe?.let { saveProbe(it) }
        if (probe?.uname?.startsWith("Windows") == true) windowsHost = true
        while (probe?.desktop == "nologin" && !closing) {
            phase.value = DesktopPhase.NeedsAction(listOf("not-logged-in"))
            delay(1000)
            probe = remote.probeDesktop()?.also { saveProbe(it) }
        }
        return probe
    }

    /** The plugin's first line; null when it said nothing, [timedOut] when that was because its time ran out. */
    private class Handshake(val line: String?, val timedOut: Boolean = false)

    private suspend fun readHandshake(s: RemoteStream): Handshake = try {
        Handshake(withTimeout(HANDSHAKE_MS) {
            // Closing the stream unblocks a silent plugin's readLine.
            val reader = async(Dispatchers.IO) { try { DesktopProtocol.readLine(s.input) } catch (_: Exception) { null } }
            try { reader.await() } catch (e: CancellationException) { s.close(); throw e }
        })
    } catch (_: TimeoutCancellationException) {
        s.close()
        line(tr("桌面插件握手超时"))
        Handshake(null, timedOut = true)
    } catch (_: Exception) { Handshake(null) }

    /** A plugin that ended without a word: its exit status (null while unknown), with what it wrote to stderr logged. */
    private suspend fun silentExit(s: RemoteStream): Int? {
        val status = withTimeoutOrNull(3000) { while (!s.closed) delay(30); s.exitStatus }
        // What stderr holds, at most 64 KiB; a plugin still running cannot hold this up past a second.
        val err = ByteArray(64 * 1024)
        var errLen = 0
        withTimeoutOrNull(1000) {
            runInterruptible(Dispatchers.IO) {
                try { while (errLen < err.size) { val r = s.errors.read(err, errLen, err.size - errLen); if (r < 0) break; errLen += r } }
                catch (_: IOException) {}
            }
        }
        val errors = err.copyOf(errLen).toString(Charsets.UTF_8).trim()
        s.close()
        if (errors.isNotEmpty()) line(errors.takeLast(500))
        return status
    }

    private suspend fun saveProbe(probe: HostDesktop) {
        runCatching { app.database.hosts().updateDesktop(host.id, host.address, host.port, host.username, probe.desktop, System.currentTimeMillis()) }
    }

    private fun serveCommand(): String {
        // Resume tokens are base64: no quotes or spaces to escape in either shell.
        val session = sessionId
        val token = resumeToken
        val resume = if (session != null && token != null) " --resume $session --token ${token.filter { it.isLetterOrDigit() || it in "+/=" }}" else ""
        val client = "${maxOf(clientWidth, clientHeight)}x${minOf(clientWidth, clientHeight)}@$clientDpi"
        val args = "--proto ${DesktopProtocol.SUPPORTED.last} --client $client$resume"
        return if (windowsHost) WindowsDesktop.serveCommand(args) else "\"\$HOME/.roamtty/bin/roamtty-desktop\" serve $args"
    }

    /** Installs or upgrades the pinned plugin; null once it is in place, else why not. */
    private suspend fun install(remote: RemoteHost, target: String, build: PluginManifest.Target, force: Boolean = false): Outcome? {
        phase.value = DesktopPhase.Connecting(DesktopPhase.Step.INSTALL)
        val failure = setup.install(remote, target, build)
        if (force) Log.i(TAG, "reinstalled after a start error: ${failure ?: "installed"}")
        return failure?.let(Outcome::Failed)
    }

    /** Starts a stream's bookkeeping afresh, opens UDP if the host offers it, and tells the host how to send. */
    private suspend fun beginStream(hello: Hello, channel: Channel<ByteArray>) {
        sessionId = hello.session
        resumeToken = hello.resumeToken
        if (display.value !in hello.displays.indices) display.value = hello.displays.indexOfFirst { it.primary }.coerceAtLeast(0)
        stats.value = stats.value.copy(encoder = hello.hardware)
        sshQueued.set(0)
        outgoing = channel
        received.clear()
        lastVideoFrame = -1L
        brokenUntilKey = false
        startUdp(hello)
        val config = mutableMapOf<String, Any>("display" to display.value, "fps" to streamFps(), "quality" to quality.value,
            "lock_on_close" to lockOnClose.value)
        if (miniOnly) config["max_bitrate"] = MINI_MAX_BITRATE
        audioAvailable.value = "opus" in hello.audio
        if (audioAvailable.value == true && audio.value) config["audio"] = true
        sendSsh(DesktopProtocol.config(config))
        if (viewOnly.value) sendSsh(DesktopProtocol.req(DesktopProtocol.REQ_VIEW_ONLY_ON))
        if ((phase.value as? DesktopPhase.Connected)?.paused == true) sendSsh(DesktopProtocol.req(DesktopProtocol.REQ_PAUSE))
        if (!everConnected) phase.value = DesktopPhase.Connecting(DesktopPhase.Step.FIRST_FRAME)
        ping.reset()
        latency.reset()
    }

    /** Runs a started stream until it ends: writes what is queued, reads what the host sends, ticks four times a second. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun stream(s: RemoteStream, hello: Hello): Outcome {
        mutableHello.value = hello
        if (hello.needs.isNotEmpty()) {
            phase.value = DesktopPhase.NeedsAction(hello.needs)
            s.close()
            stream = null
            return if (closing) Outcome.Closed else Outcome.Needs
        }
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        beginStream(hello, channel)
        coroutineScope {
            val writer = launch(Dispatchers.IO) {
                val out = BufferedOutputStream(s.output, 64 * 1024)
                try {
                    for (bytes in channel) {
                        sshQueued.decrementAndGet()
                        out.write(bytes)
                        if (channel.isEmpty) out.flush()
                    }
                } catch (_: Exception) { s.close() }
            }
            val ticker = launch {
                while (true) {
                    tick()
                    delay(250)
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
                player.stop()
                received.clear()
                shapes.clear()
                stats.value = stats.value.copy(link = "SSH", lossPercent = null)
            }
        }
        Log.i(TAG, "${host.name}: stream ended, inputs sent $inputsSent, view-only dropped $inputsDropped")
        inputsSent = 0; inputsDropped = 0
        return when {
            kicked -> Outcome.Kicked
            closing -> Outcome.Closed
            else -> Outcome.Streamed
        }
    }

    /** Reads the host's frames from the SSH channel until it closes; then stays for as long as UDP alone carries the desktop. */
    private suspend fun readFrames(s: RemoteStream) {
        val input = BufferedInputStream(s.input, 256 * 1024)
        while (!closing) {
            val frame = try { DesktopProtocol.read(input) } catch (_: Exception) { null } ?: break
            val message = try { DesktopProtocol.decode(frame) } catch (e: Exception) { Log.w(TAG, "bad frame", e); null } ?: continue
            handle(message)
            if (kicked) break
        }
        // The SSH channel is gone but UDP still carries the desktop: keep going on it alone,
        // as the host does, until UDP falls silent too.
        if (!closing && !kicked && link?.alive == true) {
            Log.i(TAG, "${host.name}: SSH channel closed, staying on UDP")
            outgoing = null
            while (!closing && !kicked && link?.alive == true) delay(200)
        }
    }

    /** Opens the host's UDP offer; the link sends its own port so a host firewall can let replies in. */
    private suspend fun startUdp(hello: Hello) {
        val offer = hello.udp ?: return
        val override = settings.udpHostOverride
        val address = withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(override.ifEmpty { host.address }) }.getOrNull() }
            ?: return
        val l = UdpLink(address, offer.port, offer.key,
            onFrame = { frame -> runCatching { DesktopProtocol.decode(frame) }.getOrNull()?.let(::handle) },
            onVideoLost = ::askKeyframe,
            log = { Log.i(TAG, "${host.name}: $it") },
            onGiveUp = { frame -> sendSsh(frame) })
        val loss = settings.testLoss
        if (loss > 0) { l.dropRate = loss; sendSsh(DesktopProtocol.config(mapOf("debug_loss" to loss))) }
        withContext(Dispatchers.IO) { l.start() }
        link = l
        sendSsh(DesktopProtocol.config(mapOf("udp_client_port" to l.localPort)))
        Log.i(TAG, "${host.name}: UDP offered on ${address.hostAddress}:${offer.port}")
    }

    /**
     * At most one keyframe request a second: a lost frame on UDP otherwise asks for each one after it.
     * A request inside that second waits for it rather than vanishing, or a still screen would stay broken.
     */
    private fun askKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyAsk < 1000) { keyAskPending = true; return }
        keyAskPending = false
        lastKeyAsk = now
        send(DesktopProtocol.req(DesktopProtocol.REQ_KEYFRAME))
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (keyAskPending) askKeyframe()
        ping.tick()
        updateLinkQuality(now)
        if (now - statsAt >= 1000) publishStats(now)
    }

    /** Two missed pings in a row, or a slow answer, is a poor link. Five clean seconds restore it. */
    private fun updateLinkQuality(now: Long) {
        val rtt = ping.rttMs
        // On UDP the link itself says it: heartbeats answer every 250 ms, so 700 ms without a datagram is
        // a stall; heavy loss counts too. Lost pings there are just loss.
        val l = link
        val bad = if (l != null && l.alive) l.silentUs > 700_000 || l.loss > 0.25 || rtt > POOR_MS
            else ping.missed >= 2 || rtt > POOR_MS
        if (bad) goodSince = 0 else if (goodSince == 0L) goodSince = now
        (phase.value as? DesktopPhase.Connected)?.let { c ->
            val poor = bad || (c.poor && now - goodSince < 5000)
            if (poor != c.poor) {
                Log.i(TAG, "${host.name}: poor=$poor (udp=${l?.alive} silent=${l?.silentUs?.div(1000)}ms loss=${l?.loss} rtt=$rtt missed=${ping.missed})")
                phase.value = c.copy(poor = poor)
            }
        }
    }

    /** What the last second carried, for the network badge and the statistics. */
    private fun publishStats(now: Long) {
        val seconds = (now - statsAt) / 1000.0
        val l = link
        val udp = l?.takeIf { it.alive }
        stats.value = stats.value.copy(fps = (frameWindow / seconds).toInt(), kbps = (byteWindow * 8 / 1000 / seconds).toInt(),
            decoder = decoder.decoderName, frames = decoder.decodedFrames, link = if (udp != null) "UDP" else "SSH",
            lossPercent = udp?.let { it.loss * 100 }, fecRecovered = l?.fecRecovered ?: 0, lostFrames = l?.lostMessages ?: 0,
            // A still screen sends no frames: the last measured second stands.
            latency = latency.take() ?: stats.value.latency,
            audioMs = player.packets.takeIf { it != audioPacketsSeen }?.let { audioPacketsSeen = it; player.queuedMs })
        frameWindow = 0
        byteWindow = 0
        statsAt = now
    }

    /** Frames from either transport; UDP's arrive on its receive thread. */
    private fun handle(m: ServerMessage) {
        when (m) {
            is ServerMessage.Video -> handleVideo(m)
            is ServerMessage.CursorShape -> handleCursorShape(m)
            is ServerMessage.Audio -> if (audio.value && (phase.value as? DesktopPhase.Connected)?.paused != true) player.submit(m)
            is ServerMessage.CursorPos -> cursor.value = RemoteCursor(m.x, m.y, m.visible, shapes[m.shape])
            is ServerMessage.State -> handleState(m)
            is ServerMessage.Ping -> send(DesktopProtocol.pong(m.t1, System.nanoTime() / 1000, System.nanoTime() / 1000))
            is ServerMessage.Pong -> handlePong(m)
        }
    }

    private fun handleVideo(m: ServerMessage.Video) {
        // Both transports may deliver around a switch: older frames than the newest are stale.
        synchronized(this) {
            if (m.frame <= lastVideoFrame) return
            // A frame lost on UDP breaks every frame after it that refers back to it: decoding
            // them smeared the picture into stripes. Hold the last good picture until a keyframe.
            if (!m.keyframe && lastVideoFrame >= 0 && m.frame != lastVideoFrame + 1) brokenUntilKey = true
            lastVideoFrame = m.frame
            if (m.keyframe) brokenUntilKey = false
            if (brokenUntilKey) { askKeyframe(); return }
        }
        val now = System.nanoTime() / 1000
        received[m.frame] = now
        // Frames can arrive with no Surface or a stalled decoder. Bound bookkeeping on receipt,
        // independent of output callbacks; trim in batches instead of scanning every decoded frame.
        if (received.size > 240) received.keys.removeAll { it < m.frame - 120 }
        latency.onReceived(m.frame, m.captureUs, m.encodedUs, now)
        frameWindow++
        byteWindow += m.data.size
        if (videoSize.value != (m.width to m.height)) videoSize.value = m.width to m.height
        stats.value = stats.value.copy(width = m.width, height = m.height)
        decoder.submit(m)
    }

    private fun handleCursorShape(m: ServerMessage.CursorShape) {
        val pixels = IntArray(m.width * m.height) { i ->
            val o = i * 4
            (m.rgba[o + 3].toInt() and 0xFF shl 24) or (m.rgba[o].toInt() and 0xFF shl 16) or
                (m.rgba[o + 1].toInt() and 0xFF shl 8) or (m.rgba[o + 2].toInt() and 0xFF)
        }
        if (m.width > 0 && m.height > 0) shapes[m.hash] = CursorImage(Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888), m.hotX, m.hotY)
        cursor.value?.let { c -> cursor.value = c.copy(shape = c.shape ?: shapes[m.hash]) }
    }

    private fun handleState(m: ServerMessage.State) {
        if (m.kicked) { kicked = true; stream?.close() }
        m.clip?.let { remoteClipboard.tryEmit(it) }
        m.needs?.let { if (it.isNotEmpty()) phase.value = DesktopPhase.NeedsAction(it) }
        m.locked?.let { notice.value = if (it) tr("请在本机解锁") else null }
        m.error?.let { line(it) }
        m.audio?.let { Log.i(TAG, "${host.name}: host audio $it: ${m.json.optString("detail")}"); audioAvailable.value = false; player.stop() }
        m.json.optJSONObject("timing")?.let(latency::onHostTiming)
    }

    private fun handlePong(m: ServerMessage.Pong) {
        latency.onPong(m.t1, m.t2, m.t3, System.nanoTime() / 1000)
        val rtt = ping.answer(m.t1) ?: return
        stats.value = stats.value.copy(rttMs = rtt)
        // The next ping goes a second after this one left.
        val wait = (1000 - rtt).coerceAtLeast(0).toLong()
        scope.launch { delay(wait); ping.next() }
    }

    private fun decoded(frame: Long, decodedUs: Long) {
        latency.onDecoded(frame, decodedUs)
        val recv = received.remove(frame) ?: decodedUs
        send(DesktopProtocol.ack(frame, recv, decodedUs))
        // Only a desktop on its way up: a frame the decoder still held after a kick or a failure (UDP
        // keeps them coming until the link closes) must not bring a dead connection back to life.
        if (phase.value is DesktopPhase.Connecting || phase.value is DesktopPhase.Recovering) {
            everConnected = true
            phase.value = DesktopPhase.Connected(paused = false)
        }
    }

    /** Sends a frame to the host: over UDP while it is alive (resent until acknowledged when it must arrive), else SSH. */
    fun send(bytes: ByteArray) {
        val l = link
        if (l != null && l.alive) l.send(bytes, UdpLink.reliable(bytes))
        else { l?.giveUp(); sendSsh(bytes) }
    }

    /**
     * Queues [bytes] for the SSH stream. Keys, buttons and control always go (dropping a key release would leave
     * it held); while the writer is [SSH_SEND_CAP] frames behind, pointer moves, wheel and ACKs are dropped.
     */
    private fun sendSsh(bytes: ByteArray) {
        val channel = outgoing ?: return
        if (sshQueued.get() >= SSH_SEND_CAP && !UdpLink.reliable(bytes)) return
        if (channel.trySend(bytes).isSuccess) sshQueued.incrementAndGet()
    }

    fun input(bytes: ByteArray) {
        if (viewOnly.value) { inputsDropped++; return }
        inputsSent++
        send(bytes)
    }

    fun setViewOnly(on: Boolean) {
        viewOnly.value = on
        send(DesktopProtocol.req(if (on) DesktopProtocol.REQ_VIEW_ONLY_ON else DesktopProtocol.REQ_VIEW_ONLY_OFF))
    }

    fun setQuality(value: String) { quality.value = value; settings.setQuality(host.id, value); send(DesktopProtocol.config(mapOf("quality" to value))) }
    fun setFps(value: Int) { fps.value = value; settings.setFps(host.id, value); send(DesktopProtocol.config(mapOf("fps" to streamFps()))) }
    fun setDisplay(index: Int) { display.value = index; send(DesktopProtocol.config(mapOf("display" to index))) }
    fun setAudio(on: Boolean) {
        audio.value = on
        settings.setAudio(host.id, on)
        if (!on) player.stop()
        if (audioAvailable.value != null) {
            // Asking again after the host said it has no sound lets it try once more (a device plugged in since).
            if (on && audioAvailable.value == false && "opus" in hello.value?.audio.orEmpty()) audioAvailable.value = true
            send(DesktopProtocol.config(mapOf("audio" to on)))
        }
    }
    fun setLockOnClose(on: Boolean) { lockOnClose.value = on; settings.setLockOnClose(host.id, on); send(DesktopProtocol.config(mapOf("lock_on_close" to on))) }

    /** Who shows the picture now; nothing showing it pauses the host, only small windows showing it thin the stream. */
    private val viewers = DesktopViewers(scope, onShown = ::resume, onHidden = { if (active) pause() }, onSmallOnlyChanged = { small ->
        send(DesktopProtocol.config(mapOf("fps" to streamFps(), "max_bitrate" to if (small) MINI_MAX_BITRATE else UNCAPPED_BITRATE)))
    })
    /** Only small windows show the picture: the host sends fewer, smaller frames ([MINI_FPS], [MINI_MAX_BITRATE]). */
    private val miniOnly: Boolean get() = viewers.smallOnly

    /** The frame rate asked of the host: the user's, capped while only a small window shows the picture. */
    private fun streamFps() = if (miniOnly) minOf(fps.value, MINI_FPS) else fps.value

    /** [owner] shows the picture, [small] or full; see [DesktopViewers]. */
    fun shown(owner: String, small: Boolean) = viewers.shown(owner, small)

    /** [owner] no longer shows the picture. */
    fun hidden(owner: String) = viewers.hidden(owner)

    /** The page went away: no pictures, the link kept for the keep-alive time. */
    fun pause() {
        send(DesktopProtocol.req(DesktopProtocol.REQ_PAUSE))
        player.stop()
        (phase.value as? DesktopPhase.Connected)?.let { phase.value = it.copy(paused = true) }
        keepAlive?.cancel()
        val minutes = settings.keepAliveMinutes
        keepAlive = scope.launch { delay(minutes * 60_000L); Log.i(TAG, "${host.name}: keep-alive over"); close() }
    }

    fun resume() {
        keepAlive?.cancel()
        keepAlive = null
        (phase.value as? DesktopPhase.Connected)?.let { phase.value = it.copy(paused = false) }
        send(DesktopProtocol.req(DesktopProtocol.REQ_RESUME))
        // Only a desktop that ended quietly (keep-alive over) starts again by itself; a failed one, e.g.
        // taken over by another device, waits for 重试.
        if (job?.isActive != true && phase.value is DesktopPhase.Idle) start()
    }

    /**
     * A new network. On UDP: a new socket and three heartbeats now, not after mosh's 10 s port hop,
     * plus a keyframe. On SSH: drop the old socket instead of waiting for keepalives; the loop resumes it.
     */
    fun networkChanged() {
        if (!everConnected || closing) return
        val l = link
        if (l != null && l.alive) {
            Log.i(TAG, "${host.name}: network changed, rebinding UDP")
            l.rebind()
            lastKeyAsk = 0
            askKeyframe()
            return
        }
        Log.i(TAG, "${host.name}: network changed, reconnecting")
        stream?.close()
        ssh?.disconnect()
    }

    fun close() {
        closing = true
        keepAlive?.cancel()
        pending.value?.answer?.complete(null)
        stream?.close()
        link?.close()
        ssh?.disconnect()
        if (job?.isActive != true) { phase.value = DesktopPhase.Idle; onEnded(this) }
    }

    fun release() {
        password = ""
        passphrase = ""
        shapes.clear()
        decoder.close()
        player.stop()
    }

    /** e2e only: drop this share of UDP datagrams on the phone side (1.0 blocks UDP). */
    internal fun testUdpDrop(rate: Double) { link?.dropRate = rate }
    internal val audioPlayer: AudioPlayer get() = player

    companion object {
        private const val TAG = "RoamttyDesktop"
        /** How long the picture may go unshown before the host pauses: longer than a switch between pages takes. */
        const val HIDE_GRACE_MS = 600L
        /** The small window's frame rate and bitrate cap (bits per second): plenty for a picture a third of the screen. */
        const val MINI_FPS = 15
        const val MINI_MAX_BITRATE = 1_500_000
        /** Lifts the cap: the host takes the lower of this and its own quality cap. */
        const val UNCAPPED_BITRATE = 1_000_000_000
        const val RECOVER_LIMIT_MS = 30_000L
        const val POOR_MS = 300L
        private const val HANDSHAKE_MS = 30_000L
        private const val SSH_SEND_CAP = 256
    }
}
