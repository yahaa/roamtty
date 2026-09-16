package io.itermux.app.ui

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.session.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

private val Background = Color(0xFF0B1118)
private val Panel = Color(0xFF14212D)
private val Mint = Color(0xFF67E8C4)
private val Muted = Color(0xFF93A8B8)

data class ConnectRequest(val host: Host, val mode: SessionMode)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItermuxScreen(app: ItermuxApp, service: SessionService?, onTerminalVisibleChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val hosts by app.database.hosts().observe().collectAsStateWithLifecycle(emptyList())
    val sessionFlow = remember(service) { service?.sessions ?: MutableStateFlow(emptyList()) }
    val sessions by sessionFlow.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("hosts") }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val terminalVisible = page == "sessions" && sessions.isNotEmpty()
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalVisible, imeVisible) { onTerminalVisibleChange(terminalVisible) }
    BackHandler(page == "sessions") { page = "hosts" }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var editorOpen by remember { mutableStateOf(false) }
    var editHost by remember { mutableStateOf<Host?>(null) }
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
                selected = session.id
                page = "sessions"
            } catch (e: Exception) {
                credentials?.clear()
                toast(if (e is IllegalStateException) "最多保留 8 个会话，请先关闭旧会话" else "无法读取凭据，请重新导入密钥或保存密码。")
            }
        }
    }
    fun connect(host: Host, mode: SessionMode = SessionMode.valueOf(host.mode)) {
        val request = ConnectRequest(host, mode)
        if (host.auth == AuthMethod.KEY.name || (host.auth == AuthMethod.PASSWORD.name && host.encryptedPassword == null)) connectRequest = request
        else start(request)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Background, primaryContainer = Color(0xFF203B3D), secondaryContainer = Color(0xFF203B3D), onSecondaryContainer = Mint, background = Background, surface = Background, surfaceVariant = Panel, onSurfaceVariant = Muted)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            // The terminal fills the display, including the area beside a camera cutout.
            // Only desktop window chrome reserves a whole edge; terminal controls avoid cutouts locally.
            val contentInsets = if (terminalVisible) WindowInsets.captionBar else WindowInsets.safeDrawing
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).imePadding()) {
                if (!imeVisible && !terminalVisible) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (landscape) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("›_", color = Mint, fontSize = 27.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("iTermux", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    if (landscape) {
                        FilterChip(page == "hosts", { page = "hosts" }, label = { Text("主机  ${hosts.size}") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(page == "sessions", { page = "sessions" }, label = { Text("会话  ${sessions.size}") })
                    }
                    TextButton(onClick = { showAbout = true }) { Text("使用指南") }
                }
                if (!terminalVisible && (!landscape || imeVisible)) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(page == "hosts", { page = "hosts" }, label = { Text("主机  ${hosts.size}") })
                    FilterChip(page == "sessions", { page = "sessions" }, label = { Text("会话  ${sessions.size}") })
                }
                if (page == "hosts") {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(search, { search = it }, label = { Text("搜索名称、地址或用户名", maxLines = 1) }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                            FilledTonalButton(onClick = { editHost = null; editorOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("＋ 主机") }
                        }
                        val filtered = hosts.filter { "${it.name} ${it.address} ${it.username}".contains(search, true) }
                        if (filtered.isEmpty()) {
                            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("[  ›_  ]", fontSize = 36.sp, fontFamily = FontFamily.Monospace, color = Mint)
                                Spacer(Modifier.height(18.dp))
                                Text(if (hosts.isEmpty()) "从第一台主机开始" else "没有匹配的主机", fontSize = 20.sp)
                                Text(if (hosts.isEmpty()) "保存 Linux 主机，在远端运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。" else "试试其他名称或地址", color = Muted, modifier = Modifier.padding(16.dp), lineHeight = 23.sp)
                            }
                        } else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                            items(filtered, key = { it.id }) { host -> HostCard(host, { connect(host) }, { editHost = host; editorOpen = true }, { deleteHost = host }) }
                        }
                    }
                } else if (sessions.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("终端已就绪", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text("连接一台主机，开始远端工作。\n应用进程重建后需手动重连；tmux 可恢复远端任务。", color = Muted, modifier = Modifier.padding(vertical = 16.dp), lineHeight = 24.sp)
                        Button(onClick = { page = "hosts" }) { Text("选择主机") }
                    }
                } else {
                    val active = sessions.find { it.id == selected } ?: sessions.last()
                    key(active.id) {
                        TerminalPane(active, Modifier.weight(1f),
                            sessions = sessions, onSelectSession = { selected = it }, onHosts = { page = "hosts" },
                            onReconnect = { service?.close(active.id); connect(active.host, active.mode) },
                            onShell = { service?.close(active.id); connect(active.host, SessionMode.SHELL) },
                            onReverify = { reverify = active }, onClose = { service?.close(active.id) })
                    }
                }
            }
        }
        if (editorOpen) HostEditor(app, editHost, onDismiss = { editorOpen = false }, onSaved = { editorOpen = false })
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

