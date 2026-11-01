package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.data.multiplexer
import io.itermux.app.session.CodingAgent
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.HostPlugin
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp

/**
 * A new session in two steps: first what runs (an AI agent or a plain terminal), then the host it runs on; the
 * launch sheet that follows picks the folder. Saved hosts are shortcuts; the last row fills in a new host on the spot,
 * the only row while there is none. Back from the hosts returns to the first step.
 * The last first-step row instead continues a session already running on a host with tmux ([onContinue]); with one
 * such host it goes straight there, and the new-host row is left out, as a new host has nothing running yet.
 */
@Composable
internal fun NewSessionSheet(hosts: List<Host>, onDismiss: () -> Unit, onNewHost: (agent: CodingAgent?) -> Unit,
    onContinue: (host: Host) -> Unit, onPick: (host: Host, agent: CodingAgent?) -> Unit) {
    // Null until picked; the terminal is its own choice, so it cannot share null with "not picked yet".
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val agent = picked?.let { name -> CodingAgent.entries.firstOrNull { it.name == name } }
    val continuing = picked == CONTINUE
    val tmuxHosts = hosts.filter { it.multiplexer == HostPlugin.TMUX }
    val title = if (picked == null) tr("新建会话") else tr("选择主机")
    val subtitle = when {
        picked == null -> tr(if (tmuxHosts.isEmpty()) "选择要运行的 AI Agent，或打开终端。" else "启动 AI Agent、打开终端，或继续已有会话。")
        continuing -> tr("继续哪台主机上的会话？")
        else -> trf("在哪台主机上运行 %s？", agent?.shortLabel ?: tr("终端"))
    }
    val choices = buildList {
        if (picked == null) {
            CodingAgent.entries.forEach { option ->
                add(Choice(option.name, option.shortLabel, option.command, "new-session-pick-${option.command}", { AgentIcon(option, 40.dp) }) {
                    picked = option.name
                })
            }
            add(Choice("terminal", tr("终端"), tr("登录 shell"), "new-session-pick-terminal", { TerminalTile(40.dp) }) { picked = TERMINAL })
            if (tmuxHosts.isNotEmpty()) add(Choice("continue", tr("继续会话"), tr("回到服务器上还在运行的会话"), "new-session-pick-continue",
                { IconTile(R.drawable.ic_history) }) { tmuxHosts.singleOrNull()?.let(onContinue) ?: run { picked = CONTINUE } })
        } else {
            (if (continuing) tmuxHosts else hosts).forEach { host ->
                add(Choice(host.id, host.name, "${host.username}@${host.address}:${host.port}", "new-session-host-${host.id}",
                    { HostSystemIcon(host.os, 40.dp) }) { if (continuing) onContinue(host) else onPick(host, agent) })
            }
            if (!continuing) add(Choice("new-host", tr("新主机"), tr("填写地址和登录方式"), "new-session-new-host", { IconTile(R.drawable.ic_add) }) {
                onNewHost(agent)
            })
        }
    }
    val landscape = isLandscape()
    AppSheet(onDismiss = onDismiss, fillHeight = false, fullScreen = landscape, wide = landscape,
        onBack = { close -> if (picked != null) picked = null else close() }) {
        if (landscape) {
            // Landscape: the flat bar with the question after the title, and the choices as two columns, so all of them
            // are in view without scrolling.
            LandscapeBar(title, detail = subtitle, leading = {
                if (picked != null) ToolbarIcon(R.drawable.ic_chevron_left, tr("返回"), Modifier.testTag("sheet-back")) { picked = null }
                else ToolbarIcon(R.drawable.ic_close, tr("关闭"), onClick = onDismiss)
            }) {
                if (picked != null) ToolbarIcon(R.drawable.ic_close, tr("关闭"), onClick = onDismiss)
            }
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).fillMaxWidth().testTag("new-session-sheet"),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = Spacing.Row)) {
                gridItems(choices, key = { it.key }) { NewSessionRow(it, compact = true) }
            }
            return@AppSheet
        }
        LazyColumn(Modifier.weight(1f, fill = false).testTag("new-session-sheet")) {
            item(key = "header") { SheetHeader(title, subtitle, onDismiss, onBack = if (picked != null) { { picked = null } } else null) }
            items(choices, key = { it.key }) { NewSessionRow(it) }
        }
    }
}

/** One row of the sheet: what it starts or where, and what a tap does. */
private class Choice(val key: String, val title: String, val subtitle: String, val tag: String,
    val leading: @Composable () -> Unit, val onClick: () -> Unit)

private const val TERMINAL = "terminal"
private const val CONTINUE = "continue"

/** A plain tile around [icon], for rows that start no program. */
@Composable
private fun IconTile(icon: Int) {
    Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A plain terminal's tile, beside the agents' own. */
@Composable
internal fun TerminalTile(size: Dp) {
    val colors = MaterialTheme.colorScheme
    // A wash of the text colour, like the agents' brand washes, so the tile shows on a card as well as on the sheet.
    val wash = colors.onSurface.copy(alpha = if (colors.background.luminance() > 0.5f) 0.08f else 0.12f)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(wash)
        .testTag("terminal-icon"), contentAlignment = Alignment.Center) {
        Text("\$_", color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun NewSessionRow(choice: Choice, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = choice.onClick).heightIn(min = 64.dp)
        .padding(start = if (compact) Spacing.Page else 24.dp, end = if (compact) Spacing.Row else 20.dp, top = 8.dp, bottom = 8.dp)
        .testTag(choice.tag), verticalAlignment = Alignment.CenterVertically) {
        choice.leading()
        Column(Modifier.weight(1f).padding(horizontal = Spacing.Row)) {
            Text(choice.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(choice.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}
