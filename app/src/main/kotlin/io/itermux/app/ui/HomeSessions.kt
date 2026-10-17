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
import androidx.compose.ui.text.font.FontStyle
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
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import io.itermux.app.session.replyPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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

/** The session list's groups, in its order: what waits for the user first, sessions without an agent last. */
internal enum class SessionGroup { WAITING, WORKING, DONE, OTHER }

/** The group of a session whose agent reports [status]: by its state while connected, else with the plain terminals. */
internal fun sessionGroup(connected: Boolean, status: AgentStatus?): SessionGroup = when {
    !connected || status == null -> SessionGroup.OTHER
    status.state == AgentStatus.State.WAITING -> SessionGroup.WAITING
    status.state == AgentStatus.State.WORKING -> SessionGroup.WORKING
    else -> SessionGroup.DONE
}

/**
 * What the list shows of a session's agent, and whether it is live: what it reports now, or, while the session comes
 * back and before its host has answered, what it reported last ([last]), so opening the app does not move every card
 * to "其它" and back. A session that failed to connect, or whose host is offline, shows none.
 */
internal fun listedAgentStatus(state: ConnectionState, polled: Boolean, live: AgentStatus?, last: AgentStatus?,
    hostOffline: Boolean): Pair<AgentStatus?, Boolean> = when {
    state.phase == Phase.CONNECTED && polled -> live to true
    (state.active || reconnectsOnOpen(state)) && !hostOffline -> last to false
    else -> null to false
}

/** What the list reads of a session to place it, kept current as its agent reports. */
private data class SessionPlace(val state: ConnectionState, val polled: Boolean, val live: AgentStatus?,
    val last: AgentStatus?, val launched: CodingAgent?)

@Composable
private fun SessionGroup.label(plainOnly: Boolean) = when (this) {
    SessionGroup.WAITING -> tr("待确认")
    SessionGroup.WORKING -> tr("运行中")
    SessionGroup.DONE -> tr("已完成")
    SessionGroup.OTHER -> if (plainOnly) tr("终端") else tr("其它")
}

@Composable
private fun SessionGroup.color() = when (this) {
    SessionGroup.WAITING -> StatusColors.warning
    SessionGroup.WORKING -> MaterialTheme.colorScheme.primary
    SessionGroup.DONE -> StatusColors.success
    SessionGroup.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Every session the app holds, grouped by what its agent is doing (waiting for the user, working, done, then the
 * rest), most active first within a group; a tap opens its terminal. With two groups or more, chips above them filter
 * to one. On a phone a group's sessions share one panel, split by hairlines; where the page has room for more columns,
 * each is a card of its own.
 */
@Composable
internal fun HomeSessionsList(sessions: List<TerminalConnection>, onOpen: (TerminalConnection) -> Unit,
    onClose: (TerminalConnection) -> Unit, hostOffline: (TerminalConnection) -> Boolean = { false },
    onForget: (TerminalConnection) -> Unit = {}) {
    // Each session's group, and whether it runs an agent at all; a session coming back stays in the group its agent
    // was last seen in.
    fun place(session: TerminalConnection) =
        SessionPlace(session.state.value, session.agentPolled.value, session.agentStatus.value, session.lastAgentStatus.value,
            session.launchedAgent.value)
    val placeOf by remember(sessions) {
        if (sessions.isEmpty()) flowOf(emptyMap())
        else combine(sessions.map { session ->
            combine(session.state, session.agentPolled, session.agentStatus, session.lastAgentStatus, session.launchedAgent) {
                state, polled, live, last, launched -> session.id to SessionPlace(state, polled, live, last, launched)
            }
        }) { it.toMap() }
    }.collectAsStateWithLifecycle(remember(sessions) { sessions.associate { it.id to place(it) } })
    val places = sessions.associate { session ->
        val place = placeOf[session.id] ?: place(session)
        val status = listedAgentStatus(place.state, place.polled, place.live, place.last, hostOffline(session)).first
        session.id to (sessionGroup(true, status) to (status != null || place.launched != null))
    }
    val groups = SessionGroup.entries.mapNotNull { group ->
        sessions.filter { (places[it.id]?.first ?: SessionGroup.OTHER) == group }.takeIf { it.isNotEmpty() }?.let { group to it }
    }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    // A filter whose group has emptied shows everything again.
    val only = groups.firstOrNull { it.first.name == filter }?.first
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = ((maxWidth - Spacing.Page * 2 + Spacing.Row) / (320.dp + Spacing.Row)).toInt().coerceAtLeast(1)
        val joined = columns == 1
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("home-sessions-list"),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Row),
            contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Page)) {
            // The last group is "终端" while it holds plain terminals only.
            fun plainOnly(members: List<TerminalConnection>) = members.none { places[it.id]?.second == true }
            if (groups.size > 1) item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                SessionFilters(groups.map { (group, members) -> Triple(group, members.size, plainOnly(members)) }, sessions.size, only) {
                    filter = it?.name
                }
            }
            groups.filter { only == null || it.first == only }.forEach { (group, members) ->
                item(key = "group-${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                    SessionGroupHeader(group, members.size, plainOnly(members))
                }
                gridItemsIndexed(members, key = { _, session -> session.id }) { index, session ->
                    val radius = 18.dp
                    val shape = when {
                        !joined || members.size == 1 -> RoundedCornerShape(radius)
                        index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
                        index == members.lastIndex -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
                        else -> RectangleShape
                    }
                    SessionRow(session, onClick = { onOpen(session) }, onClose = { onClose(session) },
                        onForget = { onForget(session) }, hostOffline = hostOffline(session), shape = shape, divider = joined && index > 0,
                        modifier = if (joined) Modifier else Modifier.padding(bottom = Spacing.Row))
                }
            }
        }
    }
}

