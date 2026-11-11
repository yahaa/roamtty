package io.itermux.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.desktop.DesktopPhase
import io.itermux.desktop.DesktopProbe
import io.itermux.ssh.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** What the home page lists and how its two tabs scroll, shared by its bar and its tabs. */
internal class HomeLists(
    val pager: PagerState,
    val sessionGrid: LazyGridState,
    val hostGrid: LazyGridState,
    /** Sessions with recent use first, as the sessions tab and the drawer list them. */
    val sessions: List<TerminalConnection>,
    val hosts: List<Host>,
    val groups: List<ListedGroup>,
    /** The group the sessions tab is narrowed to, by name; null for all. */
    val filter: MutableState<String?>,
    val reachability: HostReachabilityTracker,
    val sessionHostOffline: (TerminalConnection) -> Boolean,
)

/**
 * The bar above the home page and the settings: the short sliding one in landscape ([barSlide], with the tabs, the
 * session filters and the page's actions in it), else the tall one with the drawer, the tabs and the search.
 */
@Composable
internal fun ScreenToolbar(state: ScreenState, lists: HomeLists, landscape: Boolean, barSlide: HomeBarSlide,
    onSelectTab: (Int) -> Unit, onToggleSearch: () -> Unit, onOpenDrawer: () -> Unit) {
    val home = state.page == AppPage.HOME
    val tab = lists.pager.currentPage
    val titles = listOf(tr("会话"), tr("主机"))
    val searchDescription = tr(if (tab == 0) "搜索会话" else "搜索主机")
    if (landscape && home) HomeCompactBar(titles, listOf(lists.sessions.size, lists.hosts.size), tab, onSelect = onSelectTab,
        leading = { ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo"), onClick = onOpenDrawer) },
        middle = {
            if (tab == 0 && lists.groups.size > 1)
                SessionFilters(lists.groups, lists.groups.sumOf { it.members.size },
                    filteredGroup(lists.groups, lists.filter.value)) { lists.filter.value = it?.name }
        },
        trailing = {
            AppSearchToggle(state.searchExpanded, onToggle = onToggleSearch, searchDescription = searchDescription,
                modifier = Modifier.testTag("search-toggle"))
            if (tab == 0) ToolbarIcon(R.drawable.ic_add, tr("新建会话"), Modifier.testTag("add-session")) { state.newSessionOpen = true }
            else ToolbarIcon(R.drawable.ic_add, tr("添加主机"), Modifier.testTag("add-host")) { state.editHost(null) }
        },
        modifier = barSlide.modifier)
    // Settings in landscape: the flat bar of every other landscape page, the drawer at its start.
    else if (landscape && state.page == AppPage.SETTINGS) LandscapeBar(tr("设置"), leading = {
        ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo"), onClick = onOpenDrawer)
    }) {
        ToolbarIcon(R.drawable.ic_close, tr("关闭设置"), Modifier.testTag("close-settings"), onClick = state::closeSettings)
    }
    else AppToolbar(
        navigation = {
            // As wide as the search on the right, so the home tabs sit in the middle of the screen.
            Box(Modifier.width(48.dp)) {
                ToolbarIcon(R.drawable.ic_sidebar, tr("打开导航"), Modifier.testTag("brand-logo"), onClick = onOpenDrawer)
            }
        },
        actions = {
            if (home) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HomeTabs(titles, tab, onSelect = onSelectTab) }
                AppSearchToggle(state.searchExpanded, onToggle = onToggleSearch, searchDescription = searchDescription,
                    modifier = Modifier.testTag("search-toggle"))
            } else {
                Spacer(Modifier.weight(1f))
                ToolbarIcon(R.drawable.ic_close, tr("关闭设置"), Modifier.testTag("close-settings"), onClick = state::closeSettings)
            }
        })
}

