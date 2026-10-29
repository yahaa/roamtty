package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.itermux.app.desktop.DesktopThumbnails
import io.itermux.desktop.DesktopButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.semantics
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

/**
 * A host in the hosts list, as a screen: its desktop's last picture (before one was taken, its system's colours; for a
 * host without a desktop, a plain "no desktop" state), with a frosted strip along the bottom holding its system, name,
 * login, plugins and menu; whether it answers and what its desktop is doing sit in pills at the top left. The picture
 * opens the desktop; the strip, or the picture of a host that has none or is offline, opens its details. A long press
 * opens the menu.
 */
@Composable internal fun HostCard(host: Host, openDetail: () -> Unit, edit: () -> Unit, delete: () -> Unit, browseFiles: () -> Unit,
    managePlugins: () -> Unit = {}, reachability: HostReachability = HostReachability.Checking,
    openTerminal: () -> Unit = {}, launchAgent: () -> Unit = {},
    /** What the host's desktop is doing; [DesktopButton.HIDDEN] for a host without one. */
    desktop: DesktopButton = DesktopButton.HIDDEN, openDesktop: () -> Unit = {}, disconnectDesktop: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val hasDesktop = desktop != DesktopButton.HIDDEN
    val offline = reachability == HostReachability.Offline
    val live = desktop == DesktopButton.CONNECTED || desktop == DesktopButton.POOR || desktop == DesktopButton.RECOVERING ||
        desktop == DesktopButton.CONNECTING
    val version = DesktopThumbnails.versions.collectAsStateWithLifecycle().value[host.id]
    val picture by produceState<HostPicture?>(null, host.id, version, hasDesktop) {
        value = if (!hasDesktop) null else withContext(Dispatchers.IO) {
            DesktopThumbnails.load(context, host.id)?.let { HostPicture(it.asImageBitmap(), DesktopThumbnails.frosted(it).asImageBitmap()) }
        }
    }
    val shape = RoundedCornerShape(20.dp)
    val strip = 62.dp
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9.6f).clip(shape).background(colors.groupedContainer)
        .then(if (live) Modifier.border(2.dp, if (desktop == DesktopButton.CONNECTED) ScreenColors.success else ScreenColors.warning, shape)
            else Modifier)
        .testTag("host-item-${host.id}")) {
        val opensDesktop = hasDesktop && !offline
        Box(Modifier.matchParentSize()
            .combinedClickable(onClickLabel = if (opensDesktop) tr("打开桌面") else tr("查看详情"), role = Role.Button,
                onLongClickLabel = tr("主机选项"), onLongClick = { menu = true }, onClick = if (opensDesktop) openDesktop else openDetail)
            .testTag("host-screen-${host.id}")) {
            when {
                picture != null -> HostScreenPicture(picture!!, offline, strip)
                hasDesktop -> HostScreenBackdrop(host.os, offline, strip)
                else -> HostScreenNoDesktop(strip)
            }
        }
        // Top left: whether it answers, then what its desktop is doing.
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.clip(CircleShape).background(if (hasDesktop) ScreenColors.pill else colors.onSurface.copy(alpha = 0.07f))
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                HostReachabilityLabel(reachability, Modifier.semantics(mergeDescendants = true) {}.testTag("host-status-${host.id}"),
                    onImage = hasDesktop)
            }
            if (hasDesktop) HostDesktopPill(desktop, Modifier.testTag("host-desktop-${host.id}"))
        }
        val onImage = hasDesktop
        val system = HostSystem.fromStored(host.os)
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(strip)
            .clickable(onClickLabel = tr("查看详情"), role = Role.Button, onClick = openDetail).testTag("host-card-${host.id}")
            .padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(hostSystemIcon(system)), contentDescription = tr(system.label), Modifier.size(20.dp),
                tint = if (hostSystemBrand(system) != null) Color.Unspecified else if (onImage) Color.White else colors.onSurface)
            Column(Modifier.weight(1f).padding(start = 10.dp, end = Spacing.Tight)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when { !onImage -> colors.onSurface; offline -> Color.White.copy(alpha = 0.6f); else -> Color.White })
                Text("${host.username}@${host.address}:${host.port}", Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (onImage) Color.White.copy(alpha = 0.62f) else colors.onSurfaceVariant.copy(alpha = 0.75f))
            }
            // What keeps and carries its sessions: once probed, the plugins it has (before, the multiplexer it is set to use).
            val shown = host.installedPlugins?.let { installed -> HostPlugin.entries.filter { it in installed && it != HostPlugin.DESKTOP } }
                ?: listOfNotNull(host.multiplexer.takeIf { host.mode == SessionMode.TMUX.name })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach {
                    if (onImage) MetaTag(it.id, Modifier.testTag("host-plugin-${it.id}-${host.id}"), color = Color.White.copy(alpha = 0.85f),
                        wash = Color.White.copy(alpha = 0.14f))
                    else MetaTag(it.id, Modifier.testTag("host-plugin-${it.id}-${host.id}"))
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("host-menu-${host.id}")) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = trf("主机选项：%s", host.name),
                        tint = if (onImage) Color.White.copy(alpha = 0.85f) else colors.onSurfaceVariant)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("打开终端")) }, onClick = { menu = false; openTerminal() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sessions), contentDescription = null) },
                        modifier = Modifier.testTag("host-open-terminal-${host.id}"))
                    DropdownMenuItem(text = { Text(tr("Agent")) }, onClick = { menu = false; launchAgent() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_agent), contentDescription = null) },
                        modifier = Modifier.testTag("host-open-agent-${host.id}"))
                    DropdownMenuItem(text = { Text(tr("查看文件")) }, onClick = { menu = false; browseFiles() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                        modifier = Modifier.testTag("host-browse-files-${host.id}"))
                    if (live) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(tr("断开桌面")) }, onClick = { menu = false; disconnectDesktop() },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_desktop_off), contentDescription = null) },
                            modifier = Modifier.testTag("host-disconnect-desktop-${host.id}"))
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(tr("编辑主机")) }, onClick = { menu = false; edit() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) })
                    DropdownMenuItem(text = { Text(tr("安装插件")) }, onClick = { menu = false; managePlugins() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_plugin), contentDescription = null) },
                        modifier = Modifier.testTag("host-plugins-${host.id}"))
                    DropdownMenuItem(text = { Text(tr("删除主机")) }, onClick = { menu = false; delete() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                        colors = MenuDefaults.itemColors(textColor = colors.error, leadingIconColor = colors.error))
                }
            }
        }
    }
}

