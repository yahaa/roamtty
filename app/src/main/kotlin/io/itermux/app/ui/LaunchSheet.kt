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
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import io.itermux.ssh.ConnectionState

/** What the launch sheet's user has picked so far, and the folders read for it. */
@Stable
private class LaunchState(val host: Host, val forAgent: Boolean, private val preferences: SharedPreferences,
    initialAgent: CodingAgent?, login: PluginTarget?) {
    var recent by mutableStateOf(preferences.recentStartDirectories(host.id))
        private set
    var current by mutableStateOf(StartDirectory.HOME)
    var agent by mutableStateOf(initialAgent ?: CodingAgent.from(preferences.getString(AGENT_KEY, null)))
    var mode by mutableStateOf(preferences.agentMode())
    /** The sheet's own login for browsing, when the host page lends none. */
    var login by mutableStateOf(login)
    var askPassword by mutableStateOf(false)
    /** The folder last read, and what it holds. */
    var listing by mutableStateOf<Pair<String, RemoteFolders>?>(null)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    /** Bumped to read the folder again. */
    var retry by mutableIntStateOf(0)
    /** The text the folders are narrowed to; null while not filtering. */
    var filter by mutableStateOf<String?>(null)

    val unsupported: Boolean get() = !StartDirectory.isSupported(current)
    val yolo: Boolean get() = forAgent && mode == AgentMode.YOLO
    // While the next folder loads, the last one stays on screen (dimmed) instead of flashing a spinner.
    val stale: Boolean get() = listing?.first != current
    val shown: RemoteFolders? get() = listing?.second
    val matches: List<String> get() = shown?.folders.orEmpty().filter { it.contains(filter.orEmpty().trim(), ignoreCase = true) }

    fun forget(directory: String) {
        recent = recent - directory
        preferences.saveRecentStartDirectories(host.id, recent)
    }

    fun launch(onLaunch: (directory: String, agent: AgentLaunch?) -> Unit) {
        if (unsupported) return
        val dir = StartDirectory.normalize(shown?.takeUnless { stale }?.startDirectory(current) ?: current)
        preferences.saveRecentStartDirectories(host.id, StartDirectory.remember(recent, dir))
        if (forAgent) preferences.saveAgentChoice(agent, mode)
        onLaunch(dir, if (forAgent) AgentLaunch(agent, dir, mode) else null)
    }
}

/** The login the folders are read with (null without one), and how far it has come. */
private class FolderBrowser(val monitor: HostMonitor?, val ready: Boolean, val connection: ConnectionState?)

/**
 * One sheet to open a terminal or, with [forAgent], start Claude Code, Codex or Grok on [host]. The folder is
 * picked by tapping through the host's folders (no typing); leaving it untouched starts in home. Folders come from
 * [monitor] when the host page already has a login, else from a short-lived login of the sheet's own.
 * [initialAgent] is the agent already picked (the new-session sheet asks for it first); otherwise the last one used.
 * [title] replaces the heading when the sheet is a step of a longer flow. [onLaunch] gets the folder and, for an agent, what to run there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LaunchSheet(host: Host, forAgent: Boolean, monitor: HostMonitor?, onDismiss: () -> Unit,
    initialAgent: CodingAgent? = null, title: String? = null, onLaunch: (directory: String, agent: AgentLaunch?) -> Unit) {
    val context = LocalContext.current.applicationContext
    val state = remember(host.id, host.loginKey) {
        LaunchState(host, forAgent, context.appSettings(), initialAgent,
            login = if (monitor == null && !host.needsTypedPassword) PluginTarget(host) else null)
    }
    val browser = rememberFolderBrowser(state, monitor)
    LaunchedEffect(state.current, browser.ready, state.retry) {
        state.failed = false
        if (!browser.ready) return@LaunchedEffect
        state.loading = true
        try {
            val result = browser.monitor?.listFolders(state.current)
            if (result != null) state.listing = state.current to result else state.failed = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.failed = true }
        finally { state.loading = false }
    }
    LaunchedEffect(state.current) { state.filter = null }
    // Typing a filter: the keyboard takes half the screen, so everything but the folders steps aside until it closes.
    val typing = state.filter != null && WindowInsets.isImeVisible
    val landscape = isLandscape()
    val heading = title ?: if (forAgent) tr("用 Agent 打开") else tr("打开终端")
    val launchButton: @Composable (Modifier) -> Unit = { modifier -> LaunchButton(state, browser, modifier) { state.launch(onLaunch) } }
    AppSheet(onDismiss, fullScreen = true, wide = landscape) {
        if (landscape) {
            // Landscape, like the host page there: one flat bar with close and the heading; the folders take the
            // whole height on the left, and a side column holds the recent folders, the agent pickers and the launch button.
            LandscapeBar(heading, Modifier.testTag("launch-bar"), leading = {
                ToolbarIcon(R.drawable.ic_close, tr("关闭"), Modifier.testTag("launch-close"), onClick = onDismiss)
            })
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = Spacing.Row, end = Spacing.Row, bottom = Spacing.Row)
                .testTag("launch-sheet"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FolderBox(state, browser, Modifier.weight(1f).fillMaxHeight())
                if (!typing) LaunchSideColumn(state, launchButton)
            }
            return@AppSheet
        }
        if (!typing) SheetHeader(heading, null, onDismiss)
        Column(Modifier.weight(1f).testTag("launch-sheet")) {
            if (state.recent.isNotEmpty() && !typing) RecentFolders(state.recent, state.current, onPick = { state.current = it }, onForget = state::forget)
            FolderBox(state, browser, Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.Page))
            if (typing) return@Column
            if (forAgent) AgentChooser(state.agent, state.mode, { state.agent = it }, { state.mode = it })
            launchButton(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Row))
        }
    }
    if (state.askPassword) CredentialsDialog(ConnectRequest(host, SessionMode.SHELL), { state.askPassword = false }) { password, passphrase ->
        state.askPassword = false
        state.login = PluginTarget(host, password, passphrase)
    }
}

/** Borrows the host page's login ([monitor]); otherwise opens one just for browsing while the sheet is up. */
@Composable
private fun rememberFolderBrowser(state: LaunchState, monitor: HostMonitor?): FolderBrowser {
    val app = LocalContext.current.applicationContext as ItermuxApp
    val scope = rememberCoroutineScope()
    val own = if (monitor == null) state.login?.let { target ->
        remember(target) { HostMonitor(app, target, scope, sampleMetrics = false).also { it.start() } }
    } else null
    own?.let { DisposableEffect(it) { onDispose { it.close() } } }
    own?.pending?.collectAsStateWithLifecycle()?.value?.let { PromptDialog(it) }
    val browser = monitor ?: own
    return FolderBrowser(browser, ready = browser?.ready?.collectAsStateWithLifecycle()?.value == true,
        connection = browser?.connection?.collectAsStateWithLifecycle()?.value)
}

