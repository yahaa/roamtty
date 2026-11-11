package io.itermux.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.appSettings
import io.itermux.app.data.Host
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.AgentMode
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.RemoteFolders
import io.itermux.app.session.StartDirectory
import io.itermux.app.session.agent
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.itermux.app.data.loginKey
import io.itermux.app.data.needsTypedPassword

/**
 * One sheet to open a terminal or, with [forAgent], start Claude Code, Codex or Grok on [host]. The folder is
 * picked by tapping through the host's folders (no typing); leaving it untouched starts in home. Folders come from
 * [monitor] when the host page already has a login, else from a short-lived login of the sheet's own.
 * [initialAgent] is the agent already picked (the new-session sheet asks for it first); otherwise the last one used.
 * [title] replaces the heading when the sheet is a step of a longer flow. [onLaunch] gets the folder and, for an agent, what to run there.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun LaunchSheet(host: Host, forAgent: Boolean, monitor: HostMonitor?, onDismiss: () -> Unit,
    initialAgent: CodingAgent? = null, title: String? = null, onLaunch: (directory: String, agent: AgentLaunch?) -> Unit) {
    val context = LocalContext.current.applicationContext
    val app = context as ItermuxApp
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.appSettings() }
    var recent by remember(host.id) { mutableStateOf(preferences.recentStartDirectories(host.id)) }
    var current by remember { mutableStateOf(StartDirectory.HOME) }
    val unsupported = !StartDirectory.isSupported(current)
    var agent by remember { mutableStateOf(initialAgent ?: CodingAgent.from(preferences.getString(AGENT_KEY, null))) }
    var mode by remember { mutableStateOf(preferences.agentMode()) }
    val yolo = forAgent && mode == AgentMode.YOLO

    // Borrow the host page's login; otherwise open one just for browsing while the sheet is up.
    var login by remember(host.loginKey) { mutableStateOf(if (monitor == null && !host.needsTypedPassword) PluginTarget(host) else null) }
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
        if (!ready) return@LaunchedEffect
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
    val landscape = isLandscape()
    val heading = title ?: if (forAgent) tr("用 Agent 打开") else tr("打开终端")
    val folderBox: @Composable (Modifier) -> Unit = { modifier ->
        Surface(modifier, shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
            Column {
                val text = filter
                if (text != null) FolderFilter(text, { filter = it }, onClose = { filter = null },
                    onGo = { shown?.let { folder -> matches.firstOrNull()?.let { current = folder.child(it) } } })
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
    }
    val launchButton: @Composable (Modifier) -> Unit = { modifier ->
        val buttonColor by animateColorAsState(if (yolo) colors.error else colors.primary, label = "launch-button")
        val name = folderName(current)
        // Home always opens; other folders wait for their listing (native Windows resolves `~/x` from it), unless there is no login to list with.
        Button(onClick = ::launch, enabled = !unsupported &&
            (StartDirectory.isHome(current) || browser == null || (ready && !failed && !loading && !stale)),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor,
                contentColor = if (yolo) colors.onError else colors.onPrimary),
            contentPadding = PaddingValues(horizontal = Spacing.Page),
            modifier = modifier.heightIn(min = 48.dp).testTag("launch-open")) {
            if (forAgent) {
                Icon(painterResource(agent.iconRes), contentDescription = null, Modifier.size(18.dp))
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
    val forget: (String) -> Unit = { dir ->
        recent = recent - dir
        preferences.saveRecentStartDirectories(host.id, recent)
    }

    AppSheet(onDismiss, fullScreen = true, wide = landscape) {
        if (landscape) {
            // Landscape, like the host page there: one flat bar with close and the heading; the folders take the
            // whole height on the left, and a side column holds the recent folders, the agent pickers and the launch button.
            LandscapeBar(heading, Modifier.testTag("launch-bar"), leading = {
                ToolbarIcon(R.drawable.ic_close, tr("关闭"), Modifier.testTag("launch-close"), onClick = onDismiss)
            })
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = Spacing.Row, end = Spacing.Row, bottom = Spacing.Row)
                .testTag("launch-sheet"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                folderBox(Modifier.weight(1f).fillMaxHeight())
                if (typing) return@Row
                // The pickers and the button keep their place at the bottom; the recent folders scroll in what is left.
                Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (recent.isNotEmpty()) {
                            SideLabel(tr("最近"))
                            RecentFolderList(recent, current, onPick = { current = it }, onForget = forget)
                        }
                    }
                    if (forAgent) {
                        SideLabel("Agent")
                        AgentChooser(agent, mode, { agent = it }, { mode = it }, stacked = true)
                    }
                    launchButton(Modifier.fillMaxWidth())
                }
            }
            return@AppSheet
        }
        if (!typing) SheetHeader(heading, null, onDismiss)
        Column(Modifier.weight(1f).testTag("launch-sheet")) {
            if (recent.isNotEmpty() && !typing) RecentFolders(recent, current, onPick = { current = it }, onForget = forget)
            folderBox(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.Page))
            if (typing) return@Column
            if (forAgent) AgentChooser(agent, mode, { agent = it }, { mode = it })
            launchButton(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Row))
        }
    }
    if (askPassword) CredentialsDialog(ConnectRequest(host, SessionMode.SHELL), { askPassword = false }) { password, passphrase ->
        askPassword = false
        login = PluginTarget(host, password, passphrase)
    }
    own?.pending?.collectAsStateWithLifecycle()?.value?.let { PromptDialog(it) }
}

@Composable
private fun SideLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
}

@Composable
internal fun CenterNote(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.Section), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction, modifier = Modifier.testTag("launch-note-action")) { Text(action) }
    }
}

private const val FILTER_THRESHOLD = 8