/** The home page: the search field while it is open, then the sessions and hosts tabs side by side. */
@Composable
internal fun HomePage(state: ScreenState, actions: ScreenActions, lists: HomeLists, sessions: List<TerminalConnection>,
    desktops: Map<String, DesktopConnection>, compact: Boolean, barSlide: HomeBarSlide, searchFocus: FocusRequester) {
    val hosts = lists.hosts
    val reachability = lists.reachability
    Column(Modifier.fillMaxSize()) {
        if (state.searchExpanded) {
            AppSearchField(state.search, { state.search = it },
                tr(if (lists.pager.currentPage == 0) "标题、主机、地址或用户名" else "名称、地址或用户名"),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).padding(bottom = Spacing.Tight)
                    .focusRequester(searchFocus).testTag("host-search"))
        }
        // A live terminal or desktop already proves the host is up. Match by address:port so several
        // host cards pointing at the same server skip the unauthenticated SSH greeting probe together.
        val liveTargets = buildSet {
            sessions.filter(actions::connected).forEach { add("${it.host.address}:${it.host.port}") }
            desktops.values.forEach { desktop ->
                // Only a desktop streaming now: connecting or recovering proves nothing about the host.
                if (desktop.phase.value is DesktopPhase.Connected) add("${desktop.host.address}:${desktop.host.port}")
            }
        }
        ProbeHostReachability(reachability, hosts,
            connected = hosts.filter { "${it.address}:${it.port}" in liveTargets }.map { it.id }.toSet())
        HorizontalPager(lists.pager, Modifier.weight(1f).nestedScroll(barSlide.connection).testTag("home-pager"),
            key = { HOME_TAB_TAGS[it] }) { tab ->
            if (tab == 0) HomeSessionsTab(lists.sessions, hosts.isNotEmpty(), state.search,
                onNewSession = { state.newSessionOpen = true },
                onOpen = { state.showSession(it.id) },
                onClose = actions::closeSession,
                onForget = actions::forceDelete,
                hostOffline = lists.sessionHostOffline,
                groups = lists.groups,
                filter = lists.filter,
                compact = compact,
                gridState = lists.sessionGrid,
                desktop = { session ->
                    val host = actions.savedHost(session.host)
                    val idle = remember { MutableStateFlow<DesktopPhase>(DesktopPhase.Idle) }
                    val desktopPhase by (desktops[host.id]?.phase ?: idle).collectAsStateWithLifecycle()
                    val sessionState by session.state.collectAsStateWithLifecycle()
                    val offline = !actions.hostConnected(host) && reachability.statusOf(host) == HostReachability.Offline
                    val button = actions.desktopButton(host, !offline, sessionState.phase == Phase.CONNECTED, desktopPhase)
                    DesktopCardButton(button, "home-session-desktop-${session.id}") { actions.openDesktop(host) }
                })
            else HomeHostsTab(hosts, state.search, lists.hostGrid,
                reachability = { host ->
                    reachability.statusOf(host).let { probed ->
                        if ("${host.address}:${host.port}" in liveTargets && probed !is HostReachability.Online) HostReachability.Online(null) else probed
                    }
                },
                onAdd = { state.editHost(null) },
                onOpen = { host -> state.showHost(host.id) },
                onEdit = { host -> state.editHost(host.id) },
                onDelete = { host -> state.deleteHost = host },
                onBrowseFiles = actions::browseFiles,
                onManagePlugins = actions::managePlugins,
                onLaunch = { host, forAgent -> state.launchRequest = LaunchRequest(host, forAgent) },
                desktop = { host ->
                    val idle = remember { MutableStateFlow<DesktopPhase>(DesktopPhase.Idle) }
                    val desktopPhase by (desktops[host.id]?.phase ?: idle).collectAsStateWithLifecycle()
                    // A host needs no open session for its desktop: the desktop logs in on its own. An
                    // offline one keeps its picture, so it is derived as if online; its card knows better.
                    actions.desktopButton(host, online = true, sessionConnected = true, desktopPhase)
                },
                onDesktop = actions::openDesktop,
                onDisconnectDesktop = { host -> actions.closeDesktop(host.id) },
                compact = compact)
        }
    }
}

/** One host's detail page; [reachability] is what the host list last probed. */
@Composable
internal fun ColumnScope.HostPage(host: Host, state: ScreenState, actions: ScreenActions, reachability: HostReachability,
    connected: Boolean) {
    HostDetailPage(host,
        if (connected && reachability !is HostReachability.Online) HostReachability.Online(null) else reachability,
        onBack = { state.page = AppPage.HOME },
        onLaunch = { directory, agent -> actions.launchOn(host, directory, agent) },
        onBrowseFiles = { actions.browseFiles(host) },
        onEdit = { state.editHost(host.id) },
        onManagePlugins = { actions.managePlugins(host) },
        onDelete = { state.deleteHost = host },
        modifier = Modifier.weight(1f),
        onDesktop = { actions.openDesktop(host) }.takeIf {
            actions.desktopSupported(host) && DesktopProbe.decode(host.desktop) != DesktopProbe.NO
        })
}

