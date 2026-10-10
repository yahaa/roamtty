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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import io.itermux.app.data.hostListOrder
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
    val sessionAttention by remember(sessions) {
        if (sessions.isEmpty()) flowOf(emptyMap())
        else combine(sessions.map { session -> session.attention.map { session.id to it } }) { it.toMap() }
    }.collectAsStateWithLifecycle(emptyMap())
    val hostReachability = remember { HostReachabilityTracker() }
    // The home page and host pages show live sessions first; terminal tabs keep their open order.
    val sessionsByActivity = remember(sessions, sessionPhases) {
        sessions.sortedBy { activityRank(sessionPhases[it.id] ?: it.state.value.phase) }
    }
    // Home lists sessions that rang for the user first.
    val homeSessions = remember(sessionsByActivity, sessionAttention) {
        sessionsByActivity.sortedByDescending { sessionAttention[it.id] == true }
    }
    var page by rememberSaveable { mutableStateOf("hosts") }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(AppTheme.from(prefs.getString("theme", null))) }
    var openLastSession by remember { mutableStateOf(prefs.getBoolean(OPEN_LAST_SESSION_KEY, false)) }
    /** Bumped when a launch is remembered, so host cards re-read their quick launch. */
    var launchRevision by remember { mutableIntStateOf(0) }
    /** The wall clock for "used 5 minutes ago", ticking each minute while the home page shows. */
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var settingsReturnPage by rememberSaveable { mutableStateOf("hosts") }
    /** The host whose detail page is shown when [page] is "host". */
    var detailHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val lightTheme = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM && configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
    val terminalVisible = page == "terminal" && sessions.isNotEmpty()
    val navigationVisible = page == "hosts"
    // The file browser wears the terminal's chrome: a transparent status bar and no navigation bar.
    val terminalChrome = terminalVisible || page == "files"
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalChrome, imeVisible, lightTheme, landscape) { onChromeChange(terminalChrome, lightTheme) }
    BackHandler(page == "settings") { page = settingsReturnPage }
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
    // Once per launch, with the setting on: straight back into the last terminal (it reconnects itself on open).
    var launchHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(service) {
        if (service == null || launchHandled) return@LaunchedEffect
        service.awaitRestoration()
        launchHandled = true
        val last = prefs.getString(LAST_SESSION_KEY, null)
        if (openLastSession && page == "hosts" && pendingShareImages.isEmpty() && service.sessions.value.any { it.id == last }) {
            selected = last
            page = "terminal"
        }
    }
    LaunchedEffect(page) {
        while (page == "hosts") {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    var search by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val hostGridState = rememberLazyGridState()
    LaunchedEffect(landscape) {
        hostGridState.scrollToItem(hostGridState.firstVisibleItemIndex)
    }
    val searchFocus = remember { FocusRequester() }
    BackHandler(navigationVisible && searchExpanded && !imeVisible) {
        searchExpanded = false
        search = ""
    }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteHost by remember { mutableStateOf<Host?>(null) }
    /** A host-menu launch: the host, and whether it starts an AI agent rather than a plain terminal. */
    var launchRequest by remember { mutableStateOf<Pair<Host, Boolean>?>(null) }
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
                launch(Dispatchers.IO) { app.database.hosts().markUsed(request.host.id, System.currentTimeMillis()) }
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
        prefs.saveLastLaunch(host.id, directory, agent)
        launchRevision++
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
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            // The terminal fills the display, including the area beside a camera cutout and under the
            // transparent portrait status bar. Only desktop window chrome reserves an edge; the terminal
            // keeps its text and controls clear of the status bar and cutouts itself.
            val contentInsets = if (terminalVisible) WindowInsets.captionBar else WindowInsets.safeDrawing
            // The terminal applies the keyboard inset itself, in one step (see TerminalPane).
            val imeInsets = if (terminalVisible) WindowInsets(0) else WindowInsets.ime
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).windowInsetsPadding(imeInsets)) {
                if ((!imeVisible || searchExpanded) && !terminalVisible && page != "files" && page != "host") AppToolbar(
                    actions = {
                        if (navigationVisible) {
                            // Portrait shows "Add host" beside the large title; landscape has no large title.
                            val showAddHost = page == "hosts" && (landscape || imeVisible)
                            // Landscape has no large title, so the toolbar carries it; the app menu stays the outermost icon.
                            Box(Modifier.weight(1f).padding(horizontal = Spacing.Page - 4.dp)) {
                                if (landscape || imeVisible) HomeTitle(MaterialTheme.typography.titleLarge)
                            }
                            if (showAddHost) ToolbarIcon(R.drawable.ic_add, tr("添加主机"), Modifier.testTag("add-host")) {
                                editHostId = null; editorOpen = true
                            }
                            // A handful of hosts fits on screen; search only earns its place in a longer list.
                            if (hosts.size > SEARCH_HOST_THRESHOLD || searchExpanded) AppSearchToggle(searchExpanded, onToggle = {
                                if (searchExpanded) {
                                    search = ""
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                                searchExpanded = !searchExpanded
                            }, searchDescription = tr("搜索主机"),
                                modifier = Modifier.testTag("search-toggle"))
                            AppMenu(onSettings = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                settingsReturnPage = page
                                page = "settings"
                            }, onGuide = { showAbout = true })
                        } else {
                            // Settings opens from the app menu: closing returns to the page it was opened from.
                            Spacer(Modifier.weight(1f))
                            ToolbarIcon(R.drawable.ic_close, tr("关闭设置"), Modifier.testTag("close-settings")) { page = settingsReturnPage }
                        }
                    })
                // Landscape phones have little height: the toolbar names the page instead.
                val largeTitle = !landscape && !imeVisible
                if (page == "hosts") {
                    Column(Modifier.fillMaxSize()) {
                        if (largeTitle) LargeTitle(tr("搜索结果"), titleContent = if (search.isBlank()) {
                            { HomeTitle(MaterialTheme.typography.headlineMedium) }
                        } else null, action = {
                            ToolbarIcon(R.drawable.ic_add, tr("添加主机"), Modifier.testTag("add-host")) {
                                editHostId = null; editorOpen = true
                            }
                        })
                        if (searchExpanded) {
                            AppSearchField(search, { search = it }, tr("名称、地址或用户名"),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).padding(bottom = Spacing.Tight)
                                    .focusRequester(searchFocus).testTag("host-search"))
                        }
                        val filtered = hosts.sortedWith(hostListOrder).filter { "${it.name} ${it.address} ${it.username}".contains(search, true) }
                        // A live connection already proves the host is up, whatever the last probe said. Sessions keep
                        // the endpoint they were opened with, so one still on an edited host's old address does not count.
                        val connectedEndpoints = sessions.filter { (sessionPhases[it.id] ?: it.state.value.phase) == Phase.CONNECTED }
                            .map { Triple(it.host.id, it.host.address, it.host.port) }.toSet()
                        ProbeHostReachability(hostReachability, hosts,
                            connected = hosts.filter { Triple(it.id, it.address, it.port) in connectedEndpoints }.map { it.id }.toSet())
                        // Running sessions lead the page; a search narrows the page to hosts.
                        val sections = search.isBlank() && homeSessions.isNotEmpty()
                        if (filtered.isEmpty() && !sections) {
                            if (hosts.isEmpty()) EmptyState(tr("从第一台主机开始"), tr("保存远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。"),
                                Modifier.weight(1f),
                                artwork = { ItermuxLogo(Modifier.size(56.dp).testTag("empty-logo"), contentDescription = "Roamtty") },
                                action = { Button(onClick = { editHostId = null; editorOpen = true }) { Text(tr("添加主机")) } })
                            else EmptyState(tr("没有匹配的主机"), tr("试试其他名称或地址"), Modifier.weight(1f))
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = hostGridState, modifier = Modifier.weight(1f).testTag("host-grid"), horizontalArrangement = Arrangement.spacedBy(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Row), contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, top = if (largeTitle) 0.dp else Spacing.Tight, bottom = Spacing.Page)) {
                            if (sections) {
                                item(key = "home-sessions", span = { GridItemSpan(maxLineSpan) }) {
                                    ActiveSessionsSection(homeSessions, onOpen = { selected = it.id; page = "terminal" },
                                        Modifier.padding(bottom = Spacing.Tight))
                                }
                                if (filtered.isNotEmpty()) item(key = "hosts-header", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(tr("主机"), style = MaterialTheme.typography.sectionHeader, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = Spacing.Page).semantics { heading() }.testTag("hosts-header"))
                                }
                            }
                            items(filtered, key = { it.id }) { host ->
                                val hostSessions = sessions.filter { it.host.id == host.id }
                                val lastLaunch = remember(host.id, launchRevision) { prefs.lastLaunch(host.id) }
                                HostCard(host, { detailHostId = host.id; page = "host" }, { editHostId = host.id; editorOpen = true }, { deleteHost = host }, { browseFiles(host) }, { managePlugins(host) },
                                    hostReachability.statusOf(host).let { probed ->
                                        if (Triple(host.id, host.address, host.port) in connectedEndpoints && probed !is HostReachability.Online) HostReachability.Online(null) else probed
                                    }, openTerminal = { launchRequest = host to false }, launchAgent = { launchRequest = host to true },
                                    sessions = HostSessionCount(hostSessions.size,
                                        hostSessions.count { (sessionPhases[it.id] ?: it.state.value.phase).let { phase -> phase !in livePhases } }),
                                    lastLaunch = lastLaunch, quickLaunch = { lastLaunch?.let { launchOn(host, it.directory, it.agent) } },
                                    togglePin = { scope.launch { app.database.hosts().setPinned(host.id, !host.pinned) } }, now = now)
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
                                    launchOn(hosts.firstOrNull { it.id == filesSession.host.id } ?: filesSession.host, launch.directory, launch)
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
                    }, AppLocale.language, { option -> AppLocale.set(context, option); service?.refreshLanguage() },
                        openLastSession = openLastSession, onOpenLastSessionChange = {
                            openLastSession = it
                            prefs.edit { putBoolean(OPEN_LAST_SESSION_KEY, it) }
                        })
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
                        // The terminal is on screen, so whatever rang for the user has been seen.
                        val attention by active.attention.collectAsStateWithLifecycle()
                        LaunchedEffect(attention) { if (attention) active.attention.value = false }
                        LaunchedEffect(active.id) { prefs.edit { putString(LAST_SESSION_KEY, active.id) } }
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
        if (editorOpen) {
            val editHost = editHostId?.let { id -> hosts.firstOrNull { it.id == id } }
            if (editHostId == null || editHost != null) HostEditor(app, editHost,
                onDismiss = { editorOpen = false; editHostId = null },
                onSaved = { editorOpen = false; editHostId = null })
        }
        launchRequest?.let { (host, forAgent) ->
            LaunchSheet(host, forAgent, monitor = null, onDismiss = { launchRequest = null }) { directory, agent ->
                launchRequest = null
                launchOn(host, directory, agent)
            }
        }
        deleteHost?.let { host -> AlertDialog(onDismissRequest = { deleteHost = null }, title = { Text(trf("删除 %s？", host.name)) }, text = { Text(tr("将删除这台主机及保存的凭据，并关闭它的连接。服务器信任记录会独立保留，可通过重新核验移除。远端 tmux 任务不受影响。")) },
            confirmButton = { TextButton(onClick = { scope.launch { sessions.filter { it.host.id == host.id }.forEach { service?.close(it.id); service?.forget(it.id) }; app.database.hosts().delete(host.id); prefs.forgetHostLaunches(host.id); deleteHost = null } }) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) } },
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

