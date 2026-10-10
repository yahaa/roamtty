package io.itermux.app.ui

import io.itermux.app.tr

import android.Manifest
import android.net.Uri
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.edit
import io.itermux.app.ItermuxApp
import io.itermux.app.AppLocale
import io.itermux.app.trf
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.data.newSessionMode
import io.itermux.app.data.multiplexer
import io.itermux.app.data.prefersMosh
import io.itermux.app.session.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val sessionPhases by remember(sessions) {
        if (sessions.isEmpty()) flowOf(emptyMap())
        else combine(sessions.map { session -> session.state.map { session.id to it.phase } }) { it.toMap() }
    }.collectAsStateWithLifecycle(emptyMap())
    val hostReachability = remember { HostReachabilityTracker() }
    // The session list and drawer show live sessions first; terminal tabs keep their open order.
    val sessionsByActivity = remember(sessions, sessionPhases) {
        sessions.sortedBy { activityRank(sessionPhases[it.id] ?: it.state.value.phase) }
    }
    var page by rememberSaveable { mutableStateOf("hosts") }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(AppTheme.from(prefs.getString("theme", null))) }
    var settingsReturnPage by rememberSaveable { mutableStateOf("hosts") }
    /** The host whose detail page is shown when [page] is "host". */
    var detailHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val lightTheme = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM && configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
    val terminalVisible = page == "terminal" && sessions.isNotEmpty()
    val navigationVisible = page == "hosts"
    // The file browser wears the terminal's chrome: a transparent status bar and no navigation bar.
    val terminalChrome = terminalVisible || page == "files"
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalChrome, imeVisible, lightTheme, landscape) { onChromeChange(terminalChrome, lightTheme) }
    BackHandler(page == "settings" && !drawerState.isOpen) { page = settingsReturnPage }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    /** Leaves the terminal or file browser for its host's detail page; the host list when that host is gone. */
    fun leaveTerminal() {
        val hostId = sessions.find { it.id == selected }?.host?.id
        if (hostId != null && hosts.any { it.id == hostId }) {
            detailHostId = hostId
            page = "host"
        } else page = "hosts"
    }
    BackHandler(page == "terminal") { leaveTerminal() }
    LaunchedEffect(service, sessions, selected, page) {
        service?.awaitRestoration()
        if (service != null && page == "terminal" && service.sessions.value.none { it.id == selected }) page = "hosts"
        if (service != null && page == "files" && service.sessions.value.none { it.id == selected }) page = "hosts"
    }
    var search by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val hostGridState = rememberLazyGridState()
    /** The home page's tabs: sessions first, then hosts. */
    val homePager = rememberPagerState { 2 }
    BackHandler(page == "hosts" && homePager.currentPage != 0 && !searchExpanded && !drawerState.isOpen) {
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
    /** A host-menu launch: the host, and whether it starts an AI agent rather than a plain terminal. */
    var launchRequest by remember { mutableStateOf<Pair<Host, Boolean>?>(null) }
    /** The home page's "new session" sheet, which picks the host before the launch sheet. */
    var newSessionOpen by rememberSaveable { mutableStateOf(false) }
    /** Set while the host editor fills in a new host for a new session: whether that session starts an AI agent. */
    var newHostForAgent by rememberSaveable { mutableStateOf<Boolean?>(null) }
    /** A host just saved for a new session, waiting for the host list to carry it: its id, and whether it starts an agent. */
    var savedForSession by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var connectRequest by remember { mutableStateOf<ConnectRequest?>(null) }
    /** Session id → when it last reconnected by itself on open; see [AUTO_RECONNECT_INTERVAL_MS]. */
    val autoReconnectedAt = remember { mutableMapOf<String, Long>() }
    var terminatePrompt by remember { mutableStateOf<PendingPrompt?>(null) }
    var reverify by remember { mutableStateOf<TerminalConnection?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var pluginTarget by remember { mutableStateOf<PluginTarget?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    fun start(request: ConnectRequest, password: String = "", passphrase: String = "") {
        val bound = service ?: run { toast(tr("会话服务尚未就绪，请稍后重试")); return }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch {
            var credentials: Credentials? = null
            try {
                credentials = withContext(Dispatchers.IO) {
                    val host = request.host
                    Credentials(
                        password = if (host.auth == AuthMethod.PASSWORD.name) host.encryptedPassword?.let { app.vault.decrypt(it, "${host.id}:password") } ?: password.toByteArray() else null,
                        privateKey = host.encryptedKey?.takeIf { host.auth == AuthMethod.KEY.name }?.let { app.vault.decrypt(it, "${host.id}:key") },
                        passphrase = passphrase.takeIf { it.isNotEmpty() }?.toByteArray()
                    )
                }
                request.terminateSessionId?.let { id ->
                    toast(trf("正在终止远端 %s 会话…", request.host.multiplexer.id))
                    val failure = bound.terminate(id, credentials) { prompt ->
                        val pending = PendingPrompt(prompt)
                        terminatePrompt = pending
                        try { pending.answer.await() } finally { if (terminatePrompt === pending) terminatePrompt = null }
                    }
                    toast(failure?.let { trf("未能终止远端会话：%s", it) } ?: tr("远端 tmux 会话已终止，卡片已移除"))
                    return@launch
                }
                val session = if (request.reuseSessionId != null) {
                    bound.reconnect(request.reuseSessionId, credentials)
                } else {
                    // File browsing needs only SFTP, so it never starts a mosh server.
                    bound.open(request.host, request.mode, credentials, preferMosh = request.host.prefersMosh && !request.openFilesAfterConnect)
                }
                if (request.title.isNotBlank()) session.rename(request.title)
                request.startupCommand?.let(session::runWhenConnected)
                selected = session.id
                page = if (request.openFilesAfterConnect) "files" else "terminal"
            } catch (e: Exception) {
                credentials?.clear()
                if (e is CancellationException) throw e
                // Session limits and missing entries are reported by check()/error() with localized text.
                toast((e as? IllegalStateException)?.message ?: tr("无法读取凭据，请重新导入密钥或保存密码。"))
            }
        }
    }
    /** Asks for an unsaved password or key passphrase first. */
    fun begin(request: ConnectRequest) {
        val host = request.host
        if (host.auth == AuthMethod.KEY.name || (host.auth == AuthMethod.PASSWORD.name && host.encryptedPassword == null)) connectRequest = request
        else start(request)
    }
    fun connect(host: Host, mode: SessionMode = host.newSessionMode, title: String = "", reuseSessionId: String? = null,
        openFilesAfterConnect: Boolean = false, startupCommand: String? = null) {
        begin(ConnectRequest(host, mode, title, reuseSessionId, openFilesAfterConnect, startupCommand = startupCommand))
    }
    /** Drops a disconnected tmux session's record; with [terminateRemote], logs in to kill it on the server first. */
    fun forgetSession(session: TerminalConnection, terminateRemote: Boolean) {
        if (!terminateRemote) service?.forget(session.id)
        else begin(ConnectRequest(session.host, SessionMode.SHELL, terminateSessionId = session.id))
    }

    /** tmux restarts the pane over the live connection; a plain shell gets a fresh one by reconnecting. */
    fun restartSession(session: TerminalConnection) {
        if (session.mode != SessionMode.TMUX) connect(session.host, session.mode, session.title.value, reuseSessionId = session.id)
        else scope.launch { if (!session.restartPane()) toast(tr("未能重启会话，请检查连接后重试")) }
    }

    /** A new session in [directory]; with [agent], that agent is started there and names the session. */
    fun launchOn(host: Host, directory: String, agent: AgentLaunch?) {
        if (!StartDirectory.isSupported(directory)) {
            toast(tr(StartDirectory.UNSUPPORTED_MESSAGE))
            return
        }
        if (agent != null) connect(host, title = agent.title, startupCommand = agent.commandLine)
        else connect(host, startupCommand = StartDirectory.command(directory))
    }

    fun managePlugins(host: Host) {
        if (host.auth == AuthMethod.KEY.name || (host.auth == AuthMethod.PASSWORD.name && host.encryptedPassword == null))
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
        val existing = sessions.firstOrNull { it.host.id == host.id && it.state.value.phase == Phase.CONNECTED }
        if (existing != null) {
            selected = existing.id
            page = "files"
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
        selected = session.id
        page = "terminal"
        if (!session.transfers.uploadSharedImages(context, session, uris))
            toast(tr("已有传输进行中，请稍后再试"))
    }
    fun routeSharedImages(uris: List<Uri>, available: List<TerminalConnection>) {
        if (uris.isEmpty()) return
        val connected = available.filter { it.state.value.phase == Phase.CONNECTED }
        when {
            connected.isEmpty() -> {
                toast(tr("请先连接一台主机后再分享图片"))
                page = "hosts"
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
        ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = !terminalVisible && page != "files" && page != "host",
            drawerContent = {
                ItermuxDrawer(page, hosts.size, sessionsByActivity,
                    onHosts = { page = "hosts"; homePager.requestScrollToPage(1); scope.launch { drawerState.close() } },
                    onOpenSession = { selected = it; page = "terminal"; scope.launch { drawerState.close() } },
                    onSettings = {
                        if (page != "settings") settingsReturnPage = page
                        page = "settings"
                        scope.launch { drawerState.close() }
                    },
                    onGuide = { scope.launch { drawerState.close() }; showAbout = true },
                    onClose = { scope.launch { drawerState.close() } })
            }) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            // The terminal fills the display, including the area beside a camera cutout and under the
            // transparent portrait status bar. Only desktop window chrome reserves an edge; the terminal
            // keeps its text and controls clear of the status bar and cutouts itself.
            val contentInsets = if (terminalVisible) WindowInsets.captionBar else WindowInsets.safeDrawing
            // The terminal applies the keyboard inset itself, in one step (see TerminalPane).
            val imeInsets = if (terminalVisible) WindowInsets(0) else WindowInsets.ime
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).windowInsetsPadding(imeInsets)) {
                if ((!imeVisible || searchExpanded) && !terminalVisible && page != "files" && page != "host") AppToolbar(
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
                if (page == "hosts") {
                    Column(Modifier.fillMaxSize()) {
                        if (searchExpanded) {
                            AppSearchField(search, { search = it },
                                tr(if (homePager.currentPage == 0) "标题、主机、地址或用户名" else "名称、地址或用户名"),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).padding(bottom = Spacing.Tight)
                                    .focusRequester(searchFocus).testTag("host-search"))
                        }
                        // A live connection already proves the host is up, whatever the last probe said. Sessions keep
                        // the endpoint they were opened with, so one still on an edited host's old address does not count.
                        val connectedEndpoints = sessions.filter { (sessionPhases[it.id] ?: it.state.value.phase) == Phase.CONNECTED }
                            .map { Triple(it.host.id, it.host.address, it.host.port) }.toSet()
                        ProbeHostReachability(hostReachability, hosts,
                            connected = hosts.filter { Triple(it.id, it.address, it.port) in connectedEndpoints }.map { it.id }.toSet())
                        val firstHost = @Composable { modifier: Modifier ->
                            EmptyState(tr("从第一台主机开始"), tr("保存远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。"),
                                modifier,
                                artwork = { ItermuxLogo(Modifier.size(56.dp).testTag("empty-logo"), contentDescription = "Roamtty") },
                                action = { Button(onClick = { editHostId = null; editorOpen = true }) { Text(tr("添加主机")) } })
                        }
                        HorizontalPager(homePager, Modifier.weight(1f).testTag("home-pager"), key = { HOME_TAB_TAGS[it] }) { tab ->
                            if (tab == 0) Column(Modifier.fillMaxSize()) {
                                HomeListHeader(trf("会话 · %d", sessionsByActivity.size), tr("新建会话"), "add-session") {
                                    newSessionOpen = true
                                }
                                val matching = sessionsByActivity.filter {
                                    "${it.displayTitle} ${it.host.name} ${it.host.address} ${it.host.username}".contains(search, true)
                                }
                                when {
                                    // No host is needed first: the new-session sheet fills one in on the way.
                                    hosts.isEmpty() && sessionsByActivity.isEmpty() -> EmptyState(tr("从第一个会话开始"),
                                        tr("填写远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。"),
                                        Modifier.fillMaxSize().testTag("home-sessions-empty"),
                                        artwork = { ItermuxLogo(Modifier.size(56.dp).testTag("empty-logo"), contentDescription = "Roamtty") },
                                        action = { Button(onClick = { newSessionOpen = true }) { Text(tr("新建会话")) } })
                                    sessionsByActivity.isEmpty() -> EmptyState(tr("暂无会话"), tr("选择主机，打开终端或 AI 助手。"),
                                        Modifier.fillMaxSize().testTag("home-sessions-empty"),
                                        action = { Button(onClick = { newSessionOpen = true }) { Text(tr("新建会话")) } })
                                    matching.isEmpty() -> EmptyState(tr("没有匹配的会话"), tr("试试其他标题或主机信息"), Modifier.fillMaxSize())
                                    else -> LazyColumn(Modifier.fillMaxSize().testTag("home-sessions-list"),
                                        contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Page)) {
                                        item(key = "sessions") {
                                            HomeSessionsList(matching, onOpen = { selected = it.id; page = "terminal" },
                                                onClose = { service?.close(it.id) }, onForget = ::forgetSession)
                                        }
                                    }
                                }
                            } else Column(Modifier.fillMaxSize()) {
                                HomeListHeader(trf("主机 · %d", hosts.size), tr("添加主机"), "add-host") {
                                    editHostId = null; editorOpen = true
                                }
                                val filtered = hosts.filter { "${it.name} ${it.address} ${it.username}".contains(search, true) }
                                if (hosts.isEmpty()) firstHost(Modifier.fillMaxSize())
                                else if (filtered.isEmpty()) EmptyState(tr("没有匹配的主机"), tr("试试其他名称或地址"), Modifier.fillMaxSize())
                                else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = hostGridState, modifier = Modifier.fillMaxSize().testTag("host-grid"), horizontalArrangement = Arrangement.spacedBy(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Row), contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Page)) {
                                    items(filtered, key = { it.id }) { host -> HostCard(host, { detailHostId = host.id; page = "host" }, { editHostId = host.id; editorOpen = true }, { deleteHost = host }, { browseFiles(host) }, { managePlugins(host) },
                                        hostReachability.statusOf(host).let { probed ->
                                            if (Triple(host.id, host.address, host.port) in connectedEndpoints && probed !is HostReachability.Online) HostReachability.Online(null) else probed
                                        }, openTerminal = { launchRequest = host to false }, launchAgent = { launchRequest = host to true }) }
                                }
                            }
                        }
                    }
                } else if (page == "host") {
                    val detailHost = hosts.firstOrNull { it.id == detailHostId }
                    if (detailHost != null) key(detailHost.id) {
                        val connected = sessions.any { it.host.id == detailHost.id && (sessionPhases[it.id] ?: it.state.value.phase) == Phase.CONNECTED }
                        HostDetailPage(detailHost,
                            hostReachability.statusOf(detailHost).let { if (connected && it !is HostReachability.Online) HostReachability.Online(null) else it },
                            sessions = sessionsByActivity.filter { it.host.id == detailHost.id },
                            onBack = { page = "hosts" },
                            onOpenSession = { selected = it.id; page = "terminal" },
                            onCloseSession = { service?.close(it.id) },
                            onForgetSession = ::forgetSession,
                            onLaunch = { directory, agent -> launchOn(detailHost, directory, agent) },
                            onBrowseFiles = { browseFiles(detailHost) },
                            onEdit = { editHostId = detailHost.id; editorOpen = true },
                            onManagePlugins = { managePlugins(detailHost) },
                            onDelete = { deleteHost = detailHost },
                            modifier = Modifier.weight(1f))
                    } else if (loadedHosts != null) LaunchedEffect(Unit) { page = "hosts" } // deleted, or gone after a restore
                } else if (page == "files") {
                    val filesSession = sessions.find { it.id == selected }
                    if (filesSession != null) {
                        key(filesSession) {
                            RemoteFileBrowserPage(filesSession, onBack = ::leaveTerminal, Modifier.weight(1f),
                                // A new terminal session (tmux when the host has it) so the agent outlives the browser.
                                onLaunchAgent = { launch ->
                                    connect(hosts.firstOrNull { it.id == filesSession.host.id } ?: filesSession.host,
                                        title = launch.title, startupCommand = launch.commandLine)
                                }, onOpenTerminal = { directory ->
                                    launchOn(hosts.firstOrNull { it.id == filesSession.host.id } ?: filesSession.host, directory, null)
                                })
                        }
                    } else {
                        EmptyState(tr("会话不存在"), tr("连接已关闭，请从主机列表重新打开文件。"), Modifier.fillMaxSize(),
                            action = { FilledTonalButton(onClick = { page = "hosts" }) { Text(tr("返回主机列表")) } })
                    }
                } else if (page == "settings") {
                    SettingsPage(theme, { option ->
                        theme = option
                        prefs.edit { putString("theme", option.name) }
                    }, AppLocale.language, { option -> AppLocale.set(context, option); service?.refreshLanguage() })
                } else if (sessions.isEmpty()) {
                    EmptyState(tr("终端已就绪"), tr("连接一台主机，开始远端工作。"), Modifier.fillMaxSize(),
                        action = { FilledTonalButton(onClick = { page = "hosts" }) { Text(tr("返回主机列表")) } })
                } else {
                    val active = sessions.find { it.id == selected }
                    // Reconnecting preserves the journal id but replaces the transport and emulator.
                    if (active != null) key(active) {
                        // The saved host, not the session's copy: a password saved since then spares a prompt.
                        val reconnect = { connect(hosts.firstOrNull { it.id == active.host.id } ?: active.host,
                            active.mode, active.title.value, reuseSessionId = active.id) }
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
                            onHostDetail = ::leaveTerminal,
                            onReconnect = reconnect.takeUnless { autoReconnect && !autoTried },
                            onRestart = { restartSession(active) },
                            onShell = { service?.close(active.id); service?.forget(active.id); connect(active.host, SessionMode.SHELL, active.title.value) },
                            onReverify = { reverify = active },
                            // Closing ends the terminal page too: back to the host the session belonged to.
                            onClose = { leaveTerminal(); service?.close(active.id) })
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
        newHostForAgent?.let { forAgent ->
            HostEditor(app, null, forSession = true, onDismiss = { newHostForAgent = null },
                onSaved = { id -> newHostForAgent = null; savedForSession = id to forAgent })
        }
        savedForSession?.let { (id, forAgent) ->
            LaunchedEffect(hosts, id) {
                hosts.firstOrNull { it.id == id }?.let { savedForSession = null; launchRequest = it to forAgent }
            }
        }
        if (newSessionOpen) NewSessionSheet(hosts, onDismiss = { newSessionOpen = false },
            onNewHost = { newSessionOpen = false; newHostForAgent = it }) { host, forAgent ->
            newSessionOpen = false
            launchRequest = host to forAgent
        }
        launchRequest?.let { (host, forAgent) ->
            LaunchSheet(host, forAgent, monitor = null, onDismiss = { launchRequest = null }) { directory, agent ->
                launchRequest = null
                launchOn(host, directory, agent)
            }
        }
        deleteHost?.let { host -> AlertDialog(onDismissRequest = { deleteHost = null }, title = { Text(trf("删除 %s？", host.name)) }, text = { Text(tr("将删除这台主机及保存的凭据，并关闭它的连接。服务器信任记录会独立保留，可通过重新核验移除。远端 tmux 任务不受影响。")) },
            confirmButton = { TextButton(onClick = { scope.launch { sessions.filter { it.host.id == host.id }.forEach { service?.close(it.id); service?.forget(it.id) }; app.database.hosts().delete(host.id); deleteHost = null } }) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteHost = null }) { Text(tr("取消")) } }) }
        connectRequest?.let { request -> CredentialsDialog(request, { connectRequest = null }) { password, phrase ->
            connectRequest = null
            if (request.plugins) pluginTarget = PluginTarget(request.host, password, phrase) else start(request, password, phrase)
        } }
        terminatePrompt?.let { PromptDialog(it) }
        pluginTarget?.let { target ->
            val controller = remember(target) { HostPluginsController(app, target, scope).also { it.start() } }
            DisposableEffect(controller) { onDispose { controller.close() } }
            HostPluginsSheet(controller) { pluginTarget = null }
        }
        reverify?.let { session -> AlertDialog(onDismissRequest = { reverify = null }, title = { Text(tr("重新核验主机指纹")) }, text = { Text(trf("请先向服务器管理员确认新指纹。继续会移除此地址原有的信任记录，关闭该地址的连接，并在重连时重新展示指纹。\n\n%s", session.host.target().endpoint)) },
            confirmButton = { TextButton(onClick = { scope.launch {
                val endpoint = session.host.target().endpoint
                sessions.filter { it.host.target().endpoint == endpoint }.forEach { service?.close(it.id) }
                app.database.hosts().forgetPin(endpoint)
                reverify = null
                connect(session.host, session.mode, session.title.value, reuseSessionId = session.id.takeIf { session.mode == SessionMode.TMUX })
            } }) { Text(tr("移除旧记录并重连")) } }, dismissButton = { TextButton(onClick = { reverify = null }) { Text(tr("取消")) } }) }
        if (shareUrisPending.isNotEmpty()) {
            val choices = sessions.filter { it.state.value.phase == Phase.CONNECTED }
            fun dismissShare() { service?.pendingSharedImages?.value = emptyList() }
            AppSheet(onDismiss = ::dismissShare) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                    item { SheetHeader(tr("选择会话以上传图片"), tr("选择接收图片的主机和会话。"), ::dismissShare) }
                    items(choices.size, key = { choices[it].id }) { index ->
                        val session = choices[index]
                        val title by session.title.collectAsStateWithLifecycle()
                        val state by session.state.collectAsStateWithLifecycle()
                        GroupedRow(session.titleForDisplay(title),
                            Modifier.padding(horizontal = Spacing.Tight).clip(MaterialTheme.shapes.small)
                                .testTag("share-session-${session.id}"),
                            subtitle = "${session.host.username}@${session.host.address}:${session.host.port}",
                            leading = { SessionSystemIcon(session, 40.dp) },
                            trailing = { Chevron() },
                            enabled = state.phase == Phase.CONNECTED,
                            onClick = {
                                val uris = shareUrisPending
                                dismissShare()
                                uploadSharedImages(session, uris)
                            })
                    }
                }
            }
        }
        if (showAbout) AboutDialog(context) { showAbout = false }
    }
}
