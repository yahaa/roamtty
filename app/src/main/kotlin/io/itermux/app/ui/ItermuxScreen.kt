package io.itermux.app.ui

import android.Manifest
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
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItermuxScreen(app: ItermuxApp, service: SessionService?, onChromeChange: (Boolean, Boolean) -> Unit) {
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
    var settingsMenu by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val lightTheme = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM && configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
    val terminalVisible = page == "terminal" && sessions.isNotEmpty()
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalVisible, imeVisible, lightTheme) { onChromeChange(terminalVisible, lightTheme) }
    BackHandler(page == "terminal") { page = "sessions" }
    BackHandler(page == "sessions") { page = "hosts" }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(service, sessions, selected, page) {
        if (service != null && page == "terminal" && service.sessions.value.none { it.id == selected }) page = "sessions"
    }
    var search by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val hostGridState = rememberLazyGridState()
    val sessionGridState = rememberLazyGridState()
    LaunchedEffect(landscape) {
        hostGridState.scrollToItem(hostGridState.firstVisibleItemIndex)
        sessionGridState.scrollToItem(sessionGridState.firstVisibleItemIndex)
    }
    val searchFocus = remember { FocusRequester() }
    BackHandler(page == "hosts" && searchExpanded && !imeVisible) { searchExpanded = false; search = "" }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteHost by remember { mutableStateOf<Host?>(null) }
    var connectRequest by remember { mutableStateOf<ConnectRequest?>(null) }
    var reverify by remember { mutableStateOf<TerminalConnection?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    fun start(request: ConnectRequest, password: String = "", passphrase: String = "") {
        val bound = service ?: run { toast("会话服务尚未就绪，请稍后重试"); return }
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
                val session = bound.open(request.host, request.mode, credentials)
                session.rename(request.title)
                selected = session.id
                page = "terminal"
            } catch (e: Exception) {
                credentials?.clear()
                toast(if (e is IllegalStateException) "最多保留 8 个会话，请先关闭旧会话" else "无法读取凭据，请重新导入密钥或保存密码。")
            }
        }
    }
    fun connect(host: Host, mode: SessionMode = SessionMode.valueOf(host.mode), title: String = "") {
        val request = ConnectRequest(host, mode, title)
        if (host.auth == AuthMethod.KEY.name || (host.auth == AuthMethod.PASSWORD.name && host.encryptedPassword == null)) connectRequest = request
        else start(request)
    }

    LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
    MaterialTheme(colorScheme = if (lightTheme) LightColors else DarkColors) {
        val colors = MaterialTheme.colorScheme
        ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = !terminalVisible,
            drawerContent = {
                ItermuxDrawer(page, sessions,
                    onHosts = { page = "hosts"; scope.launch { drawerState.close() } },
                    onSessions = { page = "sessions"; scope.launch { drawerState.close() } },
                    onOpenSession = { selected = it; page = "terminal"; scope.launch { drawerState.close() } },
                    onGuide = { scope.launch { drawerState.close() }; showAbout = true },
                    onClose = { scope.launch { drawerState.close() } })
            }) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            // The terminal fills the display, including the area beside a camera cutout.
            // Only desktop window chrome reserves a whole edge; terminal controls avoid cutouts locally.
            val contentInsets = if (terminalVisible) WindowInsets.captionBar else WindowInsets.safeDrawing
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).imePadding()) {
                if ((!imeVisible || searchExpanded) && !terminalVisible) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (landscape) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        scope.launch { drawerState.open() }
                    }, modifier = Modifier.size(48.dp).testTag("brand-logo")) {
                        Icon(painterResource(R.drawable.ic_terminal), contentDescription = "打开导航",
                            tint = Color.Unspecified, modifier = Modifier.size(40.dp))
                    }
                    if (landscape && configuration.screenWidthDp >= 700) {
                        Text(if (page == "hosts") {
                            if (search.isBlank()) "已保存的主机" else "搜索结果"
                        } else "会话列表",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp), maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    if (landscape) {
                        NavigationTabs(page, hosts.size, sessions.size) { page = it }
                    }
                    if (page == "hosts") {
                        IconButton(onClick = {
                            if (searchExpanded) {
                                search = ""
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                            searchExpanded = !searchExpanded
                        }, modifier = Modifier.testTag("search-toggle")) {
                            Text(if (searchExpanded) "×" else "⌕", fontSize = 26.sp,
                                modifier = Modifier.semantics { contentDescription = if (searchExpanded) "收起搜索" else "搜索主机" })
                        }
                        if (landscape) FilledTonalButton(onClick = { editHostId = null; editorOpen = true },
                            modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)) { Text("＋ 主机") }
                    }
                    Box {
                        IconButton(onClick = { settingsMenu = true }, modifier = Modifier.testTag("settings-toggle")) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置")
                        }
                        DropdownMenu(settingsMenu, { settingsMenu = false }) {
                            Text("外观", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            AppTheme.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) },
                                    trailingIcon = { if (theme == option) Text("✓", color = colors.primary) }, onClick = {
                                    theme = option
                                    prefs.edit().putString("theme", option.name).apply()
                                    settingsMenu = false
                                })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("使用指南") }, onClick = { settingsMenu = false; showAbout = true },
                                modifier = Modifier.testTag("settings-guide"))
                        }
                    }
                }
                if (!terminalVisible && (!landscape || imeVisible)) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NavigationTabs(page, hosts.size, sessions.size, 10.dp) { page = it }
                }
                if (page == "hosts") {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        if (!landscape) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (search.isBlank()) "已保存的主机" else "搜索结果", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { editHostId = null; editorOpen = true },
                                modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("＋ 主机") }
                        }
                        if (searchExpanded) {
                            OutlinedTextField(search, { search = it }, placeholder = { Text("名称、地址或用户名") },
                                leadingIcon = { Text("⌕", fontSize = 23.sp) },
                                trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("清除") } },
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
                                Text(if (hosts.isEmpty()) "从第一台主机开始" else "没有匹配的主机", fontSize = 20.sp)
                                Text(if (hosts.isEmpty()) "保存远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。" else "试试其他名称或地址", color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp), lineHeight = 23.sp)
                            }
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = hostGridState, modifier = Modifier.weight(1f).testTag("host-grid"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = if (landscape) 8.dp else 0.dp, bottom = 12.dp)) {
                            items(filtered, key = { it.id }) { host -> HostCard(host, landscape, { connect(host) }, { editHostId = host.id; editorOpen = true }, { deleteHost = host }) }
                        }
                    }
                } else if (page == "sessions") {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        if (!landscape) Text("会话列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                        if (sessions.isEmpty()) {
                            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("暂无会话", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                                Text("连接一台主机后，会话会显示在这里。", color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                                Button(onClick = { page = "hosts" }) { Text("选择主机") }
                            }
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = sessionGridState, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = if (landscape) 8.dp else 0.dp, bottom = 12.dp)) {
                            items(sessions, key = { it.id }) { session ->
                                SessionCard(session, onOpen = { selected = session.id; page = "terminal" }, onClose = { service?.close(session.id) })
                            }
                        }
                    }
                } else if (sessions.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("终端已就绪", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text("连接一台主机，开始远端工作。", color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                        Button(onClick = { page = "sessions" }) { Text("返回会话列表") }
                    }
                } else {
                    val active = sessions.find { it.id == selected }
                    if (active != null) key(active.id) {
                        TerminalPane(active, Modifier.weight(1f),
                            sessions = sessions, lightTheme = lightTheme, onSelectSession = { selected = it },
                            onSessions = { page = "sessions" }, onHosts = { page = "hosts" },
                            onReconnect = { service?.close(active.id); connect(active.host, active.mode, active.title.value) },
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
        deleteHost?.let { host -> AlertDialog(onDismissRequest = { deleteHost = null }, title = { Text("删除 ${host.name}？") }, text = { Text("将删除这台主机及保存的凭据，并关闭它的连接。远端 tmux 任务不受影响。") },
            confirmButton = { TextButton(onClick = { scope.launch { sessions.filter { it.host.id == host.id }.forEach { service?.close(it.id) }; app.database.hosts().delete(host.id); deleteHost = null } }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteHost = null }) { Text("取消") } }) }
        connectRequest?.let { request -> CredentialsDialog(request, { connectRequest = null }) { password, phrase -> connectRequest = null; start(request, password, phrase) } }
        reverify?.let { session -> AlertDialog(onDismissRequest = { reverify = null }, title = { Text("重新核验主机指纹") }, text = { Text("请先向服务器管理员确认新指纹。继续会移除此地址原有的信任记录，关闭该地址的连接，并在重连时重新展示指纹。\n\n${session.host.target().endpoint}") },
            confirmButton = { TextButton(onClick = { scope.launch {
                val endpoint = session.host.target().endpoint
                sessions.filter { it.host.target().endpoint == endpoint }.forEach { service?.close(it.id) }
                app.database.hosts().forgetPin(endpoint)
                reverify = null
                connect(session.host, session.mode)
            } }) { Text("移除旧记录并重连") } }, dismissButton = { TextButton(onClick = { reverify = null }) { Text("取消") } }) }
        if (showAbout) AboutDialog(context) { showAbout = false }
    }
}

@Composable
private fun NavigationTabs(page: String, hostCount: Int, sessionCount: Int, spacing: Dp = 8.dp, onPageChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        FilterChip(page == "hosts", { onPageChange("hosts") }, label = { Text("主机  $hostCount") }, modifier = Modifier.testTag("hosts-tab"))
        FilterChip(page == "sessions", { onPageChange("sessions") }, label = { Text("会话  $sessionCount") }, modifier = Modifier.testTag("sessions-tab"))
    }
}