/** The home page's title: the app mark beside its name. */
@Composable
private fun HomeTitle(style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.testTag("hosts-title")) {
        ItermuxLogo(Modifier.size(with(LocalDensity.current) { style.fontSize.toDp() } * 1.15f))
        Text("Roamtty", style = style, maxLines = 1, modifier = Modifier.semantics { heading() })
    }
}

/** Settings and help, the destinations that are not hosts or sessions, behind one overflow button. */
@Composable
private fun AppMenu(onSettings: () -> Unit, onGuide: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(R.drawable.ic_more, tr("更多"), Modifier.testTag("app-menu")) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(tr("设置")) }, onClick = { open = false; onSettings() },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                modifier = Modifier.testTag("menu-settings"))
            DropdownMenuItem(text = { Text(tr("帮助")) }, onClick = { open = false; onGuide() },
                leadingIcon = { Icon(painterResource(R.drawable.ic_help), contentDescription = null) },
                modifier = Modifier.testTag("menu-guide"))
        }
    }
}

/** Phases in which a session is connected or on its way there; anything else counts as disconnected on host cards. */
private val livePhases = setOf(Phase.CONNECTED, Phase.CONNECTING, Phase.VERIFYING, Phase.AUTHENTICATING)
private const val SEARCH_HOST_THRESHOLD = 6
internal const val OPEN_LAST_SESSION_KEY = "openLastSession"
private const val LAST_SESSION_KEY = "lastSessionId"
