package io.itermux.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.AgentMode
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
 * [initialAgent] is the agent already picked (the new-session sheet asks for it first); otherwise the last one used.
 * [onLaunch] gets the folder and, for an agent, what to run there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LaunchSheet(host: Host, forAgent: Boolean, monitor: HostMonitor?, onDismiss: () -> Unit,
    initialAgent: CodingAgent? = null, onLaunch: (directory: String, agent: AgentLaunch?) -> Unit) {
    val context = LocalContext.current.applicationContext
    val app = context as ItermuxApp
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    var recent by remember(host.id) { mutableStateOf(preferences.recentStartDirectories(host.id)) }
    var current by remember { mutableStateOf(StartDirectory.HOME) }
    val unsupported = !StartDirectory.isSupported(current)
    var agent by remember { mutableStateOf(initialAgent ?: CodingAgent.from(preferences.getString(AGENT_KEY, null))) }
    var mode by remember { mutableStateOf(preferences.agentMode()) }
    val yolo = forAgent && mode == AgentMode.YOLO

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
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(current, ready, retry) {
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
    // While the next folder loads, the last one stays on screen (dimmed) instead of flashing a spinner.
    val stale = listing?.first != current
    val shown = listing?.second
    var filter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) { filter = null }
    val matches = remember(shown, filter) { shown?.folders.orEmpty().filter { it.contains(filter.orEmpty().trim(), ignoreCase = true) } }
    // Typing a filter: the keyboard takes half the screen, so everything but the folders steps aside until it closes.
    val typing = filter != null && WindowInsets.isImeVisible
    val segments = remember(current) { StartDirectory.segments(current) }
    val parent = segments.getOrNull(segments.size - 2)?.second ?: shown?.takeIf { !stale }?.parent

    fun launch() {
        if (!StartDirectory.isSupported(current)) return
        val dir = StartDirectory.normalize(shown?.takeUnless { stale }?.startDirectory(current) ?: current)
        preferences.saveRecentStartDirectories(host.id, StartDirectory.remember(recent, dir))
        if (forAgent) preferences.saveAgentChoice(agent, mode)
        onLaunch(dir, if (forAgent) AgentLaunch(agent, dir, mode) else null)
    }

    val colors = MaterialTheme.colorScheme
    AppSheet(onDismiss, fullScreen = true) {
        if (!typing) SheetHeader(if (forAgent) tr("用 AI 助手打开") else tr("打开终端"), null, onDismiss)
        Column(Modifier.weight(1f).testTag("launch-sheet")) {
            if (recent.isNotEmpty() && !typing) RecentFolders(recent, current, onPick = { current = it }, onForget = { dir ->
                recent = recent - dir
                preferences.saveRecentStartDirectories(host.id, recent)
            })
            Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.Page), shape = MaterialTheme.shapes.medium,
                color = colors.groupedContainer) {
                Column {
                    val text = filter
                    if (text != null) FolderFilter(text, { filter = it }, onClose = { filter = null },
                        onGo = { matches.firstOrNull()?.let { current = shown!!.child(it) } })
                    else PathBar(segments, parent, canFilter = (shown?.folders?.size ?: 0) > FILTER_THRESHOLD,
                        onNavigate = { current = it }, onFilter = { filter = "" })
                    GroupedDivider()
                    Box(Modifier.weight(1f)) {
                        when {
                            unsupported -> CenterNote(tr(StartDirectory.UNSUPPORTED_MESSAGE), tr("回到主目录")) { current = StartDirectory.HOME }
                            browser == null -> CenterNote(tr("输入密码后即可浏览文件夹"), tr("输入密码")) { askPassword = true }
                            connection != null && !connection.active && !ready -> CenterNote(
                                connection.message.takeIf { it.isNotBlank() }?.let { tr(it) } ?: phaseLabel(connection.phase),
                                tr("重新连接")) { browser.start() }
                            failed -> CenterNote(tr("无法打开这个文件夹"), tr(if (StartDirectory.isHome(current)) "重试" else "回到主目录")) {
                                current = StartDirectory.HOME
                                retry++
                            }
                            shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            else -> FolderList(shown, matches, stale || loading) { current = it }
                        }
                    }
                }
            }
            if (typing) return@Column
            if (forAgent) AgentChooser(agent, mode, { agent = it }, { mode = it })
            val buttonColor by animateColorAsState(if (yolo) colors.error else colors.primary, label = "launch-button")
            val name = folderName(current)
            // Home always opens; other folders wait for their listing (native Windows resolves `~/x` from it), unless there is no login to list with.
            Button(onClick = ::launch, enabled = !unsupported &&
                (StartDirectory.isHome(current) || browser == null || (ready && !failed && !loading && !stale)),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor,
                    contentColor = if (yolo) colors.onError else colors.onPrimary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Row)
                    .heightIn(min = 48.dp).testTag("launch-open")) {
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

/**
 * Where the session will start, as tappable crumbs (`~ › work › app`), so any folder above is one tap away; the arrow
 * steps up past home. The magnifier filters long folder lists.
 */
@Composable
private fun PathBar(segments: List<Pair<String, String>>, parent: String?, canFilter: Boolean,
    onNavigate: (String) -> Unit, onFilter: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(segments, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { parent?.let(onNavigate) }, enabled = parent != null, modifier = Modifier.size(40.dp).testTag("launch-parent")) {
            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = tr("上一级"), Modifier.size(20.dp))
        }
        Row(Modifier.weight(1f).horizontalScroll(scroll).testTag("launch-path"), verticalAlignment = Alignment.CenterVertically) {
            segments.forEachIndexed { index, (name, path) ->
                val last = index == segments.lastIndex
                if (index > 0) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    Modifier.size(14.dp), tint = colors.outline)
                Text(name, maxLines = 1, softWrap = false,
                    style = if (last) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (last) colors.onSurface else colors.primary,
                    modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(enabled = !last) { onNavigate(path) }
                        .padding(horizontal = 6.dp, vertical = 10.dp).testTag("launch-crumb-$index"))
            }
        }
        if (canFilter) IconButton(onClick = onFilter, modifier = Modifier.size(40.dp).testTag("launch-filter-toggle")) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = tr("筛选当前目录"),
                Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
    }
}

