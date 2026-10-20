package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection

/** The home page's sessions tab: its header, then the sessions matching [search], or why there are none. */
@Composable
internal fun HomeSessionsTab(sessions: List<TerminalConnection>, hasHosts: Boolean, search: String, onNewSession: () -> Unit,
    onOpen: (TerminalConnection) -> Unit, onClose: (TerminalConnection) -> Unit, onForget: (TerminalConnection) -> Unit,
    hostOffline: (TerminalConnection) -> Boolean) {
    Column(Modifier.fillMaxSize()) {
        HomeListHeader(trf("会话 · %d", sessions.size), tr("新建会话"), "add-session", onNewSession)
        val matching = sessions.filter {
            "${it.displayTitle} ${it.host.name} ${it.host.address} ${it.host.username}".contains(search, true)
        }
        when {
            // No host is needed first: the new-session sheet fills one in on the way.
            !hasHosts && sessions.isEmpty() -> EmptyState(tr("从第一个会话开始"),
                tr("填写远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。"),
                Modifier.fillMaxSize().testTag("home-sessions-empty"),
                artwork = { ItermuxLogo(Modifier.size(56.dp).testTag("empty-logo"), contentDescription = "Roamtty") },
                action = { Button(onClick = onNewSession) { Text(tr("新建会话")) } })
            sessions.isEmpty() -> EmptyState(tr("暂无会话"), tr("选择主机，打开终端或 AI Agent。"),
                Modifier.fillMaxSize().testTag("home-sessions-empty"),
                action = { Button(onClick = onNewSession) { Text(tr("新建会话")) } })
            matching.isEmpty() -> EmptyState(tr("没有匹配的会话"), tr("试试其他标题或主机信息"), Modifier.fillMaxSize())
            else -> HomeSessionsList(matching, onOpen = onOpen, onClose = onClose, onForget = onForget, hostOffline = hostOffline)
        }
    }
}

/** The home page's hosts tab: its header, then the hosts matching [search] as cards, or why there are none. */
@Composable
internal fun HomeHostsTab(hosts: List<Host>, search: String, gridState: LazyGridState, reachability: (Host) -> HostReachability,
    onAdd: () -> Unit, onOpen: (Host) -> Unit, onEdit: (Host) -> Unit, onDelete: (Host) -> Unit, onBrowseFiles: (Host) -> Unit,
    onManagePlugins: (Host) -> Unit, onLaunch: (host: Host, forAgent: Boolean) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HomeListHeader(trf("主机 · %d", hosts.size), tr("添加主机"), "add-host", onAdd)
        val filtered = hosts.filter { "${it.name} ${it.address} ${it.username}".contains(search, true) }
        if (hosts.isEmpty()) EmptyState(tr("从第一台主机开始"), tr("保存远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。"),
            Modifier.fillMaxSize(),
            artwork = { ItermuxLogo(Modifier.size(56.dp).testTag("empty-logo"), contentDescription = "Roamtty") },
            action = { Button(onClick = onAdd) { Text(tr("添加主机")) } })
        else if (filtered.isEmpty()) EmptyState(tr("没有匹配的主机"), tr("试试其他名称或地址"), Modifier.fillMaxSize())
        else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = gridState, modifier = Modifier.fillMaxSize().testTag("host-grid"),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Row),
            contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Page)) {
            items(filtered, key = { it.id }) { host ->
                HostCard(host, { onOpen(host) }, { onEdit(host) }, { onDelete(host) }, { onBrowseFiles(host) }, { onManagePlugins(host) },
                    reachability(host), openTerminal = { onLaunch(host, false) }, launchAgent = { onLaunch(host, true) })
            }
        }
    }
}
