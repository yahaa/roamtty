package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.clipSessionTitle
import io.itermux.ssh.SessionMode
import io.itermux.ssh.HostSystem

@Composable internal fun HostCard(host: Host, connect: () -> Unit, edit: () -> Unit, delete: () -> Unit, browseFiles: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    ListCard(onClick = connect, onClickLabel = tr("连接"), modifier = Modifier.testTag("host-card-${host.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HostSystemIcon(host.os)
            Column(Modifier.weight(1f).padding(start = Spacing.Row)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${host.username}@${host.address}:${host.port}", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("host-menu-${host.id}")) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = trf("主机选项：%s", host.name),
                        tint = colors.onSurfaceVariant)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("编辑主机")) }, onClick = { menu = false; edit() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) })
                    DropdownMenuItem(text = { Text(tr("查看文件")) }, onClick = { menu = false; browseFiles() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                        modifier = Modifier.testTag("host-browse-files-${host.id}"))
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(tr("删除主机")) }, onClick = { menu = false; delete() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                        colors = MenuDefaults.itemColors(textColor = colors.error, leadingIconColor = colors.error))
                }
            }
        }
        Row(Modifier.padding(top = Spacing.Row), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MetaTag(modeLabel(host.mode == SessionMode.TMUX.name))
            MetaTag(authLabel(host.auth))
            if (host.os == "UNKNOWN") MetaTag(tr("待检测系统"))
        }
    }
}

@Composable internal fun SessionCard(session: TerminalConnection, onOpen: () -> Unit, onClose: () -> Unit, onForget: () -> Unit = {}) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var confirmClose by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(false) }
    ListCard(onClick = onOpen, onClickLabel = tr("打开终端"), modifier = Modifier.testTag("session-${session.id}"),
        bottomPadding = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SessionSystemIcon(session)
            Column(Modifier.weight(1f).padding(start = Spacing.Row)) {
                Text(session.titleForDisplay(title), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${session.host.username}@${session.host.address}:${session.host.port}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { editTitle = true }, modifier = Modifier.testTag("rename-session-${session.id}")) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = tr("修改会话标题"),
                    tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.Tight), verticalAlignment = Alignment.CenterVertically) {
            StatusLabel(state.phase)
            Spacer(Modifier.width(Spacing.Tight))
            MetaTag(modeLabel(session.mode == SessionMode.TMUX))
            Spacer(Modifier.weight(1f))
            if (!state.active && session.mode == SessionMode.TMUX)
                TextButton(onClick = { confirmForget = true }) { Text(tr("移除记录")) }
            else TextButton(onClick = { confirmClose = true }) { Text(tr("关闭")) }
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text(trf("关闭 %s？", session.host.name)) },
        text = { Text(if (session.mode == SessionMode.TMUX) tr("远端 tmux 会话会继续运行，会话卡片保留，可稍后重连。") else tr("普通 SSH shell 会结束。")) },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text(tr("关闭连接")) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(tr("取消")) } })
    if (editTitle) SessionTitleDialog(session) { editTitle = false }
    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false },
        title = { Text(tr("移除记录")) },
        text = { Text(trf("移除后无法从此卡片重连。远端会话不会被终止，可通过 tmux attach -t %s 手动连接。", session.remoteSessionName)) },
        confirmButton = { TextButton(onClick = { confirmForget = false; onForget() },
            colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text(tr("移除记录")) } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(tr("取消")) } })
}

/** The card used by host and session lists: one shape, one surface, whole-card tap target. */
@Composable private fun ListCard(onClick: () -> Unit, onClickLabel: String, modifier: Modifier = Modifier,
    bottomPadding: Dp = Spacing.Page, content: @Composable ColumnScope.() -> Unit) {
    // The tag and click live on the same node so the card reads as one merged, labelled button.
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
        Column(modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(start = Spacing.Page, top = 14.dp, end = 4.dp, bottom = bottomPadding), content = content)
    }
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

@Composable internal fun SessionSystemIcon(session: TerminalConnection, size: Dp = 44.dp) {
    val detectedSystem by session.detectedSystem.collectAsStateWithLifecycle()
    HostSystemIcon(detectedSystem?.name ?: session.host.os, size)
}

/** Brand marks need a light tile in both themes; generic servers use the accent container. */
@Composable internal fun HostSystemIcon(os: String, size: Dp = 44.dp) {
    val system = HostSystem.fromStored(os)
    val branded = system.isLinux || system == HostSystem.WINDOWS || system == HostSystem.MACOS
    val inset = size * when (os) {
        "LINUX" -> 0.06f
        "WINDOWS" -> 0.12f
        "MACOS" -> 0.08f
        else -> if (branded) 0.12f else 0.22f
    }
    val icon = hostSystemIcon(system)
    Surface(color = if (branded) Color(0xFFF0EBE2) else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(size * 0.26f)) {
        Icon(painterResource(icon), contentDescription = tr(system.label),
            tint = if (branded) Color.Unspecified else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(inset).size(size - inset * 2))
    }
}

internal fun hostSystemIcon(system: HostSystem): Int = when (system) {
    HostSystem.UBUNTU -> R.drawable.ic_os_ubuntu
    HostSystem.DEBIAN -> R.drawable.ic_os_debian
    HostSystem.CENTOS -> R.drawable.ic_os_centos
    HostSystem.FEDORA -> R.drawable.ic_os_fedora
    HostSystem.ARCH -> R.drawable.ic_os_archlinux
    HostSystem.REDHAT -> R.drawable.ic_os_redhat
    HostSystem.OPENSUSE -> R.drawable.ic_os_opensuse
    HostSystem.ROCKY -> R.drawable.ic_os_rockylinux
    HostSystem.ALMALINUX -> R.drawable.ic_os_almalinux
    HostSystem.GENTOO -> R.drawable.ic_os_gentoo
    HostSystem.NIXOS -> R.drawable.ic_os_nixos
    HostSystem.LINUXMINT -> R.drawable.ic_os_linuxmint
    HostSystem.LINUX -> R.drawable.ic_os_linux
    HostSystem.WINDOWS -> R.drawable.ic_os_windows
    HostSystem.MACOS -> R.drawable.ic_os_macos
    HostSystem.UNKNOWN, HostSystem.OTHER -> R.drawable.ic_os_server
}
