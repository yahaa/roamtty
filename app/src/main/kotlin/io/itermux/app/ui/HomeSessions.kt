package io.itermux.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.data.Host
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.agentActivity
import io.itermux.app.session.sessionListPath
import io.itermux.app.session.sessionListTitle
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.Phase

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

/** Every session the app holds, most active first, as cards laid out like the host list; a tap opens its terminal. */
@Composable
internal fun HomeSessionsList(sessions: List<TerminalConnection>, onOpen: (TerminalConnection) -> Unit,
    onClose: (TerminalConnection) -> Unit, hostOffline: (TerminalConnection) -> Boolean = { false }) {
    LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), modifier = Modifier.fillMaxSize().testTag("home-sessions-list"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Row),
        contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Page)) {
        gridItems(sessions, key = { it.id }) { session ->
            SessionRow(session, onClick = { onOpen(session) }, onClose = { onClose(session) }, hostOffline = hostOffline(session))
        }
    }
}

/**
 * One session card: the agent's mark (or the host's system), what it works on, its status and where it runs, with close
 * in its menu. The title is the agent's task, else the agent and its folder ("Claude · roamtty"); see [sessionListTitle].
 */
@Composable
internal fun SessionRow(session: TerminalConnection, onClick: () -> Unit, onClose: () -> Unit,
    tagPrefix: String = "home-session", hostOffline: Boolean = false) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val agent by session.agentStatus.collectAsStateWithLifecycle()
    val launched by session.launchedAgent.collectAsStateWithLifecycle()
    val kind = CodingAgent.fromCommand(agent?.agent) ?: launched
    val connected = state.phase == Phase.CONNECTED
    val shown = sessionListTitle(title, agent.takeIf { connected }, kind, session.host.username)
    // Where it runs, on the status line's right: the host, and the agent's whole folder (the title names only its last part).
    val path = sessionListPath(agent.takeIf { connected }, session.host.username)
    val location = listOf(session.host.name, path).filter { it.isNotEmpty() && it != shown }.distinct().joinToString(" · ")
    // What an agent is running; no line while it runs nothing.
    val activity = agentActivity(agent.takeIf { connected })
    ListCard(onClick = onClick, onClickLabel = tr("打开终端"), modifier = Modifier.testTag("$tagPrefix-${session.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A session running an agent is told apart by the agent's mark rather than the host's system.
            if (kind != null) AgentIcon(kind, 44.dp, agent?.state.takeIf { connected })
            else SessionSystemIcon(session, 44.dp)
            // A gap before the menu, so a cut-off title's … never runs into its dots.
            Column(Modifier.weight(1f).padding(start = Spacing.Row, end = Spacing.Tight)) {
                Text(shown.ifBlank { trf("会话 %d", session.sessionNumber) }, Modifier.testTag("$tagPrefix-title-${session.id}"),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The line beneath always keeps its place, so the title stays put as commands come and go: the command
                // the agent runs, a spinner while it works on without one, how long its last turn took once it stops.
                Box(contentAlignment = Alignment.CenterStart) {
                    Text(activity.ifEmpty { " " }, if (activity.isEmpty()) Modifier else Modifier.testTag("$tagPrefix-activity-${session.id}"),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val worked = agent?.workedSeconds
                    if (activity.isEmpty() && connected && agent?.state == AgentStatus.State.WORKING)
                        WorkingSpinner(kind?.brand() ?: MaterialTheme.colorScheme.primary,
                            Modifier.testTag("$tagPrefix-working-${session.id}"))
                    else if (activity.isEmpty() && connected && worked != null)
                        TurnDone(worked, kind?.brand() ?: MaterialTheme.colorScheme.primary,
                            Modifier.testTag("$tagPrefix-done-${session.id}"))
                }
            }
            SessionMenu(session, tagPrefix, onClose)
        }
        // While connected, the agent's state stands in for "connected"; otherwise the connection comes first.
        val status = agent
        Row(Modifier.padding(top = Spacing.Row, end = Spacing.Page - 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status != null && connected) AgentStatusLine(status)
            // A session the list waits to reconnect says why it is not trying: its host does not answer.
            else StatusLabel(state.phase, Modifier.testTag("$tagPrefix-status-${session.id}"), hostOffline = hostOffline && !state.active)
            Text(location, Modifier.weight(1f).padding(start = Spacing.Tight).testTag("$tagPrefix-location-${session.id}"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

/**
 * Claude Code's spinner, for an agent busy between commands: a star that grows and shrinks through its frames beside
 * "思考中…" with a highlight sweeping across, both in [color].
 */
@Composable
private fun WorkingSpinner(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "working")
    val frame by transition.animateValue(0, SPINNER_FRAMES.size, Int.VectorConverter,
        infiniteRepeatable(tween(SPINNER_FRAMES.size * 120, easing = LinearEasing)), label = "frame")
    val sweep by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "sweep")
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        // The frames differ in width: a fixed box keeps the words beside them still.
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            Text(SPINNER_FRAMES[frame.coerceIn(SPINNER_FRAMES.indices)], style = style, color = color, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        val dim = color.copy(alpha = 0.6f)
        Text(tr("思考中…"), maxLines = 1, style = style.copy(brush = object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val x = sweep * size.width
                return LinearGradientShader(Offset(x - size.width / 3, 0f), Offset(x + size.width / 3, 0f),
                    listOf(dim, color, dim))
            }
        }))
    }
}

/** Claude Code's line after a turn: the resting star and how long the turn worked. */
@Composable
private fun TurnDone(seconds: Long, color: Color, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text("✻", style = style, color = color, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        // Not monospaced: its spaces would gape between Chinese words.
        Text(trf("已完成 · 用时 %s", duration(seconds)), modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The frames of [WorkingSpinner]'s star, out and back as Claude Code draws it; it rests on the full star, which is all
 * that shows with animations turned off.
 */
private val SPINNER_FRAMES = listOf("·", "✢", "✳", "✶", "✻", "✽").let { it + it.reversed().drop(1).dropLast(1) }
    .let { it.drop(4) + it.take(4) }

/** The session's ⋮ on its card: close it, ending everything it runs, without asking. */
@Composable
private fun SessionMenu(session: TerminalConnection, tagPrefix: String, onClose: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("$tagPrefix-menu-${session.id}")) {
            // Upright dots: a row of them beside a truncated title read as one long ellipsis.
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = tr("会话选项"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(tr("关闭"), color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onClose() },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null,
                    tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.testTag("$tagPrefix-close-${session.id}"))
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