/** The SFTP browser of [session], or what to do about a session that is gone. */
@Composable
internal fun ColumnScope.FilesPage(session: TerminalConnection?, state: ScreenState, actions: ScreenActions) {
    if (session != null) {
        key(session) {
            // Its back arrow says 返回主机详情: the files return to their host's page, as on iOS.
            RemoteFileBrowserPage(session, onBack = actions::showSessionHost, Modifier.weight(1f),
                // A new terminal session (tmux when the host has it) so the agent outlives the browser.
                onLaunchAgent = { launch -> actions.begin(launchRequest(actions.savedHost(session.host), launch.directory, launch)) },
                onOpenTerminal = { directory -> actions.launchOn(actions.savedHost(session.host), directory, null) })
        }
    } else {
        EmptyState(tr("会话不存在"), tr("连接已关闭，请从主机列表重新打开文件。"), Modifier.fillMaxSize(),
            action = { FilledTonalButton(onClick = { state.page = AppPage.HOME }) { Text(tr("返回主机列表")) } })
    }
}

/** A host's desktop page; [returnsToTerminal] when it was opened from the terminal's small window and that terminal is still there. */
@Composable
internal fun ColumnScope.DesktopRoute(connection: DesktopConnection, state: ScreenState, actions: ScreenActions,
    returnsToTerminal: () -> Boolean) {
    key(connection) {
        // The buttons capture the host, not the connection: Compose keeps the last tapped node (and its
        // lambda) until the next touch, which would hold a closed desktop's buffers till then.
        val desktopHost = connection.host
        DesktopPage(connection,
            // Opened from the terminal's small window: back to that terminal, the desktop small again.
            onLeave = { state.page = if (returnsToTerminal()) AppPage.TERMINAL else AppPage.HOME },
            onDisconnect = { actions.closeDesktop(desktopHost.id); state.page = AppPage.HOME },
            onFiles = { actions.browseFiles(actions.savedHost(desktopHost)) },
            modifier = Modifier.weight(1f))
    }
}

/** The terminal of [active], which reconnects by itself when it was dropped or restored; [sessions] are its tabs. */
@Composable
internal fun ColumnScope.TerminalRoute(active: TerminalConnection, sessions: List<TerminalConnection>, state: ScreenState,
    actions: ScreenActions, lightTheme: Boolean) {
    // Reconnecting preserves the journal id but replaces the transport and emulator.
    key(active) {
        // The saved host, not the session's copy: a password saved since then spares a prompt.
        val host = actions.savedHost(active.host)
        val reconnect = { actions.begin(active.reconnectRequest(actions.savedHost(active.host))) }
        val activeState by active.state.collectAsStateWithLifecycle()
        val autoReconnect = reconnectsOnOpen(activeState)
        // Set once the automatic attempt left this entry disconnected, e.g. a dismissed password prompt.
        var autoTried by remember { mutableStateOf(false) }
        LaunchedEffect(autoReconnect) {
            if (!autoReconnect) return@LaunchedEffect
            val last = state.autoReconnectedAt[active.id]
            if (last != null) delay(last + AUTO_RECONNECT_INTERVAL_MS - SystemClock.elapsedRealtime())
            state.autoReconnectedAt[active.id] = SystemClock.elapsedRealtime()
            autoTried = true
            reconnect()
        }
        val desktopReady = actions.desktopReady(host)
        TerminalPane(active, Modifier.weight(1f),
            sessions = sessions, lightTheme = lightTheme, onSelectSession = { state.selected = it },
            onHostDetail = actions::showSessionHost,
            onLeave = actions::leaveTerminal,
            onReconnect = reconnect.takeUnless { autoReconnect && !autoTried },
            onRestart = { actions.restartSession(active) },
            onShell = { actions.switchToShell(active) },
            onReverify = { state.reverify = active },
            // Closing ends the terminal page too: back to where it was opened from.
            onClose = { actions.leaveTerminal(); actions.closeSession(active) },
            // A tap opens the desktop small over the terminal (or closes it); a long press opens its page.
            onDesktop = { actions.toggleDesktopPip(host) }.takeIf { desktopReady },
            pip = TerminalPip(desktop = state.pipDesktopHostId?.takeIf { it == host.id }?.let(actions::desktopOf),
                onTap = { state.showDesktopFromSmallWindow(host.id) }, onClose = { state.pipDesktopHostId = null },
                onFull = {
                    if (state.pipDesktopHostId == host.id && actions.desktopOf(host.id) != null) state.showDesktopFromSmallWindow(host.id)
                    else actions.openDesktop(host)
                }.takeIf { desktopReady }))
    }
}
