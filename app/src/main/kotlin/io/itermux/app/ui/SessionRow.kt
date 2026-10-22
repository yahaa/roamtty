package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.data.multiplexer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One session: its tile beside its name, the chip of the host it runs on, the folder it is in (the home as `~`)
 * and the last prompt ("你: …", or "等待输入中…" while an idle agent has had none), then, across the
 * whole row, what the agent answered or runs and a status line with how long it has been at it and what the agent
 * needs (allow a request, or a follow-up typed right here). [compact] rows, for sessions that need nothing from the
 * user, are built the same way with only the name, the folder and that status line. Long press for its menu.
 * An agent's session is named by its folder, the agent's badge before it; others by [sessionListTitle].
 */
@Composable
internal fun SessionRow(session: TerminalConnection, onClick: () -> Unit, onClose: () -> Unit, onForget: () -> Unit = {},
    tagPrefix: String = "home-session", hostOffline: Boolean = false, shape: Shape = RoundedCornerShape(18.dp),
    divider: Boolean = false, modifier: Modifier = Modifier,
    /** Two lines only, for sessions that need nothing from the user: finished agents and plain terminals. */
    compact: Boolean = false,
    /** The desktop button at the end of the bottom row, when the host can open its desktop. */
    desktop: (@Composable () -> Unit)? = null) {
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
    // The agent's folder while it reports, else the pane's, so a session without hooks or agent says it too; on a line
    // of its own under the name, the home as `~`.
    val path = sessionListPath(status, username).ifEmpty { sessionListPath(directory, username) }
    val name = when {
        status != null -> path.takeIf { it.isNotEmpty() }?.let(::pathLeaf) ?: kind?.label.orEmpty()
        else -> sessionListTitle(title, null, kind, username).let { shown -> kind?.let { shown.removePrefix("${it.shortLabel} · ") } ?: shown }
    }.ifBlank { trf("会话 %d", session.sessionNumber) }
    val prompt = status?.task.orEmpty()
    val activity = agentActivity(status)
    val reply = status?.reply?.let(::replyPreview).orEmpty()
    val waiting = status?.state == AgentStatus.State.WAITING
    val idle = status?.state == AgentStatus.State.IDLE
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
        val titleLine: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kind != null) {
                    AgentBadge(kind)
                    Spacer(Modifier.width(6.dp))
                }
                MarqueeTitle(name, Modifier.weight(1f).testTag("$tagPrefix-title-${session.id}"))
                HostChip(session.host.name, session.host.id, hostOffline && !state.active, "$tagPrefix-host-${session.id}",
                    Modifier.padding(start = Spacing.Tight))
            }
            // Behind a folder mark; cut in the middle, so both where it starts and the folder's own name stay.
            if (path.isNotEmpty()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = tr("工作目录"), Modifier.size(12.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.75f))
                Spacer(Modifier.width(5.dp))
                Text(path, Modifier.testTag("$tagPrefix-location-${session.id}"),
                    style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
        val followUp: @Composable () -> Unit = {
            RowAction(tr("追问"), "$tagPrefix-follow-up-${session.id}", icon = R.drawable.ic_reply, iconOnly = true) { replying = !replying }
        }
        if (compact) Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionIcon(session, 40.dp, status?.state)
                Column(Modifier.weight(1f).padding(start = Spacing.Row)) { titleLine() }
            }
            // One line of how it stands, across the row's whole width: the agent's answer and when, or the connection and
            // what carries it; its buttons at the end, where every row has them.
            Row(Modifier.padding(top = Spacing.Tight).heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (status != null && live && idle) {
                        Text(if (status.workedSeconds != null) "✓" else "●", Modifier.testTag(
                            if (status.workedSeconds != null) "$tagPrefix-done-${session.id}" else "$tagPrefix-status-${session.id}"),
                            style = MaterialTheme.typography.labelMedium, color = StatusColors.success)
                        Spacer(Modifier.width(5.dp))
                        Text(reply.ifEmpty { tr("等待输入中…") }, Modifier.weight(1f, fill = false).testTag(
                            if (reply.isNotEmpty()) "$tagPrefix-reply-${session.id}" else "$tagPrefix-awaiting-${session.id}"),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(" · " + ago(elapsed), Modifier.testTag("$tagPrefix-time-${session.id}"),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1, softWrap = false)
                    } else StatusLabel(state.phase, Modifier.testTag("$tagPrefix-status-${session.id}"), hostOffline = hostOffline && !state.active)
                }
                Row(Modifier.padding(start = Spacing.Tight), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (status == null || !live) Row(Modifier.testTag("$tagPrefix-tags-${session.id}"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        sessionTransportTags(session.mode, multiplexer, session.host.multiplexer, transport.takeIf { connected })
                            .forEach { MetaTag(it, Modifier.testTag("$tagPrefix-tag-$it-${session.id}")) }
                    }
                    if (idle && live) followUp()
                    desktop?.invoke()
                }
            }
        } else Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            // The tile stands beside the name, its folder and the prompt; everything after takes the row's whole width.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionIcon(session, 40.dp, status?.state)
                Column(Modifier.weight(1f).padding(start = Spacing.Row)) {
                    titleLine()
                    if (prompt.isNotEmpty()) Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.onSurfaceVariant.copy(alpha = 0.7f))) { append(tr("你: ")) }
                        append(prompt)
                    }, Modifier.padding(top = 2.dp).testTag("$tagPrefix-prompt-${session.id}"), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (idle) Text(tr("等待输入中…"), Modifier.padding(top = 2.dp).testTag("$tagPrefix-awaiting-${session.id}"),
                        style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
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
                if (status != null && live) Text("· " + if (idle) ago(elapsed) else shortDuration(elapsed),
                    Modifier.padding(start = 6.dp).testTag("$tagPrefix-time-${session.id}"),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1, softWrap = false)
                // Then, held to the right, what keeps and carries it and what the agent needs from the user.
                Row(Modifier.weight(1f).padding(start = Spacing.Tight), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
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
                    } else if (idle && live) followUp()
                    // Rightmost, after 追问: the host's desktop.
                    desktop?.invoke()
                }
            }
        }
        if (replying || failure != null) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
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
            // Closing a disconnected tmux session logs in to end it, which an offline host never allows; force delete
            // removes the card first and ends the session if the host answers.
            if (session.closeForgets(state.active)) DropdownMenuItem(text = { Text(tr("强制删除"), color = colors.error) },
                onClick = { menu = false; forgetting = true },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = colors.error) },
                modifier = Modifier.testTag("$tagPrefix-forget-${session.id}"))
        }
        if (forgetting) AlertDialog(onDismissRequest = { forgetting = false },
            title = { Text(tr("强制删除会话？")) },
            text = { Text(trf("立即移除会话卡片，并用已保存的登录结束远端 %s 会话；主机无法连接时，远端会话会继续保留。", session.host.multiplexer.id)) },
            confirmButton = {
                TextButton(onClick = { forgetting = false; onForget() }, Modifier.testTag("$tagPrefix-forget-confirm-${session.id}")) {
                    Text(tr("删除"), color = colors.error)
                }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(tr("取消")) } })
    }
}

