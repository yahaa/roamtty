package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.itermux.app.R
import io.itermux.desktop.DesktopButton

/** The sessions whose title or host matches [search]. */
internal fun sessionsMatching(sessions: List<TerminalConnection>, search: String) = sessions.filter {
    "${it.displayTitle} ${it.host.name} ${it.host.address} ${it.host.username}".contains(search, true)
}

/**
 * The home page's sessions tab: its header, then the sessions matching [search] in their [groups] (see
 * [HomeSessionsList]), or why there are none. [compact] leaves the header and the filter chips to [HomeCompactBar].
 */
@Composable
internal fun HomeSessionsTab(sessions: List<TerminalConnection>, hasHosts: Boolean, search: String, onNewSession: () -> Unit,
    onOpen: (TerminalConnection) -> Unit, onClose: (TerminalConnection) -> Unit, onForget: (TerminalConnection) -> Unit,
    hostOffline: (TerminalConnection) -> Boolean, groups: List<ListedGroup>, filter: MutableState<String?>,
    compact: Boolean = false, gridState: LazyGridState = rememberLazyGridState(),
    desktop: @Composable (TerminalConnection) -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        if (!compact) HomeListHeader(trf("会话 · %d", sessions.size), tr("新建会话"), "add-session", onNewSession)
        val matching = sessionsMatching(sessions, search)
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
            else -> HomeSessionsList(matching, onOpen = onOpen, onClose = onClose, onForget = onForget, hostOffline = hostOffline,
                desktop = desktop, groups = groups, filter = filter, compact = compact, gridState = gridState)
        }
    }
}

/**
 * The home page's hosts tab: its header, then the hosts matching [search] as cards, or why there are none. [compact]
 * leaves the header to [HomeCompactBar].
 */
@Composable
internal fun HomeHostsTab(hosts: List<Host>, search: String, gridState: LazyGridState, reachability: (Host) -> HostReachability,
    onAdd: () -> Unit, onOpen: (Host) -> Unit, onEdit: (Host) -> Unit, onDelete: (Host) -> Unit, onBrowseFiles: (Host) -> Unit,
    onManagePlugins: (Host) -> Unit, onLaunch: (host: Host, forAgent: Boolean) -> Unit,
    /** What each host's desktop is doing, HIDDEN for one without a desktop. */
    desktop: @Composable (Host) -> DesktopButton = { DesktopButton.HIDDEN }, onDesktop: (Host) -> Unit = {},
    onDisconnectDesktop: (Host) -> Unit = {}, compact: Boolean = false) {
    Column(Modifier.fillMaxSize()) {
        if (!compact) HomeListHeader(trf("主机 · %d", hosts.size), tr("添加主机"), "add-host", onAdd)
        val filtered = hosts.filter { "${it.name} ${it.address} ${it.username}".contains(search, true) }
        if (hosts.isEmpty()) EmptyState(tr("从第一台主机开始"), tr("保存远端主机，运行 AI 编程工具。\n可直连，也可使用手机已有 VPN。"),
            Modifier.fillMaxSize(),
            artwork = { ItermuxLogo(Modifier.size(56.dp).testTag("empty-logo"), contentDescription = "Roamtty") },
            action = { Button(onClick = onAdd) { Text(tr("添加主机")) } })
        else if (filtered.isEmpty()) EmptyState(tr("没有匹配的主机"), tr("试试其他名称或地址"), Modifier.fillMaxSize())
        // Each host is a screen card of its own; where the page has room, side by side.
        else LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), state = gridState, modifier = Modifier.fillMaxSize().testTag("host-grid"),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Row),
            contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Page,
                top = if (compact) Spacing.Tight else 0.dp)) {
            items(filtered, key = { host -> host.id }) { host ->
                HostCard(host, { onOpen(host) }, { onEdit(host) }, { onDelete(host) }, { onBrowseFiles(host) }, { onManagePlugins(host) },
                    reachability(host), openTerminal = { onLaunch(host, false) }, launchAgent = { onLaunch(host, true) },
                    desktop = desktop(host), openDesktop = { onDesktop(host) }, disconnectDesktop = { onDisconnectDesktop(host) })
            }
        }
    }
}

/**
 * The home page's tabs in the toolbar, after Grok's header: the names side by side, the selected one in full
 * colour with a short bar beneath it.
 */
@Composable
internal fun HomeTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically) {
        titles.forEachIndexed { index, title ->
            val active = index == selected
            val color by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), label = "home-tab")
            Column(Modifier.clip(MaterialTheme.shapes.small)
                .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = Spacing.Row).testTag(HOME_TAB_TAGS[index]),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(width = 20.dp, height = 3.dp).clip(CircleShape)
                    .background(if (active) color else Color.Transparent))
            }
        }
    }
}

/**
 * The home page's one bar in landscape, where height is short: [leading] (the drawer), the tabs as a segmented
 * control with how many each holds, then [middle] (the session filters) and, at the end, [trailing] (search and add).
 */
@Composable
internal fun HomeCompactBar(titles: List<String>, counts: List<Int>, selected: Int, onSelect: (Int) -> Unit,
    leading: @Composable () -> Unit, middle: @Composable RowScope.() -> Unit, trailing: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(HOME_COMPACT_BAR_HEIGHT).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Row(Modifier.clip(CircleShape).background(colors.onSurface.copy(alpha = 0.07f)).padding(3.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            titles.forEachIndexed { index, title ->
                val active = index == selected
                val color by animateColorAsState(if (active) colors.onSurface else colors.onSurfaceVariant, label = "home-tab")
                Row(Modifier.height(32.dp).clip(CircleShape).background(if (active) colors.onSurface.copy(alpha = 0.14f) else Color.Transparent)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) }).padding(horizontal = 14.dp)
                    .testTag(HOME_TAB_TAGS[index]), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.width(5.dp))
                    Text("${counts[index]}", style = MaterialTheme.typography.titleSmall, color = color.copy(alpha = 0.7f), maxLines = 1,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.weight(1f).padding(horizontal = Spacing.Row), verticalAlignment = Alignment.CenterVertically, content = middle)
        trailing()
    }
}

/** How tall [HomeCompactBar] is. */
internal val HOME_COMPACT_BAR_HEIGHT = 48.dp

/** Test tags of the home tabs, in page order. */
internal val HOME_TAB_TAGS = listOf("home-tab-sessions", "home-tab-hosts")

/** The line above a home list: how many it holds on the left, the + that adds one on the right. */
@Composable
internal fun HomeListHeader(label: String, addDescription: String, addTag: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Spacing.Page, end = Spacing.Page - 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ToolbarIcon(R.drawable.ic_add, addDescription, Modifier.testTag(addTag), onClick = onAdd)
    }
}

