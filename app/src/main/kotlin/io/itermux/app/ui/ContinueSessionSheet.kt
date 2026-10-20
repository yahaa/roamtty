package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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

/**
 * The tmux sessions already running on [host]; tapping one opens it ([onAttach]), [openTmux] being those that already
 * have a card. Lists them over a short-lived login of the sheet's own, asking for a typed password only when tapped.
 * With none running it offers a fresh terminal ([onNewTerminal]); without tmux, the host's plugins ([onInstall]).
 */
@Composable
internal fun ContinueSessionSheet(host: Host, openTmux: Set<String>, onDismiss: () -> Unit, onNewTerminal: () -> Unit,
    onInstall: () -> Unit, onAttach: (name: String) -> Unit) {
    val app = LocalContext.current.applicationContext as ItermuxApp
    val scope = rememberCoroutineScope()
    var login by remember(host.loginKey) { mutableStateOf(if (!host.needsTypedPassword) PluginTarget(host) else null) }
    var askPassword by remember { mutableStateOf(false) }
    val browser = login?.let { target -> remember(target) { HostMonitor(app, target, scope, sampleMetrics = false).also { it.start() } } }
    browser?.let { DisposableEffect(it) { onDispose { it.close() } } }
    val ready = browser?.ready?.collectAsStateWithLifecycle()?.value == true
    val connection = browser?.connection?.collectAsStateWithLifecycle()?.value

    var tmux by remember { mutableStateOf<TmuxListing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var home by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ready, retry) {
        if (!ready) return@LaunchedEffect
        loading = true
        try {
            // Only to show the sessions' folders as `~/…`; first, so the list does not change under the finger.
            if (home == null) home = browser.listFolders(StartDirectory.HOME)?.home
            tmux = browser.listTmuxSessions()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { tmux = TmuxListing.Failed }
        finally { loading = false }
    }

    AppSheet(onDismiss, fullScreen = true) {
        SheetHeader(trf("继续会话 · %s", host.name), null, onDismiss)
        Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.Page).padding(bottom = Spacing.Page)
            .testTag("continue-sheet"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Spacing.Page, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("服务器上正在运行的会话"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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
                        listing is TmuxListing.Sessions -> TmuxSessionList(listing.sessions, picked = null, openTmux, loading, home) {
                            onAttach(it)
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
    if (askPassword) CredentialsDialog(ConnectRequest(host, SessionMode.SHELL), { askPassword = false }) { password, passphrase ->
        askPassword = false
        login = PluginTarget(host, password, passphrase)
    }
    browser?.pending?.collectAsStateWithLifecycle()?.value?.let { PromptDialog(it) }
}
