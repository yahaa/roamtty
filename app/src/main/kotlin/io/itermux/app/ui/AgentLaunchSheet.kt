package io.itermux.app.ui

import io.itermux.app.appSettings
import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import io.itermux.app.session.AgentMode
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.StartDirectory
import io.itermux.app.tr
import io.itermux.app.trf

/**
 * Picks an AI coding agent and its permission level for [directory], then starts it in a new session.
 * The last choice is remembered, so a repeat launch is one tap.
 */
@Composable
internal fun AgentLaunchSheet(directory: String, onDismiss: () -> Unit, onLaunch: (AgentLaunch) -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.appSettings() }
    var agent by remember { mutableStateOf(CodingAgent.from(preferences.getString(AGENT_KEY, null))) }
    var mode by remember { mutableStateOf(preferences.agentMode()) }
    val yolo = mode == AgentMode.YOLO
    val launch = AgentLaunch(agent, directory, mode)
    val supported = StartDirectory.isSupported(directory)
    val colors = MaterialTheme.colorScheme
    AppSheet(onDismiss, fullScreen = true) {
        SheetHeader(tr("用 AI Agent打开"), null, onDismiss)
        // Choices scroll in the middle; the command and the launch button stay at the bottom.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("agent-launch-sheet")) {
            FolderLabel(directory, Modifier.padding(horizontal = Spacing.Section).padding(bottom = Spacing.Section))

            SectionCaption(tr("Agent"))
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
                    PermissionRow(agent, AgentMode.READ_ONLY, R.drawable.ic_preview, selected = mode == AgentMode.READ_ONLY, danger = false,
                        tag = "agent-permission-read-only") { mode = AgentMode.READ_ONLY }
                    GroupedDivider(start = 56.dp)
                    PermissionRow(agent, AgentMode.STANDARD, R.drawable.ic_check, selected = mode == AgentMode.STANDARD, danger = false,
                        tag = "agent-permission-standard") { mode = AgentMode.STANDARD }
                    GroupedDivider(start = 56.dp)
                    PermissionRow(agent, AgentMode.YOLO, R.drawable.ic_alert, selected = yolo, danger = true, tag = "agent-permission-yolo") { mode = AgentMode.YOLO }
                }
            }
            Spacer(Modifier.height(Spacing.Section))
        }
        Column {
            if (!supported) Text(tr(StartDirectory.UNSUPPORTED_MESSAGE),
                Modifier.padding(horizontal = Spacing.Page).testTag("agent-launch-path-error"),
                color = colors.error, style = MaterialTheme.typography.bodySmall)
            CommandPreview(launch.agentCommand, Modifier.padding(horizontal = Spacing.Page))

            val buttonColor by animateColorAsState(if (yolo) colors.error else colors.primary, label = "agent-launch-button")
            Button(onClick = {
                if (!StartDirectory.isSupported(directory)) return@Button
                preferences.saveAgentChoice(agent, mode)
                onLaunch(launch)
            }, enabled = supported, colors = ButtonDefaults.buttonColors(containerColor = buttonColor,
                contentColor = if (yolo) colors.onError else colors.onPrimary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Page)
                    .heightIn(min = 52.dp).testTag("agent-launch")) {
                Icon(painterResource(R.drawable.ic_agent), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.Tight))
                Text(trf("启动 %s", agent.shortLabel), style = MaterialTheme.typography.labelLarge)
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
                Text(agent.shortLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
private fun PermissionRow(agent: CodingAgent, option: AgentMode, icon: Int, selected: Boolean, danger: Boolean, tag: String,
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
            Text(modeLabel(option, agent), style = MaterialTheme.typography.bodyLarge, color = if (danger) colors.error else colors.onSurface)
            Text(modeHint(option, agent), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (danger) colors.error else colors.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = accent))
    }
}

/**
 * The exact command the new session will run, so nothing happens behind the user's back. It stays one line
 * (scrolling sideways when long): a bottom sheet whose height changed with each agent would jump on every tap.
 */
@Composable
private fun CommandPreview(command: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(command) { scroll.scrollTo(0) }
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .background(colors.onSurface.copy(alpha = 0.05f))
        .horizontalScroll(scroll)
        .padding(horizontal = 14.dp, vertical = 12.dp).testTag("agent-launch-command")) {
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        Text("$ ", style = mono, color = colors.primary)
        Text(command, style = mono, color = colors.onSurface, maxLines = 1, softWrap = false)
    }
}

/** The last mode picked, YOLO the first time; before read-only existed only a YOLO flag was stored, so that still counts. */
internal fun SharedPreferences.agentMode(): AgentMode = getString(MODE_KEY, null)?.let(AgentMode::from)
    ?: if (getBoolean(YOLO_KEY, true)) AgentMode.YOLO else AgentMode.STANDARD

internal fun SharedPreferences.saveAgentChoice(agent: CodingAgent, mode: AgentMode) =
    edit { putString(AGENT_KEY, agent.name); putString(MODE_KEY, mode.name); remove(YOLO_KEY) }

internal const val AGENT_KEY = "agentLaunchAgent"
internal const val MODE_KEY = "agentLaunchMode"
private const val YOLO_KEY = "agentLaunchYolo"
