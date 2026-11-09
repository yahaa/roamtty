package io.itermux.app.ui

import io.itermux.app.desktop.DesktopThumbnails
import io.itermux.app.appSettings
import io.itermux.app.tr

import android.Manifest
import android.net.Uri
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import android.os.SystemClock
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.AppLocale
import io.itermux.app.TerminalFonts
import io.itermux.app.trf
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.data.newSessionMode
import io.itermux.app.data.multiplexer
import io.itermux.app.data.recentFirst
import io.itermux.app.session.*
import io.itermux.app.desktop.DesktopSettings
import io.itermux.desktop.DesktopButton
import io.itermux.desktop.DesktopPhase
import io.itermux.desktop.DesktopProbe
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import io.itermux.app.data.credentialsFor
import io.itermux.app.data.needsTypedSecret

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItermuxScreen(
    app: ItermuxApp,
    service: SessionService?,
    pendingShareImages: List<Uri> = emptyList(),
    onShareImagesConsumed: () -> Unit = {},
    /** A host whose desktop the notification asked to show. */
    pendingDesktopHost: String? = null,
    onDesktopHostConsumed: () -> Unit = {},
    /** A session an agent notification asked to show. */
    pendingSession: String? = null,
    onSessionConsumed: () -> Unit = {},
    /** Terminal chrome, light theme, and whether the system's back gesture works while the bars are hidden (the desktop). */
    onChromeChange: (Boolean, Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val hostFlow = remember(app) { app.database.hosts().observe() }
    val loadedHosts by hostFlow.collectAsStateWithLifecycle(initialValue = null)
    val recentUsage = app.recentUsage
    val recentHosts by recentUsage.hostIds.collectAsStateWithLifecycle()
    val recentSessions by recentUsage.sessionIds.collectAsStateWithLifecycle()
    // A state, not a remembered value: after a rotation the hosts load while an inner scope that reads loadedHosts
    // recomposes on its own, and a captured list would still be the empty one from before, sending the host page home.
    val hosts by remember { derivedStateOf { loadedHosts.orEmpty().recentFirst(recentHosts) { it.id } } }
    val sessionFlow = remember(service) { service?.sessions ?: MutableStateFlow(emptyList()) }
    val sessions by sessionFlow.collectAsStateWithLifecycle()
    val sessionStates by remember(sessions) {
        if (sessions.isEmpty()) flowOf(emptyMap())
        else combine(sessions.map { session -> session.state.map { session.id to it } }) { it.toMap() }
    }.collectAsStateWithLifecycle(emptyMap())
    // The terminal and file previews draw with the terminal font: load it while a session connects, not on its first frame.
    val anySession = sessions.isNotEmpty()
    LaunchedEffect(anySession) { if (anySession) TerminalFonts.preload(context) }
    val hostReachability = remember { HostReachabilityTracker() }
    // Recent use leads within each home group and in the drawer; terminal tabs keep their open order.
    val sessionsByRecency = remember(sessions, sessionStates, recentSessions) {
        sessions.sortedBy { activityRank(sessionStates[it.id] ?: it.state.value) }.recentFirst(recentSessions) { it.id }
    }
    LaunchedEffect(loadedHosts, sessions, service) {
        val savedHosts = loadedHosts ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        val restored = bound.sessions.value
        // A newly saved host can already have a session before Room emits its updated host list.
        recentUsage.retain((savedHosts.map { it.id } + restored.map { it.host.id }).toSet(), restored.map { it.id }.toSet())
    }
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    val configuration = LocalConfiguration.current
    val landscape = isLandscape()
    val settings = remember(context) { context.appSettings() }
    var theme by remember { mutableStateOf(settings.appTheme()) }
    var settingsReturnPage by rememberSaveable { mutableStateOf(AppPage.HOME) }
    /** The host whose detail page is shown on [AppPage.HOST]. */
    var detailHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val lightTheme = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM && configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
    /** The host whose desktop is shown on [AppPage.DESKTOP]. */
    var desktopHostId by rememberSaveable { mutableStateOf<String?>(null) }
    /** The host whose desktop shows in the small window over the terminal (while one of its sessions is shown). */
    var pipDesktopHostId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Set when the desktop page was opened from the terminal's small window: leaving it goes back to that terminal. */
    var desktopFromTerminal by rememberSaveable { mutableStateOf(false) }
    val desktopTargets = remember(context) { runCatching { DesktopSettings.manifest(context).targets.keys }.getOrDefault(emptySet()) }
    val desktopFlow = remember(service) { service?.desktops?.connections ?: MutableStateFlow(emptyMap()) }
    val desktops by desktopFlow.collectAsStateWithLifecycle()
    val desktopVisible = page == AppPage.DESKTOP
    val terminalVisible = page == AppPage.TERMINAL && sessions.isNotEmpty()
    val navigationVisible = page == AppPage.HOME
    // The file browser wears the terminal's chrome: a transparent status bar and no navigation bar.
    val terminalChrome = terminalVisible || page == AppPage.FILES || desktopVisible
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalChrome, imeVisible, lightTheme, landscape, desktopVisible) { onChromeChange(terminalChrome, lightTheme, desktopVisible) }
    BackHandler(page == AppPage.SETTINGS && !drawerState.isOpen) { page = settingsReturnPage }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Only visible navigation counts as use. Reconnects, polling and recomposition never update this order.
    LaunchedEffect(page, selected, detailHostId, desktopHostId, service) {
        when (page) {
            AppPage.TERMINAL, AppPage.FILES -> {
                val bound = service ?: return@LaunchedEffect
                bound.awaitRestoration()
                bound.sessions.value.find { it.id == selected }?.let { recentUsage.useSession(it.id, it.host.id) }
            }
            AppPage.HOST -> detailHostId?.let(recentUsage::useHost)
            AppPage.DESKTOP -> desktopHostId?.let(recentUsage::useHost)
            else -> Unit
        }
    }
    // A failed desktop stays for its page to explain; once that page is left it goes, with its secrets.
    LaunchedEffect(page, desktopHostId, service) {
        val registry = service?.desktops ?: return@LaunchedEffect
        registry.connections.value.keys.filter { page != AppPage.DESKTOP || it != desktopHostId }.forEach(registry::dropEnded)
    }
    /** The home page's tabs: sessions first, then hosts. */
    val homePager = rememberPagerState { 2 }
    /** Shows session [id] on the terminal page, or on [on]. */
    fun showSession(id: String, on: AppPage = AppPage.TERMINAL) {
        selected = id
        page = on
    }
    /** Opens the detail page of the selected session's host; the host list when that host is gone. */
    fun showSessionHost() {
        val hostId = sessions.find { it.id == selected }?.host?.id
        if (hostId != null && hosts.any { it.id == hostId }) {
            detailHostId = hostId
            page = AppPage.HOST
        } else page = AppPage.HOME
    }
    /** Leaves the terminal for the home page on its sessions tab. */
    fun leaveTerminal() {
        page = AppPage.HOME
        homePager.requestScrollToPage(0)
    }
    BackHandler(page == AppPage.TERMINAL) { leaveTerminal() }
    LaunchedEffect(page) { if (page != AppPage.DESKTOP) desktopFromTerminal = false }
    LaunchedEffect(service, sessions, selected, page) {
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        if ((page == AppPage.TERMINAL || page == AppPage.FILES) && bound.sessions.value.none { it.id == selected }) page = AppPage.HOME
    }
    var search by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val hostGridState = rememberLazyGridState()
    // The empty page removes the grid, but its state still holds recycled rows and their session callbacks.
    // Drop that layout when the last session goes; there is no scroll position left to preserve.
    val sessionGridState = key(sessions.isEmpty()) { rememberLazyGridState() }
    var previousHostOrder by remember { mutableStateOf(recentHosts) }
    LaunchedEffect(recentHosts) {
        if (recentHosts != previousHostOrder) {
            hostGridState.requestScrollToItem(0)
            previousHostOrder = recentHosts
        }
    }
    BackHandler(page == AppPage.HOME && homePager.currentPage != 0 && !searchExpanded && !drawerState.isOpen) {
        scope.launch { homePager.animateScrollToPage(0) }
    }
    var previousLandscape by rememberSaveable { mutableStateOf(landscape) }
    LaunchedEffect(landscape) {
        if (landscape != previousLandscape) {
            hostGridState.scrollToItem(hostGridState.firstVisibleItemIndex)
            previousLandscape = landscape
        }
    }
    val searchFocus = remember { FocusRequester() }
    BackHandler(navigationVisible && searchExpanded && !imeVisible && !drawerState.isOpen) {
        searchExpanded = false
        search = ""
    }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteHost by remember { mutableStateOf<Host?>(null) }
    /** A launch on a host: from its menu (the agent chosen on the launch sheet) or from 新建会话 (the agent picked first). */
    var launchRequest by remember { mutableStateOf<LaunchRequest?>(null) }
    /** The host of the 继续会话 sheet; saved, as turning the phone recreates the activity. */
    var continueOnId by rememberSaveable { mutableStateOf<String?>(null) }
    /** The home page's "new session" sheet, which picks the host before the launch sheet. */
    var newSessionOpen by rememberSaveable { mutableStateOf(false) }
    /** Set while the host editor fills in a new host for a new session: the agent it starts, or "" for a terminal. */
    var newHostFor by rememberSaveable { mutableStateOf<String?>(null) }
    /** A host just saved for a new session, waiting for the host list to carry it: its id, and the agent it starts. */
    var savedForSession by remember { mutableStateOf<Pair<String, CodingAgent?>?>(null) }
    var connectRequest by remember { mutableStateOf<ConnectRequest?>(null) }
    /** Session id → when it last reconnected by itself on open; see [AUTO_RECONNECT_INTERVAL_MS]. */
    val autoReconnectedAt = remember { mutableMapOf<String, Long>() }
    var terminatePrompt by remember { mutableStateOf<PendingPrompt?>(null) }
    var reverify by remember { mutableStateOf<TerminalConnection?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var pluginTarget by remember { mutableStateOf<PluginTarget?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    /** The bound service, or null after telling the user it is not ready yet. */
    fun readyService(): SessionService? = service ?: run { toast(tr("会话服务尚未就绪，请稍后重试")); null }
    /** The saved copy of [host]: one edited since a session copied it (a password saved, plugins found) wins. */
    fun savedHost(host: Host) = hosts.firstOrNull { it.id == host.id } ?: host
    /** Whether [session] is connected, read from the collected states so the screen recomposes when it changes. */
    fun connected(session: TerminalConnection) = (sessionStates[session.id] ?: session.state.value).phase == Phase.CONNECTED
    /** [open] shows the session once it connects; a reconnect from the session list stays on the list. */
    fun start(request: ConnectRequest, password: String = "", passphrase: String = "", open: Boolean = true) {
        val bound = readyService() ?: return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch {
            var credentials: Credentials? = null
            try {
                credentials = withContext(Dispatchers.IO) { app.credentialsFor(request.host, password, passphrase) }
                request.terminateSessionId?.let { id ->
                    toast(trf("正在终止远端 %s 会话…", request.host.multiplexer.id))
                    val failure = bound.terminate(id, credentials) { prompt ->
                        val pending = PendingPrompt(prompt)
                        terminatePrompt = pending
                        try { pending.answer.await() } finally { if (terminatePrompt === pending) terminatePrompt = null }
                    }
                    toast(failure?.let { trf("未能终止远端会话：%s。长按卡片可强制删除", it) } ?: tr("远端 tmux 会话已终止，卡片已移除"))
                    return@launch
                }
                val session = bound.openFor(request, credentials)
                if (!open) return@launch
                showSession(session.id, if (request.openFilesAfterConnect) AppPage.FILES else AppPage.TERMINAL)
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
        val host = request.host
        if (host.needsTypedSecret(app)) connectRequest = request
        else start(request)
    }
    fun connect(host: Host, mode: SessionMode, title: String = "", reuseSessionId: String? = null, openFilesAfterConnect: Boolean = false) {
        begin(ConnectRequest(host, mode, title, reuseSessionId, openFilesAfterConnect))
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
        if (session.host.needsTypedSecret(app)) {
            bound.forget(session.id)
            toast(trf("会话卡片已移除，但未能终止远端会话：%s", tr("没有已保存的登录信息")))
            return
        }
        scope.launch {
            val failure = try {
                val credentials = withContext(Dispatchers.IO) { app.credentialsFor(session.host, "", "") }
                try { bound.forceDelete(session.id, credentials) } finally { credentials.clear() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                bound.forget(session.id)
                e.message ?: tr("无法读取凭据，请重新导入密钥或保存密码。")
            }
            failure?.let { toast(trf("会话卡片已移除，但未能终止远端会话：%s", it)) }
        }
    }

    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    // The session in front of the user needs no notification.
    val viewing = selected.takeIf { page == AppPage.TERMINAL }
    LaunchedEffect(service, viewing) { service?.setViewing(viewing) }
    val onSessionsTab = page == AppPage.HOME && homePager.currentPage == 0 && resumed.isAtLeast(Lifecycle.State.RESUMED)
    /** A live session to the same endpoint already shows the host is up, without probing it. */
    fun hostConnected(host: Host) = service?.sessions?.value.orEmpty().any {
        it.host.endpointKey == host.endpointKey && it.state.value.phase == Phase.CONNECTED
    }
    ReconnectFromSessionList(onSessionsTab, service, sessions, hosts, hostReachability, autoReconnectedAt, ::hostConnected) { host, session ->
        start(session.reconnectRequest(host), open = false)
    }
    /** tmux restarts the pane over the live connection; a plain shell gets a fresh one by reconnecting. */
    fun restartSession(session: TerminalConnection) {
        if (session.mode != SessionMode.TMUX) begin(session.reconnectRequest())
        else scope.launch { if (!session.restartPane()) toast(tr("未能重启会话，请检查连接后重试")) }
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
            showSession(it.id)
            return
        }
        begin(ConnectRequest(host, SessionMode.TMUX, attach = name))
    }

    /** The phone's screen in pixels and its density, which cap the desktop's encode size. */
    fun startDesktop(host: Host, password: String = "", passphrase: String = "", show: Boolean = true) {
        val bound = readyService() ?: return
        val metrics = context.resources.displayMetrics
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else metrics.widthPixels to metrics.heightPixels
        bound.openDesktop(host, password, passphrase, w, h, metrics.densityDpi)
        if (!show) return
        desktopHostId = host.id
        page = AppPage.DESKTOP
    }
    /** The desktop button: straight in when it is already open, else log in (asking for an unsaved secret first). */
    fun openDesktop(host: Host) {
        val bound = readyService() ?: return
        val existing = bound.desktops[host.id]
        if (existing != null && existing.active) {
            desktopHostId = host.id
            page = AppPage.DESKTOP
            return
        }
        if (host.needsTypedSecret(app)) connectRequest = ConnectRequest(host, SessionMode.SHELL, desktop = true)
        else startDesktop(host)
    }
    /** The desktop page takes the screen from the terminal's small window; leaving it comes back to the terminal. */
    fun desktopFromSmallWindow(hostId: String) {
        desktopHostId = hostId; desktopFromTerminal = true; page = AppPage.DESKTOP
    }
    /** The dock button's long press: the host's desktop in the small window over the terminal, or gone again. */
    fun toggleDesktopPip(host: Host) {
        if (pipDesktopHostId == host.id) { pipDesktopHostId = null; return }
        val existing = service?.desktops?.get(host.id)
        if (existing == null || !existing.active) {
            // Signing in needs the desktop page when a secret must be typed; otherwise it connects in the small window.
            if (host.needsTypedSecret(app)) return openDesktop(host)
            startDesktop(host, show = false)
        }
        pipDesktopHostId = host.id
    }
    // A small window whose desktop went away goes with it.
    LaunchedEffect(desktops) {
        if (pipDesktopHostId != null && desktops[pipDesktopHostId] == null && service?.desktops?.get(pipDesktopHostId!!) == null) pipDesktopHostId = null
    }
    fun desktopSupported(host: Host): Boolean {
        val system = HostSystem.fromStored(host.os)
        val family = when { system.isLinux -> "linux-"; system == HostSystem.MACOS -> "macos-"; system == HostSystem.WINDOWS -> "windows-"; else -> return false }
        return desktopTargets.any { it.startsWith(family) }
    }
    /** The desktop button of [host], as [DesktopButton.derive] tells it from the host's probe and its desktop's [phase]. */
    fun desktopButton(host: Host, online: Boolean, sessionConnected: Boolean, phase: DesktopPhase) = DesktopButton.derive(
        online, sessionConnected, HostSystem.fromStored(host.os).let { if (it.isLinux) "LINUX" else it.name },
        desktopSupported(host), DesktopProbe.decode(host.desktop), host.desktopProbedAt, System.currentTimeMillis(), phase)
    LaunchedEffect(pendingSession, service) {
        val id = pendingSession ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        // A session closed since the notification came leaves the app where it was.
        if (bound.sessions.value.any { it.id == id }) showSession(id)
        onSessionConsumed()
    }
    LaunchedEffect(pendingDesktopHost, service) {
        val id = pendingDesktopHost ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        if (bound.desktops[id] != null) { desktopHostId = id; page = AppPage.DESKTOP }
        onDesktopHostConsumed()
    }

    fun managePlugins(host: Host) {
        if (host.needsTypedSecret(app))
            connectRequest = ConnectRequest(host, host.newSessionMode, plugins = true)
        else pluginTarget = PluginTarget(host)
    }

    /** Open the SFTP browser: reuse a CONNECTED session for this host, else connect a SHELL session. */
    fun browseFiles(host: Host) {
        readyService() ?: return
        val existing = sessions.connectedTo(host)
        if (existing != null) {
            showSession(existing.id, AppPage.FILES)
            return
        }
        // Prefer a short-lived SHELL session so browsing does not create a remote tmux session.
        connect(host, SessionMode.SHELL, openFilesAfterConnect = true)
    }

    // System share sheet → SFTP upload (+ Codex/Grok paste) on a connected session.
    val shareFlow = remember(service) { service?.pendingSharedImages ?: MutableStateFlow(emptyList()) }
    val shareUrisPending by shareFlow.collectAsStateWithLifecycle()
    fun uploadSharedImages(session: TerminalConnection, uris: List<Uri>) {
        if (uris.isEmpty()) return
        showSession(session.id)
        if (!session.transfers.uploadSharedImages(context, session, uris))
            toast(tr("已有传输进行中，请稍后再试"))
    }
    fun routeSharedImages(uris: List<Uri>, available: List<TerminalConnection>) {
        if (uris.isEmpty()) return
        val connected = available.filter { it.state.value.phase == Phase.CONNECTED }
        when {
            connected.isEmpty() -> {
                toast(tr("请先连接一台主机后再分享图片"))
                page = AppPage.HOME
            }
            connected.size == 1 -> uploadSharedImages(connected.first(), uris)
            else -> {
                service?.pendingSharedImages?.value = shareUrisPending + uris
            }
        }
    }
    LaunchedEffect(pendingShareImages, service) {
        if (pendingShareImages.isEmpty() || service == null) return@LaunchedEffect
        service.awaitRestoration()
        val uris = pendingShareImages.toList()
        routeSharedImages(uris, service.sessions.value)
        onShareImagesConsumed()
    }
    LaunchedEffect(searchExpanded, page) { if (searchExpanded && navigationVisible) searchFocus.requestFocus() }
    MaterialTheme(colorScheme = if (lightTheme) LightColors else DarkColors, shapes = AppShapes, typography = AppTypography) {
        if (loadedHosts == null) {
            StartupLoading()
            return@MaterialTheme
        }
        val colors = MaterialTheme.colorScheme
        ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = !terminalVisible && page != AppPage.FILES && page != AppPage.HOST && !desktopVisible,
            drawerContent = {
                ItermuxDrawer(page, hosts.size, sessionsByRecency,
                    onHosts = { page = AppPage.HOME; homePager.requestScrollToPage(1); scope.launch { drawerState.close() } },
                    onOpenSession = { showSession(it); scope.launch { drawerState.close() } },
                    onSettings = {
                        if (page != AppPage.SETTINGS) settingsReturnPage = page
                        page = AppPage.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    onGuide = { scope.launch { drawerState.close() }; showAbout = true },
                    onClose = { scope.launch { drawerState.close() } })
            }) {
        // A session's host is offline when nothing connects to it and its last probe found it down.
        fun sessionHostOffline(session: TerminalConnection) = hosts.firstOrNull { it.id == session.host.id }
            ?.let { !hostConnected(it) && hostReachability.statusOf(it) == HostReachability.Offline } == true
        val sessionGroups = rememberSessionGroups(sessionsMatching(sessionsByRecency, search), ::sessionHostOffline)
        val sessionFilter = rememberSaveable { mutableStateOf<String?>(null) }
        // In landscape the home page has one short bar, which slides away as a list scrolls on and comes back as soon
        // as it scrolls back; it stays while the search is open.
        val compactHome = landscape && navigationVisible
        val barHeight = with(LocalDensity.current) { HOME_COMPACT_BAR_HEIGHT.toPx() }
        var barOffset by remember { mutableFloatStateOf(0f) }
        val barSlides = compactHome && !searchExpanded
        LaunchedEffect(barSlides) { if (!barSlides) barOffset = 0f }
        // A scroll that skips nested scrolling (scrolling to an item, a reset to the top) can bring the list back to its
        // first row with the bar still away: the bar follows it back.
        val homeListAtTop by remember(sessionGridState, hostGridState, homePager) {
            derivedStateOf {
                (if (homePager.currentPage == 0) sessionGridState else hostGridState)
                    .let { it.firstVisibleItemIndex == 0 && it.firstVisibleItemScrollOffset == 0 }
            }
        }
        LaunchedEffect(homeListAtTop, barSlides) {
            if (homeListAtTop && barSlides && barOffset < 0f) animate(barOffset, 0f) { value, _ -> barOffset = value }
        }
        val barScroll = remember(barSlides, barHeight) {
            object : NestedScrollConnection {
                // The bar moves first; the list scrolls by what is left.
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!barSlides) return Offset.Zero
                    val before = barOffset
                    barOffset = (before + available.y).coerceIn(-barHeight, 0f)
                    return Offset(0f, barOffset - before)
                }

                // Never left half way: to whichever end is nearer.
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (barSlides && barOffset > -barHeight && barOffset < 0f)
                        animate(barOffset, if (barOffset < -barHeight / 2) -barHeight else 0f) { value, _ -> barOffset = value }
                    return Velocity.Zero
                }
            }
        }
        fun toggleSearch() {
            if (searchExpanded) {
                search = ""
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            searchExpanded = !searchExpanded
        }
        val openDrawer = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            scope.launch { drawerState.open() }
            Unit
        }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            // The terminal fills the display, including the area beside a camera cutout and under the
            // transparent portrait status bar. Only desktop window chrome reserves an edge; the terminal
            // keeps its text and controls clear of the status bar and cutouts itself.
            val contentInsets = if (terminalVisible) WindowInsets.captionBar else if (desktopVisible) WindowInsets(0) else WindowInsets.safeDrawing
            // The terminal applies the keyboard inset itself, in one step (see TerminalPane).
            val imeInsets = if (terminalVisible || desktopVisible) WindowInsets(0) else WindowInsets.ime
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).windowInsetsPadding(imeInsets)) {
                val toolbarVisible = (!imeVisible || searchExpanded) && !terminalVisible && page != AppPage.FILES && page != AppPage.HOST && !desktopVisible
                if (toolbarVisible && compactHome) HomeCompactBar(listOf(tr("会话"), tr("主机")), listOf(sessions.size, hosts.size),
                    homePager.currentPage, onSelect = { scope.launch { homePager.animateScrollToPage(it) } },
                    leading = { ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo"), onClick = openDrawer) },
                    middle = {
                        if (homePager.currentPage == 0 && sessionGroups.size > 1)
                            SessionFilters(sessionGroups, sessionGroups.sumOf { it.members.size },
                                filteredGroup(sessionGroups, sessionFilter.value)) { sessionFilter.value = it?.name }
                    },
                    trailing = {
                        AppSearchToggle(searchExpanded, onToggle = ::toggleSearch,
                            searchDescription = tr(if (homePager.currentPage == 0) "搜索会话" else "搜索主机"),
                            modifier = Modifier.testTag("search-toggle"))
                        if (homePager.currentPage == 0) ToolbarIcon(R.drawable.ic_add, tr("新建会话"), Modifier.testTag("add-session")) {
                            newSessionOpen = true
                        } else ToolbarIcon(R.drawable.ic_add, tr("添加主机"), Modifier.testTag("add-host")) {
                            editHostId = null; editorOpen = true
                        }
                    },
                    // Slid up by [barOffset], and only as tall as what still shows, so the list takes the rest.
                    modifier = Modifier.clipToBounds().layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val shown = (placeable.height + barOffset.roundToInt()).coerceAtLeast(0)
                        layout(placeable.width, shown) { placeable.place(0, barOffset.roundToInt()) }
                    })
                // Settings in landscape: the flat bar of every other landscape page, the drawer at its start.
                else if (toolbarVisible && landscape && page == AppPage.SETTINGS) LandscapeBar(tr("设置"), leading = {
                    ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo"), onClick = openDrawer)
                }) {
                    ToolbarIcon(R.drawable.ic_close, tr("关闭设置"), Modifier.testTag("close-settings")) { page = settingsReturnPage }
                }
                else if (toolbarVisible) AppToolbar(
                    navigation = {
                        // As wide as the search on the right, so the home tabs sit in the middle of the screen.
                        Box(Modifier.width(48.dp)) {
                            ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo"), onClick = openDrawer)
                        }
                    },
                    actions = {
                        if (navigationVisible) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                HomeTabs(listOf(tr("会话"), tr("主机")), homePager.currentPage,
                                    onSelect = { scope.launch { homePager.animateScrollToPage(it) } })
                            }
                            AppSearchToggle(searchExpanded, onToggle = ::toggleSearch, searchDescription = tr(if (homePager.currentPage == 0) "搜索会话" else "搜索主机"),
                                modifier = Modifier.testTag("search-toggle"))
                        } else {
                            // Settings is a drawer destination: closing returns to the page it was opened from.
                            Spacer(Modifier.weight(1f))
                            ToolbarIcon(R.drawable.ic_close, tr("关闭设置"), Modifier.testTag("close-settings")) { page = settingsReturnPage }
                        }
                    })
                if (page == AppPage.HOME) {
                    Column(Modifier.fillMaxSize()) {
                        if (searchExpanded) {
                            AppSearchField(search, { search = it },
                                tr(if (homePager.currentPage == 0) "标题、主机、地址或用户名" else "名称、地址或用户名"),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).padding(bottom = Spacing.Tight)
                                    .focusRequester(searchFocus).testTag("host-search"))
                        }
                        // A live terminal or desktop already proves the host is up. Match by address:port so several
                        // host cards pointing at the same server skip the unauthenticated SSH greeting probe together.
                        val liveTargets = buildSet {
                            sessions.filter(::connected)
                                .forEach { add("${it.host.address}:${it.host.port}") }
                            desktops.values.forEach { c ->
                                // Only a desktop streaming now: connecting or recovering proves nothing about the host.
                                if (c.phase.value is DesktopPhase.Connected) add("${c.host.address}:${c.host.port}")
                            }
                        }
                        ProbeHostReachability(hostReachability, hosts,
                            connected = hosts.filter { "${it.address}:${it.port}" in liveTargets }.map { it.id }.toSet())
                        HorizontalPager(homePager, Modifier.weight(1f).nestedScroll(barScroll).testTag("home-pager"),
                            key = { HOME_TAB_TAGS[it] }) { tab ->
                            if (tab == 0) HomeSessionsTab(sessionsByRecency, hosts.isNotEmpty(), search,
                                onNewSession = { newSessionOpen = true },
                                onOpen = { showSession(it.id) },
                                onClose = ::closeSession,
                                onForget = ::forceDelete,
                                hostOffline = ::sessionHostOffline,
                                groups = sessionGroups,
                                filter = sessionFilter,
                                compact = compactHome,
                                gridState = sessionGridState,
                                desktop = { session ->
                                    val host = savedHost(session.host)
                                    val connection = desktops[host.id]
                                    val idle = remember { MutableStateFlow<DesktopPhase>(DesktopPhase.Idle) }
                                    val desktopPhase by (connection?.phase ?: idle).collectAsStateWithLifecycle()
                                    val sessionState by session.state.collectAsStateWithLifecycle()
                                    val offline = !hostConnected(host) && hostReachability.statusOf(host) == HostReachability.Offline
                                    val button = desktopButton(host, !offline, sessionState.phase == Phase.CONNECTED, desktopPhase)
                                    DesktopCardButton(button, "home-session-desktop-${session.id}") { openDesktop(host) }
                                })
                            else HomeHostsTab(hosts, search, hostGridState,
                                reachability = { host ->
                                    hostReachability.statusOf(host).let { probed ->
                                        if ("${host.address}:${host.port}" in liveTargets && probed !is HostReachability.Online) HostReachability.Online(null) else probed
                                    }
                                },
                                onAdd = { editHostId = null; editorOpen = true },
                                onOpen = { host -> detailHostId = host.id; page = AppPage.HOST },
                                onEdit = { host -> editHostId = host.id; editorOpen = true },
                                onDelete = { host -> deleteHost = host },
                                onBrowseFiles = ::browseFiles,
                                onManagePlugins = ::managePlugins,
                                onLaunch = { host, forAgent -> launchRequest = LaunchRequest(host, forAgent) },
                                desktop = { host ->
                                    val idle = remember { MutableStateFlow<DesktopPhase>(DesktopPhase.Idle) }
                                    val desktopPhase by (desktops[host.id]?.phase ?: idle).collectAsStateWithLifecycle()
                                    // A host needs no open session for its desktop: the desktop logs in on its own. An
                                    // offline one keeps its picture, so it is derived as if online; its card knows better.
                                    desktopButton(host, online = true, sessionConnected = true, desktopPhase)
                                },
                                onDesktop = ::openDesktop,
                                onDisconnectDesktop = { host -> service?.desktops?.close(host.id) },
                                compact = compactHome)
                        }
                    }
                } else if (page == AppPage.HOST) {
                    val detailHost = hosts.firstOrNull { it.id == detailHostId }
                    if (detailHost != null) key(detailHost.id) {
                        val detailConnected = sessions.any { it.host.id == detailHost.id && connected(it) }
                        HostDetailPage(detailHost,
                            hostReachability.statusOf(detailHost).let { if (detailConnected && it !is HostReachability.Online) HostReachability.Online(null) else it },
                            onBack = { page = AppPage.HOME },
                            onLaunch = { directory, agent -> launchOn(detailHost, directory, agent) },
                            onBrowseFiles = { browseFiles(detailHost) },
                            onEdit = { editHostId = detailHost.id; editorOpen = true },
                            onManagePlugins = { managePlugins(detailHost) },
                            onDelete = { deleteHost = detailHost },
                            modifier = Modifier.weight(1f),
                            onDesktop = { openDesktop(detailHost) }.takeIf {
                                desktopSupported(detailHost) && DesktopProbe.decode(detailHost.desktop) != DesktopProbe.NO
                            })
                    } else if (loadedHosts != null) LaunchedEffect(Unit) { page = AppPage.HOME } // deleted, or gone after a restore
                } else if (page == AppPage.FILES) {
                    val filesSession = sessions.find { it.id == selected }
                    if (filesSession != null) {
                        key(filesSession) {
                            // Its back arrow says 返回主机详情: the files return to their host's page, as on iOS.
                            RemoteFileBrowserPage(filesSession, onBack = ::showSessionHost, Modifier.weight(1f),
                                // A new terminal session (tmux when the host has it) so the agent outlives the browser.
                                onLaunchAgent = { launch ->
                                    begin(launchRequest(savedHost(filesSession.host), launch.directory, launch))
                                }, onOpenTerminal = { directory ->
                                    launchOn(savedHost(filesSession.host), directory, null)
                                })
                        }
                    } else {
                        EmptyState(tr("会话不存在"), tr("连接已关闭，请从主机列表重新打开文件。"), Modifier.fillMaxSize(),
                            action = { FilledTonalButton(onClick = { page = AppPage.HOME }) { Text(tr("返回主机列表")) } })
                    }
                } else if (page == AppPage.DESKTOP) {
                    // The registry itself, not only the collected map: right after the service binds again (rotation),
                    // the map still holds the empty value it had before.
                    val connection = desktops[desktopHostId] ?: desktopHostId?.let { service?.desktops?.get(it) }
                    if (connection != null) key(connection) {
                        // The buttons capture the host, not the connection: Compose keeps the last tapped node (and its
                        // lambda) until the next touch, which would hold a closed desktop's buffers till then.
                        val desktopHost = connection.host
                        DesktopPage(connection, onLeave = {
                            // Opened from the terminal's small window: back to that terminal, the desktop small again.
                            if (desktopFromTerminal && sessions.any { it.id == selected }) page = AppPage.TERMINAL else page = AppPage.HOME
                        },
                            onDisconnect = { service?.desktops?.close(desktopHost.id); page = AppPage.HOME },
                            onFiles = { browseFiles(savedHost(desktopHost)) },
                            modifier = Modifier.weight(1f))
                    // A recreated activity (rotation) binds the service again first; only a desktop that is really gone goes home.
                    } else if (service != null) LaunchedEffect(Unit) { page = AppPage.HOME }
                } else if (page == AppPage.SETTINGS) {
                    SettingsPage(theme, { option ->
                        theme = option
                        settings.saveAppTheme(option)
                    }, AppLocale.language, { option -> AppLocale.set(context, option); service?.refreshLanguage() })
                } else if (sessions.isEmpty()) {
                    EmptyState(tr("终端已就绪"), tr("连接一台主机，开始远端工作。"), Modifier.fillMaxSize(),
                        action = { FilledTonalButton(onClick = { page = AppPage.HOME }) { Text(tr("返回主机列表")) } })
                } else {
                    val active = sessions.find { it.id == selected }
                    // Reconnecting preserves the journal id but replaces the transport and emulator.
                    if (active != null) key(active) {
                        // The saved host, not the session's copy: a password saved since then spares a prompt.
                        val reconnect = { begin(active.reconnectRequest(savedHost(active.host))) }
                        val activeState by active.state.collectAsStateWithLifecycle()
                        val autoReconnect = reconnectsOnOpen(activeState)
                        // Set once the automatic attempt left this entry disconnected, e.g. a dismissed password prompt.
                        var autoTried by remember { mutableStateOf(false) }
                        LaunchedEffect(autoReconnect) {
                            if (!autoReconnect) return@LaunchedEffect
                            val last = autoReconnectedAt[active.id]
                            if (last != null) delay(last + AUTO_RECONNECT_INTERVAL_MS - SystemClock.elapsedRealtime())
                            autoReconnectedAt[active.id] = SystemClock.elapsedRealtime()
                            autoTried = true
                            reconnect()
                        }
                        TerminalPane(active, Modifier.weight(1f),
                            sessions = sessions, lightTheme = lightTheme, onSelectSession = { selected = it },
                            onHostDetail = ::showSessionHost,
                            onLeave = ::leaveTerminal,
                            onReconnect = reconnect.takeUnless { autoReconnect && !autoTried },
                            onRestart = { restartSession(active) },
                            onShell = { service?.close(active.id); service?.forget(active.id); connect(active.host, SessionMode.SHELL, active.title.value) },
                            onReverify = { reverify = active },
                            // Closing ends the terminal page too: back to where it was opened from.
                            onClose = { leaveTerminal(); closeSession(active) },
                            onDesktop = savedHost(active.host).let { h ->
                                {
                                    // With the desktop in the small window, the button does what a tap on it does.
                                    if (pipDesktopHostId == h.id && desktops[h.id] != null) desktopFromSmallWindow(h.id)
                                    else openDesktop(h)
                                }.takeIf { desktopSupported(h) && DesktopProbe.decode(h.desktop) == DesktopProbe.YES }
                            },
                            pip = savedHost(active.host).let { h ->
                                TerminalPip(desktop = pipDesktopHostId?.takeIf { it == h.id }?.let { desktops[it] },
                                    onTap = { desktopFromSmallWindow(h.id) }, onClose = { pipDesktopHostId = null },
                                    onToggle = { toggleDesktopPip(h) }.takeIf { desktopSupported(h) && DesktopProbe.decode(h.desktop) == DesktopProbe.YES })
                            })
                    }
                }
            }
        }
        }
        if (editorOpen) {
            val editHost = editHostId?.let { id -> hosts.firstOrNull { it.id == id } }
            if (editHostId == null || editHost != null) HostEditor(app, editHost,
                onDismiss = { editorOpen = false; editHostId = null },
                onSaved = { editorOpen = false; editHostId = null })
        }
        newHostFor?.let { name ->
            val agent = CodingAgent.entries.firstOrNull { it.name == name }
            HostEditor(app, null, forSession = true, onDismiss = { newHostFor = null },
                onSaved = { id -> newHostFor = null; savedForSession = id to agent })
        }
        savedForSession?.let { (id, agent) ->
            LaunchedEffect(hosts, id) {
                hosts.firstOrNull { it.id == id }?.let { savedForSession = null; launchRequest = LaunchRequest(it, agent != null, agent, newSession = true) }
            }
        }
        if (newSessionOpen) NewSessionSheet(hosts, onDismiss = { newSessionOpen = false },
            onNewHost = { newSessionOpen = false; newHostFor = it?.name.orEmpty() },
            onContinue = { newSessionOpen = false; continueOnId = it.id }) { host, agent ->
            newSessionOpen = false
            launchRequest = LaunchRequest(host, agent != null, agent, newSession = true)
        }
        continueOnId?.let { id -> hosts.firstOrNull { it.id == id } }?.let { host ->
            ContinueSessionSheet(host, sessions.openTmuxNames(host), onDismiss = { continueOnId = null },
                onNewTerminal = { continueOnId = null; launchOn(host, StartDirectory.HOME, null) },
                onInstall = { continueOnId = null; managePlugins(host) }) {
                continueOnId = null
                attachOn(host, it)
            }
        }
        launchRequest?.let { (host, forAgent, initialAgent, newSession) ->
            LaunchSheet(host, forAgent, monitor = null, onDismiss = { launchRequest = null }, initialAgent = initialAgent,
                title = if (newSession) tr(if (forAgent) "选择工作目录" else "新建会话") else null) { directory, agent ->
                launchRequest = null
                launchOn(host, directory, agent)
            }
        }
        deleteHost?.let { host ->
            DeleteHostDialog(host, onDismiss = { deleteHost = null }) {
                scope.launch {
                    // Keep remote tmux running; only drop the phone's links (and any desktop keep-alive).
                    sessions.filter { it.host.id == host.id }.forEach { service?.disconnectLocal(it.id, forgetCard = true) }
                    service?.desktops?.close(host.id)
                    app.database.hosts().delete(host.id)
                    DesktopThumbnails.remove(app, host.id)
                    PreviewStorage.forget(app, host.id)
                    deleteHost = null
                }
            }
        }
        connectRequest?.let { request -> CredentialsDialog(request, { connectRequest = null }) { password, phrase ->
            connectRequest = null
            if (request.plugins) pluginTarget = PluginTarget(request.host, password, phrase)
            else if (request.desktop) startDesktop(request.host, password, phrase)
            else start(request, password, phrase)
        } }
        terminatePrompt?.let { PromptDialog(it) }
        pluginTarget?.let { target ->
            val controller = remember(target) { HostPluginsController(app, target, scope).also { it.start() } }
            DisposableEffect(controller) { onDispose { controller.close() } }
            HostPluginsSheet(controller) { pluginTarget = null }
        }
        reverify?.let { session ->
            val endpoint = session.host.target().endpoint
            ReverifyHostDialog(endpoint, onDismiss = { reverify = null }) {
                scope.launch {
                    // Close local links only; remote tmux must keep running across a fingerprint re-check.
                    sessions.filter { it.host.target().endpoint == endpoint }.forEach {
                        service?.disconnectLocal(it.id, forgetCard = it.mode != SessionMode.TMUX || it.external)
                    }
                    service?.desktops?.connections?.value?.values
                        ?.filter { it.host.target().endpoint == endpoint }
                        ?.map { it.host.id }?.distinct()
                        ?.forEach { service?.desktops?.close(it) }
                    app.database.hosts().forgetPin(endpoint)
                    reverify = null
                    connect(session.host, session.mode, session.title.value, reuseSessionId = session.id.takeIf { session.mode == SessionMode.TMUX })
                }
            }
        }
        if (shareUrisPending.isNotEmpty()) {
            fun dismissShare() { service?.pendingSharedImages?.value = emptyList() }
            ShareTargetSheet(sessions.filter { it.state.value.phase == Phase.CONNECTED }, ::dismissShare) { session ->
                val uris = shareUrisPending
                dismissShare()
                uploadSharedImages(session, uris)
            }
        }
        if (showAbout) AboutDialog(context) { showAbout = false }
    }
}

/**
 * A launch sheet to open: on [host], for an agent or a terminal, with [agent] already picked when there is one.
 * [newSession]: the last step of a new session from the sessions tab, after what runs and the host were picked.
 */
private data class LaunchRequest(val host: Host, val forAgent: Boolean, val agent: CodingAgent? = null, val newSession: Boolean = false)