/**
 * The chips above the groups: all sessions, then each group with how many it holds and whether it holds plain
 * terminals only; [selected] is the one shown alone.
 */
@Composable
private fun SessionFilters(groups: List<Triple<SessionGroup, Int, Boolean>>, total: Int, selected: SessionGroup?,
    onSelect: (SessionGroup?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Spacing.Hairline),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterPill(tr("全部"), total, null, selected == null, "home-session-filter-all") { onSelect(null) }
        groups.forEach { (group, count, plainOnly) ->
            FilterPill(group.label(plainOnly), count, group.color(), selected == group,
                "home-session-filter-${group.name.lowercase()}") { onSelect(if (selected == group) null else group) }
        }
    }
}

@Composable
private fun FilterPill(label: String, count: Int, dot: Color?, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.height(32.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = if (selected) 0.12f else 0.06f))
        .border(1.dp, if (selected) colors.onSurface.copy(alpha = 0.3f) else Color.Transparent, CircleShape)
        .selectable(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant, fontWeight = FontWeight.Normal)
        Spacer(Modifier.width(5.dp))
        Text("$count", style = MaterialTheme.typography.labelLarge, maxLines = 1, color = colors.onSurface,
            fontWeight = FontWeight.SemiBold)
    }
}

/** A group's line: a ring in its colour (filled for the one waiting on the user), its name and how many it holds. */
@Composable
private fun SessionGroupHeader(group: SessionGroup, count: Int, plainOnly: Boolean) {
    val color = group.color()
    Row(Modifier.padding(start = 6.dp, top = 14.dp, bottom = 8.dp).testTag("home-session-group-${group.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).border(2.dp, color, CircleShape)
            .background(if (group == SessionGroup.WAITING) color else Color.Transparent))
        Spacer(Modifier.width(8.dp))
        Text(group.label(plainOnly), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = 0.8f), maxLines = 1)
    }
}

/**
 * One session: its tile beside its name and the last prompt ("你: …", or "等待输入中…" while an idle agent has had
 * none), then, across the whole row, what the agent answered or runs and a status line with where it runs and what
 * the agent needs (allow a request, or a follow-up typed right here). A plain terminal has its host and folder beneath
 * its name. Long press for its menu.
 * An agent's session is named by its folder, the agent's badge before it (the prompt has its own line);
 * others by [sessionListTitle].
 */