/** Takes the path bar's place while filtering, so the folders keep the room; Enter opens the first match. */
@Composable
private fun FolderFilter(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit, onGo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp).testTag("launch-filter-close")) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = tr("关闭筛选"), Modifier.size(20.dp))
        }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(tr("筛选当前目录"), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            BasicTextField(value, onValueChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("launch-filter"))
        }
        if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_clear), contentDescription = tr("清除"), Modifier.size(18.dp),
                tint = colors.onSurfaceVariant)
        }
    }
}

/** Recently used folders, one tap each; the cross forgets one. */
@Composable
private fun RecentFolders(recent: List<String>, current: String, onPick: (String) -> Unit, onForget: (String) -> Unit) {
    LazyRow(Modifier.padding(bottom = Spacing.Tight), contentPadding = PaddingValues(horizontal = Spacing.Page),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
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

/** [folders] of [listing] (all, or those matching the filter); [busy] dims them while the next folder loads. */
@Composable
private fun FolderList(listing: RemoteFolders, folders: List<String>, busy: Boolean, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().alpha(if (busy) 0.5f else 1f).testTag("launch-folders")) {
            if (folders.isEmpty()) item(key = "empty") {
                Text(if (listing.folders.isEmpty()) tr("这里没有子文件夹") else tr("没有匹配的文件夹"),
                    Modifier.fillMaxWidth().padding(Spacing.Section), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
            items(folders, key = { "f:$it" }) { name ->
                FolderRow(name, enabled = !busy) { onOpen(listing.child(name)) }
                GroupedDivider(start = 50.dp)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun FolderRow(name: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 44.dp)
        .padding(horizontal = Spacing.Page, vertical = 6.dp).testTag("launch-folder-$name"),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteFileIcon(name, directory = true, size = 22.dp)
        Spacer(Modifier.width(Spacing.Row))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
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

/** Agent and permission as two dropdowns on one line; YOLO is red, and its "!" says what it allows. */
@Composable
private fun AgentChooser(agent: CodingAgent, mode: AgentMode, onAgent: (CodingAgent) -> Unit, onMode: (AgentMode) -> Unit) {
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
        CodingAgent.CODEX -> "以只读沙箱启动，可在助手中调整权限"
        CodingAgent.GROK -> "只读沙箱；状态与临时目录仍可写"
    })
    AgentMode.STANDARD -> tr("改文件或运行命令前先问你")
    AgentMode.YOLO -> tr("跳过所有确认，直接执行")
}

internal fun SharedPreferences.recentStartDirectories(hostId: String): List<String> =
    StartDirectory.sanitizeRecent(getString("$RECENT_KEY:$hostId", null).orEmpty().split('\n'))

internal fun SharedPreferences.saveRecentStartDirectories(hostId: String, directories: List<String>) =
    edit { putString("$RECENT_KEY:$hostId", StartDirectory.sanitizeRecent(directories).joinToString("\n")) }

private const val RECENT_KEY = "startDirectories"
private const val FILTER_THRESHOLD = 8
