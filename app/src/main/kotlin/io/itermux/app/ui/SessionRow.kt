package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.data.multiplexer
import io.itermux.app.session.AgentSessionUsage
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.agentActivity
import io.itermux.app.session.replyPreview
import io.itermux.app.session.sessionListPath
import io.itermux.app.session.sessionListTitle
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.Phase
import kotlinx.coroutines.launch
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import io.itermux.ssh.ConnectionState
import kotlinx.coroutines.CoroutineScope

/**
 * One session: its tile beside its name, the chip of the host it runs on, the folder it is in (the home as `~`)
 * and the last prompt ("你: …", or "等待输入中…" while an idle agent has had none), then, across the
 * whole row, what the agent answered or runs and a status line with how long it has been at it and what the agent
 * needs (allow a request, or a follow-up typed right here). Every line is there, empty if need be, and the reply
 * takes one, so all agents' cards share one height. [compact] rows, for sessions without an agent's report,
 * are built the same way with only the name, the folder and a status line of the connection. Long press for its menu.
 * A card the user named shows that name; otherwise an agent's session is named by its folder, the agent's badge
 * before it, and others by [sessionListTitle].
 */
@Composable
internal fun SessionRow(session: TerminalConnection, onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    onForget: () -> Unit = {}, tagPrefix: String = "home-session", hostOffline: Boolean = false,
    shape: Shape = RoundedCornerShape(18.dp), divider: Boolean = false,
    /** Only the name, the folder and the connection: for sessions without an agent's report. */
    compact: Boolean = false,
    /** The desktop button at the end of the bottom row, when the host can open its desktop. */
    desktop: (@Composable () -> Unit)? = null) {
    val card = sessionCard(session, tagPrefix, hostOffline)
    val ui = rememberSessionCardUi(session.id)
    val status = card.status
    LaunchedEffect(status?.state) { ui.failure = null; if (status?.state != AgentStatus.State.IDLE) ui.replying = false }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val accent = StatusColors.warning
    Column(modifier.fillMaxWidth()
        .onGloballyPositioned { ui.card = Rect(it.positionOnScreen(), it.size.toSize()) }
        .pointerInput(Unit) {
            awaitEachGesture { ui.press = ui.card.topLeft + awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
        }
        .clip(shape).background(colors.groupedContainer)
        .drawBehind { if (card.waiting) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
        .combinedClickable(onClickLabel = tr("打开终端"), role = Role.Button, onLongClickLabel = tr("会话选项"),
            onLongClick = { ui.menu = true }, onClick = onClick)
        .testTag("$tagPrefix-${session.id}")) {
        if (divider) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(colors.onSurface.copy(alpha = 0.07f)))
        val showUsageFooter = card.showUsageFooter
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            if (compact) CompactCardBody(card, desktop) else AgentCardBody(card, ui, onClick, desktop)
            if (showUsageFooter) SessionUsageFooter(card.usage!!, card.fade)
        }
        if (ui.replying || ui.failure != null) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            if (ui.replying) FollowUpField(ui.draft, { ui.draft = it }, card.kind, ui.sending, tagPrefix, session.id) {
                ui.sendFollowUp(scope, session)
            }
            ui.failure?.let {
                Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = colors.error, maxLines = 2)
            }
        }
        SessionCardMenu(card, ui, onClose)
        SessionCardDialogs(card, ui, onForget)
    }
}

/** What a session's card shows, read from the session's flows. */
private class SessionCard(
    val session: TerminalConnection,
    val tagPrefix: String,
    val state: ConnectionState,
    /** The agent it runs, by its report or by how it was started. */
    val kind: CodingAgent?,
    /** What the agent reported, and whether it is [live]: while it reconnects, the last report stands in. */
    val status: AgentStatus?,
    val live: Boolean,
    /** An offline host's card keeps what it last showed, its marks faded, so it reads as a record rather than live. */
    val offline: Boolean,
    /** What the user named it; blank for the [automatic] name. */
    val customName: String,
    val automatic: String,
    /** The folder it is in, the home as `~`; empty for none. */
    val path: String,
    /** What keeps and carries it: tmux, mosh. */
    val tags: List<String>,
    /** Current session usage snapshot for AI agent sessions; null for non-agent sessions. */
    val usage: AgentSessionUsage? = null,
) {
    val name = customName.ifBlank { automatic }
    val prompt = status?.task.orEmpty()
    val activity = agentActivity(status)
    val reply = status?.reply?.let(::replyPreview).orEmpty()
    val waiting = status?.state == AgentStatus.State.WAITING
    val idle = status?.state == AgentStatus.State.IDLE
    val fade = Modifier.alpha(if (offline) OFFLINE_ALPHA else 1f)
    val showUsageFooter = usage != null && (usage.contextPercent != null || usage.sessionTotalTokens > 0L)
    fun tag(part: String) = "$tagPrefix-$part-${session.id}"
}

