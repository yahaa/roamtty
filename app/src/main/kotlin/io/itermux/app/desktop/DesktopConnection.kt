package io.itermux.app.desktop

import android.graphics.Bitmap
import android.util.Log
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.session.PendingPrompt
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.ui.credentialsFor
import io.itermux.app.ui.pageLogin
import io.itermux.desktop.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** The cursor the plugin reports, in native pixels of the selected display; [shape] null until its image came. */
internal data class RemoteCursor(val x: Int, val y: Int, val visible: Boolean, val shape: CursorImage?)
internal class CursorImage(val bitmap: Bitmap, val hotX: Int, val hotY: Int)

/** What the network badge and the statistics show. */
internal data class DesktopStats(val rttMs: Int? = null, val fps: Int = 0, val kbps: Int = 0, val width: Int = 0, val height: Int = 0,
    val frames: Long = 0, val decoder: String = "", val link: String = "SSH", val encoder: String = "",
    /** UDP only: share of shards lost before FEC, frames FEC rebuilt, frames lost outright. */
    val lossPercent: Double? = null, val fecRecovered: Long = 0, val lostFrames: Long = 0)

/**
 * One host's desktop (plan §4.1): its own SSH login, the plugin's `serve` on an exec channel, frames
 * to the [decoder], input from the page. It outlives the page: [pause] keeps it for the keep-alive time
 * without pictures, and a dropped link is resumed with the session token for up to 30 s.
 */
