package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr

/** A home page section title: an icon beside the name, with the section's add button on the right. */
@Composable
internal fun HomeSectionTitle(title: String, icon: Int, style: TextStyle, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Icon(painterResource(icon), contentDescription = null,
                modifier = Modifier.size(with(LocalDensity.current) { style.fontSize.toDp() }))
            Text(title, style = style, maxLines = 1, modifier = Modifier.semantics { heading() })
        }
        action?.invoke()
    }
}

/** Every session the app holds, most active first, above the hosts; a tap opens its terminal. */
@Composable
internal fun HomeSessionsList(sessions: List<TerminalConnection>, onOpen: (TerminalConnection) -> Unit,
    onClose: (TerminalConnection) -> Unit, onForget: (TerminalConnection, Boolean) -> Unit) {
    GroupedSection(modifier = Modifier.testTag("home-sessions")) {
        if (sessions.isEmpty()) GroupedRow(tr("暂无会话"), Modifier.testTag("home-sessions-empty"),
            subtitle = tr("点右上角 + 新建会话"))
        else sessions.forEachIndexed { index, session ->
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