@Composable
internal fun SessionRow(session: TerminalConnection, onClick: () -> Unit, onClose: () -> Unit, onForget: () -> Unit = {},
    tagPrefix: String = "home-session", hostOffline: Boolean = false, shape: Shape = RoundedCornerShape(18.dp),
    divider: Boolean = false, modifier: Modifier = Modifier) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val agent by session.agentStatus.collectAsStateWithLifecycle()
    val polled by session.agentPolled.collectAsStateWithLifecycle()
    val last by session.lastAgentStatus.collectAsStateWithLifecycle()
    val launched by session.launchedAgent.collectAsStateWithLifecycle()
    val directory by session.directory.collectAsStateWithLifecycle()
    val multiplexer by session.multiplexer.collectAsStateWithLifecycle()
    val transport by session.transport.collectAsStateWithLifecycle()
    val kind = CodingAgent.fromCommand((agent ?: last)?.agent) ?: launched
    val connected = state.phase == Phase.CONNECTED
    // While it reconnects, what the agent reported last; nothing it offers can be done until it answers again.
    val (status, live) = listedAgentStatus(state, polled, agent, last, hostOffline)
    val colors = MaterialTheme.colorScheme
    val username = session.host.username
    // The agent's folder while it reports, else the pane's, so a session without hooks or agent says it too.
    val path = sessionListPath(status, username).ifEmpty { sessionListPath(directory, username) }
    val name = when {
        status != null -> path.takeIf { it.isNotEmpty() }?.let { if (it == "~" || it == "/") it else it.trimEnd('/').substringAfterLast('/') }
            ?: kind?.label.orEmpty()
        else -> sessionListTitle(title, null, kind, username).let { shown -> kind?.let { shown.removePrefix("${it.shortLabel} · ") } ?: shown }
    }.ifBlank { trf("会话 %d", session.sessionNumber) }
    // Where it runs: the host and the whole folder (the name holds only its last part).
    val location = listOf(session.host.name, path).filter { it.isNotEmpty() && it != name }.distinct().joinToString(" · ")
    val prompt = status?.task.orEmpty()
    val activity = agentActivity(status)
    val reply = status?.reply?.let(::replyPreview).orEmpty()
    val waiting = status?.state == AgentStatus.State.WAITING
    val idle = status?.state == AgentStatus.State.IDLE
    // The line under the name holds the prompt, or says an idle agent awaits one, so every agent row reads alike;
    // where it runs then moves to the status line.
    val promptLine = prompt.isNotEmpty() || idle
    val elapsed = rememberElapsedSeconds(status)

    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var replying by rememberSaveable(session.id) { mutableStateOf(false) }
    var draft by rememberSaveable(session.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status?.state) { failure = null; if (status?.state != AgentStatus.State.IDLE) replying = false }
    fun sendFollowUp() {
        if (sending || draft.isBlank()) return
        sending = true
        scope.launch {
            val sent = session.followUpAgent(draft)
            sending = false
            if (sent) { draft = ""; replying = false } else failure = tr("未发送：Agent 不在等待输入")
        }
    }

    val accent = StatusColors.warning
    Column(modifier.fillMaxWidth().clip(shape).background(colors.groupedContainer)
        .drawBehind { if (waiting) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
        .combinedClickable(onClickLabel = tr("打开终端"), role = Role.Button, onLongClickLabel = tr("会话选项"),
            onLongClick = { menu = true }, onClick = onClick)
        .testTag("$tagPrefix-${session.id}")) {
        if (divider) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(colors.onSurface.copy(alpha = 0.07f)))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            // The tile stands beside the name and the line under it; everything after takes the row's whole width.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionIcon(session, 40.dp, status?.state)
                Column(Modifier.weight(1f).padding(start = Spacing.Row)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (kind != null) {
                            AgentBadge(kind)
                            Spacer(Modifier.width(6.dp))
                        }
                        MarqueeTitle(name, Modifier.weight(1f).testTag("$tagPrefix-title-${session.id}"))
                        if (status != null && live) Text(if (idle) ago(elapsed) else shortDuration(elapsed),
                            Modifier.padding(start = Spacing.Tight).testTag("$tagPrefix-time-${session.id}"),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1, softWrap = false)
                    }
                    if (prompt.isNotEmpty()) Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.onSurfaceVariant.copy(alpha = 0.7f))) { append(tr("你: ")) }
                        append(prompt)
                    }, Modifier.padding(top = 2.dp).testTag("$tagPrefix-prompt-${session.id}"), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (idle) Text(tr("等待输入中…"), Modifier.padding(top = 2.dp).testTag("$tagPrefix-awaiting-${session.id}"),
                        style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
                    else Text(location, Modifier.padding(top = 2.dp).testTag("$tagPrefix-location-${session.id}"),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
            // What the agent runs or asks to run, else what it answered last.
            if (activity.isNotEmpty()) Text(activity, Modifier.padding(top = Spacing.Tight).testTag("$tagPrefix-activity-${session.id}"),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (idle && reply.isNotEmpty()) Text(reply, Modifier.padding(top = Spacing.Tight).testTag("$tagPrefix-reply-${session.id}"),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = Spacing.Tight).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    status == null || !live -> StatusLabel(state.phase, Modifier.testTag("$tagPrefix-status-${session.id}"),
                        hostOffline = hostOffline && !state.active)
                    waiting -> AgentStateLabel(status.state, tr("等待确认"), Modifier.testTag("$tagPrefix-status-${session.id}"))
                    !idle -> WorkingSpinner(kind?.brand() ?: colors.primary, if (activity.isEmpty()) tr("思考中…") else tr("运行中"),
                        Modifier.testTag("$tagPrefix-working-${session.id}"))
                    status.workedSeconds != null -> TurnDone(status.workedSeconds, Modifier.testTag("$tagPrefix-done-${session.id}"))
                    else -> AgentStateLabel(status.state, tr("空闲"), Modifier.testTag("$tagPrefix-status-${session.id}"))
                }
                // Then, held to the right, where it runs (beside its name already for a session without a prompt line),
                // what keeps and carries it, and what the agent needs from the user.
                Row(Modifier.weight(1f).padding(start = Spacing.Tight), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (promptLine) Text(location, Modifier.weight(1f, fill = false).testTag("$tagPrefix-location-${session.id}"),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.testTag("$tagPrefix-tags-${session.id}"), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        sessionTransportTags(session.mode, multiplexer, session.host.multiplexer, transport.takeIf { connected })
                            .forEach { MetaTag(it, Modifier.testTag("$tagPrefix-tag-$it-${session.id}")) }
                    }
                    if (waiting && live) {
                        RowAction(tr("查看"), "$tagPrefix-view-${session.id}", plain = true, onClick = onClick)
                        RowAction(tr("允许"), "$tagPrefix-approve-${session.id}", fill = StatusColors.warning) {
                            scope.launch { if (!session.approveAgent()) failure = tr("未发送：Agent 已不在等待确认") }
                        }
                    } else if (idle && live) RowAction(tr("追问"), "$tagPrefix-follow-up-${session.id}", icon = R.drawable.ic_reply) {
                        replying = !replying
                    }
                }
            }
            if (replying) FollowUpField(draft, { draft = it }, kind, sending, tagPrefix, session.id, ::sendFollowUp)
            failure?.let {
                Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = colors.error, maxLines = 2)
            }
        }
        // Long press: close it, ending everything it runs, without asking; a tmux session that was already on the
        // server is only detached.
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(tr(if (session.external) "断开" else "关闭"), color = colors.error) },
                onClick = { menu = false; onClose() },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = colors.error) },
                modifier = Modifier.testTag("$tagPrefix-close-${session.id}"))
            // Closing a disconnected tmux session logs in to end it, which an offline host never allows.
            if (session.closeForgets(state.active)) DropdownMenuItem(text = { Text(tr("强制删除"), color = colors.error) },
                onClick = { menu = false; forgetting = true },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = colors.error) },
                modifier = Modifier.testTag("$tagPrefix-forget-${session.id}"))
        }
        if (forgetting) AlertDialog(onDismissRequest = { forgetting = false },
            title = { Text(tr("强制删除会话？")) },
            text = { Text(trf("只移除本机的会话卡片，不登录主机；远端 %s 会话若仍在运行，会继续保留。", session.host.multiplexer.id)) },
            confirmButton = {
                TextButton(onClick = { forgetting = false; onForget() }, Modifier.testTag("$tagPrefix-forget-confirm-${session.id}")) {
                    Text(tr("删除"), color = colors.error)
                }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(tr("取消")) } })
    }
}