/** A desktop's last picture and its blurred copy for the strip over it. */
private class HostPicture(val image: ImageBitmap, val frosted: ImageBitmap)

/** The status colours over a picture, whatever the theme: the dark theme's, which read on the dark strip. */
private object ScreenColors {
    val success = Color(0xFF7FD19B)
    val warning = Color(0xFFF2C46D)
    val danger = Color(0xFFF2A39B)
    /** The pills over the picture's corner. */
    val pill = Color(0x99141416)
    /** The frosted strip's tint over the blurred picture. */
    val strip = Color(0x5E161618)
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** The picture filling the card, cropped to it, with the bottom [strip] showing its blurred copy under a dark tint. */
@Composable private fun HostScreenPicture(picture: HostPicture, offline: Boolean, strip: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        val filter = if (offline) grayscale else null
        fun draw(image: ImageBitmap) {
            // Centre-crop the picture to the card.
            val scale = maxOf(size.width / image.width, size.height / image.height)
            val w = (size.width / scale).roundToInt().coerceIn(1, image.width)
            val h = (size.height / scale).roundToInt().coerceIn(1, image.height)
            drawImage(image, IntOffset((image.width - w) / 2, (image.height - h) / 2), IntSize(w, h),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = filter,
                filterQuality = FilterQuality.Medium)
        }
        draw(picture.image)
        if (offline) drawRect(Color.Black.copy(alpha = 0.45f))
        val top = size.height - strip.toPx()
        clipRect(top = top) {
            draw(picture.frosted)
            drawRect(ScreenColors.strip.copy(alpha = if (offline) 0.62f else ScreenColors.strip.alpha))
        }
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, top), Offset(size.width, top), 1.dp.toPx())
    }
}

