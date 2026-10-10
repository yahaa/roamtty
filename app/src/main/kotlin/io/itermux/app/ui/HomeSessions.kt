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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** Picks the host for a new session: a plain terminal or an AI agent, each continuing on the launch sheet. */
@Composable
internal fun NewSessionSheet(hosts: List<Host>, onDismiss: () -> Unit, onPick: (host: Host, forAgent: Boolean) -> Unit) {
    AppSheet(onDismiss = onDismiss, fillHeight = false) {
        LazyColumn(Modifier.weight(1f, fill = false).testTag("new-session-sheet")) {
            item { SheetHeader(tr("新建会话"), tr("选择主机，打开终端或 AI 助手。"), onDismiss) }
            items(hosts, key = { it.id }) { host ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
                    .testTag("new-session-host-${host.id}"), verticalAlignment = Alignment.CenterVertically) {
                    HostSystemIcon(host.os, 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.Row)) {
                        Text(host.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${host.username}@${host.address}:${host.port}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onPick(host, false) }, contentPadding = PaddingValues(horizontal = Spacing.Row),
                        modifier = Modifier.testTag("new-session-terminal-${host.id}")) { Text(tr("终端"), maxLines = 1, softWrap = false) }
                    FilledTonalButton(onClick = { onPick(host, true) }, contentPadding = PaddingValues(horizontal = Spacing.Row),
                        modifier = Modifier.testTag("new-session-agent-${host.id}")) { Text(tr("AI 助手"), maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