/** The agent's name on a small wash of its colour, before the session's name. */
@Composable
private fun AgentBadge(agent: CodingAgent) {
    val brand = agent.brand()
    Text(agent.shortLabel, Modifier.clip(RoundedCornerShape(5.dp)).background(brand.copy(alpha = 0.14f))
        .padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = brand,
        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
}

/** A dot and [label] in the colour of [state]. */
@Composable
private fun AgentStateLabel(state: AgentStatus.State, label: String, modifier: Modifier = Modifier) {
    val color = state.color()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}

/** A small pill button on a row's status line: filled with [fill], text only when [plain], else on a neutral wash. */
@Composable
private fun RowAction(label: String, tag: String, icon: Int? = null, fill: Color? = null, plain: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = fill ?: if (plain) Color.Transparent else colors.onSurface.copy(alpha = 0.07f)
    val content = if (fill != null) Color(0xFF1D1A12) else if (plain) colors.onSurfaceVariant else colors.onSurface
    Row(Modifier.height(28.dp).clip(CircleShape).background(background).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = if (plain) 6.dp else 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(13.dp), tint = content)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}

/** The follow-up typed on the row: a line and a send button; the keyboard's send works too. */
@Composable
private fun FollowUpField(text: String, onText: (String) -> Unit, agent: CodingAgent?, sending: Boolean, tagPrefix: String,
    id: String, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(text, onText, Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp))
            .background(colors.background).border(1.dp, colors.onSurface.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .focusRequester(focus).testTag("$tagPrefix-follow-up-input-$id"),
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(trf("继续问 %s…", agent?.shortLabel ?: "Agent"), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
                    field()
                }
            })
        Spacer(Modifier.width(Spacing.Tight))
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (text.isBlank() || sending) colors.onSurface.copy(alpha = 0.3f) else colors.onSurface)
            .clickable(enabled = text.isNotBlank() && !sending, role = Role.Button, onClickLabel = tr("发送"), onClick = onSend)
            .testTag("$tagPrefix-follow-up-send-$id"), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = tr("发送"), Modifier.size(18.dp), tint = colors.surface)
        }
    }
}