internal class DesktopConnection(
    val host: Host,
    private val app: ItermuxApp,
    private val scope: CoroutineScope,
    /** Typed secrets, kept in memory so a dropped link logs in again by itself. */
    private val password: String,
    private val passphrase: String,
    /** The phone's screen: its long edge caps the encode size. */
    private val clientWidth: Int,
    private val clientHeight: Int,
    private val clientDpi: Int,
    private val onEnded: (DesktopConnection) -> Unit,
) {
    private val settings = DesktopSettings(app)
    private val manifest by lazy { DesktopSettings.manifest(app) }
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
    /** The video size of the last frame: the page's surface is fixed to it. */
    val videoSize = MutableStateFlow(0 to 0)

    val decoder = VideoDecoder(onDecoded = ::decoded, onNeedKeyframe = { askKeyframe() })

    @Volatile private var outgoing: Channel<ByteArray>? = null
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
    private val shapes = ConcurrentHashMap<Long, CursorImage>()
    @Volatile private var lastPong = 0L
    @Volatile private var pingSentUs = 0L
    /** The last ping got its pong; only an unanswered one counts as waiting. */
    @Volatile private var pingAnswered = true
    /** Pings in a row that went unanswered for 300 ms; on UDP a single one may simply be lost. */
    @Volatile private var missedPings = 0
    /** RTT smoothed like TCP's SRTT (1/4 new sample): one slow answer is not a slow network. */
    @Volatile private var smoothedRtt = -1.0
    /** RTT samples since the stream started: the first answers race the first keyframe's encode. */
    @Volatile private var rttSamples = 0
    @Volatile private var poorSince = 0L
    @Volatile private var goodSince = 0L
    private var frameWindow = 0
    private var byteWindow = 0L

    val active: Boolean get() = phase.value !is DesktopPhase.Idle && phase.value !is DesktopPhase.Failed

    fun start() {
        if (job?.isActive == true) return
        closing = false
        kicked = false
        agentReloaded = false
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
                    recoveringSince = System.currentTimeMillis()
                    phase.value = DesktopPhase.Recovering(recoveringSince!!)
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
        val credentials = try { app.credentialsFor(host, password, passphrase) } catch (e: Exception) {
            return Outcome.Failed(tr("无法读取凭据，请重新导入密钥或保存密码。"))
        }
        val login = app.pageLogin(host, credentials, pending)
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
        var probe = remote.probeDesktop()
        probe?.let { saveProbe(it) }
        while (probe?.desktop == "nologin" && !closing) {
            phase.value = DesktopPhase.NeedsAction(listOf("not-logged-in"))
            delay(1000)
            probe = remote.probeDesktop()?.also { saveProbe(it) }
        }
        if (closing) return Outcome.Closed
        if (probe == null) return Outcome.Failed(tr("无法检测这台主机的桌面"))
        if (probe.desktop == "no") return Outcome.Failed(tr("这台主机没有图形桌面"))
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
            val first = try { DesktopProtocol.readLine(s.input) } catch (_: Exception) { null }
            if (first == null) {
                val status = withTimeoutOrNull(3000) { while (!s.closed) delay(30); s.exitStatus }
                val errors = runCatching { s.errors.readBytes().toString(Charsets.UTF_8).trim() }.getOrDefault("")
                s.close()
                if (errors.isNotEmpty()) line(errors.takeLast(500))
                if (status == 127 || status == WindowsDesktop.NOT_FOUND) { install(remote, target.first, target.second)?.let { return it }; continue }
                return if (s.closed && status != null) Outcome.Failed(trf("桌面插件启动失败（退出码 %d）", status)) else Outcome.Dropped()
            }
            when (val start = DesktopProtocol.parseStart(first)) {
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
                                val ok = DesktopPluginInstaller(remote, fetch = ::download, onLine = { line(it) }).registerAgent()
                                Log.i(TAG, "${host.name}: agent not running, reloaded: $ok")
                                if (ok) { tries--; continue }
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

    private suspend fun saveProbe(probe: HostDesktop) {
        runCatching { app.database.hosts().updateDesktop(host.id, host.address, host.port, host.username, probe.desktop, System.currentTimeMillis()) }
    }

    /** The macOS agent was reloaded once for this connection after serve found it not running. */
    @Volatile private var agentReloaded = false

    /** The host's shell is cmd.exe or PowerShell (a native Windows host). */
    @Volatile private var windowsHost = false

    private fun serveCommand(): String {
        // Resume tokens are base64: no quotes or spaces to escape in either shell.
        val resume = if (sessionId != null && resumeToken != null) " --resume $sessionId --token ${resumeToken!!.filter { it.isLetterOrDigit() || it in "+/=" }}" else ""
        val client = "${maxOf(clientWidth, clientHeight)}x${minOf(clientWidth, clientHeight)}@$clientDpi"
        val args = "--proto ${DesktopProtocol.SUPPORTED.last} --client $client$resume"
        return if (windowsHost) WindowsDesktop.serveCommand(args) else "\"\$HOME/.roamtty/bin/roamtty-desktop\" serve $args"
    }

    /** Installs or upgrades the pinned plugin; null once it is in place, else why not. */
    private suspend fun install(remote: RemoteHost, target: String, build: PluginManifest.Target, force: Boolean = false): Outcome? {
        phase.value = DesktopPhase.Connecting(DesktopPhase.Step.INSTALL)
        line(trf("安装桌面插件 %s（%s）", manifest.version, target))
        val openh264 = PluginManifest.openh264File(target, manifest.openh264)?.let { "${PluginManifest.OPENH264_BASE}/$it" }
        val installer = DesktopPluginInstaller(remote, fetch = ::download, onStep = { step ->
            line(when (step) {
                DesktopPluginInstaller.Step.HOST_DOWNLOAD -> tr("主机直接下载…")
                DesktopPluginInstaller.Step.PHONE_DOWNLOAD -> tr("主机下载失败，改为手机下载后上传…")
                DesktopPluginInstaller.Step.UPLOAD -> tr("上传到主机…")
                DesktopPluginInstaller.Step.VERIFY -> tr("校验 SHA-256…")
                DesktopPluginInstaller.Step.ENCODER_HOST -> tr("获取 Cisco OpenH264 编码器…")
                DesktopPluginInstaller.Step.ENCODER_PHONE -> tr("主机获取编码器失败，改为手机下载后上传…")
                DesktopPluginInstaller.Step.REGISTER -> tr("注册插件…")
                DesktopPluginInstaller.Step.DONE -> tr("安装完成")
            })
        }, onLine = { if (!it.startsWith("__ROAMTTY_")) line(it) })
        val result = installer.install(DesktopPluginInstaller.Build(manifest.version, manifest.url(build), build.sha256, openh264))
        if (force) Log.i(TAG, "reinstalled after a start error: $result")
        return when (result) {
            DesktopPluginInstaller.Result.Installed -> null
            is DesktopPluginInstaller.Result.Failed -> Outcome.Failed(when (result.reason) {
                DesktopPluginInstaller.Reason.DOWNLOAD -> tr("下载桌面插件失败")
                DesktopPluginInstaller.Reason.CHECKSUM -> tr("桌面插件校验失败")
                DesktopPluginInstaller.Reason.ENCODER -> tr("下载 OpenH264 编码器失败")
                DesktopPluginInstaller.Reason.HOST -> tr("无法在主机上写入插件")
            })
        }
    }

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        var current = URL(url)
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            try {
                when (c.responseCode) {
                    in 300..399 -> current = URL(current, c.getHeaderField("Location") ?: error("redirect"))
                    in 200..299 -> return@withContext c.inputStream.use { it.readBytes() }
                    else -> error("HTTP ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }
        error("too many redirects")
    }

    private suspend fun stream(s: RemoteStream, hello: Hello): Outcome {
        mutableHello.value = hello
        if (hello.needs.isNotEmpty()) {
            phase.value = DesktopPhase.NeedsAction(hello.needs)
            s.close()
            stream = null
            return if (closing) Outcome.Closed else Outcome.Needs
        }
        sessionId = hello.session
        resumeToken = hello.resumeToken
        if (display.value !in hello.displays.indices) display.value = hello.displays.indexOfFirst { it.primary }.coerceAtLeast(0)
        stats.value = stats.value.copy(encoder = hello.hardware)
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        outgoing = channel
        lastVideoFrame = -1L
        brokenUntilKey = false
        startUdp(hello, channel)
        val config = mutableMapOf<String, Any>("display" to display.value, "fps" to fps.value, "quality" to quality.value,
            "lock_on_close" to lockOnClose.value)
        channel.trySend(DesktopProtocol.config(config))
        if (viewOnly.value) channel.trySend(DesktopProtocol.req(DesktopProtocol.REQ_VIEW_ONLY_ON))
        if ((phase.value as? DesktopPhase.Connected)?.paused == true) channel.trySend(DesktopProtocol.req(DesktopProtocol.REQ_PAUSE))
        if (hello.needs.isNotEmpty()) phase.value = DesktopPhase.NeedsAction(hello.needs)
        else if (!everConnected) phase.value = DesktopPhase.Connecting(DesktopPhase.Step.FIRST_FRAME)
        lastPong = System.currentTimeMillis()
        pingSentUs = 0L
        pingAnswered = true
        missedPings = 0
        smoothedRtt = -1.0
        rttSamples = 0
        coroutineScope {
            val writer = launch(Dispatchers.IO) {
                val out = BufferedOutputStream(s.output, 64 * 1024)
                try {
                    for (bytes in channel) {
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
                withContext(Dispatchers.IO) {
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
            } finally {
                outgoing = null
                channel.close()
                writer.cancel()
                ticker.cancel()
                s.close()
                stream = null
                link?.close()
                link = null
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

    /** Opens the host's UDP offer; the link sends its own port so a host firewall can let replies in. */
    private suspend fun startUdp(hello: Hello, channel: Channel<ByteArray>) {
        val offer = hello.udp ?: return
        val override = settings.udpHostOverride
        val address = withContext(Dispatchers.IO) { runCatching { java.net.InetAddress.getByName(override.ifEmpty { host.address }) }.getOrNull() }
            ?: return
        val l = UdpLink(address, offer.port, offer.key,
            onFrame = { frame -> runCatching { DesktopProtocol.decode(frame) }.getOrNull()?.let(::handle) },
            onVideoLost = ::askKeyframe,
            log = { Log.i(TAG, "${host.name}: $it") })
        val loss = settings.testLoss
        if (loss > 0) { l.dropRate = loss; channel.trySend(DesktopProtocol.config(mapOf("debug_loss" to loss))) }
        withContext(Dispatchers.IO) { l.start() }
        link = l
        channel.trySend(DesktopProtocol.config(mapOf("udp_client_port" to l.localPort)))
        Log.i(TAG, "${host.name}: UDP offered on ${address.hostAddress}:${offer.port}")
    }

    @Volatile private var keyAskPending = false

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

    private var statsAt = System.currentTimeMillis()

    private fun tick() {
        val now = System.currentTimeMillis()
        if (keyAskPending) askKeyframe()
        if (pingSentUs == 0L) {
            pingAnswered = false
            pingSentUs = System.nanoTime() / 1000
            send(DesktopProtocol.ping(pingSentUs))
        }
        // A ping unanswered past 300 ms is sent again (UDP may have dropped it); two in a row, or a slow
        // answer, is a poor link. Five clean seconds restore it.
        val waiting = if (!pingAnswered && pingSentUs != 0L) (System.nanoTime() / 1000 - pingSentUs) / 1000 else 0
        if (waiting > POOR_MS) {
            missedPings++
            pingSentUs = System.nanoTime() / 1000
            send(DesktopProtocol.ping(pingSentUs))
        }
        val rtt = smoothedRtt.takeIf { it >= 0 && rttSamples >= 3 }?.toInt() ?: 0
        // On UDP the link itself says it: heartbeats answer every 250 ms, so 700 ms without a datagram is
        // a stall; heavy loss counts too. Lost pings there are just loss.
        val l = link
        val bad = if (l != null && l.alive) l.silentUs > 700_000 || l.loss > 0.25 || rtt > POOR_MS
            else missedPings >= 2 || rtt > POOR_MS
        if (bad) { poorSince = now; goodSince = 0 } else if (goodSince == 0L) goodSince = now
        (phase.value as? DesktopPhase.Connected)?.let { c ->
            val poor = if (bad) true else if (c.poor && now - goodSince < 5000) true else false
            if (poor != c.poor) {
                Log.i(TAG, "${host.name}: poor=$poor (udp=${l?.alive} silent=${l?.silentUs?.div(1000)}ms loss=${l?.loss} rtt=$rtt missed=$missedPings)")
                phase.value = c.copy(poor = poor)
            }
        }
        if (now - statsAt >= 1000) {
            val seconds = (now - statsAt) / 1000.0
            val l = link
            val udp = l?.alive == true
            stats.value = stats.value.copy(fps = (frameWindow / seconds).toInt(), kbps = (byteWindow * 8 / 1000 / seconds).toInt(),
                decoder = decoder.decoderName, frames = decoder.decodedFrames, link = if (udp) "UDP" else "SSH",
                lossPercent = if (udp) l!!.loss * 100 else null, fecRecovered = l?.fecRecovered ?: 0, lostFrames = l?.lostMessages ?: 0)
            frameWindow = 0
            byteWindow = 0
            statsAt = now
        }
    }

    /** Frames from either transport; UDP's arrive on its receive thread. */
    private fun handle(m: ServerMessage) {
        when (m) {
            is ServerMessage.Video -> {
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
                received[m.frame] = System.nanoTime() / 1000
                frameWindow++
                byteWindow += m.data.size
                if (videoSize.value != (m.width to m.height)) videoSize.value = m.width to m.height
                stats.value = stats.value.copy(width = m.width, height = m.height)
                decoder.submit(m)
            }
            is ServerMessage.CursorShape -> {
                Log.d(TAG, "${host.name}: cursor shape ${m.width}x${m.height} hot ${m.hotX},${m.hotY}")
                val pixels = IntArray(m.width * m.height) { i ->
                    val o = i * 4
                    (m.rgba[o + 3].toInt() and 0xFF shl 24) or (m.rgba[o].toInt() and 0xFF shl 16) or
                        (m.rgba[o + 1].toInt() and 0xFF shl 8) or (m.rgba[o + 2].toInt() and 0xFF)
                }
                if (m.width > 0 && m.height > 0) shapes[m.hash] = CursorImage(Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888), m.hotX, m.hotY)
                cursor.value?.let { c -> cursor.value = c.copy(shape = c.shape ?: shapes[m.hash]) }
            }
            is ServerMessage.CursorPos -> cursor.value = RemoteCursor(m.x, m.y, m.visible, shapes[m.shape])
            is ServerMessage.State -> {
                if (m.kicked) { kicked = true; stream?.close() }
                m.clip?.let { remoteClipboard.tryEmit(it) }
                m.needs?.let { if (it.isNotEmpty()) phase.value = DesktopPhase.NeedsAction(it) }
                m.locked?.let { notice.value = if (it) tr("请在本机解锁") else null }
                m.error?.let { line(it) }
            }
            is ServerMessage.Ping -> send(DesktopProtocol.pong(m.t1, System.nanoTime() / 1000, System.nanoTime() / 1000))
            is ServerMessage.Pong -> {
                if (m.t1 == pingSentUs && !pingAnswered) {
                    pingAnswered = true
                    missedPings = 0
                    val rtt = ((System.nanoTime() / 1000 - m.t1) / 1000).toInt()
                    smoothedRtt = if (smoothedRtt < 0) rtt.toDouble() else smoothedRtt * 0.75 + rtt * 0.25
                    rttSamples++
                    stats.value = stats.value.copy(rttMs = rtt)
                    lastPong = System.currentTimeMillis()
                    // The next ping goes a second after this one left.
                    val wait = (1000 - rtt).coerceAtLeast(0).toLong()
                    scope.launch { delay(wait); pingSentUs = 0L }
                }
            }
        }
    }

    private fun decoded(frame: Long, decodedUs: Long) {
        val recv = received.remove(frame) ?: decodedUs
        received.keys.removeAll { it < frame - 120 }
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
        else outgoing?.trySend(bytes)
    }

    fun input(bytes: ByteArray) {
        if (viewOnly.value) { inputsDropped++; return }
        inputsSent++
        send(bytes)
    }

    /** Per stream, for the log: inputs sent, and those view-only held back. */
    @Volatile private var inputsSent = 0
    @Volatile private var inputsDropped = 0

    fun setViewOnly(on: Boolean) {
        viewOnly.value = on
        send(DesktopProtocol.req(if (on) DesktopProtocol.REQ_VIEW_ONLY_ON else DesktopProtocol.REQ_VIEW_ONLY_OFF))
    }

    fun setQuality(value: String) { quality.value = value; settings.setQuality(host.id, value); send(DesktopProtocol.config(mapOf("quality" to value))) }
    fun setFps(value: Int) { fps.value = value; settings.setFps(host.id, value); send(DesktopProtocol.config(mapOf("fps" to value))) }
    fun setDisplay(index: Int) { display.value = index; send(DesktopProtocol.config(mapOf("display" to index))) }
    fun setLockOnClose(on: Boolean) { lockOnClose.value = on; settings.setLockOnClose(host.id, on); send(DesktopProtocol.config(mapOf("lock_on_close" to on))) }

    /** The page went away: no pictures, the link kept for the keep-alive time. */
    fun pause() {
        send(DesktopProtocol.req(DesktopProtocol.REQ_PAUSE))
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

    fun release() { decoder.close() }

    /** e2e only: drop this share of UDP datagrams on the phone side (1.0 blocks UDP). */
    internal fun testUdpDrop(rate: Double) { link?.dropRate = rate }
    internal val udpLink: UdpLink? get() = link

    companion object {
        private const val TAG = "RoamttyDesktop"
        const val RECOVER_LIMIT_MS = 30_000L
        const val POOR_MS = 300L
    }
}
