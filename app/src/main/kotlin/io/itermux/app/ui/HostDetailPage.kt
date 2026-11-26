package io.itermux.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.data.installedPlugins
import io.itermux.app.data.multiplexer
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.HostMetrics
import io.itermux.app.tr
import io.itermux.ssh.HostPlugin
import io.itermux.ssh.HostSystem
import io.itermux.ssh.SessionMode
import io.itermux.app.data.loginKey
import io.itermux.app.data.needsTypedPassword
import io.itermux.app.data.needsTypedSecret

/**
 * What tapping a host opens: its details, live health read over a private SSH login, and the ways to work on it.
 * Hosts with a saved secret (or a key) start monitoring at once; a password-only host asks for it first.
 */
@Composable
internal fun HostDetailPage(
    host: Host,
    reachability: HostReachability,
    onBack: () -> Unit,
    /** Opens a session in the folder picked on the launch sheet, starting [AgentLaunch] there when given. */
    onLaunch: (directory: String, agent: AgentLaunch?) -> Unit,
    onBrowseFiles: () -> Unit,
    onEdit: () -> Unit,
    onManagePlugins: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the host's desktop; null hides the button (desktop off, or the host has none). */
    onDesktop: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val app = LocalContext.current.applicationContext as ItermuxApp
    val scope = rememberCoroutineScope()
    var login by remember(host.loginKey) { mutableStateOf(if (host.needsTypedPassword) null else PluginTarget(host)) }
    var askPassword by remember(host.loginKey) { mutableStateOf(false) }
    /** null, or whether the launch sheet starts an AI agent. */
    var launching by remember(host.id) { mutableStateOf<Boolean?>(null) }
    val monitor = login?.let { target ->
        remember(target) { HostMonitor(app, target, scope).also { it.start() } }
    }
    monitor?.let { DisposableEffect(it) { onDispose { it.close() } } }
    LifecycleResumeEffect(monitor) {
        monitor?.visible?.value = true
        onPauseOrDispose { monitor?.visible?.value = false }
    }

    val onLogin = { if (monitor != null && !host.needsTypedSecret(app.vault)) monitor.start() else askPassword = true }
    if (isLandscape()) {
        HostDetailLandscape(host, reachability, monitor, onBack, onAgent = { launching = true }, onTerminal = { launching = false },
            onBrowseFiles, onDesktop, { anchor -> HostDetailMenu(onManagePlugins, onEdit, onDelete, anchor) }, onLogin, modifier)
    } else Column(modifier.fillMaxSize().testTag("host-detail")) {
        AppToolbar(title = host.name,
            navigation = { ToolbarIcon(R.drawable.ic_chevron_left, tr("返回主机列表"), Modifier.testTag("host-detail-back"), onClick = onBack) },
            actions = { HostDetailMenu(onManagePlugins, onEdit, onDelete) { ToolbarIcon(R.drawable.ic_more, tr("更多"), Modifier.testTag("host-detail-more"), onClick = it) } })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = Spacing.Page),
                verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                val metrics = monitor?.metrics?.collectAsStateWithLifecycle()?.value
                HostHeader(host, reachability, metrics)
                val labels = listOfNotNull(tr("Agent"), tr("终端"), tr("文件"), onDesktop?.let { tr("桌面") })
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.labelLarge
                val density = LocalDensity.current
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // A full row on a narrow phone: the icons step aside before a label would be cut short.
                    val each = (maxWidth - Spacing.Tight * (labels.size - 1)) / labels.size
                    val widest = labels.maxOf { with(density) { measurer.measure(it, style).size.width.toDp() } }
                    val icons = widest + ACTION_ICON_CHROME <= each
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                        HostAction(tr("Agent"), R.drawable.ic_agent, primary = true, tag = "host-detail-agent",
                            modifier = Modifier.weight(1f), icon = icons) { launching = true }
                        HostAction(tr("终端"), R.drawable.ic_sessions, tag = "host-detail-terminal", modifier = Modifier.weight(1f),
                            icon = icons) { launching = false }
                        HostAction(tr("文件"), R.drawable.ic_folder, tag = "host-detail-files", modifier = Modifier.weight(1f),
                            icon = icons, onClick = onBrowseFiles)
                        if (onDesktop != null) HostAction(tr("桌面"), R.drawable.ic_desktop, tag = "host-detail-desktop",
                            modifier = Modifier.weight(1f), icon = icons, onClick = onDesktop)
                    }
                }
                if (monitor == null) MonitorLoginCard { askPassword = true }
                else {
                    HostAiUsageSection(monitor)
                    MonitorSection(monitor, onLogin = onLogin)
                }
                HostInfoSection(host, metrics)
                monitor?.let { HostPortsSection(it) }
                Spacer(Modifier.height(Spacing.Page))
            }
        }
    }
    if (askPassword) CredentialsDialog(ConnectRequest(host, SessionMode.SHELL), { askPassword = false }) { password, passphrase ->
        askPassword = false
        login = PluginTarget(host, password, passphrase)
    }
    monitor?.pending?.collectAsStateWithLifecycle()?.value?.let { PromptDialog(it) }
    launching?.let { forAgent ->
        // The page's own login lists folders, so the sheet opens straight onto them.
        LaunchSheet(host, forAgent, monitor, onDismiss = { launching = null }) { directory, agent ->
            launching = null
            onLaunch(directory, agent)
        }
    }
}

