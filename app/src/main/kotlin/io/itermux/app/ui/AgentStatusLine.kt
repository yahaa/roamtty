package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.session.AgentStatus
import io.itermux.app.tr
import io.itermux.app.trf
import kotlinx.coroutines.delay

/** What an agent in the session is doing: state, the tool or task behind it, and how long the state has lasted. */
@Composable
internal fun AgentStatusLine(status: AgentStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (color, label) = when (status.state) {
        AgentStatus.State.WORKING -> colors.primary to tr("运行中")
        AgentStatus.State.WAITING -> StatusColors.warning to tr("等待确认")
        AgentStatus.State.IDLE -> StatusColors.success to tr("空闲")
    }
    val ticks by produceState(0L, status) { while (true) { value += 1; delay(10_000) } }
    // The status arrives with the time the state has lasted; the clock below only moves it on between polls.
    val elapsed = status.elapsedMillis + ticks * 10_000
    val what = when (status.state) {
        AgentStatus.State.IDLE -> status.task
        AgentStatus.State.WORKING ->
            listOf(status.tool, status.detail).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { status.task }
        AgentStatus.State.WAITING -> status.detail.ifEmpty { status.tool }
    }
    Row(modifier.testTag("agent-status"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
        if (what.isNotEmpty()) Text(" · $what", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status.state != AgentStatus.State.IDLE) Text(" · ${duration(elapsed / 1000)}", style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

private fun duration(seconds: Long): String = when {
    seconds < 60 -> trf("%d 秒", seconds)
    seconds < 3600 -> trf("%d 分钟", seconds / 60)
    else -> trf("%d 小时 %d 分钟", seconds / 3600, seconds % 3600 / 60)
}
