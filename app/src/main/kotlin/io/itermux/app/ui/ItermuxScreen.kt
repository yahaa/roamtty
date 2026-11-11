package io.itermux.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.AppLocale
import io.itermux.app.ItermuxApp
import io.itermux.app.TerminalFonts
import io.itermux.app.appSettings
import io.itermux.app.data.logsInUnattended
import io.itermux.app.data.recentFirst
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's one screen: it collects the hosts, sessions and desktops, keeps where the user is in a [ScreenState],
 * and shows that page under its bar, with the drawer beside it and the open sheets and dialogs over it. What the
 * pages ask for is done by [ScreenActions].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItermuxScreen(
    app: ItermuxApp,
    service: SessionService?,
    pendingShareImages: List<Uri> = emptyList(),
    onShareImagesConsumed: () -> Unit = {},
    /** A host whose desktop the notification asked to show. */
    pendingDesktopHost: String? = null,
    onDesktopHostConsumed: () -> Unit = {},
    /** A session an agent notification asked to show. */
    pendingSession: String? = null,
    onSessionConsumed: () -> Unit = {},
    /** Terminal chrome, light theme, and whether the system's back gesture works while the bars are hidden (the desktop). */
    onChromeChange: (Boolean, Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val state = rememberScreenState()

    // What the screen shows: hosts, sessions with their connection states, desktops and images shared into the app.
    val hostFlow = remember(app) { app.database.hosts().observe() }
    val loadedHosts by hostFlow.collectAsStateWithLifecycle(initialValue = null)
    val recentUsage = app.recentUsage
    val recentHosts by recentUsage.hostIds.collectAsStateWithLifecycle()
    val recentSessions by recentUsage.sessionIds.collectAsStateWithLifecycle()
    // A state, not a remembered value: after a rotation the hosts load while an inner scope that reads loadedHosts
    // recomposes on its own, and a captured list would still be the empty one from before, sending the host page home.
    val hostsState = remember { derivedStateOf { loadedHosts.orEmpty().recentFirst(recentHosts) { it.id } } }
    val hosts by hostsState
    val sessionFlow = remember(service) { service?.sessions ?: MutableStateFlow(emptyList()) }
    val sessionsState = sessionFlow.collectAsStateWithLifecycle()
    val sessions by sessionsState
    val sessionStatesState = remember(sessions) {
        if (sessions.isEmpty()) flowOf(emptyMap())
        else combine(sessions.map { session -> session.state.map { session.id to it } }) { it.toMap() }
    }.collectAsStateWithLifecycle(emptyMap())
    val sessionStates by sessionStatesState
    val desktopFlow = remember(service) { service?.desktops?.connections ?: MutableStateFlow(emptyMap()) }
    val desktopsState = desktopFlow.collectAsStateWithLifecycle()
    val desktops by desktopsState
    val shareFlow = remember(service) { service?.pendingSharedImages ?: MutableStateFlow(emptyList()) }
    val shareUrisPending by shareFlow.collectAsStateWithLifecycle()
    // The terminal and file previews draw with the terminal font: load it while a session connects, not on its first frame.
    val anySession = sessions.isNotEmpty()
    LaunchedEffect(anySession) { if (anySession) TerminalFonts.preload(context) }
    val hostReachability = remember { HostReachabilityTracker() }
    // Recent use leads within each home group and in the drawer; terminal tabs keep their open order.
    val sessionsByRecency = remember(sessions, sessionStates, recentSessions) {
        sessions.sortedBy { activityRank(sessionStates[it.id] ?: it.state.value) }.recentFirst(recentSessions) { it.id }
    }
    LaunchedEffect(loadedHosts, sessions, service) {
        val savedHosts = loadedHosts ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        val restored = bound.sessions.value
        // A newly saved host can already have a session before Room emits its updated host list.
        recentUsage.retain((savedHosts.map { it.id } + restored.map { it.host.id }).toSet(), restored.map { it.id }.toSet())
    }

    val configuration = LocalConfiguration.current
    val landscape = isLandscape()
    val settings = remember(context) { context.appSettings() }
    var theme by remember { mutableStateOf(settings.appTheme()) }
    val lightTheme = theme == AppTheme.LIGHT ||
        (theme == AppTheme.SYSTEM && configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    /** The home page's tabs: sessions first, then hosts. */
    val homePager = rememberPagerState { 2 }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val desktopTargets = remember(context) { runCatching { DesktopSettings.manifest(context).targets.keys }.getOrDefault(emptySet()) }
    val boundService = rememberUpdatedState(service)
    val actions = remember(app, context, scope, state, homePager) {
        ScreenActions(app, context, scope, state, homePager, boundService, sessionsState, sessionStatesState, hostsState,
            desktopsState, desktopTargets, requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
    }

    val page = state.page
    val desktopVisible = page == AppPage.DESKTOP
    val terminalVisible = page == AppPage.TERMINAL && sessions.isNotEmpty()
    val navigationVisible = page == AppPage.HOME
    // The file browser wears the terminal's chrome: a transparent status bar and no navigation bar.
    val terminalChrome = terminalVisible || page == AppPage.FILES || desktopVisible
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalChrome, imeVisible, lightTheme, landscape, desktopVisible) { onChromeChange(terminalChrome, lightTheme, desktopVisible) }
    BackHandler(page == AppPage.SETTINGS && !drawerState.isOpen) { state.closeSettings() }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    // Only visible navigation counts as use. Reconnects, polling and recomposition never update this order.
    LaunchedEffect(page, state.selected, state.detailHostId, state.desktopHostId, service) {
        when (page) {
            AppPage.TERMINAL, AppPage.FILES -> {
                val bound = service ?: return@LaunchedEffect
                bound.awaitRestoration()
                bound.sessions.value.find { it.id == state.selected }?.let { recentUsage.useSession(it.id, it.host.id) }
            }
            AppPage.HOST -> state.detailHostId?.let(recentUsage::useHost)
            AppPage.DESKTOP -> state.desktopHostId?.let(recentUsage::useHost)
            else -> Unit
        }
    }
    // A failed desktop stays for its page to explain; once that page is left it goes, with its secrets.
    LaunchedEffect(page, state.desktopHostId, service) {
        val registry = service?.desktops ?: return@LaunchedEffect
        registry.connections.value.keys.filter { page != AppPage.DESKTOP || it != state.desktopHostId }.forEach(registry::dropEnded)
    }
    BackHandler(page == AppPage.TERMINAL) { actions.leaveTerminal() }
    LaunchedEffect(page) { if (page != AppPage.DESKTOP) state.desktopFromTerminal = false }
    LaunchedEffect(service, sessions, state.selected, page) {
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        if ((page == AppPage.TERMINAL || page == AppPage.FILES) && bound.sessions.value.none { it.id == state.selected }) state.page = AppPage.HOME
    }
    val hostGridState = rememberLazyGridState()
    // The empty page removes the grid, but its state still holds recycled rows and their session callbacks.
    // Drop that layout when the last session goes; there is no scroll position left to preserve.
    val sessionGridState = key(sessions.isEmpty()) { rememberLazyGridState() }
    var previousHostOrder by remember { mutableStateOf(recentHosts) }
    LaunchedEffect(recentHosts) {
        if (recentHosts != previousHostOrder) {
            hostGridState.requestScrollToItem(0)
            previousHostOrder = recentHosts
        }
    }
    BackHandler(page == AppPage.HOME && homePager.currentPage != 0 && !state.searchExpanded && !drawerState.isOpen) {
        scope.launch { homePager.animateScrollToPage(0) }
    }
    var previousLandscape by rememberSaveable { mutableStateOf(landscape) }
    LaunchedEffect(landscape) {
        if (landscape != previousLandscape) {
            hostGridState.scrollToItem(hostGridState.firstVisibleItemIndex)
            previousLandscape = landscape
        }
    }
    val searchFocus = remember { FocusRequester() }
    BackHandler(navigationVisible && state.searchExpanded && !imeVisible && !drawerState.isOpen) {
        state.searchExpanded = false
        state.search = ""
    }

    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    // The session in front of the user needs no notification.
    val viewing = state.selected.takeIf { page == AppPage.TERMINAL }
    LaunchedEffect(service, viewing) { service?.setViewing(viewing) }
    val onSessionsTab = page == AppPage.HOME && homePager.currentPage == 0 && resumed.isAtLeast(Lifecycle.State.RESUMED)
    ReconnectFromSessionList(onSessionsTab, service, sessions, hosts, hostReachability, state.autoReconnectedAt, actions::hostConnected,
        loginUnattended = { it.logsInUnattended(app.vault) }) { host, session ->
        actions.start(session.reconnectRequest(host), open = false)
    }
    // A small window whose desktop went away goes with it. Asked of the registry once the service is bound: right
    // after a rotation recreates the activity, the collected map is still empty though the desktop is there.
    LaunchedEffect(desktops, service) {
        val registry = service?.desktops ?: return@LaunchedEffect
        val id = state.pipDesktopHostId ?: return@LaunchedEffect
        if (registry[id] == null) state.pipDesktopHostId = null
    }
    LaunchedEffect(pendingSession, service) {
        val id = pendingSession ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        // A session closed since the notification came leaves the app where it was.
        if (bound.sessions.value.any { it.id == id }) state.showSession(id)
        onSessionConsumed()
    }
    LaunchedEffect(pendingDesktopHost, service) {
        val id = pendingDesktopHost ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        if (bound.desktops[id] != null) state.showDesktop(id)
        onDesktopHostConsumed()
    }
    LaunchedEffect(pendingShareImages, service) {
        if (pendingShareImages.isEmpty() || service == null) return@LaunchedEffect
        service.awaitRestoration()
        val uris = pendingShareImages.toList()
        actions.routeSharedImages(uris, service.sessions.value)
        onShareImagesConsumed()
    }
    LaunchedEffect(state.searchExpanded, page) { if (state.searchExpanded && navigationVisible) searchFocus.requestFocus() }

    MaterialTheme(colorScheme = if (lightTheme) LightColors else DarkColors, shapes = AppShapes, typography = AppTypography) {
        if (loadedHosts == null) {
            StartupLoading()
            return@MaterialTheme
        }
        val colors = MaterialTheme.colorScheme
        val closeDrawer = { scope.launch { drawerState.close() }; Unit }
        ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = !terminalVisible && page != AppPage.FILES && page != AppPage.HOST && !desktopVisible,
            drawerContent = {
                ItermuxDrawer(page, hosts.size, sessionsByRecency,
                    onHosts = { state.page = AppPage.HOME; homePager.requestScrollToPage(1); closeDrawer() },
                    onOpenSession = { state.showSession(it); closeDrawer() },
                    onSettings = { state.openSettings(); closeDrawer() },
                    onGuide = { closeDrawer(); state.showAbout = true },
                    onClose = closeDrawer)
            }) {
            // A session's host is offline when nothing connects to it and its last probe found it down.
            fun sessionHostOffline(session: TerminalConnection) = hosts.firstOrNull { it.id == session.host.id }
                ?.let { !actions.hostConnected(it) && hostReachability.statusOf(it) == HostReachability.Offline } == true
            val sessionGroups = rememberSessionGroups(sessionsMatching(sessionsByRecency, state.search), ::sessionHostOffline)
            val sessionFilter = rememberSaveable { mutableStateOf<String?>(null) }
            val lists = HomeLists(homePager, sessionGridState, hostGridState, sessionsByRecency, hosts, sessionGroups, sessionFilter,
                hostReachability, ::sessionHostOffline)
            // In landscape the home page has one short bar, which slides away as a list scrolls on; it stays while
            // the search is open.
            val compactHome = landscape && navigationVisible
            val homeListAtTop by remember(sessionGridState, hostGridState, homePager) {
                derivedStateOf {
                    (if (homePager.currentPage == 0) sessionGridState else hostGridState)
                        .let { it.firstVisibleItemIndex == 0 && it.firstVisibleItemScrollOffset == 0 }
                }
            }
            val barSlide = rememberHomeBarSlide(slides = compactHome && !state.searchExpanded, listAtTop = homeListAtTop)
            fun hideKeyboard() {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            fun toggleSearch() {
                if (state.searchExpanded) {
                    state.search = ""
                    hideKeyboard()
                }
                state.searchExpanded = !state.searchExpanded
            }
            val openDrawer = {
                hideKeyboard()
                scope.launch { drawerState.open() }
                Unit
            }
            Surface(Modifier.fillMaxSize(), color = colors.background) {
                // The terminal fills the display, including the area beside a camera cutout and under the
                // transparent portrait status bar. Only desktop window chrome reserves an edge; the terminal
                // keeps its text and controls clear of the status bar and cutouts itself.
                val contentInsets = if (terminalVisible) WindowInsets.captionBar else if (desktopVisible) WindowInsets(0) else WindowInsets.safeDrawing
                // The terminal applies the keyboard inset itself, in one step (see TerminalPane).
                val imeInsets = if (terminalVisible || desktopVisible) WindowInsets(0) else WindowInsets.ime
                Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).windowInsetsPadding(imeInsets)) {
                    val toolbarVisible = (!imeVisible || state.searchExpanded) && !terminalVisible && page != AppPage.FILES &&
                        page != AppPage.HOST && !desktopVisible
                    if (toolbarVisible) ScreenToolbar(state, lists, landscape, barSlide, ::toggleSearch, openDrawer)
                    when (page) {
                        AppPage.HOME -> HomePage(state, actions, lists, sessions, desktops, compactHome, barSlide, searchFocus)
                        AppPage.HOST -> {
                            val detailHost = hosts.firstOrNull { it.id == state.detailHostId }
                            if (detailHost != null) key(detailHost.id) {
                                HostPage(detailHost, state, actions, hostReachability.statusOf(detailHost),
                                    connected = sessions.any { it.host.id == detailHost.id && actions.connected(it) })
                            } else if (loadedHosts != null) LaunchedEffect(Unit) { state.page = AppPage.HOME } // deleted, or gone after a restore
                        }
                        AppPage.FILES -> FilesPage(sessions.find { it.id == state.selected }, state, actions)
                        AppPage.DESKTOP -> {
                            val connection = actions.desktopOf(state.desktopHostId)
                            if (connection != null) DesktopRoute(connection, state, actions,
                                returnsToTerminal = { state.desktopFromTerminal && sessions.any { it.id == state.selected } })
                            // A recreated activity (rotation) binds the service again first; only a desktop that is really gone goes home.
                            else if (service != null) LaunchedEffect(Unit) { state.page = AppPage.HOME }
                        }
                        AppPage.SETTINGS -> SettingsPage(theme, { option ->
                            theme = option
                            settings.saveAppTheme(option)
                        }, AppLocale.language, { option -> AppLocale.set(context, option); service?.refreshLanguage() })
                        AppPage.TERMINAL -> if (sessions.isEmpty()) {
                            EmptyState(tr("终端已就绪"), tr("连接一台主机，开始远端工作。"), Modifier.fillMaxSize(),
                                action = { FilledTonalButton(onClick = { state.page = AppPage.HOME }) { Text(tr("返回主机列表")) } })
                        } else {
                            sessions.find { it.id == state.selected }?.let { TerminalRoute(it, sessions, state, actions, lightTheme) }
                        }
                    }
                }
            }
        }
        ScreenDialogs(app, service, state, actions, hosts, sessions, shareUrisPending)
    }
}
