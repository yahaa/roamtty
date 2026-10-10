package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import io.itermux.app.R
import io.itermux.app.session.CodingAgent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.session.AgentStatus
import io.itermux.app.tr
import io.itermux.app.trf
import kotlinx.coroutines.delay

/** The colour an agent state is shown in. */
@Composable
private fun AgentStatus.State.color() = when (this) {
    AgentStatus.State.WORKING -> MaterialTheme.colorScheme.primary
    AgentStatus.State.WAITING -> StatusColors.warning
    AgentStatus.State.IDLE -> StatusColors.success
}

/**
 * An agent's mark on a tile in its own colours; with [state], a dot in the corner shows what it is doing, so a list of
 * sessions tells at a glance which agent is busy and which waits for an answer.
 */
@Composable
internal fun AgentIcon(agent: CodingAgent, size: Dp, state: AgentStatus.State? = null) {
    val (brand, icon) = when (agent) {
        CodingAgent.CLAUDE -> Color(0xFFD97757) to R.drawable.ic_agent_claude
        CodingAgent.CODEX -> Color(0xFF4F7CFF) to R.drawable.ic_agent_codex
        CodingAgent.GROK -> Color(0xFF8A8A92) to R.drawable.ic_agent_grok
    }
    Box(Modifier.size(size).testTag("agent-icon-${agent.command}")) {
        Surface(color = brand.copy(alpha = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) 0.14f else 0.24f),
            shape = RoundedCornerShape(size * 0.28f), modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = agent.label, tint = brand, modifier = Modifier.size(size * 0.58f))
            }
        }
        if (state != null) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.34f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface).padding(size * 0.05f).clip(CircleShape).background(state.color()))
    }
}

/**
 * What an agent in the session is doing: its state and how long that has lasted, the tool behind it, and below that the
 * task it was given (the last prompt). [meta] sits quietly at the end of the first line.
 */
@Composable
internal fun AgentStatusLine(status: AgentStatus, modifier: Modifier = Modifier, meta: String = "") {
    val colors = MaterialTheme.colorScheme
    val color = status.state.color()
    val label = when (status.state) {
        AgentStatus.State.WORKING -> tr("运行中")
        AgentStatus.State.WAITING -> tr("等待确认")
        AgentStatus.State.IDLE -> tr("空闲")
    }
    val ticks by produceState(0L, status) { while (true) { value += 1; delay(10_000) } }
    // The status arrives with the time the state has lasted; the clock below only moves it on between polls.
    val elapsed = status.elapsedMillis + ticks * 10_000
    val what = when (status.state) {
        AgentStatus.State.IDLE -> ""
        AgentStatus.State.WORKING -> listOf(status.tool, status.detail).filter { it.isNotEmpty() }.joinToString(": ")
        AgentStatus.State.WAITING -> status.detail.ifEmpty { status.tool }
    }
    Column(modifier.testTag("agent-status"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false)
            Text(" · ${duration(elapsed / 1000)}", style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
            if (what.isNotEmpty()) Text(" · $what", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Spacer(Modifier.weight(1f))
            if (meta.isNotEmpty()) SessionMeta(meta, Modifier.padding(start = Spacing.Tight))
        }
        if (status.task.isNotEmpty()) Text(status.task, Modifier.testTag("agent-task"), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun duration(seconds: Long): String = when {
    seconds < 60 -> trf("%d 秒", seconds)
    seconds < 3600 -> trf("%d 分钟", seconds / 60)
    else -> trf("%d 小时 %d 分钟", seconds / 3600, seconds % 3600 / 60)
}

/** A session's quiet details, such as its host, multiplexer and transport, in one line of small text. */
@Composable
internal fun SessionMeta(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.testTag("session-meta"), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
}