@Composable private fun HostCard(host: Host, connect: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = connect, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF203B3D), shape = RoundedCornerShape(12.dp)) { Text(host.name.take(1).uppercase(), color = Mint, fontSize = 23.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(host.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("${host.username}@${host.address}:${host.port}", color = Muted, fontSize = 12.sp, maxLines = 2)
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("•••") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑主机") }, onClick = { menu = false; edit() })
                        DropdownMenuItem(text = { Text("删除主机") }, onClick = { menu = false; delete() })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (host.mode == "TMUX") "tmux · 可恢复" else "SSH · 普通会话", fontSize = 11.sp, color = Mint)
                Text("/", color = Muted)
                Text(authLabel(host.auth), fontSize = 11.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                Text("连接 →", fontSize = 13.sp, color = Mint)
            }
        }
    }
}

fun authLabel(auth: String) = when (auth) { "KEY" -> "私钥"; "INTERACTIVE" -> "交互式认证"; else -> "密码" }
fun phaseLabel(phase: Phase) = when (phase) {
    Phase.CONNECTING -> "连接中"; Phase.VERIFYING -> "待验证"; Phase.AUTHENTICATING -> "认证中"
    Phase.CONNECTED -> "已连接"; Phase.DISCONNECTED -> "已断开"; Phase.AUTH_FAILED -> "认证失败"
    Phase.HOST_KEY_CHANGED -> "指纹变化"; Phase.TMUX_MISSING -> "缺少 tmux"; Phase.FAILED -> "连接失败"
}

@Composable private fun CredentialsDialog(request: ConnectRequest, dismiss: () -> Unit, connect: (String, String) -> Unit) {
    var secret by remember { mutableStateOf("") }
    val key = request.host.auth == "KEY"
    AlertDialog(onDismissRequest = dismiss, title = { Text("连接 ${request.host.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (key) "私钥口令仅用于本次连接；未加密私钥可留空。" else "密码仅用于本次连接，默认不保存。")
            OutlinedTextField(secret, { secret = it }, label = { Text(if (key) "私钥口令（可留空）" else "SSH 密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { if (key) connect("", secret) else connect(secret, ""); secret = "" }) { Text("连接") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun AboutDialog(context: Context, dismiss: () -> Unit) {
    var licenses by remember { mutableStateOf(false) }
    val licenseText = remember { runCatching { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("许可文件请参阅源码中的 THIRD_PARTY_NOTICES.md。") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (licenses) "第三方许可" else "iTermux 0.1.0") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (licenses) licenseText else "1. 添加主机；使用私钥时导入 OpenSSH 或 PEM 格式的 Ed25519/RSA 私钥。首次连接先核对 SHA-256 指纹。\n\n2. 连接后操作栏默认隐藏，点击右上角 ⋯ 展开。键盘默认收起；点击终端输入区自动弹出，也可通过键盘按钮开关。\n\n3. 更多菜单中可打开“编辑文本”、粘贴或调整字号。编辑框中的文字先留在草稿，确认后发送；发送文本与回车分开。长按终端可选择复制。右侧和编辑框中的 ←/→ 可移动光标，⌫ 点按删除字符、长按清空当前输入。终端内持续按住输入法删除键，连续退格约 1 秒后清空当前行；快速连续点按也可能触发。清空需远端支持 Ctrl+U/Ctrl+K。\n\n4. 连接页横竖屏都默认全屏，边缘滑动可临时显示系统栏。会话图标用于切换会话。更多菜单可将操作栏固定在右侧，固定偏好会保留；选择“隐藏操作栏”后，点击右上角 ⋯ 恢复。编辑文字时暂时收起操作栏，关闭编辑框后恢复。拖动最右侧滚动条可查看本地历史，停止后自动隐藏；阅读旧内容时保持位置，拖到底部继续跟随输出。\n\n5. tmux 模式创建或附着 itermux 会话；断线后手动重连即可继续远端任务。通知栏可全部断开连接。")
            TextButton(onClick = { licenses = !licenses }) { Text(if (licenses) "返回指南" else "查看第三方许可") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}