/** The path, or the filter being typed, over the folders to tap through, or why there are none. */
@Composable
private fun FolderBox(state: LaunchState, browser: FolderBrowser, modifier: Modifier) {
    val shown = state.shown
    val current = state.current
    val matches = state.matches
    val segments = remember(current) { StartDirectory.segments(current) }
    val parent = segments.getOrNull(segments.size - 2)?.second ?: shown?.takeIf { !state.stale }?.parent
    val connection = browser.connection
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
        Column {
            val text = state.filter
            if (text != null) FolderFilter(text, { state.filter = it }, onClose = { state.filter = null },
                onGo = { shown?.let { folder -> matches.firstOrNull()?.let { state.current = folder.child(it) } } })
            else PathBar(segments, parent, canFilter = (shown?.folders?.size ?: 0) > FILTER_THRESHOLD,
                onNavigate = { state.current = it }, onFilter = { state.filter = "" })
            GroupedDivider()
            Box(Modifier.weight(1f)) {
                when {
                    state.unsupported -> CenterNote(tr(StartDirectory.UNSUPPORTED_MESSAGE), tr("回到主目录")) { state.current = StartDirectory.HOME }
                    browser.monitor == null -> CenterNote(tr("输入密码后即可浏览文件夹"), tr("输入密码")) { state.askPassword = true }
                    connection != null && !connection.active && !browser.ready -> CenterNote(
                        connection.message.takeIf { it.isNotBlank() }?.let { tr(it) } ?: phaseLabel(connection.phase),
                        tr("重新连接")) { browser.monitor.start() }
                    state.failed -> CenterNote(tr("无法打开这个文件夹"), tr(if (StartDirectory.isHome(current)) "重试" else "回到主目录")) {
                        state.current = StartDirectory.HOME
                        state.retry++
                    }
                    shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    else -> FolderList(shown, matches, state.stale || state.loading) { state.current = it }
                }
            }
        }
    }
}

/** Opens the terminal, or starts the agent, in the folder picked; red while the agent would run without asking. */
@Composable
private fun LaunchButton(state: LaunchState, browser: FolderBrowser, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current = state.current
    val buttonColor by animateColorAsState(if (state.yolo) colors.error else colors.primary, label = "launch-button")
    val name = folderName(current)
    val home = StartDirectory.isHome(current)
    // Home always opens; other folders wait for their listing (native Windows resolves `~/x` from it), unless there is no login to list with.
    Button(onClick = onClick, enabled = !state.unsupported &&
        (home || browser.monitor == null || (browser.ready && !state.failed && !state.loading && !state.stale)),
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor,
            contentColor = if (state.yolo) colors.onError else colors.onPrimary),
        contentPadding = PaddingValues(horizontal = Spacing.Page),
        modifier = modifier.heightIn(min = 48.dp).testTag("launch-open")) {
        if (state.forAgent) {
            Icon(painterResource(state.agent.iconRes), contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.Tight))
        }
        Text(when {
            state.forAgent && home -> trf("在主目录启动 %s", state.agent.shortLabel)
            state.forAgent -> trf("在 %s 启动 %s", name, state.agent.shortLabel)
            home -> tr("在主目录打开终端")
            else -> trf("在 %s 打开终端", name)
        },
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Landscape's side column: the pickers and the button keep their place at the bottom; the recent folders scroll in what is left. */
@Composable
private fun LaunchSideColumn(state: LaunchState, launchButton: @Composable (Modifier) -> Unit) {
    Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (state.recent.isNotEmpty()) {
                SideLabel(tr("最近"))
                RecentFolderList(state.recent, state.current, onPick = { state.current = it }, onForget = state::forget)
            }
        }
        if (state.forAgent) {
            SideLabel("Agent")
            AgentChooser(state.agent, state.mode, { state.agent = it }, { state.mode = it }, stacked = true)
        }
        launchButton(Modifier.fillMaxWidth())
    }
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
