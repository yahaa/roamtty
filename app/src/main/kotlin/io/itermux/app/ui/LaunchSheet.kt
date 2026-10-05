package io.itermux.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.RemoteFolders
import io.itermux.app.session.StartDirectory
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CancellationException

/**
 * One sheet to open a terminal or, with [forAgent], start Claude Code, Codex or Grok on [host]. The folder is
 * picked by tapping through the host's folders (no typing); leaving it untouched starts in home. Folders come from
 * [monitor] when the host page already has a login, else from a short-lived login of the sheet's own.
 * [onLaunch] gets the folder and, for an agent, what to run there.
 */
@Composable
internal fun LaunchSheet(host: Host, forAgent: Boolean, monitor: HostMonitor?, onDismiss: () -> Unit,
    onLaunch: (directory: String, agent: AgentLaunch?) -> Unit) {
    val context = LocalContext.current.applicationContext
    val app = context as ItermuxApp
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    var recent by remember(host.id) { mutableStateOf(preferences.recentStartDirectories(host.id)) }
    var current by remember { mutableStateOf(StartDirectory.HOME) }
    var agent by remember { mutableStateOf(CodingAgent.from(preferences.getString(AGENT_KEY, null))) }
    var yolo by remember { mutableStateOf(preferences.getBoolean(YOLO_KEY, false)) }

    // Borrow the host page's login; otherwise open one just for browsing while the sheet is up.
    var login by remember(host.id) { mutableStateOf(if (monitor == null && !host.needsTypedPassword) PluginTarget(host) else null) }
    var askPassword by remember { mutableStateOf(false) }
    val own = if (monitor == null) login?.let { target ->
        remember(target) { HostMonitor(app, target, scope, sampleMetrics = false).also { it.start() } }
    } else null
    own?.let { DisposableEffect(it) { onDispose { it.close() } } }
    val browser = monitor ?: own
    val ready = browser?.ready?.collectAsStateWithLifecycle()?.value == true
    val connection = browser?.connection?.collectAsStateWithLifecycle()?.value

    var listing by remember { mutableStateOf<Pair<String, RemoteFolders>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(current, ready) {
        failed = false
        if (!ready || browser == null) return@LaunchedEffect
        loading = true
        try {
            val result = browser.listFolders(current)
            if (result != null) listing = current to result else failed = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val shown = listing?.takeIf { it.first == current }?.second

    fun launch() {
        val dir = StartDirectory.normalize(current)
        preferences.saveRecentStartDirectories(host.id, StartDirectory.remember(recent, dir))
        if (forAgent) preferences.edit { putString(AGENT_KEY, agent.name); putBoolean(YOLO_KEY, yolo) }
        onLaunch(dir, if (forAgent) AgentLaunch(agent, dir, yolo) else null)
    }

    val colors = MaterialTheme.colorScheme
    AppSheet(onDismiss) {
        SheetHeader(if (forAgent) tr("用 AI 助手打开") else tr("打开终端"), "${host.name} · ${host.username}@${host.address}", onDismiss)
        Column(Modifier.weight(1f).testTag("launch-sheet")) {
            CurrentFolder(current)
            QuickFolders(recent, current, onPick = { current = it }, onForget = { dir ->
                recent = recent - dir
                preferences.saveRecentStartDirectories(host.id, recent)
            })
            Spacer(Modifier.height(Spacing.Tight))
            Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.Page), shape = MaterialTheme.shapes.medium,
                color = colors.groupedContainer) {
                when {
                    browser == null -> CenterNote(tr("输入密码后即可浏览文件夹"), tr("输入密码")) { askPassword = true }
                    connection != null && !connection.active && !ready -> CenterNote(
                        connection.message.takeIf { it.isNotBlank() }?.let { tr(it) } ?: phaseLabel(connection.phase),
                        tr("重新连接")) { browser.start() }
                    failed -> CenterNote(tr("无法打开这个文件夹"), tr("回到主目录")) { current = StartDirectory.HOME }
                    shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    else -> FolderList(shown, loading) { current = it }
                }
            }
            if (forAgent) AgentChooser(agent, yolo, { agent = it }, { yolo = it })
            val buttonColor by animateColorAsState(if (forAgent && yolo) colors.error else colors.primary, label = "launch-button")
            val name = folderName(current)
            Button(onClick = ::launch, enabled = !failed,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor,
                    contentColor = if (forAgent && yolo) colors.onError else colors.onPrimary),
                modifier = Modifier.fillMaxWidth().padding(Spacing.Page).heightIn(min = 52.dp).testTag("launch-open")) {
                if (forAgent) {
                    Icon(painterResource(R.drawable.ic_agent), contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.Tight))
                }
                val home = StartDirectory.isHome(current)
                Text(when {
                    forAgent && home -> trf("在主目录启动 %s", agent.shortLabel)
                    forAgent -> trf("在 %s 启动 %s", name, agent.shortLabel)
                    home -> tr("在主目录打开终端")
                    else -> trf("在 %s 打开终端", name)
                },
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (askPassword) CredentialsDialog(ConnectRequest(host, SessionMode.SHELL), { askPassword = false }) { password, passphrase ->
        askPassword = false
        login = PluginTarget(host, password, passphrase)
    }
    own?.pending?.collectAsStateWithLifecycle()?.value?.let { PromptDialog(it) }
}

private fun folderName(directory: String) = if (StartDirectory.isHome(directory)) tr("主目录")
    else directory.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/** Where the session will start; changes as the user taps through folders. */
@Composable
private fun CurrentFolder(directory: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Section).padding(bottom = Spacing.Row),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteFileIcon("", directory = true, size = 36.dp)
        Spacer(Modifier.width(Spacing.Row))
        Column(Modifier.weight(1f)) {
            Text(folderName(directory), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(directory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.testTag("launch-path"))
        }
    }
}

/** Home and recently used folders, one tap each; the cross forgets a recent one. */
@Composable
private fun QuickFolders(recent: List<String>, current: String, onPick: (String) -> Unit, onForget: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.Page), horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        item {
            FilterChip(StartDirectory.isHome(current), onClick = { onPick(StartDirectory.HOME) }, label = { Text(tr("主目录")) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_hosts), contentDescription = null, Modifier.size(16.dp)) },
                modifier = Modifier.testTag("launch-home"))
        }
        items(recent, key = { it }) { dir ->
            FilterChip(current == dir, onClick = { onPick(dir) }, label = { Text(folderName(dir), maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null, Modifier.size(16.dp)) },
                trailingIcon = {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = trf("移除 %s", dir),
                        Modifier.size(16.dp).clickable { onForget(dir) })
                },
                modifier = Modifier.testTag("launch-recent-${recent.indexOf(dir)}"))
        }
    }
}