/** The last part of [path], or the path itself when it is a root ("/", "C:\\"). */
private fun pathLeaf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

/** The agent's name on a small wash of its colour, before the session's name. */
@Composable
private fun AgentBadge(agent: CodingAgent) {
    val brand = agent.brand()
    Text(agent.shortLabel, Modifier.clip(RoundedCornerShape(5.dp)).background(brand.copy(alpha = 0.14f))
        .padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = brand,
        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
}

/** The host a session runs on: its name on a wash of [hostColor], grey around a hollow dot while the host is offline. */
@Composable
private fun HostChip(name: String, id: String, offline: Boolean, tag: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = if (offline) colors.onSurfaceVariant.copy(alpha = 0.7f) else hostColor(id)
    Row(modifier.widthIn(max = 140.dp).clip(CircleShape)
        .background(if (offline) colors.onSurface.copy(alpha = 0.06f) else tint.copy(alpha = 0.14f))
        .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape)
            .then(if (offline) Modifier.border(1.5.dp, tint, CircleShape) else Modifier.background(tint)))
        Spacer(Modifier.width(5.dp))
        Text(name, Modifier.testTag(tag), style = MaterialTheme.typography.labelMedium, color = tint,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
private fun RowAction(label: String, tag: String, icon: Int? = null, fill: Color? = null, plain: Boolean = false,
    iconOnly: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = fill ?: if (plain) Color.Transparent else colors.onSurface.copy(alpha = 0.07f)
    val content = if (fill != null) Color(0xFF1D1A12) else if (plain) colors.onSurfaceVariant else colors.onSurface
    // Only the icon, in a round button; the label is what TalkBack reads.
    if (icon != null && iconOnly) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(background).clickable(role = Role.Button, onClick = onClick)
            .testTag(tag), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = label, Modifier.size(14.dp), tint = content)
        }
        return
    }
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
