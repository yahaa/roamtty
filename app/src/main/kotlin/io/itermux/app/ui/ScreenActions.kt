package io.itermux.app.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.data.credentialsFor
import io.itermux.app.data.multiplexer
import io.itermux.app.data.needsTypedSecret
import io.itermux.app.data.newSessionMode
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.login.PendingPrompt
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.SessionService
import io.itermux.app.session.StartDirectory
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.voice.VoiceAudioEngine
import io.itermux.app.voice.VoiceConnection
import io.itermux.app.voice.VoiceReadyOutcome
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.DesktopButton
import io.itermux.desktop.DesktopPhase
import io.itermux.desktop.DesktopProbe
import io.itermux.desktop.VoiceMode
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Credentials
import io.itermux.ssh.HostSystem
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What [ItermuxScreen]'s pages and sheets ask for: sessions opened, closed and reconnected, desktops, files, plugins
 * and shared images. It reads what the screen collected (the bound service, sessions, hosts, desktops) as it is when
 * asked, and moves the screen through its [state].
 */
@Stable
internal class ScreenActions(
    private val app: ItermuxApp,
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: ScreenState,
    private val homePager: PagerState,
    private val boundService: State<SessionService?>,
    private val openSessions: State<List<TerminalConnection>>,
    private val sessionStates: State<Map<String, ConnectionState>>,
    private val savedHosts: State<List<Host>>,
    private val openDesktops: State<Map<String, DesktopConnection>>,
    private val openVoices: State<Map<String, VoiceConnection>>,
    /** The desktop plugin's targets ("linux-x86_64", …), which tell the hosts it runs on. */
    private val desktopTargets: Set<String>,
    /** Asks for the notification permission when it is still missing; sessions run without it. */
    private val requestNotifications: () -> Unit,
) {
    private val service get() = boundService.value
    private val sessions get() = openSessions.value
    private val hosts get() = savedHosts.value

    fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }

    /** The bound service, or null after telling the user it is not ready yet. */
    private fun readyService(): SessionService? = service ?: run { toast(tr("会话服务尚未就绪，请稍后重试")); null }

    /** The saved copy of [host]: one edited since a session copied it (a password saved, plugins found) wins. */
    fun savedHost(host: Host) = hosts.firstOrNull { it.id == host.id } ?: host

    /** Whether [session] is connected, read from the collected states so the screen recomposes when it changes. */
    fun connected(session: TerminalConnection) = (sessionStates.value[session.id] ?: session.state.value).phase == Phase.CONNECTED

    /** A live session to the same endpoint already shows the host is up, without probing it. */
    fun hostConnected(host: Host) = service?.sessions?.value.orEmpty().any {
        it.host.endpointKey == host.endpointKey && it.state.value.phase == Phase.CONNECTED
    }

    /** Opens the detail page of the selected session's host; the host list when that host is gone. */
    fun showSessionHost() {
        val hostId = sessions.find { it.id == state.selected }?.host?.id
        if (hostId != null && hosts.any { it.id == hostId }) state.showHost(hostId)
        else state.page = AppPage.HOME
    }

    /** Leaves the terminal for the home page on its sessions tab. */
    fun leaveTerminal() {
        state.page = AppPage.HOME
        homePager.requestScrollToPage(0)
    }

    /** [open] shows the session once it connects; a reconnect from the session list stays on the list. */
    fun start(request: ConnectRequest, password: String = "", passphrase: String = "", open: Boolean = true) {
        val bound = readyService() ?: return
        requestNotifications()
        scope.launch {
            var credentials: Credentials? = null
            try {
                credentials = withContext(Dispatchers.IO) { app.vault.credentialsFor(request.host, password, passphrase) }
                request.terminateSessionId?.let { id ->
                    toast(trf("正在终止远端 %s 会话…", request.host.multiplexer.id))
                    val failure = bound.terminate(id, credentials) { prompt ->
                        val pending = PendingPrompt(prompt)
                        state.terminatePrompt = pending
                        try { pending.answer.await() } finally { if (state.terminatePrompt === pending) state.terminatePrompt = null }
                    }
                    toast(failure?.let { trf("未能终止远端会话：%s。长按卡片可强制删除", it) } ?: tr("远端 tmux 会话已终止，卡片已移除"))
                    return@launch
                }
                val session = bound.openFor(request, credentials)
                if (!open) return@launch
                state.showSession(session.id, if (request.openFilesAfterConnect) AppPage.FILES else AppPage.TERMINAL)
            } catch (e: Exception) {
                credentials?.clear()
                if (e is CancellationException) throw e
                if (!open) return@launch
                // Session limits and missing entries are reported by check()/error() with localized text.
                toast((e as? IllegalStateException)?.message ?: tr("无法读取凭据，请重新导入密钥或保存密码。"))
            }
        }
    }

    /** Asks for an unsaved password, or an encrypted key's passphrase, first. */
    fun begin(request: ConnectRequest) {
        if (request.host.needsTypedSecret(app.vault)) state.connectRequest = request
        else start(request)
    }

    fun connect(host: Host, mode: SessionMode, title: String = "", reuseSessionId: String? = null, openFilesAfterConnect: Boolean = false) {
        begin(ConnectRequest(host, mode, title, reuseSessionId, openFilesAfterConnect))
    }

    /** The secret [request] waited for was typed: goes on with what it was asked for. */
    fun continueWith(request: ConnectRequest, password: String, passphrase: String) {
        state.connectRequest = null
        when {
            request.plugins -> state.pluginTarget = PluginTarget(request.host, password, passphrase)
            request.desktop -> {
                startDesktop(request.host, password, passphrase, show = !request.desktopSmall)
                if (request.desktopSmall) state.pipDesktopHostId = request.host.id
            }
            request.voice -> {
                val targetSession = sessions.firstOrNull { it.id == request.voiceSessionId }
                if (targetSession != null) {
                    launchVoice(
                        session = targetSession,
                        host = request.host,
                        mode = request.voiceMode ?: VoiceMode.LIVE,
                        route = request.voiceRoute ?: AudioRoute.SPEAKER,
                        password = password,
                        passphrase = passphrase,
                    )
                }
            }
            else -> start(request, password, passphrase)
        }
    }

    /**
     * Ends a session and everything in it, without asking. A disconnected tmux session is killed over a fresh login;
     * see [SessionService.close] for the rest.
     */
    fun closeSession(session: TerminalConnection) {
        if (session.closeForgets(session.state.value.active)) begin(session.terminateRequest())
        else service?.close(session.id)
    }

    /**
     * Force delete: the card goes at once. Its tmux session is then ended over a fresh login with the saved credentials,
     * asking nothing, and one that could not be ended is reported.
     */
    fun forceDelete(session: TerminalConnection) {
        val bound = service ?: return
        if (session.state.value.active) return
        if (session.host.needsTypedSecret(app.vault)) {
            bound.forget(session.id)
            toast(trf("会话卡片已移除，但未能终止远端会话：%s", tr("没有已保存的登录信息")))
            return
        }
        scope.launch {
            val failure = try {
                val credentials = withContext(Dispatchers.IO) { app.vault.credentialsFor(session.host, "", "") }
                try { bound.forceDelete(session.id, credentials) } finally { credentials.clear() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                bound.forget(session.id)
                e.message ?: tr("无法读取凭据，请重新导入密钥或保存密码。")
            }
            failure?.let { toast(trf("会话卡片已移除，但未能终止远端会话：%s", it)) }
        }
    }

    /** tmux restarts the pane over the live connection; a plain shell gets a fresh one by reconnecting. */
    fun restartSession(session: TerminalConnection) {
        if (session.mode != SessionMode.TMUX) begin(session.reconnectRequest())
        else scope.launch { if (!session.restartPane()) toast(tr("未能重启会话，请检查连接后重试")) }
    }

    /** Gives up [session]'s tmux for a plain shell on the same host, under the same title. */
    fun switchToShell(session: TerminalConnection) {
        service?.close(session.id)
        service?.forget(session.id)
        connect(session.host, SessionMode.SHELL, session.title.value)
    }

    /** A new session in [directory]; with [agent], that agent is started there and names the session. */
    fun launchOn(host: Host, directory: String, agent: AgentLaunch?) {
        if (!StartDirectory.isSupported(directory)) {
            toast(tr(StartDirectory.UNSUPPORTED_MESSAGE))
            return
        }
        begin(launchRequest(host, directory, agent))
    }

    /** Attaches a tmux session already on the server; one the app already holds opens its card instead. */
    fun attachOn(host: Host, name: String) {
        sessions.tmuxCard(host, name)?.let {
            state.showSession(it.id)
            return
        }
        begin(ConnectRequest(host, SessionMode.TMUX, attach = name))
    }

    /** Logs in to [host]'s desktop; the phone's screen in pixels and its density cap the desktop's encode size. */
    private fun startDesktop(host: Host, password: String = "", passphrase: String = "", show: Boolean = true) {
        val bound = readyService() ?: return
        val metrics = context.resources.displayMetrics
        val (width, height) = if (Build.VERSION.SDK_INT >= 30)
            context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else metrics.widthPixels to metrics.heightPixels
        bound.openDesktop(host, password, passphrase, width, height, metrics.densityDpi)
        if (show) state.showDesktop(host.id)
    }

    /** The desktop button: straight in when it is already open, else log in (asking for an unsaved secret first). */
    fun openDesktop(host: Host) {
        val bound = readyService() ?: return
        val existing = bound.desktops[host.id]
        if (existing != null && existing.active) {
            state.showDesktop(host.id)
            return
        }
        if (host.needsTypedSecret(app.vault)) state.connectRequest = ConnectRequest(host, SessionMode.SHELL, desktop = true)
        else startDesktop(host)
    }

    /** The dock's desktop button: the host's desktop in the small window over the terminal, or gone again. */
    fun toggleDesktopPip(host: Host) {
        if (state.pipDesktopHostId == host.id) { state.pipDesktopHostId = null; return }
        val existing = service?.desktops?.get(host.id)
        if (existing == null || !existing.active) {
            // A secret to type is asked for first; the desktop then opens small all the same.
            if (host.needsTypedSecret(app.vault)) {
                state.connectRequest = ConnectRequest(host, SessionMode.SHELL, desktop = true, desktopSmall = true)
                return
            }
            startDesktop(host, show = false)
        }
        state.pipDesktopHostId = host.id
    }

    /**
     * [hostId]'s desktop. The registry itself is asked besides the collected map: right after the service binds again
     * (a rotation recreates the activity), the map still holds the empty value it had before.
     */
    fun desktopOf(hostId: String?): DesktopConnection? =
        hostId?.let { openDesktops.value[it] ?: service?.desktops?.get(it) }

    fun closeDesktop(hostId: String) { service?.desktops?.close(hostId) }

    fun voiceOf(hostId: String?): VoiceConnection? =
        hostId?.let { openVoices.value[it] ?: service?.voices?.get(it) }

    fun closeVoice(hostId: String) { service?.stopVoice(hostId) }

    fun isBluetoothAudioAvailable(): Boolean = VoiceAudioEngine.isBluetoothAvailable(context)

    private fun sessionById(id: String): TerminalConnection? =
        service?.sessions?.value?.find { it.id == id }

    /** Starts an `agy /voice` session after user confirmation (asking for an unsaved SSH secret first if needed). */
    fun startVoice(session: TerminalConnection, host: Host, mode: VoiceMode, route: AudioRoute) {
        if (host.needsTypedSecret(app.vault)) {
            state.connectRequest = ConnectRequest(
                host = host,
                mode = SessionMode.SHELL,
                voice = true,
                voiceSessionId = session.id,
                voiceMode = mode,
                voiceRoute = route,
            )
            return
        }
        launchVoice(session, host, mode, route)
    }

    private fun launchVoice(
        session: TerminalConnection,
        host: Host,
        mode: VoiceMode,
        route: AudioRoute,
        password: String = "",
        passphrase: String = "",
    ) {
        val bound = readyService() ?: return
        val targetSessionId = session.id
        bound.startVoice(host, targetSessionId, password, passphrase, mode, route) { readyMode, micPort ->
            val target = sessionById(targetSessionId)
            if (target == null || target.state.value.phase != Phase.CONNECTED) {
                return@startVoice VoiceReadyOutcome.SESSION_GONE
            }
            if (!target.isAgyActive()) {
                return@startVoice VoiceReadyOutcome.AGY_EXITED
            }
            if (micPort != io.itermux.desktop.VoiceProtocol.DEFAULT_MIC_PORT) {
                toast(trf("语音端口 24713 被占用，已改用 %d；若当前 agy 无响应请重启会话", micPort))
            }
            // Trigger agy's native interactive voice hotkey once the bridge & ANTIGRAVITY_MIC are ready.
            val keySeq = if (readyMode == VoiceMode.LIVE) "\u001b[17~" else "\u001b[15~" // F6 / F5
            target.write(keySeq)
            VoiceReadyOutcome.INJECTED
        }
    }

    fun toggleVoiceMute(host: Host) {
        val voice = voiceOf(host.id) ?: return
        val muted = voice.toggleMute() ?: return
        // F6 pauses/resumes agy voice capture on the owning session only.
        val target = sessionById(voice.sessionId)
        if (target != null && target.state.value.phase == Phase.CONNECTED) {
            target.write("\u001b[17~")
        }
        toast(tr(if (muted) "已静音（F6）" else "已恢复麦克风"))
    }

    fun cycleVoiceRoute(host: Host) {
        val voice = voiceOf(host.id) ?: return
        val route = voice.cycleAudioRoute()
        val label = when (route) {
            AudioRoute.SPEAKER -> tr("已切换扬声器")
            AudioRoute.EARPIECE -> tr("已切换听筒")
            AudioRoute.BLUETOOTH -> tr("已切换蓝牙耳机")
        }
        toast(label)
    }

    fun hangupVoice(host: Host) {
        val voice = voiceOf(host.id)
        if (voice != null && voice.startKeySent) {
            // F7 ends agy's active voice mode only if the start key was actually sent to the owning connected session.
            val target = sessionById(voice.sessionId)
            if (target != null && target.state.value.phase == Phase.CONNECTED) {
                target.write("\u001b[18~")
            }
        }
        service?.stopVoice(host.id)
    }

    fun desktopSupported(host: Host): Boolean {
        val system = HostSystem.fromStored(host.os)
        val family = when {
            system.isLinux -> "linux-"
            system == HostSystem.MACOS -> "macos-"
            system == HostSystem.WINDOWS -> "windows-"
            else -> return false
        }
        return desktopTargets.any { it.startsWith(family) }
    }

    /** Whether [host] offers its desktop from a terminal: the plugin runs there and a desktop was found. */
    fun desktopReady(host: Host) = desktopSupported(host) && DesktopProbe.decode(host.desktop) == DesktopProbe.YES

    /** The desktop button of [host], as [DesktopButton.derive] tells it from the host's probe and its desktop's [phase]. */
    fun desktopButton(host: Host, online: Boolean, sessionConnected: Boolean, phase: DesktopPhase) = DesktopButton.derive(
        online, sessionConnected, HostSystem.fromStored(host.os).let { if (it.isLinux) "LINUX" else it.name },
        desktopSupported(host), DesktopProbe.decode(host.desktop), host.desktopProbedAt, System.currentTimeMillis(), phase)

    fun managePlugins(host: Host) {
        if (host.needsTypedSecret(app.vault)) state.connectRequest = ConnectRequest(host, host.newSessionMode, plugins = true)
        else state.pluginTarget = PluginTarget(host)
    }

    /** Open the SFTP browser: reuse a CONNECTED session for this host, else connect a SHELL session. */
    fun browseFiles(host: Host) {
        readyService() ?: return
        val existing = sessions.connectedTo(host)
        if (existing != null) {
            state.showSession(existing.id, AppPage.FILES)
            return
        }
        // Prefer a short-lived SHELL session so browsing does not create a remote tmux session.
        connect(host, SessionMode.SHELL, openFilesAfterConnect = true)
    }

    /** System share sheet → SFTP upload (+ Codex/Grok paste) on a connected session. */
    fun uploadSharedImages(session: TerminalConnection, uris: List<Uri>) {
        if (uris.isEmpty()) return
        state.showSession(session.id)
        if (!session.transfers.uploadSharedImages(context, session, uris))
            toast(tr("已有传输进行中，请稍后再试"))
    }

    /** Shared images go to the one connected session; with several, they wait for the user to pick one. */
    fun routeSharedImages(uris: List<Uri>, available: List<TerminalConnection>) {
        if (uris.isEmpty()) return
        val connected = available.filter { it.state.value.phase == Phase.CONNECTED }
        when {
            connected.isEmpty() -> {
                toast(tr("请先连接一台主机后再分享图片"))
                state.page = AppPage.HOME
            }
            connected.size == 1 -> uploadSharedImages(connected.first(), uris)
            else -> service?.pendingSharedImages?.let { it.value = it.value + uris }
        }
    }

    fun dismissSharedImages() { service?.pendingSharedImages?.value = emptyList() }
}