/** A host whose desktop has no picture yet: its system's colour fading to dark, its logo above the strip. */
@Composable private fun HostScreenBackdrop(os: String, offline: Boolean, strip: Dp) {
    val system = HostSystem.fromStored(os)
    val brand = (hostSystemBrand(system) ?: MaterialTheme.colorScheme.primary).let {
        if (offline) Color(0xFF6A6A6E) else it
    }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(brand.copy(alpha = 0.3f).compositeOver(Color(0xFF1C1C1E)),
        Color(0xFF151517)), radius = 900f))) {
        Box(Modifier.fillMaxWidth().fillMaxHeight().padding(bottom = strip), contentAlignment = Alignment.Center) {
            Icon(painterResource(hostSystemIcon(system)), contentDescription = null, Modifier.size(44.dp)
                .then(if (offline) Modifier.graphicsLayer(alpha = 0.5f) else Modifier),
                tint = if (hostSystemBrand(system) != null && !offline) Color.Unspecified else Color.White.copy(alpha = 0.7f))
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(strip).background(Color.Black.copy(alpha = 0.28f)))
    }
}

/** A host without a desktop: says so plainly, the strip a shade apart. */
@Composable private fun HostScreenNoDesktop(strip: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(colors.onSurface.copy(alpha = 0.05f), Color.Transparent),
        radius = 900f))) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(bottom = strip), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_desktop_off), contentDescription = null, Modifier.size(40.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.55f))
            Text(tr("没有图形桌面"), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
            Text(tr("这台主机只能用终端"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(strip).background(colors.onSurface.copy(alpha = 0.035f)))
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = strip).fillMaxWidth().height(1.dp)
            .background(colors.onSurface.copy(alpha = 0.07f)))
    }
}

/** The desktop's state in a dark pill over the picture's corner, after its icon: connected, on its way, weak, or waiting on the user. */
@Composable private fun HostDesktopPill(state: DesktopButton, modifier: Modifier) {
    val (tint, label) = when (state) {
        DesktopButton.CONNECTED -> ScreenColors.success to tr("已连接")
        DesktopButton.CONNECTING -> Color.White.copy(alpha = 0.7f) to tr("连接中")
        DesktopButton.POOR -> ScreenColors.warning to tr("网络较差")
        DesktopButton.RECOVERING -> ScreenColors.warning to tr("重新连接中")
        DesktopButton.NEEDS_ACTION -> ScreenColors.warning to tr("需要登录")
        else -> return
    }
    Row(modifier.clip(CircleShape).background(ScreenColors.pill).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_desktop), contentDescription = tr("桌面"), Modifier.size(13.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A dot in the colour of [reachability] beside what it says: online with the latency, offline or checking. */
@Composable internal fun HostReachabilityLabel(reachability: HostReachability, modifier: Modifier = Modifier,
    /** Over a desktop's picture: the colours that read on its dark strip. */
    onImage: Boolean = false) {
    val (color, label) = when (reachability) {
        is HostReachability.Online -> (if (onImage) ScreenColors.success else StatusColors.success) to
            (reachability.latencyMs?.let { trf("在线 · %s", latencyLabel(it)) } ?: tr("在线"))
        HostReachability.Offline -> (if (onImage) ScreenColors.danger else StatusColors.danger) to tr("离线")
        HostReachability.Checking -> (if (onImage) Color.White.copy(alpha = 0.5f) else StatusColors.idle) to tr("检测中")
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = when { reachability is HostReachability.Online -> color; onImage -> Color.White.copy(alpha = 0.72f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant })
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
internal fun hostSystemBrand(system: HostSystem): Color? = when (system) {
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
