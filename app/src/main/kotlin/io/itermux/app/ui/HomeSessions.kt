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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.data.multiplexer
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
 * One session card: the agent's mark (or the terminal's) beside what it works on and where it runs, then its status
 * and what the agent is doing, with close in its menu. The title is the agent's task, else the agent and its folder ("Claude · roamtty"); see [sessionListTitle].
 */
@Composable
internal fun SessionRow(session: TerminalConnection, onClick: () -> Unit, onClose: () -> Unit,
    tagPrefix: String = "home-session", hostOffline: Boolean = false) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val agent by session.agentStatus.collectAsStateWithLifecycle()
    val launched by session.launchedAgent.collectAsStateWithLifecycle()
    val directory by session.directory.collectAsStateWithLifecycle()
    val multiplexer by session.multiplexer.collectAsStateWithLifecycle()
    val transport by session.transport.collectAsStateWithLifecycle()
    val kind = CodingAgent.fromCommand(agent?.agent) ?: launched
    val connected = state.phase == Phase.CONNECTED
    val shown = sessionListTitle(title, agent.takeIf { connected }, kind, session.host.username)
    // Where it runs, on the status line's right: the host, and the whole folder (the title names only its last part).
    // The agent's folder while it reports, else the pane's, so a session without hooks or agent says it too.
    val path = sessionListPath(agent.takeIf { connected }, session.host.username)
        .ifEmpty { sessionListPath(directory, session.host.username) }
    val location = listOf(session.host.name, path).filter { it.isNotEmpty() && it != shown }.distinct().joinToString(" · ")
    // What an agent is running; no line while it runs nothing.
    val activity = agentActivity(agent.takeIf { connected })
    val worked = agent?.workedSeconds
    val working = activity.isEmpty() && connected && agent?.state == AgentStatus.State.WORKING
    val done = activity.isEmpty() && connected && worked != null
    ListCard(onClick = onClick, onClickLabel = tr("打开终端"), modifier = Modifier.testTag("$tagPrefix-${session.id}")) {
        Row(verticalAlignment = Alignment.Top) {
            // The tile it was created from (the host is named beneath).
            SessionIcon(session, 44.dp, agent?.state.takeIf { connected })
            // The title on top and where it runs beneath, so the line under the title is never empty. A gap before the
            // menu keeps a long title clear of its dots.
            Column(Modifier.weight(1f).padding(start = Spacing.Row, end = Spacing.Tight)) {
                MarqueeTitle(shown.ifBlank { trf("会话 %d", session.sessionNumber) },
                    Modifier.testTag("$tagPrefix-title-${session.id}"))
                Text(location, Modifier.padding(top = 2.dp).testTag("$tagPrefix-location-${session.id}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            SessionMenu(session, tagPrefix, onClose)
        }
        // While connected, the agent's state stands in for "connected"; otherwise the connection comes first.
        val status = agent
        Row(Modifier.padding(top = Spacing.Row, end = Spacing.Page - 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status != null && connected) AgentStatusLine(status)
            // A session the list waits to reconnect says why it is not trying: its host does not answer.
            else StatusLabel(state.phase, Modifier.testTag("$tagPrefix-status-${session.id}"), hostOffline = hostOffline && !state.active)
            // On the right, the command the agent runs, a spinner while it works on without one, how long its last turn
            // took once it stops; otherwise what keeps and carries the session, tagged as on a host card.
            Box(Modifier.weight(1f).padding(start = Spacing.Row), contentAlignment = Alignment.CenterEnd) {
                if (activity.isEmpty() && !working && !done)
                    Row(Modifier.fillMaxWidth().testTag("$tagPrefix-tags-${session.id}"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        sessionTransportTags(session.mode, multiplexer, session.host.multiplexer, transport.takeIf { connected })
                            .forEach { MetaTag(it, Modifier.testTag("$tagPrefix-tag-$it-${session.id}")) }
                    }
                else if (activity.isNotEmpty())
                    Text(activity, Modifier.testTag("$tagPrefix-activity-${session.id}"),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else if (working)
                    WorkingSpinner(kind?.brand() ?: MaterialTheme.colorScheme.primary,
                        Modifier.testTag("$tagPrefix-working-${session.id}"))
                else if (done)
                    TurnDone(worked, kind?.brand() ?: MaterialTheme.colorScheme.primary,
                        Modifier.testTag("$tagPrefix-done-${session.id}"))
            }
        }
    }
}

/**
 * A card title on one line that, when too long for it, keeps scrolling like a subtitle (rests 1.2 s, then 30 dp/s with a
 * third of the line between copies) and fades out at the right edge; a title that fits stays still and unfaded.
 */
@Composable
internal fun MarqueeTitle(text: String, modifier: Modifier = Modifier) {
    // The marquee lays the text out at its full width; more than the line gets means it scrolls.
    var full by remember { mutableIntStateOf(0) }
    var line by remember { mutableIntStateOf(0) }
    val fade = if (full > line) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val edge = 18.dp.toPx()
        drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - edge,
            endX = size.width), blendMode = BlendMode.DstIn)
    } else Modifier
    Text(text, modifier.onSizeChanged { line = it.width }.then(fade).basicMarquee(iterations = Int.MAX_VALUE),
        style = MaterialTheme.typography.titleMedium, maxLines = 1, onTextLayout = { full = it.size.width })
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
 * A new session in two steps: first what runs (an AI agent or a plain terminal), then the host it runs on; the
 * launch sheet that follows picks the folder. Saved hosts are shortcuts; the last row fills in a new host on the spot,
 * the only row while there is none. Back from the hosts returns to the first step.
 */
@Composable
internal fun NewSessionSheet(hosts: List<Host>, onDismiss: () -> Unit, onNewHost: (agent: CodingAgent?) -> Unit,
    onPick: (host: Host, agent: CodingAgent?) -> Unit) {
    // Null until picked; the terminal is its own choice, so it cannot share null with "not picked yet".
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val agent = picked?.let { name -> CodingAgent.entries.firstOrNull { it.name == name } }
    AppSheet(onDismiss = onDismiss, fillHeight = false, onBack = { close -> if (picked != null) picked = null else close() }) {
        LazyColumn(Modifier.weight(1f, fill = false).testTag("new-session-sheet")) {
            if (picked == null) {
                item { SheetHeader(tr("新建会话"), tr("选择要运行的 AI Agent，或打开终端。"), onDismiss) }
                items(CodingAgent.entries, key = { it.name }) { option ->
                    NewSessionRow(option.label, option.command, "new-session-pick-${option.command}",
                        leading = { AgentIcon(option, 40.dp) }) { picked = option.name }
                }
                item(key = "terminal") {
                    NewSessionRow(tr("终端"), tr("登录 shell"), "new-session-pick-terminal", leading = { TerminalTile(40.dp) }) {
                        picked = TERMINAL
                    }
                }
            } else {
                item {
                    SheetHeader(tr("选择主机"), trf("在哪台主机上运行 %s？", agent?.label ?: tr("终端")), onDismiss,
                        onBack = { picked = null })
                }
                items(hosts, key = { it.id }) { host ->
                    NewSessionRow(host.name, "${host.username}@${host.address}:${host.port}", "new-session-host-${host.id}",
                        leading = { HostSystemIcon(host.os, 40.dp) }) { onPick(host, agent) }
                }
                item(key = "new-host") {
                    NewSessionRow(tr("新主机"), tr("填写地址和登录方式"), "new-session-new-host", leading = {
                        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }) { onNewHost(agent) }
                }
            }
        }
    }
}

private const val TERMINAL = "terminal"

/** A plain terminal's tile, beside the agents' own. */
@Composable
internal fun TerminalTile(size: androidx.compose.ui.unit.Dp) {
    val colors = MaterialTheme.colorScheme
    // A wash of the text colour, like the agents' brand washes, so the tile shows on a card as well as on the sheet.
    val wash = colors.onSurface.copy(alpha = if (colors.background.luminance() > 0.5f) 0.08f else 0.12f)
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.28f)).background(wash)
        .testTag("terminal-icon"), contentAlignment = Alignment.Center) {
        Text("\$_", color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun NewSessionRow(title: String, subtitle: String, tag: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp)
        .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = Spacing.Row)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}
