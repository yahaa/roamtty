package io.itermux.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import io.itermux.app.session.SessionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.DrawerState
import io.itermux.app.data.RecentUsage

/** What Back does on the screen's own pages; the pages over them add their own. */
@Composable
internal fun ScreenBackHandlers(state: ScreenState, actions: ScreenActions, drawerState: DrawerState, homePager: PagerState,
    imeVisible: Boolean) {
    val scope = rememberCoroutineScope()
    val page = state.page
    BackHandler(page == AppPage.SETTINGS && !drawerState.isOpen) { state.closeSettings() }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(page == AppPage.TERMINAL) { actions.leaveTerminal() }
    BackHandler(page == AppPage.HOME && homePager.currentPage != 0 && !state.searchExpanded && !drawerState.isOpen) {
        scope.launch { homePager.animateScrollToPage(0) }
    }
    BackHandler(page == AppPage.HOME && state.searchExpanded && !imeVisible && !drawerState.isOpen) {
        state.searchExpanded = false
        state.search = ""
    }
}

/** Keeps where the user is in step with what exists: recent use, ended desktops, closed sessions, the viewed session. */
@Composable
internal fun ScreenNavigationEffects(state: ScreenState, service: SessionService?, data: ScreenData, recentUsage: RecentUsage) {
    val page = state.page
    val sessions = data.sessions
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
    LaunchedEffect(page) { if (page != AppPage.DESKTOP) state.desktopFromTerminal = false }
    LaunchedEffect(service, sessions, state.selected, page) {
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        if ((page == AppPage.TERMINAL || page == AppPage.FILES) && bound.sessions.value.none { it.id == state.selected }) state.page = AppPage.HOME
    }
    // The session in front of the user needs no notification.
    val viewing = state.selected.takeIf { page == AppPage.TERMINAL }
    LaunchedEffect(service, viewing) { service?.setViewing(viewing) }
    // A small window whose desktop went away goes with it. Asked of the registry once the service is bound: right
    // after a rotation recreates the activity, the collected map is still empty though the desktop is there.
    LaunchedEffect(data.desktops, service) {
        val registry = service?.desktops ?: return@LaunchedEffect
        val id = state.pipDesktopHostId ?: return@LaunchedEffect
        if (registry[id] == null) state.pipDesktopHostId = null
    }
}

/** What the activity was started to show: a session or a desktop from a notification, images shared into the app. */
internal class ScreenRequests(
    val shareImages: List<Uri>,
    val onShareImagesConsumed: () -> Unit,
    val desktopHost: String?,
    val onDesktopHostConsumed: () -> Unit,
    val session: String?,
    val onSessionConsumed: () -> Unit,
)

/** Answers each of [requests] once the service is bound and its sessions are restored. */
@Composable
internal fun ScreenRequestEffects(requests: ScreenRequests, state: ScreenState, actions: ScreenActions, service: SessionService?) {
    LaunchedEffect(requests.session, service) {
        val id = requests.session ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        bound.awaitRestoration()
        // A session closed since the notification came leaves the app where it was.
        if (bound.sessions.value.any { it.id == id }) state.showSession(id)
        requests.onSessionConsumed()
    }
    LaunchedEffect(requests.desktopHost, service) {
        val id = requests.desktopHost ?: return@LaunchedEffect
        val bound = service ?: return@LaunchedEffect
        if (bound.desktops[id] != null) state.showDesktop(id)
        requests.onDesktopHostConsumed()
    }
    LaunchedEffect(requests.shareImages, service) {
        if (requests.shareImages.isEmpty() || service == null) return@LaunchedEffect
        service.awaitRestoration()
        val uris = requests.shareImages.toList()
        actions.routeSharedImages(uris, service.sessions.value)
        requests.onShareImagesConsumed()
    }
}
