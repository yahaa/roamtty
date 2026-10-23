package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.data.installedPlugins
import io.itermux.app.data.multiplexer
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.SessionMode
import io.itermux.ssh.HostSystem
import io.itermux.ssh.HostPlugin
import io.itermux.ssh.TerminalTransport

/**
 * A host in the hosts list, built like a session row: its system's tile beside its name and address, then a status
 * line with whether it answers, what carries its sessions (tmux or psmux, mosh) and its desktop. [shape] and [divider] join
 * the rows of a one-column list into one panel.
 */
@Composable internal fun HostCard(host: Host, openDetail: () -> Unit, edit: () -> Unit, delete: () -> Unit, browseFiles: () -> Unit,
    managePlugins: () -> Unit = {}, reachability: HostReachability = HostReachability.Checking,
    openTerminal: () -> Unit = {}, launchAgent: () -> Unit = {}, shape: Shape = MaterialTheme.shapes.medium, divider: Boolean = false,
    /** The host's desktop as an icon tag at the end of the status line, when it can open one. */
    desktop: @Composable () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    ListCard(onClick = openDetail, onClickLabel = tr("查看详情"), modifier = Modifier.testTag("host-card-${host.id}"), shape = shape,
        divider = divider) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HostSystemIcon(host.os)
            Column(Modifier.weight(1f).padding(start = Spacing.Row, end = Spacing.Tight)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${host.username}@${host.address}:${host.port}", Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("host-menu-${host.id}")) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = trf("主机选项：%s", host.name),
                        tint = colors.onSurfaceVariant)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("打开终端")) }, onClick = { menu = false; openTerminal() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sessions), contentDescription = null) },
                        modifier = Modifier.testTag("host-open-terminal-${host.id}"))
                    DropdownMenuItem(text = { Text(tr("AI Agent")) }, onClick = { menu = false; launchAgent() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_agent), contentDescription = null) },
                        modifier = Modifier.testTag("host-open-agent-${host.id}"))
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(tr("编辑主机")) }, onClick = { menu = false; edit() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) })
                    DropdownMenuItem(text = { Text(tr("查看文件")) }, onClick = { menu = false; browseFiles() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                        modifier = Modifier.testTag("host-browse-files-${host.id}"))
                    DropdownMenuItem(text = { Text(tr("安装插件")) }, onClick = { menu = false; managePlugins() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_plugin), contentDescription = null) },
                        modifier = Modifier.testTag("host-plugins-${host.id}"))
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(tr("删除主机")) }, onClick = { menu = false; delete() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                        colors = MenuDefaults.itemColors(textColor = colors.error, leadingIconColor = colors.error))
                }
            }
        }
        // Ends where the session rows' status lines do, under the menu button's icon.
        Row(Modifier.padding(top = Spacing.Tight, end = 10.dp).heightIn(min = 28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HostReachabilityLabel(reachability, Modifier.testTag("host-status-${host.id}").padding(end = 2.dp))
            Spacer(Modifier.weight(1f))
            // At the end, only what keeps and carries its sessions: once probed, the plugins it actually has (before, the
            // multiplexer it is set to use), then its desktop as an icon tag.
            val plugins = host.installedPlugins
            val shown = plugins?.let { installed -> HostPlugin.entries.filter { it in installed && it != HostPlugin.DESKTOP } }
                ?: listOfNotNull(host.multiplexer.takeIf { host.mode == SessionMode.TMUX.name })
            shown.forEach { MetaTag(it.id, Modifier.testTag("host-plugin-${it.id}-${host.id}")) }
            desktop()
        }
    }
}

/** A dot in the colour of [reachability] beside what it says: online with the latency, offline or checking. */
@Composable internal fun HostReachabilityLabel(reachability: HostReachability, modifier: Modifier = Modifier) {
    val (color, label) = when (reachability) {
        is HostReachability.Online -> StatusColors.success to (reachability.latencyMs?.let { trf("在线 · %s", latencyLabel(it)) } ?: tr("在线"))
        HostReachability.Offline -> StatusColors.danger to tr("离线")
        HostReachability.Checking -> StatusColors.idle to tr("检测中")
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (reachability is HostReachability.Online) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun latencyLabel(ms: Long): String = if (ms < 1) "<1 ms" else "$ms ms"

/**
 * Whether closing [session] would log in to end it: a disconnected tmux session the app created, still running
 * remotely. One that was already on the server is only detached.
 */
internal fun TerminalConnection.closeForgets(active: Boolean): Boolean = !active && mode == SessionMode.TMUX && !external

/** The card used by the host and session lists: one shape, one surface, whole-card tap target. */
@Composable internal fun ListCard(onClick: () -> Unit, onClickLabel: String, modifier: Modifier = Modifier,
    bottomPadding: Dp = Spacing.Page, shape: Shape = MaterialTheme.shapes.medium, divider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    // The tag and click live on the same node so the card reads as one merged, labelled button.
    Surface(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.groupedContainer) {
        Column(modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)) {
            if (divider) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)))
            Column(Modifier.padding(start = Spacing.Page, top = 14.dp, end = 4.dp, bottom = bottomPadding), content = content)
        }
    }
}

