package io.itermux.app.ui

import android.net.Uri
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.TerminalFonts
import io.itermux.app.data.recentFirst
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.State
import io.itermux.app.data.Host
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.voice.VoiceConnection
import io.itermux.ssh.ConnectionState

/**
 * What the screen shows: hosts, sessions with their connection states, desktops and images shared into the app.
 * The states are handed on as states: [ScreenActions] reads them when an action runs, not when it was made.
 */
internal class ScreenData(
    /** The saved hosts; null until the database has answered. */
    val loadedHosts: List<Host>?,
    /** The saved hosts with recent use first. */
    val hostsState: State<List<Host>>,
    val sessionsState: State<List<TerminalConnection>>,
    val sessionStatesState: State<Map<String, ConnectionState>>,
    val desktopsState: State<Map<String, DesktopConnection>>,
    val voicesState: State<Map<String, VoiceConnection>>,
    val sharedImages: List<Uri>,
    val recentHosts: List<String>,
    /** Recent use leads within each home group and in the drawer; terminal tabs keep their open order. */
    val sessionsByRecency: List<TerminalConnection>,
) {
    val hosts: List<Host> get() = hostsState.value
    val sessions: List<TerminalConnection> get() = sessionsState.value
    val desktops: Map<String, DesktopConnection> get() = desktopsState.value
    val voices: Map<String, VoiceConnection> get() = voicesState.value
}

@Composable
internal fun rememberScreenData(app: ItermuxApp, service: SessionService?): ScreenData {
    val context = LocalContext.current
    val hostFlow = remember(app) { app.database.hosts().observe() }
    val loadedHosts by hostFlow.collectAsStateWithLifecycle(initialValue = null)
    val recentUsage = app.recentUsage
    val recentHosts by recentUsage.hostIds.collectAsStateWithLifecycle()
    val recentSessions by recentUsage.sessionIds.collectAsStateWithLifecycle()
    // A state, not a remembered value: after a rotation the hosts load while an inner scope that reads loadedHosts
    // recomposes on its own, and a captured list would still be the empty one from before, sending the host page home.
    val hostsState = remember { derivedStateOf { loadedHosts.orEmpty().recentFirst(recentHosts) { it.id } } }
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
    val voiceFlow = remember(service) { service?.voices?.connections ?: MutableStateFlow(emptyMap()) }
    val voicesState = voiceFlow.collectAsStateWithLifecycle()
    val shareFlow = remember(service) { service?.pendingSharedImages ?: MutableStateFlow(emptyList()) }
    val sharedImages by shareFlow.collectAsStateWithLifecycle()
    // The terminal and file previews draw with the terminal font: load it while a session connects, not on its first frame.
    val anySession = sessions.isNotEmpty()
    LaunchedEffect(anySession) { if (anySession) TerminalFonts.preload(context) }
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
    return ScreenData(loadedHosts, hostsState, sessionsState, sessionStatesState, desktopsState, voicesState, sharedImages, recentHosts,
        sessionsByRecency)
}

/** The home page's grids: the sessions' and the hosts'. */
@Composable
internal fun rememberHomeGrids(data: ScreenData, landscape: Boolean): Pair<LazyGridState, LazyGridState> {
    val hostGridState = rememberLazyGridState()
    // The empty page removes the grid, but its state still holds recycled rows and their session callbacks.
    // Drop that layout when the last session goes; there is no scroll position left to preserve.
    val sessionGridState = key(data.sessions.isEmpty()) { rememberLazyGridState() }
    val recentHosts = data.recentHosts
    var previousHostOrder by remember { mutableStateOf(recentHosts) }
    LaunchedEffect(recentHosts) {
        if (recentHosts != previousHostOrder) {
            hostGridState.requestScrollToItem(0)
            previousHostOrder = recentHosts
        }
    }
    var previousLandscape by rememberSaveable { mutableStateOf(landscape) }
    LaunchedEffect(landscape) {
        if (landscape != previousLandscape) {
            hostGridState.scrollToItem(hostGridState.firstVisibleItemIndex)
            previousLandscape = landscape
        }
    }
    return sessionGridState to hostGridState
}
