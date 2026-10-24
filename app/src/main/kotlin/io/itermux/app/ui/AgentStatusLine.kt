package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.itermux.app.session.AgentStatus
import io.itermux.app.trf

/** The colour an agent state is shown in. */
@Composable
internal fun AgentStatus.State.color() = when (this) {
    AgentStatus.State.WORKING -> MaterialTheme.colorScheme.primary
    AgentStatus.State.WAITING -> StatusColors.warning
    AgentStatus.State.IDLE -> StatusColors.success
}

/** The colour of an agent's mark. */
@Composable
internal fun CodingAgent.brand(): Color = when (this) {
    CodingAgent.CLAUDE -> Color(0xFFD97757)
    CodingAgent.CODEX -> Color(0xFF6F86FF)
    // Grok's mark is black on white or white on black: it takes the text colour.
    CodingAgent.GROK -> MaterialTheme.colorScheme.onSurface
}

/**
 * An agent's mark on a tile in its own colours; with [state], a dot in the corner shows what it is doing, so a list of
 * sessions tells at a glance which agent is busy and which waits for an answer.
 */
@Composable
internal fun AgentIcon(agent: CodingAgent, size: Dp, state: AgentStatus.State? = null) {
    val brand = agent.brand()
    val icon = when (agent) {
        CodingAgent.CLAUDE -> R.drawable.ic_agent_claude
        CodingAgent.CODEX -> R.drawable.ic_agent_codex
        CodingAgent.GROK -> R.drawable.ic_agent_grok
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

internal fun duration(seconds: Long): String = when {
    seconds < 60 -> trf("%d 秒", seconds)
    seconds < 3600 -> trf("%d 分钟", seconds / 60)
    else -> trf("%d 小时 %d 分钟", seconds / 3600, seconds % 3600 / 60)
}
