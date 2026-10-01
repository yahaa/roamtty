package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.clipSessionTitle
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode

@Composable internal fun HostCard(host: Host, compact: Boolean, connect: () -> Unit, edit: () -> Unit, delete: () -> Unit, browseFiles: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val menuControl: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("host-menu-${host.id}")) {
                Text("•••", modifier = Modifier.semantics { contentDescription = trf("主机选项：%s", host.name) })
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(tr("编辑主机")) }, onClick = { menu = false; edit() })
                DropdownMenuItem(text = { Text(tr("查看文件")) }, onClick = { menu = false; browseFiles() },
                    modifier = Modifier.testTag("host-browse-files-${host.id}"))
                DropdownMenuItem(text = { Text(tr("删除主机")) }, onClick = { menu = false; delete() })
            }
        }
    }
    Card(onClick = connect, colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
        if (compact) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HostSystemIcon(host.os)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(host.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${host.username}@${host.address}:${host.port}", color = colors.onSurfaceVariant,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${if (host.mode == "TMUX") "tmux · 可恢复" else "SSH · 普通会话"}  /  ${authLabel(host.auth)}",
                    color = colors.primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(tr("连接 →"), color = colors.primary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            menuControl()
        } else Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HostSystemIcon(host.os)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(host.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("${host.username}@${host.address}:${host.port}", color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
                    if (host.os == "UNKNOWN") Text(tr("系统未设置 · 编辑主机可选择"), color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
                menuControl()
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (host.mode == "TMUX") tr("tmux · 可恢复") else tr("SSH · 普通会话"), fontSize = 11.sp, color = colors.primary)
                Text("/", color = colors.onSurfaceVariant)
                Text(authLabel(host.auth), fontSize = 11.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(tr("连接 →"), fontSize = 13.sp, color = colors.primary)
            }
        }
    }
}

@Composable internal fun SessionCard(session: TerminalConnection, onOpen: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var confirmClose by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(false) }
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant), shape = RoundedCornerShape(18.dp), modifier = Modifier.testTag("session-${session.id}")) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HostSystemIcon(session.host.os)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(session.titleForDisplay(title), fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${session.host.username}@${session.host.address}:${session.host.port}",
                        color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { editTitle = true }, modifier = Modifier.testTag("rename-session-${session.id}")) {
                    Text("✎", modifier = Modifier.semantics { contentDescription = tr("修改会话标题") })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (session.mode == SessionMode.TMUX) tr("tmux · 可恢复") else tr("SSH · 普通会话"),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(10.dp))
                Text("● ${phaseLabel(state.phase)}", color = if (state.phase == Phase.CONNECTED) colors.primary else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmClose = true }) { Text(tr("关闭")) }
            }
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text(trf("关闭 %s？", session.host.name)) },
        text = { Text(if (session.mode == SessionMode.TMUX) tr("远端 tmux 会话会继续运行。") else tr("普通 SSH shell 会结束。")) },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text(tr("关闭连接")) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(tr("取消")) } })
    if (editTitle) SessionTitleDialog(session) { editTitle = false }
}

@Composable internal fun SessionTitleDialog(session: TerminalConnection, onDismiss: () -> Unit) {
    var title by remember(session.id) { mutableStateOf(session.title.value) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    fun finish() { focusManager.clearFocus(force = true); keyboardController?.hide(); onDismiss() }
    AlertDialog(onDismissRequest = ::finish, title = { Text(tr("修改会话标题")) },
        text = { Column {
            Text(tr("主机名与会话标题在同一行显示。标题留空则显示会话编号。"))
            OutlinedTextField(title, { title = clipSessionTitle(it) }, label = { Text(tr("会话标题")) },
                placeholder = { Text(session.defaultTitle) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("session-title-input"))
        } },
        confirmButton = { TextButton(onClick = { session.rename(title); finish() }) { Text(tr("保存")) } },
        dismissButton = { TextButton(onClick = ::finish) { Text(tr("取消")) } })
}

@Composable private fun HostSystemIcon(os: String) {
    val branded = os == "LINUX" || os == "WINDOWS" || os == "MACOS"
    val inset = when (os) {
        "LINUX" -> 3.dp
        "WINDOWS" -> 6.dp
        "MACOS" -> 4.dp
        else -> 11.dp
    }
    val (icon, label) = when (os) {
        "LINUX" -> R.drawable.ic_os_linux to "Linux"
        "WINDOWS" -> R.drawable.ic_os_windows to "Windows"
        "MACOS" -> R.drawable.ic_os_macos to "macOS"
        "OTHER" -> R.drawable.ic_os_server to tr("其他系统")
        else -> R.drawable.ic_os_server to tr("未知系统")
    }
    Surface(color = if (branded) Color(0xFFF0EBE2) else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp)) {
        Icon(painterResource(icon), contentDescription = label,
            tint = if (branded) Color.Unspecified else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(inset).size(49.dp - inset * 2))
    }
}
