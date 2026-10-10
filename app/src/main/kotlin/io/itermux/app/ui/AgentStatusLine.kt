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
        CodingAgent.CODEX -> Color(0xFF6F86FF) to R.drawable.ic_agent_codex
        // Grok's mark is black on white or white on black: it takes the text colour.
        CodingAgent.GROK -> MaterialTheme.colorScheme.onSurface to R.drawable.ic_agent_grok
    }
    Box(Modifier.size(size).testTag("agent-icon-${agent.command}")) {
        Surface(color = brand.copy(alpha = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) 0.12f else 0.18f),
            shape = RoundedCornerShape(size * 0.28f), modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = agent.label, tint = brand, modifier = Modifier.size(size * 0.55f))
            }
        }
        if (state != null) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.34f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface).padding(size * 0.05f).clip(CircleShape).background(state.color()))
    }
}

/**
 * What an agent in the session is doing: its state and how long that has lasted. The task it was given is the
 * session's title, so it is not repeated here.
 */
@Composable
internal fun AgentStatusLine(status: AgentStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val color = status.state.color()
    val label = when (status.state) {
        AgentStatus.State.WORKING -> tr("运行中")
        AgentStatus.State.WAITING -> tr("等待确认")
        AgentStatus.State.IDLE -> tr("空闲")
    }
    // The status arrives with the time the state has lasted; the clock below only moves it on between polls, starting
    // over with each status (produceState would keep its count across them).
    var ticks by remember(status) { mutableLongStateOf(0L) }
    LaunchedEffect(status) { while (true) { delay(10_000); ticks += 1 } }
    val elapsed = status.elapsedMillis + ticks * 10_000
    Row(modifier.testTag("agent-status"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
        Text(" · ${duration(elapsed / 1000)}", style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

private fun duration(seconds: Long): String = when {
    seconds < 60 -> trf("%d 秒", seconds)
    seconds < 3600 -> trf("%d 分钟", seconds / 60)
    else -> trf("%d 小时 %d 分钟", seconds / 3600, seconds % 3600 / 60)
}
