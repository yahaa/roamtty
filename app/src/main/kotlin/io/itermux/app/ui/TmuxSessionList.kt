package io.itermux.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.agent
import io.itermux.app.session.agentActivity
import io.itermux.app.session.idleShell
import io.itermux.app.session.replyPreview
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.TmuxSessionInfo
import kotlinx.coroutines.launch

/** tmux sessions on the server, most recently used first; tapping one picks it. [home] shortens their folders to `~`. */
@Composable
internal fun TmuxSessionList(sessions: List<TmuxSessionInfo>, picked: String?, open: Set<String>, busy: Boolean, home: String?,
    onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val now = System.currentTimeMillis() / 1000
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("launch-tmux-sessions")) {
            items(sessions, key = { it.name }) { session ->
                val selected = session.name == picked
                val status = session.agent
                val agent = CodingAgent.fromCommand(status?.agent) ?: CodingAgent.fromCommand(session.command)
                val headline = tmuxHeadline(session, status, agent, home)
                Row(Modifier.fillMaxWidth().background(if (selected) colors.primary.copy(alpha = 0.1f) else Color.Transparent)
                    .selectable(selected, enabled = session.attachable, role = Role.RadioButton) { onPick(session.name) }
                    .alpha(if (session.attachable) 1f else 0.5f).heightIn(min = 52.dp)
                    .padding(horizontal = Spacing.Page, vertical = 8.dp).testTag("launch-tmux-${session.name}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (agent != null) AgentIcon(agent, 32.dp, status?.state) else TerminalTile(32.dp)
                    Spacer(Modifier.width(Spacing.Row))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(headline, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            if (session.name in open) Text(tr("已打开"), style = MaterialTheme.typography.labelSmall, color = colors.primary,
                                modifier = Modifier.padding(start = 6.dp).border(BorderStroke(0.5.dp, colors.primary), MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                        tmuxDoing(status)?.let { doing ->
                            Text(doing, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = status?.state?.color() ?: colors.onSurfaceVariant)
                        }
                        Text(tmuxDetail(session, headline, home, now), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = if (session.attachable) colors.onSurfaceVariant else colors.error)
                    }
                    if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = tr("已选择"), Modifier.size(18.dp),
                        tint = colors.primary)
                }
                GroupedDivider(start = 60.dp)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

/** [directory] with [home] as `~`. */
private fun tmuxPath(directory: String, home: String?) =
    if (home != null && home != "/" && (directory == home || directory.startsWith("$home/"))) "~" + directory.removePrefix(home) else directory

/**
 * What the session is for: its agent's task, else the agent or the program it runs and the folder ("Claude · roamtty",
 * "vim · roamtty"), else the folder of the shell; the session's own name when tmux said none.
 */
private fun tmuxHeadline(session: TmuxSessionInfo, status: AgentStatus?, agent: CodingAgent?, home: String?): String {
    status?.task?.takeIf { it.isNotBlank() }?.let { return it }
    val path = tmuxPath(session.directory, home)
    val program = agent?.shortLabel ?: session.command.takeUnless { session.idleShell }
    val folder = if (path == "~" || path == "/") path else path.trimEnd('/').substringAfterLast('/')
    return when {
        program != null -> if (path.isEmpty()) program else "$program · $folder"
        else -> path.ifEmpty { session.name }
    }
}

/** The agent's state and what it runs or last answered: "运行中 · Running npm test", "空闲 · 已修复…". */
private fun tmuxDoing(status: AgentStatus?): String? {
    status ?: return null
    val state = when (status.state) {
        AgentStatus.State.WORKING -> tr("运行中")
        AgentStatus.State.WAITING -> tr("等待确认")
        AgentStatus.State.IDLE -> tr("空闲")
    }
    val more = if (status.state == AgentStatus.State.IDLE) replyPreview(status.reply) else agentActivity(status)
    return if (more.isEmpty()) state else "$state · $more"
}

/**
 * "~/work/app · 5分钟前 · 3 个窗口 · 2 个客户端已连接 · roamtty-1a2b": the folder unless [headline] is it; a name the app
 * made up goes last, as it says least, one somebody chose first.
 */
private fun tmuxDetail(session: TmuxSessionInfo, headline: String, home: String?, now: Long): String {
    if (!session.attachable) return tr("名称含有特殊字符，暂不支持打开")
    val parts = mutableListOf<String>()
    val path = tmuxPath(session.directory, home)
    if (path.isNotEmpty() && path != headline) parts += path
    session.activity?.let { parts += ago((now - it).coerceAtLeast(0)) }
    if (session.windows > 1) parts += trf("%d 个窗口", session.windows)
    if (session.attached > 0) parts += trf("%d 个客户端已连接", session.attached)
    if (headline != session.name) {
        if (session.name.startsWith("roamtty-")) parts += session.name else parts.add(0, session.name)
    }
    return parts.joinToString(" · ")
}

/**
 * The selected session on its own, for the launch sheet's side column in landscape: its mark, what it is for, what it
 * is doing and where it runs. [open] badges it 已打开.
 */
@Composable
internal fun TmuxSessionCard(session: TmuxSessionInfo, open: Boolean, home: String?) {
    val colors = MaterialTheme.colorScheme
    val status = session.agent
    val agent = CodingAgent.fromCommand(status?.agent) ?: CodingAgent.fromCommand(session.command)
    val headline = tmuxHeadline(session, status, agent, home)
    Surface(Modifier.fillMaxWidth().testTag("launch-tmux-selected"), shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
        Column(Modifier.padding(Spacing.Row), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (agent != null) AgentIcon(agent, 36.dp, status?.state) else TerminalTile(36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (open) Text(tr("已打开"), style = MaterialTheme.typography.labelSmall, color = colors.primary,
                        modifier = Modifier.padding(top = 3.dp).border(BorderStroke(0.5.dp, colors.primary), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
            tmuxDoing(status)?.let { doing ->
                Text(doing, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = status?.state?.color() ?: colors.onSurfaceVariant)
            }
            Text(tmuxDetail(session, headline, home, System.currentTimeMillis() / 1000), style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
        }
    }
}