@Composable
private fun sessionCard(session: TerminalConnection, tagPrefix: String, hostOffline: Boolean): SessionCard {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val customName by session.name.collectAsStateWithLifecycle()
    val agent by session.agentStatus.collectAsStateWithLifecycle()
    val polled by session.agentPolled.collectAsStateWithLifecycle()
    val last by session.lastAgentStatus.collectAsStateWithLifecycle()
    val launched by session.launchedAgent.collectAsStateWithLifecycle()
    val directory by session.directory.collectAsStateWithLifecycle()
    val multiplexer by session.multiplexer.collectAsStateWithLifecycle()
    val transport by session.transport.collectAsStateWithLifecycle()
    val usage by session.agentUsage.collectAsStateWithLifecycle()
    val kind = CodingAgent.fromCommand((agent ?: last)?.agent) ?: launched
    // While it reconnects, what the agent reported last; nothing it offers can be done until it answers again.
    val (status, live) = listedAgentStatus(state, polled, agent, last, hostOffline)
    val username = session.host.username
    // The agent's folder while it reports, else the pane's, so a session without hooks or agent says it too; on a line
    // of its own under the name, the home as `~`.
    val path = sessionListPath(status, username).ifEmpty { sessionListPath(directory, username) }
    val automatic = when {
        status != null -> path.takeIf { it.isNotEmpty() }?.let(::pathLeaf) ?: kind?.label.orEmpty()
        else -> sessionListTitle(title, null, kind, username).let { shown -> kind?.let { shown.removePrefix("${it.shortLabel} · ") } ?: shown }
    }.ifBlank { trf("会话 %d", session.sessionNumber) }
    val tags = sessionTransportTags(session.mode, multiplexer, session.host.multiplexer,
        transport.takeIf { state.phase == Phase.CONNECTED })
    return SessionCard(session, tagPrefix, state, kind, status, live, offline = hostOffline && !state.active, customName,
        automatic, path, tags, usage = usage.takeIf { kind != null })
}

/** What the user is doing with a card: its menu, its dialogs and the follow-up typed on it. */
@Stable
private class SessionCardUi(renaming: MutableState<Boolean>, replying: MutableState<Boolean>, draft: MutableState<String>) {
    var menu by mutableStateOf(false)
    var renaming by renaming
    // Where the card is and where it was last touched, on the screen, for the long-press menu to open there.
    var card by mutableStateOf(Rect.Zero)
    var press by mutableStateOf(Offset.Zero)
    var forgetting by mutableStateOf(false)
    var replying by replying
    var draft by draft
    var sending by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)

    fun sendFollowUp(scope: CoroutineScope, session: TerminalConnection) {
        if (sending || draft.isBlank()) return
        sending = true
        scope.launch {
            val sent = session.followUpAgent(draft)
            sending = false
            if (sent) { draft = ""; replying = false } else failure = tr("未发送：Agent 不在等待输入")
        }
    }
}

@Composable
private fun rememberSessionCardUi(sessionId: String): SessionCardUi {
    val renaming = rememberSaveable(sessionId) { mutableStateOf(false) }
    val replying = rememberSaveable(sessionId) { mutableStateOf(false) }
    val draft = rememberSaveable(sessionId) { mutableStateOf("") }
    return remember(renaming, replying, draft) { SessionCardUi(renaming, replying, draft) }
}

