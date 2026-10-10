package io.itermux.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr

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

/** Every session the app holds, most active first; a tap opens its terminal. */
@Composable
internal fun HomeSessionsList(sessions: List<TerminalConnection>, onOpen: (TerminalConnection) -> Unit,
    onClose: (TerminalConnection) -> Unit, onForget: (TerminalConnection, Boolean) -> Unit) {
    GroupedSection(modifier = Modifier.testTag("home-sessions")) {
        sessions.forEachIndexed { index, session ->
            if (index > 0) GroupedDivider()
            key(session.id) {
                HostSessionRow(session, onClick = { onOpen(session) }, onClose = { onClose(session) },
                    onForget = { onForget(session, it) }, showHost = true, tagPrefix = "home-session")
            }
        }
    }
}

/**
 * Picks the host for a new session: a plain terminal or an AI agent, each continuing on the launch sheet. Saved hosts
 * are shortcuts; the last row fills in a new host on the spot, the only row while there is none.
 */
@Composable
internal fun NewSessionSheet(hosts: List<Host>, onDismiss: () -> Unit, onNewHost: (forAgent: Boolean) -> Unit,
    onPick: (host: Host, forAgent: Boolean) -> Unit) {
    AppSheet(onDismiss = onDismiss, fillHeight = false) {
        LazyColumn(Modifier.weight(1f, fill = false).testTag("new-session-sheet")) {
            item { SheetHeader(tr("新建会话"), tr(if (hosts.isEmpty()) "填写主机信息，打开终端或 AI 助手。"
                else "选择已有主机，或填写新主机。"), onDismiss) }
            items(hosts, key = { it.id }) { host ->
                NewSessionRow(host.name, "${host.username}@${host.address}:${host.port}", "new-session-host-${host.id}",
                    "new-session-terminal-${host.id}", "new-session-agent-${host.id}", leading = { HostSystemIcon(host.os, 40.dp) }) {
                    onPick(host, it)
                }
            }
            item(key = "new-host") {
                NewSessionRow(tr("新主机"), tr("填写地址和登录方式"), "new-session-new-host", "new-session-new-host-terminal",
                    "new-session-new-host-agent", leading = {
                        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, onPick = onNewHost)
            }
        }
    }
}

@Composable
private fun NewSessionRow(title: String, subtitle: String, tag: String, terminalTag: String, agentTag: String,
    leading: @Composable () -> Unit, onPick: (forAgent: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = Spacing.Row)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { onPick(false) }, contentPadding = PaddingValues(horizontal = Spacing.Row),
            modifier = Modifier.testTag(terminalTag)) { Text(tr("终端"), maxLines = 1, softWrap = false) }
        FilledTonalButton(onClick = { onPick(true) }, contentPadding = PaddingValues(horizontal = Spacing.Row),
            modifier = Modifier.testTag(agentTag)) { Text(tr("AI 助手"), maxLines = 1, softWrap = false) }
    }
}
