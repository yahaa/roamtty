package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

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
 * to one. Sessions without an agent's report take compact rows. On a phone a group's sessions share one panel, split by hairlines; where the page has room for more columns,
 * each is a card of its own.
 */
@Composable
internal fun HomeSessionsList(sessions: List<TerminalConnection>, onOpen: (TerminalConnection) -> Unit,
    onClose: (TerminalConnection) -> Unit, hostOffline: (TerminalConnection) -> Boolean = { false },
    onForget: (TerminalConnection) -> Unit = {}, desktop: @Composable (TerminalConnection) -> Unit = {}) {
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
                    val last = index == members.lastIndex
                    val shape = when {
                        !joined || (index == 0 && last) -> RoundedCornerShape(radius)
                        index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
                        last -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
                        else -> RectangleShape
                    }
                    SessionRow(session, onClick = { onOpen(session) }, onClose = { onClose(session) },
                        onForget = { onForget(session) }, hostOffline = hostOffline(session), shape = shape, divider = joined && index > 0,
                        modifier = if (joined) Modifier else Modifier.padding(bottom = Spacing.Row),
                        compact = group == SessionGroup.OTHER, desktop = { desktop(session) })
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
