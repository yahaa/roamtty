package io.itermux.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import io.itermux.app.R
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.CodingAgent
import io.itermux.app.tr
import io.itermux.app.trf

/**
 * Picks an AI coding agent and its permission level for [directory], then starts it in a new session.
 * The last choice is remembered, so a repeat launch is one tap.
 */
@Composable
internal fun AgentLaunchSheet(directory: String, onDismiss: () -> Unit, onLaunch: (AgentLaunch) -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    var agent by remember { mutableStateOf(CodingAgent.from(preferences.getString(AGENT_KEY, null))) }
    var yolo by remember { mutableStateOf(preferences.getBoolean(YOLO_KEY, false)) }
    val launch = AgentLaunch(agent, directory, yolo)
    val colors = MaterialTheme.colorScheme
    AppSheet(onDismiss, fillHeight = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("agent-launch-sheet")) {
            SheetHeader(tr("用 AI 助手打开"), null, onDismiss)
            FolderLabel(directory, Modifier.padding(horizontal = Spacing.Section).padding(bottom = Spacing.Section))

            SectionCaption(tr("助手"))
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CodingAgent.entries.forEach { option ->
                    AgentCard(option, selected = option == agent, Modifier.weight(1f)) { agent = option }
                }
            }

            Spacer(Modifier.height(Spacing.Section))
            SectionCaption(tr("权限"))
            GroupedSection(Modifier.padding(horizontal = Spacing.Page)) {
                Column(Modifier.selectableGroup()) {
                    PermissionRow(tr("普通权限"), tr("修改文件或执行命令前先征求你的确认"),
                        R.drawable.ic_check, selected = !yolo, danger = false, tag = "agent-permission-standard") { yolo = false }
                    GroupedDivider(start = 56.dp)
                    PermissionRow(tr("高级权限 · YOLO"), tr("跳过所有确认，自动执行全部操作"),
                        R.drawable.ic_alert, selected = yolo, danger = true, tag = "agent-permission-yolo") { yolo = true }
                }
            }
            AnimatedVisibility(yolo, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(tr("助手将不经确认直接运行命令、修改或删除文件。请只在可信的项目目录中使用。"),
                    Modifier.padding(start = Spacing.Section, end = Spacing.Section, top = Spacing.Tight),
                    style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            Spacer(Modifier.height(Spacing.Section))
            CommandPreview(launch.agentCommand, Modifier.padding(horizontal = Spacing.Page))

            val buttonColor by animateColorAsState(if (yolo) colors.error else colors.primary, label = "agent-launch-button")
            Button(onClick = {
                preferences.edit { putString(AGENT_KEY, agent.name); putBoolean(YOLO_KEY, yolo) }
                onLaunch(launch)
            }, colors = ButtonDefaults.buttonColors(containerColor = buttonColor,
                contentColor = if (yolo) colors.onError else colors.onPrimary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Page)
                    .heightIn(min = 52.dp).testTag("agent-launch")) {
                Icon(painterResource(R.drawable.ic_agent), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.Tight))
                Text(trf("启动 %s", agent.label), style = MaterialTheme.typography.labelLarge)
            }
            Text(tr("将在新会话中打开，主机支持时使用 tmux，断开后任务仍在运行。"),
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Section).padding(bottom = Spacing.Page),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderLabel(directory: String, modifier: Modifier = Modifier) {
    val name = directory.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RemoteFileIcon("", directory = true, size = 36.dp)
        Spacer(Modifier.width(Spacing.Row))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(directory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.testTag("agent-launch-path"))
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(text, style = MaterialTheme.typography.sectionHeader, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.Section, end = Spacing.Section, bottom = Spacing.Tight))
}

@Composable
private fun AgentCard(agent: CodingAgent, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, label = "agent-card-border")
    Surface(modifier.selectable(selected, onClick = onSelect, role = Role.RadioButton)
        .testTag("agent-option-${agent.name.lowercase()}"),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primary.copy(alpha = 0.08f) else colors.groupedContainer,
        border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, border)) {
        Box {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.Row, vertical = 14.dp)) {
                AgentBadge(agent, 36.dp)
                Spacer(Modifier.height(10.dp))
                Text(agent.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = colors.onSurface)
                Text(agent.command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant, maxLines = 1)
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp).clip(RoundedCornerShape(50))
                .background(colors.primary), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(12.dp), tint = colors.onPrimary)
            }
        }
    }
}

/** A small tile in each agent's own colour, so the three are told apart at a glance. */
@Composable
internal fun AgentBadge(agent: CodingAgent, size: androidx.compose.ui.unit.Dp) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (background, glyphColor) = when (agent) {
        CodingAgent.CLAUDE -> Brush.linearGradient(listOf(Color(0xFFE08A68), Color(0xFFC96442))) to Color.White
        CodingAgent.CODEX -> Brush.linearGradient(listOf(Color(0xFF7C83FD), Color(0xFF3D6DF2))) to Color.White
        CodingAgent.GROK -> (if (dark) Brush.linearGradient(listOf(Color(0xFFF5F5F5), Color(0xFFD9D9D9)))
            else Brush.linearGradient(listOf(Color(0xFF2A2A2A), Color(0xFF050505)))) to (if (dark) Color.Black else Color.White)
    }
    val glyph = when (agent) {
        CodingAgent.CLAUDE -> "✻"
        CodingAgent.CODEX -> ">_"
        CodingAgent.GROK -> "G"
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(background), contentAlignment = Alignment.Center) {
        Text(glyph, color = glyphColor, fontWeight = FontWeight.Bold,
            fontFamily = if (agent == CodingAgent.CODEX) FontFamily.Monospace else FontFamily.Default,
            fontSize = (size.value * if (agent == CodingAgent.CLAUDE) 0.56f else 0.42f).sp)
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, icon: Int, selected: Boolean, danger: Boolean, tag: String,
    onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = if (danger) colors.error else colors.primary
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton)
        .heightIn(min = 64.dp).padding(horizontal = Spacing.Page, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp), tint = accent)
        }
        Spacer(Modifier.width(Spacing.Row))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger && selected) colors.error else colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = accent))
    }
}

/** The exact command the new session will run, so nothing happens behind the user's back. */
@Composable
private fun CommandPreview(command: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .background(colors.onSurface.copy(alpha = 0.05f))
        .padding(horizontal = 14.dp, vertical = 12.dp).testTag("agent-launch-command")) {
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        Text("$ ", style = mono, color = colors.primary)
        Text(command, style = mono, color = colors.onSurface)
    }
}

private const val AGENT_KEY = "agentLaunchAgent"
private const val YOLO_KEY = "agentLaunchYolo"
