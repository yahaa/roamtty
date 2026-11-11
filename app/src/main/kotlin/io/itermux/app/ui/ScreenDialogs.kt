package io.itermux.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.desktop.DesktopThumbnails
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.SessionService
import io.itermux.app.session.StartDirectory
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.launch

/** The sheets and dialogs [state] has open, over whichever page shows. */
@Composable
internal fun ScreenDialogs(app: ItermuxApp, service: SessionService?, state: ScreenState, actions: ScreenActions,
    hosts: List<Host>, sessions: List<TerminalConnection>, sharedImages: List<android.net.Uri>) {
    val scope = rememberCoroutineScope()
    if (state.editorOpen) {
        val editHost = state.editHostId?.let { id -> hosts.firstOrNull { it.id == id } }
        if (state.editHostId == null || editHost != null)
            HostEditor(app, editHost, onDismiss = state::closeEditor, onSaved = { state.closeEditor() })
    }
    NewSessionFlow(app, state, actions, hosts, sessions)
    state.deleteHost?.let { host ->
        DeleteHostDialog(host, onDismiss = { state.deleteHost = null }) {
            scope.launch {
                // Keep remote tmux running; only drop the phone's links (and any desktop keep-alive).
                sessions.filter { it.host.id == host.id }.forEach { service?.disconnectLocal(it.id, forgetCard = true) }
                actions.closeDesktop(host.id)
                app.database.hosts().delete(host.id)
                DesktopThumbnails.remove(app, host.id)
                PreviewStorage.forget(app, host.id)
                state.deleteHost = null
            }
        }
    }
    state.connectRequest?.let { request ->
        CredentialsDialog(request, { state.connectRequest = null }) { password, phrase -> actions.continueWith(request, password, phrase) }
    }
    state.terminatePrompt?.let { PromptDialog(it) }
    state.pluginTarget?.let { target ->
        val controller = remember(target) { HostPluginsController(app, target, scope).also { it.start() } }
        DisposableEffect(controller) { onDispose { controller.close() } }
        HostPluginsSheet(controller) { state.pluginTarget = null }
    }
    state.reverify?.let { session ->
        val endpoint = session.host.target().endpoint
        ReverifyHostDialog(endpoint, onDismiss = { state.reverify = null }) {
            scope.launch {
                // Close local links only; remote tmux must keep running across a fingerprint re-check.
                sessions.filter { it.host.target().endpoint == endpoint }.forEach {
                    service?.disconnectLocal(it.id, forgetCard = it.mode != SessionMode.TMUX || it.external)
                }
                service?.desktops?.connections?.value?.values
                    ?.filter { it.host.target().endpoint == endpoint }
                    ?.map { it.host.id }?.distinct()
                    ?.forEach(actions::closeDesktop)
                app.database.hosts().forgetPin(endpoint)
                state.reverify = null
                actions.connect(session.host, session.mode, session.title.value,
                    reuseSessionId = session.id.takeIf { session.mode == SessionMode.TMUX })
            }
        }
    }
    if (sharedImages.isNotEmpty()) {
        ShareTargetSheet(sessions.filter { it.state.value.phase == Phase.CONNECTED }, actions::dismissSharedImages) { session ->
            actions.dismissSharedImages()
            actions.uploadSharedImages(session, sharedImages)
        }
    }
    if (state.showAbout) AboutDialog(LocalContext.current) { state.showAbout = false }
}

/**
 * A new session, step by step: what runs and on which host (or a host to add first, or a tmux session already on
 * one to continue), then the folder it starts in.
 */
@Composable
private fun NewSessionFlow(app: ItermuxApp, state: ScreenState, actions: ScreenActions, hosts: List<Host>,
    sessions: List<TerminalConnection>) {
    state.newHostFor?.let { name ->
        val agent = CodingAgent.entries.firstOrNull { it.name == name }
        HostEditor(app, null, forSession = true, onDismiss = { state.newHostFor = null },
            onSaved = { id -> state.newHostFor = null; state.savedForSession = id to agent })
    }
    state.savedForSession?.let { (id, agent) ->
        LaunchedEffect(hosts, id) {
            hosts.firstOrNull { it.id == id }?.let {
                state.savedForSession = null
                state.launchRequest = LaunchRequest(it, agent != null, agent, newSession = true)
            }
        }
    }
    if (state.newSessionOpen) NewSessionSheet(hosts, onDismiss = { state.newSessionOpen = false },
        onNewHost = { state.newSessionOpen = false; state.newHostFor = it?.name.orEmpty() },
        onContinue = { state.newSessionOpen = false; state.continueOnId = it.id }) { host, agent ->
        state.newSessionOpen = false
        state.launchRequest = LaunchRequest(host, agent != null, agent, newSession = true)
    }
    state.continueOnId?.let { id -> hosts.firstOrNull { it.id == id } }?.let { host ->
        ContinueSessionSheet(host, sessions.openTmuxNames(host), onDismiss = { state.continueOnId = null },
            onNewTerminal = { state.continueOnId = null; actions.launchOn(host, StartDirectory.HOME, null) },
            onInstall = { state.continueOnId = null; actions.managePlugins(host) }) {
            state.continueOnId = null
            actions.attachOn(host, it)
        }
    }
    state.launchRequest?.let { (host, forAgent, initialAgent, newSession) ->
        LaunchSheet(host, forAgent, monitor = null, onDismiss = { state.launchRequest = null }, initialAgent = initialAgent,
            title = if (newSession) tr(if (forAgent) "选择工作目录" else "新建会话") else null) { directory, agent ->
            state.launchRequest = null
            actions.launchOn(host, directory, agent)
        }
    }
}