/** Plugins, edit and delete, all behind the one overflow button [anchor] draws; it is handed the click that opens them. */
@Composable
internal fun HostDetailMenu(onManagePlugins: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, anchor: @Composable (() -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        anchor { menu = true }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(tr("安装插件")) }, onClick = { menu = false; onManagePlugins() },
                modifier = Modifier.testTag("host-detail-plugins"),
                leadingIcon = { Icon(painterResource(R.drawable.ic_plugin), contentDescription = null) })
            DropdownMenuItem(text = { Text(tr("编辑主机")) }, onClick = { menu = false; onEdit() },
                modifier = Modifier.testTag("host-detail-edit"),
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(tr("删除主机")) }, onClick = { menu = false; onDelete() },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error))
        }
    }
}

@Composable
private fun HostHeader(host: Host, reachability: HostReachability, metrics: HostMetrics?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        HostSystemIcon(host.os, 56.dp)
        Column(Modifier.weight(1f).padding(start = Spacing.Page)) {
            Text("${host.username}@${host.address}:${host.port}", style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(metrics?.os ?: tr(HostSystem.fromStored(host.os).label), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            HostReachabilityLabel(reachability, Modifier.testTag("host-detail-status"))
        }
    }
}

/** What a titled action adds around its label: the icon, the gap and the padding on each side. */
private val ACTION_ICON_CHROME = 18.dp + 6.dp + Spacing.Tight * 2

/** A compact pill: icon beside the label, so the actions take one slim row instead of a block of tiles. */
@Composable
private fun HostAction(label: String, iconRes: Int, tag: String, modifier: Modifier = Modifier, primary: Boolean = false,
    /** False drops the icon, leaving the label all the room. */
    icon: Boolean = true, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon) {
            Icon(painterResource(iconRes), contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val shape = MaterialTheme.shapes.small
    val padding = PaddingValues(horizontal = Spacing.Tight)
    val sized = modifier.height(40.dp).testTag(tag)
    if (primary) Button(onClick, sized, shape = shape, contentPadding = padding, content = content)
    else FilledTonalButton(onClick, sized, shape = shape, contentPadding = padding, content = content)
}

@Composable
internal fun MetricRow(label: String, value: String?, hint: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.Page, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** How the app reaches the host: address, login and what a new session opens. */
@Composable
internal fun connectionRows(host: Host): List<Pair<String, String>> = listOf(
    tr("地址") to host.address,
    tr("端口") to host.port.toString(),
    tr("用户名") to host.username,
    tr("认证方式") to (authLabel(host.auth) + (host.keyLabel?.takeIf { host.auth == "KEY" }?.let { " · $it" } ?: "")),
    tr("新会话") to modeLabel(host.mode == SessionMode.TMUX.name, host.multiplexer),
    tr("插件") to (host.installedPlugins?.let { found ->
        HostPlugin.entries.filter { it in found }.joinToString(" · ") { it.id }.ifEmpty { tr("未安装") }
    } ?: tr("未检测")),
)

/** What the host says about itself, as far as it reported. */
@Composable
internal fun systemRows(metrics: HostMetrics?): List<Pair<String, String>> = listOfNotNull(
    metrics?.hostname?.let { tr("主机名") to it },
    metrics?.kernel?.let { tr("内核") to it },
    metrics?.arch?.let { tr("架构") to it },
    metrics?.cpuModel?.let { tr("处理器") to it },
)

@Composable
private fun HostInfoSection(host: Host, metrics: HostMetrics?) {
    InfoGroup(tr("主机信息"), connectionRows(host) + systemRows(metrics))
}

/** A grouped list of label and value rows under [header], the values selectable. */
@Composable
internal fun InfoGroup(header: String, rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    GroupedSection(modifier, header = header) {
        SelectionContainer {
            Column {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) GroupedDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.Page, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = Spacing.Page))
                        Spacer(Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
