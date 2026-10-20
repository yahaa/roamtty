package io.itermux.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.session.AgentMode
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.agent
import io.itermux.app.tr
import kotlinx.coroutines.launch

/** Agent and permission as two dropdowns on one line; YOLO is red, and its "!" says what it allows. */
@Composable
internal fun AgentChooser(agent: CodingAgent, mode: AgentMode, onAgent: (CodingAgent) -> Unit, onMode: (AgentMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val yolo = mode == AgentMode.YOLO
    Row(Modifier.fillMaxWidth().padding(start = Spacing.Page, end = Spacing.Page, top = Spacing.Row),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Picker(Modifier.weight(1f), "agent-picker", anchor = {
            AgentBadge(agent, 20.dp)
            Spacer(Modifier.width(Spacing.Tight))
            Text(agent.shortLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
        }) { close ->
            CodingAgent.entries.forEach { option ->
                PickerItem(option == agent, "agent-option-${option.name.lowercase()}", onClick = { onAgent(option); close() },
                    leading = { AgentBadge(option, 24.dp) }) {
                    Text(option.shortLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(option.command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariant)
                }
            }
        }
        Picker(Modifier.weight(1f), "agent-mode-picker", borderColor = if (yolo) colors.error else null, anchor = {
            Text(modeLabel(mode, agent), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (yolo) colors.error else colors.onSurface, modifier = Modifier.weight(1f, fill = false))
            if (yolo) YoloWarning()
        }) { close ->
            AgentMode.entries.forEach { option ->
                val danger = option == AgentMode.YOLO
                PickerItem(option == mode, "agent-mode-${option.name.lowercase().replace('_', '-')}",
                    onClick = { onMode(option); close() }) {
                    Text(modeLabel(option, agent), style = MaterialTheme.typography.bodyLarge,
                        color = if (danger) colors.error else colors.onSurface)
                    Text(modeHint(option, agent), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (danger) colors.error else colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** An outlined field showing the current choice; tapping it drops the options below. */
@Composable
private fun Picker(modifier: Modifier, tag: String, borderColor: Color? = null, anchor: @Composable RowScope.() -> Unit,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = true }.testTag(tag),
            shape = MaterialTheme.shapes.small, color = colors.groupedContainer,
            border = BorderStroke(if (borderColor != null) 1.dp else 0.5.dp, borderColor ?: colors.outlineVariant)) {
            Row(Modifier.heightIn(min = 44.dp).padding(start = Spacing.Row, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                anchor()
                Spacer(Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_expand_down), contentDescription = null, Modifier.size(18.dp),
                    tint = colors.onSurfaceVariant)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

@Composable
private fun PickerItem(selected: Boolean, tag: String, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    DropdownMenuItem(text = { Column(content = content) }, onClick = onClick, leadingIcon = leading,
        trailingIcon = if (selected) { { Icon(painterResource(R.drawable.ic_check), contentDescription = tr("已选择"),
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) } } else null,
        modifier = Modifier.testTag(tag))
}

/** The red "!" beside YOLO; tapping it explains, in red, what the mode lets the agent do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YoloWarning() {
    val colors = MaterialTheme.colorScheme
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), state = state, tooltip = {
        PlainTooltip(containerColor = colors.surfaceContainerHighest, contentColor = colors.error) {
            Text(modeHint(AgentMode.YOLO), style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.testTag("agent-mode-warning"))
        }
    }) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = tr("YOLO 模式说明"), tint = colors.error,
            modifier = Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(50))
                .clickable { scope.launch { state.show() } }.padding(7.dp).testTag("agent-mode-info"))
    }
}

internal fun modeLabel(mode: AgentMode, agent: CodingAgent = CodingAgent.CODEX) = when (mode) {
    AgentMode.READ_ONLY -> tr(if (agent == CodingAgent.CLAUDE) "计划" else "只读")
    AgentMode.STANDARD -> tr("普通")
    AgentMode.YOLO -> "YOLO"
}

internal fun modeHint(mode: AgentMode, agent: CodingAgent = CodingAgent.CODEX) = when (mode) {
    AgentMode.READ_ONLY -> tr(when (agent) {
        CodingAgent.CLAUDE -> "先分析并制定计划，批准后开始修改"
        CodingAgent.CODEX -> "以只读沙箱启动，可在 Agent 中调整权限"
        CodingAgent.GROK -> "只读沙箱；状态与临时目录仍可写"
    })
    AgentMode.STANDARD -> tr("改文件或运行命令前先问你")
    AgentMode.YOLO -> tr("跳过所有确认，直接执行")
}