/** The name with the agent's badge and the host's chip, and under it the folder. */
@Composable
private fun CardTitleLines(card: SessionCard) {
    val colors = MaterialTheme.colorScheme
    val session = card.session
    val path = card.path
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (card.kind != null) {
            AgentBadge(card.kind, card.fade)
            Spacer(Modifier.width(6.dp))
        }
        MarqueeTitle(card.name, Modifier.weight(1f).testTag(card.tag("title")))
        HostChip(session.host.name, session.host.id, card.offline, card.tag("host"), Modifier.padding(start = Spacing.Tight))
    }
    // Behind a folder mark; cut in the middle, so both where it starts and the folder's own name stay. Without
    // a folder the line stays, empty, so every card of a kind is as tall as the next.
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (path.isNotEmpty()) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = tr("工作目录"), Modifier.size(12.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.75f))
            Spacer(Modifier.width(5.dp))
        }
        Text(path.ifEmpty { " " }, Modifier.weight(1f).then(if (path.isNotEmpty()) Modifier.testTag(card.tag("location")) else Modifier),
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

/** A session without an agent's report: the name, the folder and a status line of the connection. */
@Composable
private fun CompactCardBody(card: SessionCard, desktop: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SessionIcon(card.session, 40.dp, card.status?.state, modifier = card.fade)
        Column(Modifier.weight(1f).padding(start = Spacing.Row)) { CardTitleLines(card) }
    }
    // How it is connected and what carries it, across the row's whole width; the desktop at the end, where every
    // row has its buttons.
    Row(Modifier.padding(top = Spacing.Tight).heightIn(min = STATUS_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        StatusLabel(card.state.phase, Modifier.weight(1f).testTag(card.tag("status")), hostOffline = card.offline)
        Row(Modifier.padding(start = Spacing.Tight), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.testTag(card.tag("tags")),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                card.tags.forEach { MetaTag(it, Modifier.testTag(card.tag("tag-$it"))) }
            }
            desktop?.invoke()
        }
    }
}

/** An agent's session: the prompt beside the tile, then what it runs or answered, then its status and what it needs. */
@Composable
private fun AgentCardBody(card: SessionCard, ui: SessionCardUi, onClick: () -> Unit, desktop: (@Composable () -> Unit)?) {
    // The tile stands beside the name, its folder and the prompt; everything after takes the row's whole width.
    Row(verticalAlignment = Alignment.CenterVertically) {
        SessionIcon(card.session, 40.dp, card.status?.state, modifier = card.fade)
        Column(Modifier.weight(1f).padding(start = Spacing.Row)) {
            CardTitleLines(card)
            CardPromptLine(card)
        }
    }
    CardActivityLine(card)
    CardStatusRow(card, ui, onClick, desktop)
}

