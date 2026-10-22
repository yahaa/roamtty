package io.itermux.app.ui

import io.itermux.app.appSettings
import io.itermux.app.tr

import android.Manifest
import android.net.Uri
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalContext
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
import io.itermux.app.data.prefersMosh
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
    onChromeChange: (Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val hostFlow = remember(app) { app.database.hosts().observe() }
    val loadedHosts by hostFlow.collectAsStateWithLifecycle(initialValue = null)
    val hosts = loadedHosts.orEmpty()
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
    // The session list and drawer show live sessions first; terminal tabs keep their open order.
    val sessionsByActivity = remember(sessions, sessionStates) {
        sessions.sortedBy { activityRank(sessionStates[it.id] ?: it.state.value) }
    }
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val settings = remember(context) { context.appSettings() }
    var theme by remember { mutableStateOf(settings.appTheme()) }
    var settingsReturnPage by rememberSaveable { mutableStateOf(AppPage.HOME) }
    /** The host whose detail page is shown on [AppPage.HOST]. */
    var detailHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val lightTheme = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM && configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
    /** The host whose desktop is shown on [AppPage.DESKTOP]. */
    var desktopHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val desktopSettings = remember(context) { DesktopSettings(context) }
    var desktopEnabled by remember { mutableStateOf(desktopSettings.enabled) }
    DisposableEffect(settings) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "desktop.enabled") desktopEnabled = desktopSettings.enabled
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val desktopTargets = remember(context) { runCatching { DesktopSettings.manifest(context).targets.keys }.getOrDefault(emptySet()) }
    val desktopFlow = remember(service) { service?.desktops?.connections ?: MutableStateFlow(emptyMap()) }
    val desktops by desktopFlow.collectAsStateWithLifecycle()
    val desktopVisible = page == AppPage.DESKTOP
    val terminalVisible = page == AppPage.TERMINAL && sessions.isNotEmpty()
    val navigationVisible = page == AppPage.HOME
    // The file browser wears the terminal's chrome: a transparent status bar and no navigation bar.
    val terminalChrome = terminalVisible || page == AppPage.FILES || desktopVisible
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalChrome, imeVisible, lightTheme, landscape) { onChromeChange(terminalChrome, lightTheme) }
    BackHandler(page == AppPage.SETTINGS && !drawerState.isOpen) { page = settingsReturnPage }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
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
    /** Leaves the terminal or file browser for the home page on its sessions tab. */
    fun leaveTerminal() {
        page = AppPage.HOME
        homePager.requestScrollToPage(0)
    }
    BackHandler(page == AppPage.TERMINAL) { leaveTerminal() }
    LaunchedEffect(service, sessions, selected, page) {
        service?.awaitRestoration()
        if (service != null && page == AppPage.TERMINAL && service.sessions.value.none { it.id == selected }) page = AppPage.HOME
        if (service != null && page == AppPage.FILES && service.sessions.value.none { it.id == selected }) page = AppPage.HOME
    }
    var search by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val hostGridState = rememberLazyGridState()
    BackHandler(page == AppPage.HOME && homePager.currentPage != 0 && !searchExpanded && !drawerState.isOpen) {
        scope.launch { homePager.animateScrollToPage(0) }
    }
    LaunchedEffect(landscape) {
        hostGridState.scrollToItem(hostGridState.firstVisibleItemIndex)
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
    var continueOn by remember { mutableStateOf<Host?>(null) }
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
    /** [open] shows the session once it connects; a reconnect from the session list stays on the list. */
    fun start(request: ConnectRequest, password: String = "", passphrase: String = "", open: Boolean = true) {
        val bound = service ?: run { toast(tr("会话服务尚未就绪，请稍后重试")); return }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
    fun startDesktop(host: Host, password: String = "", passphrase: String = "") {
        val bound = service ?: run { toast(tr("会话服务尚未就绪，请稍后重试")); return }
        val metrics = context.resources.displayMetrics
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else metrics.widthPixels to metrics.heightPixels
        bound.openDesktop(host, password, passphrase, w, h, metrics.densityDpi)
        desktopHostId = host.id
        page = AppPage.DESKTOP
    }
    /** The desktop button: straight in when it is already open, else log in (asking for an unsaved secret first). */
    fun openDesktop(host: Host) {
        val bound = service ?: run { toast(tr("会话服务尚未就绪，请稍后重试")); return }
        val existing = bound.desktops[host.id]
        if (existing != null && existing.active) {
            desktopHostId = host.id
            page = AppPage.DESKTOP
            return
        }
        if (host.needsTypedSecret(app)) connectRequest = ConnectRequest(host, SessionMode.SHELL, desktop = true)
        else startDesktop(host)
    }
    fun desktopSupported(host: Host): Boolean {
        val system = HostSystem.fromStored(host.os)
        val family = when { system.isLinux -> "linux-"; system == HostSystem.MACOS -> "macos-"; system == HostSystem.WINDOWS -> "windows-"; else -> return false }
        return desktopTargets.any { it.startsWith(family) }
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
        val bound = service
        if (bound == null) {
            toast(tr("会话服务尚未就绪，请稍后重试"))
            return
        }
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
                ItermuxDrawer(page, hosts.size, sessionsByActivity,
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
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            // The terminal fills the display, including the area beside a camera cutout and under the
            // transparent portrait status bar. Only desktop window chrome reserves an edge; the terminal
            // keeps its text and controls clear of the status bar and cutouts itself.
            val contentInsets = if (terminalVisible) WindowInsets.captionBar else if (desktopVisible) WindowInsets(0) else WindowInsets.safeDrawing
            // The terminal applies the keyboard inset itself, in one step (see TerminalPane).
            val imeInsets = if (terminalVisible || desktopVisible) WindowInsets(0) else WindowInsets.ime
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).windowInsetsPadding(imeInsets)) {
                if ((!imeVisible || searchExpanded) && !terminalVisible && page != AppPage.FILES && page != AppPage.HOST && !desktopVisible) AppToolbar(
                    navigation = {
                        // As wide as the search on the right, so the home tabs sit in the middle of the screen.
                        Box(Modifier.width(48.dp)) {
                            ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo")) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                scope.launch { drawerState.open() }
                            }
                        }
                    },
                    actions = {
                        if (navigationVisible) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                HomeTabs(listOf(tr("会话"), tr("主机")), homePager.currentPage,
                                    onSelect = { scope.launch { homePager.animateScrollToPage(it) } })
                            }
                            AppSearchToggle(searchExpanded, onToggle = {
                                if (searchExpanded) {
                                    search = ""
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                                searchExpanded = !searchExpanded
                            }, searchDescription = tr(if (homePager.currentPage == 0) "搜索会话" else "搜索主机"),
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
                        // A live connection already proves the host is up, whatever the last probe said. Sessions keep
                        // the endpoint they were opened with, so one still on an edited host's old address does not count.
                        val connectedEndpoints = sessions.filter { (sessionStates[it.id]?.phase ?: it.state.value.phase) == Phase.CONNECTED }
                            .map { it.host.endpointKey }.toSet()
                        ProbeHostReachability(hostReachability, hosts,
                            connected = hosts.filter { it.endpointKey in connectedEndpoints }.map { it.id }.toSet())
                        HorizontalPager(homePager, Modifier.weight(1f).testTag("home-pager"), key = { HOME_TAB_TAGS[it] }) { tab ->
                            if (tab == 0) HomeSessionsTab(sessionsByActivity, hosts.isNotEmpty(), search,
                                onNewSession = { newSessionOpen = true },
                                onOpen = { showSession(it.id) },
                                onClose = ::closeSession,
                                onForget = ::forceDelete,
                                hostOffline = { session ->
                                    hosts.firstOrNull { it.id == session.host.id }
                                        ?.let { !hostConnected(it) && hostReachability.statusOf(it) == HostReachability.Offline } == true
                                },
                                desktop = { session ->
                                    val host = hosts.firstOrNull { it.id == session.host.id } ?: session.host
                                    val connection = desktops[host.id]
                                    val idle = remember { MutableStateFlow<DesktopPhase>(DesktopPhase.Idle) }
                                    val desktopPhase by (connection?.phase ?: idle).collectAsStateWithLifecycle()
                                    val sessionState = sessionStates[session.id] ?: session.state.value
                                    val offline = !hostConnected(host) && hostReachability.statusOf(host) == HostReachability.Offline
                                    val button = DesktopButton.derive(desktopEnabled, !offline, sessionState.phase == Phase.CONNECTED,
                                        HostSystem.fromStored(host.os).let { if (it.isLinux) "LINUX" else it.name }, desktopSupported(host),
                                        DesktopProbe.decode(host.desktop), host.desktopProbedAt, System.currentTimeMillis(), desktopPhase)
                                    DesktopCardButton(button, "home-session-desktop-${session.id}") { openDesktop(host) }
                                })
                            else HomeHostsTab(hosts, search, hostGridState,
                                reachability = { host ->
                                    hostReachability.statusOf(host).let { probed ->
                                        if (host.endpointKey in connectedEndpoints && probed !is HostReachability.Online) HostReachability.Online(null) else probed
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
                                    val offline = !hostConnected(host) && hostReachability.statusOf(host) == HostReachability.Offline
                                    // A host needs no open session for its desktop: the desktop logs in on its own.
                                    val button = DesktopButton.derive(desktopEnabled, !offline, true,
                                        HostSystem.fromStored(host.os).let { if (it.isLinux) "LINUX" else it.name }, desktopSupported(host),
                                        DesktopProbe.decode(host.desktop), host.desktopProbedAt, System.currentTimeMillis(), desktopPhase)
                                    DesktopCardButton(button, "host-desktop-${host.id}") { openDesktop(host) }
                                })
                        }
                    }
                } else if (page == AppPage.HOST) {
                    val detailHost = hosts.firstOrNull { it.id == detailHostId }
                    if (detailHost != null) key(detailHost.id) {
                        val connected = sessions.any { it.host.id == detailHost.id && (sessionStates[it.id]?.phase ?: it.state.value.phase) == Phase.CONNECTED }
                        HostDetailPage(detailHost,
                            hostReachability.statusOf(detailHost).let { if (connected && it !is HostReachability.Online) HostReachability.Online(null) else it },
                            onBack = { page = AppPage.HOME },
                            onLaunch = { directory, agent -> launchOn(detailHost, directory, agent) },
                            openTmux = sessions.openTmuxNames(detailHost),
                            onAttach = { attachOn(detailHost, it) },
                            onBrowseFiles = { browseFiles(detailHost) },
                            onEdit = { editHostId = detailHost.id; editorOpen = true },
                            onManagePlugins = { managePlugins(detailHost) },
                            onDelete = { deleteHost = detailHost },
                            modifier = Modifier.weight(1f),
                            onDesktop = { openDesktop(detailHost) }.takeIf {
                                desktopEnabled && desktopSupported(detailHost) && DesktopProbe.decode(detailHost.desktop) != DesktopProbe.NO
                            })
                    } else if (loadedHosts != null) LaunchedEffect(Unit) { page = AppPage.HOME } // deleted, or gone after a restore
                } else if (page == AppPage.FILES) {
                    val filesSession = sessions.find { it.id == selected }
                    if (filesSession != null) {
                        key(filesSession) {
                            RemoteFileBrowserPage(filesSession, onBack = ::leaveTerminal, Modifier.weight(1f),
                                // A new terminal session (tmux when the host has it) so the agent outlives the browser.
                                onLaunchAgent = { launch ->
                                    begin(launchRequest(hosts.firstOrNull { it.id == filesSession.host.id } ?: filesSession.host, launch.directory, launch))
                                }, onOpenTerminal = { directory ->
                                    launchOn(hosts.firstOrNull { it.id == filesSession.host.id } ?: filesSession.host, directory, null)
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
                        DesktopPage(connection, onLeave = { page = AppPage.HOME },
                            onDisconnect = { service?.desktops?.close(connection.host.id); page = AppPage.HOME },
                            onFiles = { browseFiles(hosts.firstOrNull { it.id == connection.host.id } ?: connection.host) },
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
                        val reconnect = { begin(active.reconnectRequest(hosts.firstOrNull { it.id == active.host.id } ?: active.host)) }
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
                            onReconnect = reconnect.takeUnless { autoReconnect && !autoTried },
                            onRestart = { restartSession(active) },
                            onShell = { service?.close(active.id); service?.forget(active.id); connect(active.host, SessionMode.SHELL, active.title.value) },
                            onReverify = { reverify = active },
                            // Closing ends the terminal page too: back to where it was opened from.
                            onClose = { leaveTerminal(); closeSession(active) },
                            onDesktop = (hosts.firstOrNull { it.id == active.host.id } ?: active.host).let { h ->
                                { openDesktop(h) }.takeIf { desktopEnabled && desktopSupported(h) && DesktopProbe.decode(h.desktop) == DesktopProbe.YES }
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
            onContinue = { newSessionOpen = false; continueOn = it }) { host, agent ->
            newSessionOpen = false
            launchRequest = LaunchRequest(host, agent != null, agent, newSession = true)
        }
        continueOn?.let { host ->
            ContinueSessionSheet(host, sessions.openTmuxNames(host), onDismiss = { continueOn = null },
                onNewTerminal = { continueOn = null; launchOn(host, StartDirectory.HOME, null) },
                onInstall = { continueOn = null; managePlugins(host) }) {
                continueOn = null
                attachOn(host, it)
            }
        }
        launchRequest?.let { (host, forAgent, initialAgent, newSession) ->
            LaunchSheet(host, forAgent, monitor = null, onDismiss = { launchRequest = null }, initialAgent = initialAgent,
                title = if (newSession) tr(if (forAgent) "选择工作目录" else "新建会话") else null, openTmux = sessions.openTmuxNames(host),
                onAttach = { launchRequest = null; attachOn(host, it) }) { directory, agent ->
                launchRequest = null
                launchOn(host, directory, agent)
            }
        }
        deleteHost?.let { host ->
            DeleteHostDialog(host, onDismiss = { deleteHost = null }) {
                scope.launch {
                    sessions.filter { it.host.id == host.id }.forEach { service?.close(it.id); service?.forget(it.id) }
                    app.database.hosts().delete(host.id)
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
                    sessions.filter { it.host.target().endpoint == endpoint }.forEach { service?.close(it.id) }
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

/** A launch sheet to open: on [host], for an agent or a terminal, with [agent] already picked when there is one. */
/** [newSession]: the last step of a new session from the sessions tab, after what runs and the host were picked. */
private data class LaunchRequest(val host: Host, val forAgent: Boolean, val agent: CodingAgent? = null, val newSession: Boolean = false)
