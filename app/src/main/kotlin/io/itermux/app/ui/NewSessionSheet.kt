package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.itermux.app.session.CodingAgent
import io.itermux.app.tr
import io.itermux.app.trf
import kotlinx.coroutines.launch

/**
 * A new session in two steps: first what runs (an AI agent or a plain terminal), then the host it runs on; the
 * launch sheet that follows picks the folder. Saved hosts are shortcuts; the last row fills in a new host on the spot,
 * the only row while there is none. Back from the hosts returns to the first step.
 */
@Composable
internal fun NewSessionSheet(hosts: List<Host>, onDismiss: () -> Unit, onNewHost: (agent: CodingAgent?) -> Unit,
    onPick: (host: Host, agent: CodingAgent?) -> Unit) {
    // Null until picked; the terminal is its own choice, so it cannot share null with "not picked yet".
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val agent = picked?.let { name -> CodingAgent.entries.firstOrNull { it.name == name } }
    AppSheet(onDismiss = onDismiss, fillHeight = false, onBack = { close -> if (picked != null) picked = null else close() }) {
        LazyColumn(Modifier.weight(1f, fill = false).testTag("new-session-sheet")) {
            if (picked == null) {
                item { SheetHeader(tr("新建会话"), tr("选择要运行的 AI Agent，或打开终端。"), onDismiss) }
                items(CodingAgent.entries, key = { it.name }) { option ->
                    NewSessionRow(option.shortLabel, option.command, "new-session-pick-${option.command}",
                        leading = { AgentIcon(option, 40.dp) }) { picked = option.name }
                }
                item(key = "terminal") {
                    NewSessionRow(tr("终端"), tr("登录 shell"), "new-session-pick-terminal", leading = { TerminalTile(40.dp) }) {
                        picked = TERMINAL
                    }
                }
            } else {
                item {
                    SheetHeader(tr("选择主机"), trf("在哪台主机上运行 %s？", agent?.shortLabel ?: tr("终端")), onDismiss,
                        onBack = { picked = null })
                }
                items(hosts, key = { it.id }) { host ->
                    NewSessionRow(host.name, "${host.username}@${host.address}:${host.port}", "new-session-host-${host.id}",
                        leading = { HostSystemIcon(host.os, 40.dp) }) { onPick(host, agent) }
                }
                item(key = "new-host") {
                    NewSessionRow(tr("新主机"), tr("填写地址和登录方式"), "new-session-new-host", leading = {
                        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }) { onNewHost(agent) }
                }
            }
        }
    }
}

private const val TERMINAL = "terminal"

/** A plain terminal's tile, beside the agents' own. */
@Composable
internal fun TerminalTile(size: androidx.compose.ui.unit.Dp) {
    val colors = MaterialTheme.colorScheme
    // A wash of the text colour, like the agents' brand washes, so the tile shows on a card as well as on the sheet.
    val wash = colors.onSurface.copy(alpha = if (colors.background.luminance() > 0.5f) 0.08f else 0.12f)
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.28f)).background(wash)
        .testTag("terminal-icon"), contentAlignment = Alignment.Center) {
        Text("\$_", color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun NewSessionRow(title: String, subtitle: String, tag: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp)
        .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = Spacing.Row)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}