/** The last prompt, or that an idle agent waits for one; empty otherwise, so the line is always there. */
@Composable
private fun CardPromptLine(card: SessionCard) {
    val colors = MaterialTheme.colorScheme
    if (card.prompt.isNotEmpty()) Text(buildAnnotatedString {
        withStyle(SpanStyle(color = colors.onSurfaceVariant.copy(alpha = 0.7f))) { append(tr("你: ")) }
        append(card.prompt)
    }, Modifier.padding(top = 2.dp).testTag(card.tag("prompt")), style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    else Text(if (card.idle) tr("等待输入中…") else " ",
        Modifier.padding(top = 2.dp).then(if (card.idle) Modifier.testTag(card.tag("awaiting")) else Modifier),
        style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
}

/**
 * One line, whatever there is to say: what the agent runs or asks to run, else what it answered last, else
 * that it has not; so every agent's card is as tall as the next.
 */
@Composable
private fun CardActivityLine(card: SessionCard) {
    val colors = MaterialTheme.colorScheme
    val line = Modifier.padding(top = Spacing.Tight)
    if (card.activity.isNotEmpty()) Text(card.activity, line.testTag(card.tag("activity")),
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        color = colors.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    else if (card.idle && card.reply.isNotEmpty()) Text(card.reply, line.testTag(card.tag("reply")),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    else Text(if (card.idle) tr("还没有回复") else " ", line.then(if (card.idle) Modifier.testTag(card.tag("no-reply")) else Modifier),
        style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = colors.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1)
}

/** What the agent is doing and for how long; then, held to the right, what keeps and carries it and what the agent needs. */
@Composable
private fun CardStatusRow(card: SessionCard, ui: SessionCardUi, onClick: () -> Unit, desktop: (@Composable () -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val session = card.session
    val status = card.status
    val live = card.live
    val elapsed = rememberElapsedSeconds(status)
    Row(Modifier.padding(top = Spacing.Tight).heightIn(min = STATUS_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        when {
            status == null || !live -> StatusLabel(card.state.phase, Modifier.testTag(card.tag("status")),
                hostOffline = card.offline, last = status)
            card.waiting -> AgentStateLabel(status.state, tr("等待确认"), Modifier.testTag(card.tag("status")))
            !card.idle -> WorkingSpinner(card.kind?.brand() ?: colors.primary, if (card.activity.isEmpty()) tr("思考中…") else tr("运行中"),
                Modifier.testTag(card.tag("working")))
            status.workedSeconds != null -> TurnDone(status.workedSeconds, Modifier.testTag(card.tag("done")))
            else -> AgentStateLabel(status.state, tr("空闲"), Modifier.testTag(card.tag("status")))
        }
        if (status != null && live) Text("· " + if (card.idle) ago(elapsed) else shortDuration(elapsed),
            Modifier.padding(start = 6.dp).testTag(card.tag("time")),
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1, softWrap = false)
        SessionRowTrailing(card.tags, card.tagPrefix, session.id, Modifier.weight(1f).padding(start = Spacing.Tight)) {
            if (card.waiting && live) {
                RowAction(tr("查看"), card.tag("view"), plain = true, onClick = onClick)
                RowAction(tr("允许"), card.tag("approve"), fill = StatusColors.warning) {
                    scope.launch { if (!session.approveAgent()) ui.failure = tr("未发送：Agent 已不在等待确认") }
                }
            } else if (card.idle && live) {
                RowAction(tr("追问"), card.tag("follow-up"), icon = R.drawable.ic_reply, iconOnly = true) { ui.replying = !ui.replying }
            }
            // Rightmost, after 追问: the host's desktop.
            desktop?.invoke()
        }
    }
}

/**
 * Long press: name it, or close it, ending everything it runs, without asking; a tmux session that was already on
 * the server is only detached. Closing a disconnected tmux session logs in to end it, which an offline host never
 * allows; force delete removes the card first and ends the session if the host answers.
 */
@Composable
private fun SessionCardMenu(card: SessionCard, ui: SessionCardUi, onClose: () -> Unit) {
    if (!ui.menu) return
    val session = card.session
    val closeForgets = session.closeForgets(card.state.active)
    SessionMenu(ui.card, ui.press, listOf(
        listOf(SessionMenuAction(tr("重命名"), R.drawable.ic_edit, card.tag("rename")) { ui.renaming = true }),
        listOfNotNull(
            SessionMenuAction(tr(if (session.external) "断开" else "关闭"), R.drawable.ic_close_circle,
                card.tag("close"), tr(when {
                    session.external -> "服务器上的会话保留"
                    closeForgets -> "登录主机后结束会话"
                    else -> "结束会话里运行的程序"
                }), destructive = true, onClick = onClose),
            if (closeForgets) SessionMenuAction(tr("强制删除"), R.drawable.ic_delete,
                card.tag("forget"), tr("先移除卡片，主机恢复后结束"), destructive = true) { ui.forgetting = true }
            else null)
    ), onDismiss = { ui.menu = false })
}

/** Naming the card, and whether to force delete it. */
@Composable
private fun SessionCardDialogs(card: SessionCard, ui: SessionCardUi, onForget: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val session = card.session
    val path = card.path
    if (ui.renaming) RenameSessionDialog(card.customName, card.automatic,
        if (card.status != null) NameSuggestion(tr("提问"), card.prompt)
        else path.takeIf { it.isNotEmpty() }?.let { NameSuggestion(tr("目录"), pathLeaf(it)) },
        card.tag("rename"), onSave = session::setName, onDismiss = { ui.renaming = false }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.kind != null) {
                AgentBadge(card.kind)
                Spacer(Modifier.width(6.dp))
            }
            HostChip(session.host.name, session.host.id, card.offline, card.tag("rename-host"))
        }
        if (path.isNotEmpty()) Text(path, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis)
    }
    if (ui.forgetting) AlertDialog(onDismissRequest = { ui.forgetting = false },
        title = { Text(tr("强制删除会话？")) },
        text = { Text(trf("立即移除会话卡片，并用已保存的登录结束远端 %s 会话；主机无法连接时，远端会话会继续保留。", session.host.multiplexer.id)) },
        confirmButton = {
            TextButton(onClick = { ui.forgetting = false; onForget() }, Modifier.testTag(card.tag("forget-confirm"))) {
                Text(tr("删除"), color = colors.error)
            }
        },
        dismissButton = { TextButton(onClick = { ui.forgetting = false }) { Text(tr("取消")) } })
}
