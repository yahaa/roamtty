package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.StartDirectory
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CancellationException
import io.itermux.app.data.loginKey
import io.itermux.app.data.needsTypedPassword

/**
 * The tmux sessions already running on [host]: tapping one picks it (the most recently used to start with), the button
 * opens it ([onAttach]); [openTmux] are those that already have a card. In landscape the picked one also shows beside
 * the list. Lists them over a short-lived login of the sheet's own, asking for a typed password only when tapped. With
 * none running it offers a fresh terminal ([onNewTerminal]); without tmux, the host's plugins ([onInstall]).
 */
@Composable
internal fun ContinueSessionSheet(host: Host, openTmux: Set<String>, onDismiss: () -> Unit, onNewTerminal: () -> Unit,
    onInstall: () -> Unit, onAttach: (name: String) -> Unit) {
    val app = LocalContext.current.applicationContext as ItermuxApp
    val scope = rememberCoroutineScope()
    var login by remember(host.loginKey) { mutableStateOf(if (!host.needsTypedPassword) PluginTarget(host) else null) }
    var askPassword by remember { mutableStateOf(false) }
    val browser = login?.let { target ->
        remember(target) { HostMonitor(app, target, scope, sampleMetrics = false).also { it.start() } }
    }
    browser?.let { DisposableEffect(it) { onDispose { it.close() } } }
    val ready = browser?.ready?.collectAsStateWithLifecycle()?.value == true
    val connection = browser?.connection?.collectAsStateWithLifecycle()?.value

    var tmux by remember { mutableStateOf<TmuxListing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var home by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ready, retry) {
        if (!ready) return@LaunchedEffect
        loading = true
        try {
            // Only to show the sessions' folders as `~/…`; first, so the list does not change under the finger.
            if (home == null) home = browser.listFolders(StartDirectory.HOME)?.home
            val listing = browser.listTmuxSessions()
            tmux = listing
            if (listing is TmuxListing.Sessions) picked = picked?.takeIf { name -> listing.sessions.any { it.name == name && it.attachable } }
                ?: listing.sessions.firstOrNull { it.attachable }?.name
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { tmux = TmuxListing.Failed }
        finally { loading = false }
    }

    val landscape = isLandscape()
    val heading = trf("继续会话 · %s", host.name)
    val sessions = (tmux as? TmuxListing.Sessions)?.sessions
    val attachReady = picked != null && sessions?.any { it.name == picked && it.attachable } == true

    val listBox: @Composable (Modifier) -> Unit = { modifier ->
        Surface(modifier.testTag("continue-sheet"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Spacing.Page, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("服务器上的 tmux 会话"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { retry++ }, enabled = ready && !loading, modifier = Modifier.size(40.dp).testTag("continue-refresh")) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = tr("刷新"), Modifier.size(20.dp))
                    }
                }
                GroupedDivider()
                Box(Modifier.weight(1f)) {
                    val listing = tmux
                    when {
                        browser == null -> CenterNote(tr("输入密码后即可查看会话"), tr("输入密码")) { askPassword = true }
                        connection != null && !connection.active && !ready -> CenterNote(
                            connection.message.takeIf { it.isNotBlank() }?.let { tr(it) } ?: phaseLabel(connection.phase),
                            tr("重新连接")) { browser.start() }
                        listing == TmuxListing.Missing -> CenterNote(trf("%s 没有安装 tmux", host.name), tr("安装插件"), onInstall)
                        listing == TmuxListing.Failed -> CenterNote(tr("无法读取 tmux 会话"), tr("重试")) { retry++ }
                        listing is TmuxListing.Sessions && listing.sessions.isEmpty() ->
                            CenterNote(trf("%s 上没有正在运行的会话", host.name), trf("在 %s 新开终端", host.name), onNewTerminal)
                        listing is TmuxListing.Sessions -> TmuxSessionList(listing.sessions, picked, openTmux, loading, home) { picked = it }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
    // The same words in both orientations: the list (and in landscape the card) already names the session.
    val attachButton: @Composable (Modifier) -> Unit = { modifier ->
        val name = picked
        Button(onClick = { if (attachReady && name != null) onAttach(name) }, enabled = attachReady,
            contentPadding = PaddingValues(horizontal = Spacing.Page), modifier = modifier.heightIn(min = 48.dp).testTag("continue-attach")) {
            Text(if (name == null) tr("选择一个 tmux 会话") else tr("打开会话"),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    AppSheet(onDismiss, fullScreen = true, wide = landscape) {
        if (landscape) {
            // Like the launch sheet there: one flat bar, the list over the whole height, and a side column with the
            // picked session and the button at its foot.
            LandscapeBar(heading, Modifier.testTag("continue-bar"), leading = {
                ToolbarIcon(R.drawable.ic_close, tr("关闭"), Modifier.testTag("continue-close"), onClick = onDismiss)
            })
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = Spacing.Row, end = Spacing.Row, bottom = Spacing.Row),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listBox(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    Spacer(Modifier.weight(1f))
                    sessions?.firstOrNull { it.name == picked }?.let { session ->
                        Text(tr("已选择"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        TmuxSessionCard(session, session.name in openTmux, home = home)
                    }
                    attachButton(Modifier.fillMaxWidth())
                }
            }
        } else {
            SheetHeader(heading, null, onDismiss)
            listBox(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.Page))
            attachButton(Modifier.fillMaxWidth().padding(horizontal = Spacing.Page, vertical = Spacing.Row))
        }
    }
    if (askPassword) CredentialsDialog(ConnectRequest(host, SessionMode.SHELL), { askPassword = false }) { password, passphrase ->
        askPassword = false
        login = PluginTarget(host, password, passphrase)
    }
    browser?.pending?.collectAsStateWithLifecycle()?.value?.let { PromptDialog(it) }
}
