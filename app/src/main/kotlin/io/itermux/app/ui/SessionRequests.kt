package io.itermux.app.ui

import io.itermux.app.data.Host
import io.itermux.app.data.newSessionMode
import io.itermux.app.data.prefersMosh
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.SessionService
import io.itermux.app.session.StartDirectory
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode

/** A new session on [host] in [directory]; with [agent], that agent is started there and names the session. */
internal fun launchRequest(host: Host, directory: String, agent: AgentLaunch?) =
    if (agent != null) ConnectRequest(host, host.newSessionMode, agent.title, startupCommand = agent.commandLine,
        agentHooks = agent.reportsStatus, agent = agent.agent)
    else ConnectRequest(host, host.newSessionMode, startupCommand = StartDirectory.command(directory))

/** A fresh login on this card's host: a disconnected tmux session is ended on the server; see [SessionService.close]. */
internal fun TerminalConnection.terminateRequest() = ConnectRequest(host, SessionMode.SHELL, terminateSessionId = id)

/** Reconnects this card on [host], the saved copy when there is one: a password saved since then spares a prompt. */
internal fun TerminalConnection.reconnectRequest(host: Host = this.host) =
    ConnectRequest(host, mode, title.value, reuseSessionId = id)

/**
 * Opens the session [request] asks for: this card reconnected, or a new one. Then names it and queues its startup command.
 * The caller handles [ConnectRequest.terminateSessionId] requests itself.
 */
internal suspend fun SessionService.openFor(request: ConnectRequest, credentials: Credentials): TerminalConnection {
    val session = if (request.reuseSessionId != null) {
        reconnect(request.reuseSessionId, credentials)
    } else {
        // File browsing needs only SFTP, so it never starts a mosh server.
        open(request.host, request.mode, credentials, preferMosh = request.host.prefersMosh && !request.openFilesAfterConnect,
            attach = request.attach)
    }
    if (request.title.isNotBlank()) session.rename(request.title)
    request.agent?.let { session.launchedAgent.value = it }
    request.startupCommand?.let { session.runWhenConnected(it, request.agent.takeIf { request.agentHooks }) }
    return session
}

/** The card already attached to [host]'s tmux session [name], if any. */
internal fun List<TerminalConnection>.tmuxCard(host: Host, name: String) =
    firstOrNull { it.host.id == host.id && it.mode == SessionMode.TMUX && it.remoteSessionName == name }

/** The tmux sessions of [host] that already have a card, which the launch sheet marks as open. */
internal fun List<TerminalConnection>.openTmuxNames(host: Host) =
    filter { it.host.id == host.id && it.mode == SessionMode.TMUX }.map { it.remoteSessionName }.toSet()

/** A connected session on [host] whose SFTP the file browser can reuse. */
internal fun List<TerminalConnection>.connectedTo(host: Host) =
    firstOrNull { it.host.id == host.id && it.state.value.phase == Phase.CONNECTED }