/**
 * Seconds the agent has been in its state, moved on between polls by a clock of its own that starts over with each
 * status; 0 without one.
 */
@Composable
private fun rememberElapsedSeconds(status: AgentStatus?): Long {
    var ticks by remember(status) { mutableLongStateOf(0L) }
    LaunchedEffect(status) { if (status != null) while (true) { delay(10_000); ticks += 1 } }
    return ((status?.elapsedMillis ?: 0L) + ticks * 10_000) / 1000
}

/** A running state's length, short as Claude Code's own: "12s", "4m20s", "1h05m". */
internal fun shortDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
    else -> "${seconds / 3600}h${(seconds % 3600 / 60).toString().padStart(2, '0')}m"
}

/** How long ago an agent finished, without spaces so it never breaks across lines: "刚刚", "3分钟前". */
internal fun ago(seconds: Long): String = when {
    seconds < 60 -> tr("刚刚")
    seconds < 3600 -> trf("%d分钟前", seconds / 60)
    seconds < 86_400 -> trf("%d小时前", seconds / 3600)
    else -> trf("%d天前", seconds / 86_400)
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
 * Claude Code's spinner, for a busy agent: a star that grows and shrinks through its frames beside [label] ("思考中…"
 * between commands) with a highlight sweeping across, both in [color].
 */
@Composable
private fun WorkingSpinner(color: Color, label: String, modifier: Modifier = Modifier) {
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
        Text(label, maxLines = 1, style = style.copy(brush = object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val x = sweep * size.width
                return LinearGradientShader(Offset(x - size.width / 3, 0f), Offset(x + size.width / 3, 0f),
                    listOf(dim, color, dim))
            }
        }))
    }
}

/** Claude Code's line after a turn: the resting star and how long the turn worked ("✻ 2m54s"), in the done colour. */
@Composable
private fun TurnDone(seconds: Long, modifier: Modifier = Modifier) {
    val color = StatusColors.success
    Row(modifier.clearAndSetSemantics { contentDescription = trf("已完成 · 用时 %s", duration(seconds)) },
        verticalAlignment = Alignment.CenterVertically) {
        Text("✻", style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Text(shortDuration(seconds), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}

/**
 * The frames of [WorkingSpinner]'s star, out and back as Claude Code draws it; it rests on the full star, which is all
 * that shows with animations turned off.
 */
private val SPINNER_FRAMES = listOf("·", "✢", "✳", "✶", "✻", "✽").let { it + it.reversed().drop(1).dropLast(1) }
    .let { it.drop(4) + it.take(4) }

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
                    NewSessionRow(option.shortLabel, option.command, "new-session-pick-${option.command}",
                        leading = { AgentIcon(option, 40.dp) }) { picked = option.name }
                }
                item(key = "terminal") {
                    NewSessionRow(tr("终端"), tr("登录 shell"), "new-session-pick-terminal", leading = { TerminalTile(40.dp) }) {
                        picked = TERMINAL
                    }
                }
            } else {
                item {
                    SheetHeader(tr("选择主机"), trf("在哪台主机上运行 %s？", agent?.shortLabel ?: tr("终端")), onDismiss,
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