@Composable
private fun FolderList(listing: RemoteFolders, loading: Boolean, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().testTag("launch-folders")) {
        listing.parent?.let { parent ->
            item(key = "..") {
                FolderRow(tr("上一级"), R.drawable.ic_arrow_up, "launch-parent") { onOpen(parent) }
                GroupedDivider(start = 52.dp)
            }
        }
        if (listing.folders.isEmpty()) item(key = "empty") {
            Text(tr("这里没有子文件夹"), Modifier.fillMaxWidth().padding(Spacing.Section), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
        }
        items(listing.folders, key = { "f:$it" }) { name ->
            FolderRow(name, null, "launch-folder-$name") { onOpen(listing.child(name)) }
            GroupedDivider(start = 52.dp)
        }
        if (loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun FolderRow(title: String, icon: Int?, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = Spacing.Page, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary)
        else RemoteFileIcon(title, directory = true, size = 24.dp)
        Spacer(Modifier.width(Spacing.Row))
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Chevron()
    }
}

@Composable
private fun CenterNote(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.Section), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction, modifier = Modifier.testTag("launch-note-action")) { Text(action) }
    }
}

/** The three agents side by side and the YOLO switch, so choosing never leaves the sheet. */
@Composable
private fun AgentChooser(agent: CodingAgent, yolo: Boolean, onAgent: (CodingAgent) -> Unit, onYolo: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = Spacing.Page, end = Spacing.Page, top = Spacing.Row).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        CodingAgent.entries.forEach { option ->
            val selected = option == agent
            val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, label = "agent-chip")
            Surface(Modifier.weight(1f).selectable(selected, onClick = { onAgent(option) }, role = Role.RadioButton)
                .testTag("agent-option-${option.name.lowercase()}"), shape = MaterialTheme.shapes.small,
                color = if (selected) colors.primary.copy(alpha = 0.08f) else colors.groupedContainer,
                border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, border)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentBadge(option, 24.dp)
                    Spacer(Modifier.width(Spacing.Tight))
                    Text(option.shortLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).padding(top = Spacing.Tight)
        .selectable(yolo, onClick = { onYolo(!yolo) }, role = Role.Switch).testTag("agent-yolo"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = Spacing.Tight)) {
            Text(tr("YOLO 模式"), style = MaterialTheme.typography.bodyLarge, color = if (yolo) colors.error else colors.onSurface)
            Text(tr("不经确认直接运行命令、修改或删除文件"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(yolo, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.error))
    }
}

internal fun SharedPreferences.recentStartDirectories(hostId: String): List<String> =
    getString("$RECENT_KEY:$hostId", null).orEmpty().split('\n').filter { it.isNotEmpty() }

internal fun SharedPreferences.saveRecentStartDirectories(hostId: String, directories: List<String>) =
    edit { putString("$RECENT_KEY:$hostId", directories.joinToString("\n")) }

private const val RECENT_KEY = "startDirectories"
