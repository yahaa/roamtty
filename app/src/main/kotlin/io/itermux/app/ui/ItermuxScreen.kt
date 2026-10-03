package io.itermux.app.ui

import io.itermux.app.tr

import android.Manifest
import android.net.Uri
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.itermux.app.session.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    var page by rememberSaveable { mutableStateOf("hosts") }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(AppTheme.from(prefs.getString("theme", null))) }
    var settingsReturnPage by rememberSaveable { mutableStateOf("hosts") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val lightTheme = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM && configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
    val terminalVisible = page == "terminal" && sessions.isNotEmpty()
    val navigationVisible = page == "hosts" || page == "sessions"
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalVisible, imeVisible, lightTheme) { onChromeChange(terminalVisible, lightTheme) }
    BackHandler(page == "terminal") { page = "sessions" }
    BackHandler(page == "sessions") { page = "hosts" }
    BackHandler(page == "settings" && !drawerState.isOpen) { page = settingsReturnPage }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(service, sessions, selected, page) {
        service?.awaitRestoration()
        if (service != null && page == "terminal" && service.sessions.value.none { it.id == selected }) page = "sessions"
        if (service != null && page == "files" && service.sessions.value.none { it.id == selected }) page = "hosts"
    }
    var search by rememberSaveable { mutableStateOf("") }
    var sessionSearch by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val hostGridState = rememberLazyGridState()
    val sessionGridState = rememberLazyGridState()
    LaunchedEffect(landscape) {
        hostGridState.scrollToItem(hostGridState.firstVisibleItemIndex)
        sessionGridState.scrollToItem(sessionGridState.firstVisibleItemIndex)
    }
    val searchFocus = remember { FocusRequester() }
    BackHandler(navigationVisible && searchExpanded && !imeVisible && !drawerState.isOpen) {
        searchExpanded = false
        search = ""
        sessionSearch = ""
    }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteHost by remember { mutableStateOf<Host?>(null) }
    var connectRequest by remember { mutableStateOf<ConnectRequest?>(null) }
    var reverify by remember { mutableStateOf<TerminalConnection?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
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
                val session = if (request.reuseSessionId != null) {
                    bound.reconnect(request.reuseSessionId, credentials)
                } else {
                    bound.open(request.host, request.mode, credentials)
                }
                if (request.title.isNotBlank()) session.rename(request.title)
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
    fun connect(host: Host, mode: SessionMode = SessionMode.valueOf(host.mode), title: String = "", reuseSessionId: String? = null, openFilesAfterConnect: Boolean = false) {
        val request = ConnectRequest(host, mode, title, reuseSessionId, openFilesAfterConnect)
        if (host.auth == AuthMethod.KEY.name || (host.auth == AuthMethod.PASSWORD.name && host.encryptedPassword == null)) connectRequest = request
        else start(request)
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
        if (uris.size > 1) toast(trf("共 %d 张图片，将依次上传", uris.size))
        if (!session.transfers.uploadSharedImages(context, session, uris))
            toast(tr("已有传输进行中，请稍后再试"))
    }
    fun routeSharedImages(uris: List<Uri>, available: List<TerminalConnection>) {
        if (uris.isEmpty()) return
        val connected = available.filter { it.state.value.phase == Phase.CONNECTED }
        when {
            connected.isEmpty() -> {
                toast(tr("请先连接一台主机后再分享图片"))
                page = if (available.isNotEmpty()) "sessions" else "hosts"
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
        ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = !terminalVisible && page != "files",
            drawerContent = {
                ItermuxDrawer(page, sessions,
                    onHosts = { page = "hosts"; scope.launch { drawerState.close() } },
                    onSessions = { page = "sessions"; scope.launch { drawerState.close() } },
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
            // The terminal fills the display, including the area beside a camera cutout.
            // Only desktop window chrome reserves a whole edge; terminal controls avoid cutouts locally.
            val contentInsets = if (terminalVisible) WindowInsets.captionBar else WindowInsets.safeDrawing
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).imePadding()) {
                if ((!imeVisible || searchExpanded) && !terminalVisible && page != "files") AppToolbar(
                    navigation = {
                        ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo")) {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            scope.launch { drawerState.open() }
                        }
                    },
                    actions = {
                        if (navigationVisible) {
                            Box(Modifier.weight(1f).padding(horizontal = Spacing.Tight), contentAlignment = Alignment.Center) {
                                NavigationTabs(page, hosts.size, sessions.size, Modifier.widthIn(max = 280.dp).fillMaxWidth()) { page = it }
                            }
                            AppSearchToggle(searchExpanded, onToggle = {
                                if (searchExpanded) {
                                    search = ""
                                    sessionSearch = ""
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                                searchExpanded = !searchExpanded
                            }, searchDescription = if (page == "hosts") tr("搜索主机") else tr("搜索会话"),
                                modifier = Modifier.testTag("search-toggle"))
                            if (page == "hosts") ToolbarIcon(R.drawable.ic_add, tr("添加主机"), Modifier.testTag("add-host")) {
                                editHostId = null; editorOpen = true
                            } else Spacer(Modifier.width(48.dp))
                        } else {
                            // Settings is a drawer destination: closing returns to the page it was opened from.
                            Spacer(Modifier.weight(1f))
                            ToolbarIcon(R.drawable.ic_close, tr("关闭设置"), Modifier.testTag("close-settings")) { page = settingsReturnPage }
                        }
                    })
                // Landscape phones have little height: the toolbar segment already names the page.
                val largeTitle = !landscape && !imeVisible
                if (page == "hosts") {
                    Column(Modifier.fillMaxSize()) {
                        if (largeTitle) LargeTitle(if (search.isBlank()) tr("已保存的主机") else tr("搜索结果"))
                        if (searchExpanded) {
                            AppSearchField(search, { search = it }, tr("名称、地址或用户名"),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).padding(bottom = Spacing.Tight)
                                    .focusRequester(searchFocus).testTag("host-search"))
                        }
                        val filtered = hosts.filter { "${it.name} ${it.address} ${it.username}".contains(search, true) }
                        if (filtered.isEmpty()) {
                            if (hosts.isEmpty()) EmptyState(tr("从第一台主机开始"), tr("保存远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。"),
                                Modifier.weight(1f),
                                artwork = { ItermuxLogo(Modifier.size(56.dp).testTag("empty-logo"), contentDescription = "iTermux") },
                                action = { Button(onClick = { editHostId = null; editorOpen = true }) { Text(tr("添加主机")) } })
                            else EmptyState(tr("没有匹配的主机"), tr("试试其他名称或地址"), Modifier.weight(1f))
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = hostGridState, modifier = Modifier.weight(1f).testTag("host-grid"), horizontalArrangement = Arrangement.spacedBy(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Row), contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, top = if (largeTitle) 0.dp else Spacing.Tight, bottom = Spacing.Page)) {
                            items(filtered, key = { it.id }) { host -> HostCard(host, { connect(host) }, { editHostId = host.id; editorOpen = true }, { deleteHost = host }, { browseFiles(host) }) }
                        }
                    }
                } else if (page == "sessions") {
                    Column(Modifier.fillMaxSize()) {
                        if (largeTitle) LargeTitle(if (sessionSearch.isBlank()) tr("会话列表") else tr("搜索结果"))
                        if (searchExpanded) AppSearchField(sessionSearch, { sessionSearch = it }, tr("标题、主机、地址或用户名"),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).padding(bottom = Spacing.Tight)
                                .focusRequester(searchFocus).testTag("session-search"))
                        val filteredSessions = sessions.filter {
                            "${it.displayTitle} ${it.host.name} ${it.host.address} ${it.host.username}".contains(sessionSearch, true)
                        }
                        if (filteredSessions.isEmpty()) {
                            if (sessions.isEmpty()) EmptyState(tr("暂无会话"), tr("连接一台主机后，会话会显示在这里。"), Modifier.weight(1f),
                                action = { FilledTonalButton(onClick = { page = "hosts" }) { Text(tr("选择主机")) } })
                            else EmptyState(tr("没有匹配的会话"), tr("试试其他标题或主机信息"), Modifier.weight(1f))
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = sessionGridState, modifier = Modifier.weight(1f).testTag("session-grid"), horizontalArrangement = Arrangement.spacedBy(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Row), contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, top = if (largeTitle) 0.dp else Spacing.Tight, bottom = Spacing.Page)) {
                            items(filteredSessions, key = { it.id }) { session ->
                                SessionCard(session, onOpen = { selected = session.id; page = "terminal" }, onClose = { service?.close(session.id) }, onForget = { service?.forget(session.id) })
                            }
                        }
                    }
                } else if (page == "files") {
                    val filesSession = sessions.find { it.id == selected }
                    if (filesSession != null) {
                        key(filesSession) {
                            RemoteFileBrowserPage(filesSession, onBack = { page = "hosts" }, Modifier.weight(1f))
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
                        action = { FilledTonalButton(onClick = { page = "sessions" }) { Text(tr("返回会话列表")) } })
                } else {
                    val active = sessions.find { it.id == selected }
                    // Manual reconnect preserves the journal id but replaces the transport and emulator.
                    if (active != null) key(active) {
                        TerminalPane(active, Modifier.weight(1f),
                            sessions = sessions, lightTheme = lightTheme, onSelectSession = { selected = it },
                            onSessions = { page = "sessions" }, onHosts = { page = "hosts" },
                            onReconnect = { connect(active.host, active.mode, active.title.value, reuseSessionId = active.id) },
                            onNewSession = { connect(active.host, active.mode) },
                            onShell = { service?.close(active.id); service?.forget(active.id); connect(active.host, SessionMode.SHELL, active.title.value) },
                            onReverify = { reverify = active }, onClose = { service?.close(active.id) })
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
        deleteHost?.let { host -> AlertDialog(onDismissRequest = { deleteHost = null }, title = { Text(trf("删除 %s？", host.name)) }, text = { Text(tr("将删除这台主机及保存的凭据，并关闭它的连接。远端 tmux 任务不受影响。")) },
            confirmButton = { TextButton(onClick = { scope.launch { sessions.filter { it.host.id == host.id }.forEach { service?.close(it.id); service?.forget(it.id) }; app.database.hosts().delete(host.id); deleteHost = null } }) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteHost = null }) { Text(tr("取消")) } }) }
        connectRequest?.let { request -> CredentialsDialog(request, { connectRequest = null }) { password, phrase -> connectRequest = null; start(request, password, phrase) } }
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
                            leading = { HostSystemIcon(session.host.os, 40.dp) },
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

@Composable
private fun NavigationTabs(page: String, hostCount: Int, sessionCount: Int, modifier: Modifier = Modifier, onPageChange: (String) -> Unit) {
    SegmentedControl(listOf(
        Segment(trf("主机  %d", hostCount), page == "hosts", "hosts-tab") { onPageChange("hosts") },
        Segment(trf("会话  %d", sessionCount), page == "sessions", "sessions-tab") { onPageChange("sessions") },
    ), modifier)
}
