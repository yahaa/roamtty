package io.itermux.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
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
import io.itermux.app.AppLocale
import io.itermux.app.ItermuxApp
import io.itermux.app.appSettings
import io.itermux.app.data.logsInUnattended
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DrawerState
import io.itermux.app.data.Host

/**
 * The app's one screen: it collects the hosts, sessions and desktops ([ScreenData]), keeps where the user is in a
 * [ScreenState], and shows that page under its bar, with the drawer beside it and the open sheets and dialogs over
 * it. What the pages ask for is done by [ScreenActions].
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
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val state = rememberScreenState()
    val data = rememberScreenData(app, service)
    val sessions = data.sessions

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
        ScreenActions(app, context, scope, state, homePager, boundService, data.sessionsState, data.sessionStatesState,
            data.hostsState, data.desktopsState, data.voicesState, desktopTargets, requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
    }

    val page = state.page
    val desktopVisible = page == AppPage.DESKTOP
    val terminalVisible = page == AppPage.TERMINAL && sessions.isNotEmpty()
    // The file browser wears the terminal's chrome: a transparent status bar and no navigation bar.
    val terminalChrome = terminalVisible || page == AppPage.FILES || desktopVisible
    // Older IMEs reveal system bars without changing window focus. Restore immersive mode on close.
    LaunchedEffect(terminalChrome, imeVisible, lightTheme, landscape, desktopVisible) { onChromeChange(terminalChrome, lightTheme, desktopVisible) }
    ScreenBackHandlers(state, actions, drawerState, homePager, imeVisible)
    ScreenNavigationEffects(state, service, data, app.recentUsage)
    val (sessionGridState, hostGridState) = rememberHomeGrids(data, landscape)

    val hostReachability = remember { HostReachabilityTracker() }
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val onSessionsTab = page == AppPage.HOME && homePager.currentPage == 0 && resumed.isAtLeast(Lifecycle.State.RESUMED)
    ReconnectFromSessionList(onSessionsTab, service, sessions, data.hosts, hostReachability, state.autoReconnectedAt, actions::hostConnected,
        loginUnattended = { it.logsInUnattended(app.vault) }) { host, session ->
        actions.start(session.reconnectRequest(host), open = false)
    }
    ScreenRequestEffects(ScreenRequests(pendingShareImages, onShareImagesConsumed, pendingDesktopHost, onDesktopHostConsumed,
        pendingSession, onSessionConsumed), state, actions, service)
    // Surface voice bridge failures reliably even when VoiceRegistry immediately cleans up the ended connection.
    LaunchedEffect(service) {
        service?.voices?.failures?.collect { failure ->
            if (failure.message.isNotBlank()) actions.toast(failure.message)
        }
    }

    MaterialTheme(colorScheme = if (lightTheme) LightColors else DarkColors, shapes = AppShapes, typography = AppTypography) {
        if (data.loadedHosts == null) {
            StartupLoading()
            return@MaterialTheme
        }
        // A session's host is offline when nothing connects to it and its last probe found it down.
        fun sessionHostOffline(session: TerminalConnection) = data.hosts.firstOrNull { it.id == session.host.id }
            ?.let { !actions.hostConnected(it) && hostReachability.statusOf(it) == HostReachability.Offline } == true
        val sessionGroups = rememberSessionGroups(sessionsMatching(data.sessionsByRecency, state.search), ::sessionHostOffline)
        val sessionFilter = rememberSaveable { mutableStateOf<String?>(null) }
        val lists = HomeLists(homePager, sessionGridState, hostGridState, data.sessionsByRecency, data.hosts, sessionGroups,
            sessionFilter, hostReachability, ::sessionHostOffline)
        ScreenDrawer(state, drawerState, lists, gesturesEnabled = !terminalChrome && page != AppPage.HOST) {
            ScreenBody(state, actions, data, lists, service, drawerState, lightTheme, SettingsControls(theme, onTheme = { option ->
                theme = option
                settings.saveAppTheme(option)
            }, onLanguage = { option -> AppLocale.set(context, option); service?.refreshLanguage() }))
        }
        ScreenDialogs(app, service, state, actions, data.hosts, sessions, data.sharedImages)
    }
}

/** The drawer beside the pages: the hosts, the open sessions, settings and the guide. */
@Composable
private fun ScreenDrawer(state: ScreenState, drawerState: DrawerState, lists: HomeLists, gesturesEnabled: Boolean,
    content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val closeDrawer = { scope.launch { drawerState.close() }; Unit }
    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = gesturesEnabled, drawerContent = {
        ItermuxDrawer(state.page, lists.hosts.size, lists.sessions,
            onHosts = { state.page = AppPage.HOME; lists.pager.requestScrollToPage(1); closeDrawer() },
            onOpenSession = { state.showSession(it); closeDrawer() },
            onSettings = { state.openSettings(); closeDrawer() },
            onGuide = { closeDrawer(); state.showAbout = true },
            onClose = closeDrawer)
    }, content = content)
}

