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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val hosts by app.database.hosts().observe().collectAsStateWithLifecycle(emptyList())
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
                toast(if (e is IllegalStateException) tr("最多保留 8 个会话，请先关闭旧会话") else tr("无法读取凭据，请重新导入密钥或保存密码。"))
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
    var shareSessionIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var shareUrisPending by rememberSaveable { mutableStateOf<List<Uri>>(emptyList()) }
    fun uploadSharedImages(session: TerminalConnection, uris: List<Uri>) {
        if (uris.isEmpty()) return
        selected = session.id
        page = "terminal"
        if (uris.size > 1) toast(trf("共 %d 张图片，将依次上传", uris.size))
        scope.launch {
            for (uri in uris) {
                session.transfers.status.first { !it.running }
                if (session.transfers.status.value.result != null) session.transfers.dismiss()
                session.transfers.uploadImageForCli(context, session, uri)
                if (!session.transfers.status.value.running) break
                val completed = session.transfers.status.first { !it.running }
                if (completed.succeeded != true) break
            }
        }
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
                shareUrisPending = uris
                shareSessionIds = connected.map { it.id }
            }
        }
    }
    LaunchedEffect(pendingShareImages, service) {
        if (pendingShareImages.isEmpty() || service == null) return@LaunchedEffect
        service.awaitRestoration()
        val uris = pendingShareImages.toList()
        onShareImagesConsumed()
        routeSharedImages(uris, service.sessions.value)
    }
    LaunchedEffect(searchExpanded, page) { if (searchExpanded && navigationVisible) searchFocus.requestFocus() }
    MaterialTheme(colorScheme = if (lightTheme) LightColors else DarkColors) {
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
                if ((!imeVisible || searchExpanded) && !terminalVisible && page != "files") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (landscape) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        scope.launch { drawerState.open() }
                    }, modifier = Modifier.size(48.dp).testTag("brand-logo")) {
                        Icon(painterResource(R.drawable.ic_sidebar), contentDescription = tr("打开导航"),
                            tint = colors.onSurface, modifier = Modifier.size(24.dp))
                    }
                    if (landscape && configuration.screenWidthDp >= 700) {
                        Text(if (page == "hosts") {
                            if (search.isBlank()) tr("已保存的主机") else tr("搜索结果")
                        } else if (page == "sessions") tr("会话列表")
                        else if (page == "files") tr("服务器文件")
                        else tr("设置"),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp), maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    if (landscape && navigationVisible) {
                        NavigationTabs(page, hosts.size, sessions.size) { page = it }
                    }
                    if (navigationVisible) {
                        IconButton(onClick = {
                            if (searchExpanded) {
                                search = ""
                                sessionSearch = ""
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                            searchExpanded = !searchExpanded
                        }, modifier = Modifier.testTag("search-toggle")) {
                            if (searchExpanded) Text("×", fontSize = 26.sp,
                                modifier = Modifier.semantics { contentDescription = tr("收起搜索") })
                            else Icon(painterResource(R.drawable.ic_search),
                                contentDescription = if (page == "hosts") tr("搜索主机") else tr("搜索会话"))
                        }
                        if (page == "hosts" && landscape) FilledTonalButton(onClick = { editHostId = null; editorOpen = true },
                            modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)) { Text(tr("＋ 主机")) }
                    }
                    if (page == "settings") IconButton(onClick = { page = settingsReturnPage },
                        modifier = Modifier.testTag("close-settings")) {
                        Text("×", fontSize = 26.sp, modifier = Modifier.semantics { contentDescription = tr("关闭设置") })
                    }
                }
                if (navigationVisible && (!landscape || imeVisible)) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NavigationTabs(page, hosts.size, sessions.size, 10.dp) { page = it }
                }
                if (page == "hosts") {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        if (!landscape) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (search.isBlank()) tr("已保存的主机") else tr("搜索结果"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { editHostId = null; editorOpen = true },
                                modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text(tr("＋ 主机")) }
                        }
                        if (searchExpanded) {
                            OutlinedTextField(search, { search = it }, placeholder = { Text(tr("名称、地址或用户名")) },
                                leadingIcon = { Text("⌕", fontSize = 23.sp) },
                                trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text(tr("清除")) } },
                                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                    .focusRequester(searchFocus).testTag("host-search"),
                                shape = RoundedCornerShape(14.dp))
                        }
                        val filtered = hosts.filter { "${it.name} ${it.address} ${it.username}".contains(search, true) }
                        if (filtered.isEmpty()) {
                            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                if (hosts.isEmpty()) Icon(painterResource(R.drawable.ic_terminal), contentDescription = "iTermux",
                                    tint = Color.Unspecified, modifier = Modifier.size(76.dp).testTag("empty-logo"))
                                Spacer(Modifier.height(18.dp))
                                Text(if (hosts.isEmpty()) tr("从第一台主机开始") else tr("没有匹配的主机"), fontSize = 20.sp)
                                Text(if (hosts.isEmpty()) tr("保存远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。") else tr("试试其他名称或地址"), color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp), lineHeight = 23.sp)
                            }
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = hostGridState, modifier = Modifier.weight(1f).testTag("host-grid"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = if (landscape) 8.dp else 0.dp, bottom = 12.dp)) {
                            items(filtered, key = { it.id }) { host -> HostCard(host, landscape, { connect(host) }, { editHostId = host.id; editorOpen = true }, { deleteHost = host }, { browseFiles(host) }) }
                        }
                    }
                } else if (page == "sessions") {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        if (!landscape) Text(if (sessionSearch.isBlank()) tr("会话列表") else tr("搜索结果"),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                        if (searchExpanded) OutlinedTextField(sessionSearch, { sessionSearch = it },
                            placeholder = { Text(tr("标题、主机、地址或用户名")) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                            trailingIcon = { if (sessionSearch.isNotEmpty()) TextButton(onClick = { sessionSearch = "" }) { Text(tr("清除")) } },
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                .focusRequester(searchFocus).testTag("session-search"),
                            shape = RoundedCornerShape(14.dp))
                        val filteredSessions = sessions.filter {
                            "${it.displayTitle} ${it.host.name} ${it.host.address} ${it.host.username}".contains(sessionSearch, true)
                        }
                        if (filteredSessions.isEmpty()) {
                            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (sessions.isEmpty()) tr("暂无会话") else tr("没有匹配的会话"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (sessions.isEmpty()) tr("连接一台主机后，会话会显示在这里。") else tr("试试其他标题或主机信息"),
                                    color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                                if (sessions.isEmpty()) Button(onClick = { page = "hosts" }) { Text(tr("选择主机")) }
                            }
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = sessionGridState, modifier = Modifier.weight(1f).testTag("session-grid"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = if (landscape) 8.dp else 0.dp, bottom = 12.dp)) {
                            items(filteredSessions, key = { it.id }) { session ->
                                SessionCard(session, onOpen = { selected = session.id; page = "terminal" }, onClose = { service?.close(session.id) })
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
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("会话不存在"), fontSize = 18.sp)
                            Button(onClick = { page = "hosts" }, modifier = Modifier.padding(top = 16.dp)) { Text(tr("返回主机列表")) }
                        }
                    }
                } else if (page == "settings") {
                    SettingsPage(theme, { option ->
                        theme = option
                        prefs.edit { putString("theme", option.name) }
                    }, AppLocale.language, { option -> AppLocale.set(context, option); service?.refreshLanguage() })
                } else if (sessions.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tr("终端已就绪"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text(tr("连接一台主机，开始远端工作。"), color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                        Button(onClick = { page = "sessions" }) { Text(tr("返回会话列表")) }
                    }
                } else {
                    val active = sessions.find { it.id == selected }
                    // Manual reconnect preserves the journal id but replaces the transport and emulator.
                    if (active != null) key(active) {
                        TerminalPane(active, Modifier.weight(1f),
                            sessions = sessions, lightTheme = lightTheme, onSelectSession = { selected = it },
                            onSessions = { page = "sessions" }, onHosts = { page = "hosts" },
                            onReconnect = { connect(active.host, active.mode, active.title.value, reuseSessionId = active.id) },
                            onNewSession = { connect(active.host, active.mode) },
                            onShell = { service?.close(active.id); connect(active.host, SessionMode.SHELL, active.title.value) },
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
            confirmButton = { TextButton(onClick = { scope.launch { sessions.filter { it.host.id == host.id }.forEach { service?.close(it.id) }; app.database.hosts().delete(host.id); deleteHost = null } }) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteHost = null }) { Text(tr("取消")) } }) }
        connectRequest?.let { request -> CredentialsDialog(request, { connectRequest = null }) { password, phrase -> connectRequest = null; start(request, password, phrase) } }
        reverify?.let { session -> AlertDialog(onDismissRequest = { reverify = null }, title = { Text(tr("重新核验主机指纹")) }, text = { Text(trf("请先向服务器管理员确认新指纹。继续会移除此地址原有的信任记录，关闭该地址的连接，并在重连时重新展示指纹。\n\n%s", session.host.target().endpoint)) },
            confirmButton = { TextButton(onClick = { scope.launch {
                val endpoint = session.host.target().endpoint
                sessions.filter { it.host.target().endpoint == endpoint }.forEach { service?.close(it.id) }
                app.database.hosts().forgetPin(endpoint)
                reverify = null
                connect(session.host, session.mode)
            } }) { Text(tr("移除旧记录并重连")) } }, dismissButton = { TextButton(onClick = { reverify = null }) { Text(tr("取消")) } }) }
        shareSessionIds?.let { ids ->
            val choices = sessions.filter { it.id in ids }
            fun dismissShare() { shareSessionIds = null; shareUrisPending = emptyList() }
            AppSheet(onDismiss = ::dismissShare) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                    item { SheetHeader(tr("选择会话以上传图片"), tr("选择接收图片的主机和会话。"), ::dismissShare) }
                    items(choices.size, key = { choices[it].id }) { index ->
                        val session = choices[index]
                        val title by session.title.collectAsStateWithLifecycle()
                        val state by session.state.collectAsStateWithLifecycle()
                        TextButton(onClick = {
                            val uris = shareUrisPending
                            dismissShare()
                            uploadSharedImages(session, uris)
                        }, enabled = state.phase == Phase.CONNECTED,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("share-session-${session.id}")) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(title.ifBlank { session.defaultTitle }, style = MaterialTheme.typography.bodyLarge)
                                Text("${session.host.username}@${session.host.address}:${session.host.port}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (showAbout) AboutDialog(context) { showAbout = false }
    }
}

@Composable
private fun NavigationTabs(page: String, hostCount: Int, sessionCount: Int, spacing: Dp = 8.dp, onPageChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        FilterChip(page == "hosts", { onPageChange("hosts") }, label = { Text(trf("主机  %d", hostCount)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_hosts), contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("hosts-tab"))
        FilterChip(page == "sessions", { onPageChange("sessions") }, label = { Text(trf("会话  %d", sessionCount)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_sessions), contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("sessions-tab"))
    }
}