/**
 * A session's tile, the one it was created from: the agent's mark while one runs or was launched, else the plain
 * terminal's. [state] puts the agent's status dot in its corner.
 */
@Composable internal fun SessionIcon(session: TerminalConnection, size: Dp = 44.dp, state: AgentStatus.State? = null) {
    val agent by session.agentStatus.collectAsStateWithLifecycle()
    val launched by session.launchedAgent.collectAsStateWithLifecycle()
    val kind = CodingAgent.fromCommand(agent?.agent) ?: launched
    if (kind != null) AgentIcon(kind, size, state) else TerminalTile(size)
}

/**
 * Brand marks sit on a soft wash of their own colour, so a list of hosts reads as a set of tinted
 * tiles rather than bright stickers. Logos keep their original colours and are optically sized:
 * square marks run smaller than discs, tall marks slightly larger. Generic servers use the accent.
 */
@Composable internal fun HostSystemIcon(os: String, size: Dp = 44.dp) {
    val system = HostSystem.fromStored(os)
    val colors = MaterialTheme.colorScheme
    val brand = hostSystemBrand(system)
    val dark = colors.background.luminance() <= 0.5f
    val logo = size * when (system) {
        HostSystem.WINDOWS -> 0.5f
        HostSystem.LINUX, HostSystem.DEBIAN, HostSystem.MACOS -> 0.64f
        HostSystem.OPENSUSE -> 0.68f
        HostSystem.UNKNOWN, HostSystem.OTHER -> 0.5f
        else -> 0.6f
    }
    // Debian's dark red, Tux's black body and Red Hat's black disc vanish on a dark wash, so in the
    // dark theme those keep a muted light tile; light-theme tiles are a pale wash over the card.
    val container = when {
        brand == null -> colors.primaryContainer
        !dark -> brand.copy(alpha = 0.12f)
        system in darkInkSystems -> brand.copy(alpha = 0.14f).compositeOver(Color(0xFFE6E1D8))
        else -> brand.copy(alpha = 0.22f)
    }
    Surface(color = container,
        shape = RoundedCornerShape(size * 0.28f), modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(hostSystemIcon(system)), contentDescription = tr(system.label),
                tint = if (brand != null) Color.Unspecified else colors.onPrimaryContainer,
                modifier = Modifier.size(logo))
        }
    }
}

private val darkInkSystems = setOf(HostSystem.DEBIAN, HostSystem.LINUX, HostSystem.REDHAT)

/** The wash behind each logo: the brand's primary colour, or null for the generic server glyph. */
private fun hostSystemBrand(system: HostSystem): Color? = when (system) {
    HostSystem.UBUNTU -> Color(0xFFE95420)
    HostSystem.DEBIAN -> Color(0xFFD70A53)
    HostSystem.CENTOS -> Color(0xFF9C4C99)
    HostSystem.FEDORA -> Color(0xFF51A2DA)
    HostSystem.ARCH -> Color(0xFF1793D1)
    HostSystem.REDHAT -> Color(0xFFEE0000)
    HostSystem.OPENSUSE -> Color(0xFF73BA25)
    HostSystem.ROCKY -> Color(0xFF10B981)
    HostSystem.ALMALINUX -> Color(0xFF0F8BD6)
    HostSystem.GENTOO -> Color(0xFF8E7FC4)
    HostSystem.NIXOS -> Color(0xFF7EBAE4)
    HostSystem.LINUXMINT -> Color(0xFF87CF3E)
    HostSystem.LINUX -> Color(0xFFF2B705)
    HostSystem.WINDOWS -> Color(0xFF0078D4)
    HostSystem.MACOS -> Color(0xFF9A9A9F)
    HostSystem.UNKNOWN, HostSystem.OTHER -> null
}

internal fun hostSystemIcon(system: HostSystem): Int = when (system) {
    HostSystem.UBUNTU -> R.drawable.ic_os_ubuntu
    HostSystem.DEBIAN -> R.drawable.ic_os_debian
    HostSystem.CENTOS -> R.drawable.ic_os_centos
    HostSystem.FEDORA -> R.drawable.ic_os_fedora
    HostSystem.ARCH -> R.drawable.ic_os_archlinux
    HostSystem.REDHAT -> R.drawable.ic_os_redhat
    HostSystem.OPENSUSE -> R.drawable.ic_os_opensuse
    HostSystem.ROCKY -> R.drawable.ic_os_rockylinux
    HostSystem.ALMALINUX -> R.drawable.ic_os_almalinux
    HostSystem.GENTOO -> R.drawable.ic_os_gentoo
    HostSystem.NIXOS -> R.drawable.ic_os_nixos
    HostSystem.LINUXMINT -> R.drawable.ic_os_linuxmint
    HostSystem.LINUX -> R.drawable.ic_os_linux
    HostSystem.WINDOWS -> R.drawable.ic_os_windows
    HostSystem.MACOS -> R.drawable.ic_os_macos
    HostSystem.UNKNOWN, HostSystem.OTHER -> R.drawable.ic_os_server
}