/** What the settings page shows and changes. */
private class SettingsControls(val theme: AppTheme, val onTheme: (AppTheme) -> Unit, val onLanguage: (io.itermux.app.AppLanguage) -> Unit)

/** The page the user is on, under the home page's bar, within the insets that page wants. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenBody(state: ScreenState, actions: ScreenActions, data: ScreenData, lists: HomeLists, service: SessionService?,
    drawerState: DrawerState, lightTheme: Boolean, settings: SettingsControls) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val landscape = isLandscape()
    val page = state.page
    val navigationVisible = page == AppPage.HOME
    val desktopVisible = page == AppPage.DESKTOP
    val terminalVisible = page == AppPage.TERMINAL && data.sessions.isNotEmpty()
    // In landscape the home page has one short bar, which slides away as a list scrolls on; it stays while
    // the search is open.
    val compactHome = landscape && navigationVisible
    val homeListAtTop by remember(lists.sessionGrid, lists.hostGrid, lists.pager) {
        derivedStateOf {
            (if (lists.pager.currentPage == 0) lists.sessionGrid else lists.hostGrid)
                .let { it.firstVisibleItemIndex == 0 && it.firstVisibleItemScrollOffset == 0 }
        }
    }
    val barSlide = rememberHomeBarSlide(slides = compactHome && !state.searchExpanded, listAtTop = homeListAtTop)
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(state.searchExpanded, page) { if (state.searchExpanded && navigationVisible) searchFocus.requestFocus() }
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The terminal fills the display, including the area beside a camera cutout and under the
        // transparent portrait status bar. Only desktop window chrome reserves an edge; the terminal
        // keeps its text and controls clear of the status bar and cutouts itself.
        val contentInsets = if (terminalVisible) WindowInsets.captionBar else if (desktopVisible) WindowInsets(0) else WindowInsets.safeDrawing
        // The terminal applies the keyboard inset itself, in one step (see TerminalPane).
        val imeInsets = if (terminalVisible || desktopVisible) WindowInsets(0) else WindowInsets.ime
        Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets).windowInsetsPadding(imeInsets)) {
            val toolbarVisible = (!imeVisible || state.searchExpanded) && !terminalVisible && page != AppPage.FILES &&
                page != AppPage.HOST && !desktopVisible
            if (toolbarVisible) ScreenToolbar(state, lists, landscape, barSlide,
                // The screen's scope: the bar may leave (the keyboard opening) while the pager still moves.
                onSelectTab = { scope.launch { lists.pager.animateScrollToPage(it) } }, ::toggleSearch, openDrawer)
            when (page) {
                AppPage.HOME -> HomePage(state, actions, lists, data.sessions, data.desktops, compactHome, barSlide, searchFocus)
                AppPage.HOST -> HostRoute(data.hosts.firstOrNull { it.id == state.detailHostId }, state, actions, lists, data.sessions)
                AppPage.FILES -> FilesPage(data.sessions.find { it.id == state.selected }, state, actions)
                AppPage.DESKTOP -> {
                    val connection = actions.desktopOf(state.desktopHostId)
                    if (connection != null) DesktopRoute(connection, state, actions,
                        returnsToTerminal = { state.desktopFromTerminal && data.sessions.any { it.id == state.selected } })
                    // A recreated activity (rotation) binds the service again first; only a desktop that is really gone goes home.
                    else if (service != null) LaunchedEffect(Unit) { state.page = AppPage.HOME }
                }
                AppPage.SETTINGS -> SettingsPage(settings.theme, settings.onTheme, AppLocale.language, settings.onLanguage)
                AppPage.TERMINAL -> if (data.sessions.isEmpty()) {
                    EmptyState(tr("终端已就绪"), tr("连接一台主机，开始远端工作。"), Modifier.fillMaxSize(),
                        action = { FilledTonalButton(onClick = { state.page = AppPage.HOME }) { Text(tr("返回主机列表")) } })
                } else {
                    data.sessions.find { it.id == state.selected }?.let { TerminalRoute(it, data.sessions, state, actions, lightTheme) }
                }
            }
        }
    }
}

/** The page of [host]; a host that was deleted, or is gone after a restore, sends the screen home. */
@Composable
private fun ColumnScope.HostRoute(host: Host?, state: ScreenState, actions: ScreenActions, lists: HomeLists,
    sessions: List<TerminalConnection>) {
    if (host != null) key(host.id) {
        HostPage(host, state, actions, lists.reachability.statusOf(host),
            connected = sessions.any { it.host.id == host.id && actions.connected(it) })
    } else LaunchedEffect(Unit) { state.page = AppPage.HOME }
}
