package io.itermux.app.ui

import io.itermux.app.session.CodingAgent
import io.itermux.app.tr
import io.itermux.app.trf

import io.itermux.app.data.Host
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.HostPlugin
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import io.itermux.ssh.TerminalTransport

internal data class ConnectRequest(
    val host: Host,
    val mode: SessionMode,
    val title: String = "",
    /** When set, reattach this journal entry (same remote tmux name) instead of opening a new session. */
    val reuseSessionId: String? = null,
    /** After connect succeeds, open the remote file browser instead of the terminal. */
    val openFilesAfterConnect: Boolean = false,
    /** Credentials are for the plugin installer, not a terminal. */
    val plugins: Boolean = false,
    /** Credentials are for the host's remote desktop. */
    val desktop: Boolean = false,
    /** Typed into the new terminal once it connects, e.g. an AI coding agent started from the file browser. */
    val startupCommand: String? = null,
    /** The startup command starts an agent that reports through [io.itermux.app.session.AgentHooks]. */
    val agentHooks: Boolean = false,
    /** The agent the startup command starts. */
    val agent: CodingAgent? = null,
    /** How the startup command starts [agent], to name the tmux session it reports to once that is known. */
    val launch: io.itermux.app.session.AgentLaunch? = null,
    /** Credentials end this closed entry's remote tmux session instead of opening a terminal. */
    val terminateSessionId: String? = null,
    /** Attach this tmux session already on the server instead of creating one. */
    val attach: String? = null
)

/** Messages of a connection that dropped or was restored from the journal, not one the user or the server ended. */
internal const val RESTORED_MESSAGE = "连接已中断。会话记录已恢复，未发送的输入不会重发。"
internal const val DROPPED_MESSAGE = "SSH 已断开。未发送的输入已丢弃。"

/** Opening such a terminal reconnects it at once instead of offering a reconnect button. */
internal fun reconnectsOnOpen(state: ConnectionState) =
    state.phase == Phase.DISCONNECTED && state.message in setOf(RESTORED_MESSAGE, DROPPED_MESSAGE)

internal const val UNREACHABLE_MESSAGE = "网络不可达或连接被中断，请检查网络和 VPN 后重连。"
internal const val TIMEOUT_MESSAGE = "连接超时，请检查网络、VPN 和主机端口。"

/**
 * The session list also reconnects a session that gave up on an unreachable or silent host, which may be back by
 * then; it only does so once [hostBackSince] says the host answers again.
 */
internal fun reconnectsFromList(state: ConnectionState) =
    reconnectsOnOpen(state) || state.phase == Phase.FAILED && state.message in setOf(UNREACHABLE_MESSAGE, TIMEOUT_MESSAGE)

/**
 * Whether a probe measured at or after [since] found the host's sshd answering. Until then the session list leaves an
 * offline host's sessions alone instead of showing them "connecting" again and again.
 */
internal fun hostBackSince(status: HostReachability, checkedAt: Long?, since: Long) =
    status is HostReachability.Online && checkedAt != null && checkedAt >= since

/** At most one automatic reconnect per session within this window, so a host that drops at once cannot loop. */
internal const val AUTO_RECONNECT_INTERVAL_MS = 10_000L

internal fun authLabel(auth: String) = when (auth) {
    "KEY" -> tr("私钥")
    "INTERACTIVE" -> tr("交互式认证")
    else -> tr("密码")
}

internal fun modeLabel(tmux: Boolean, multiplexer: HostPlugin = HostPlugin.TMUX) =
    if (tmux) trf("%s · 可恢复", multiplexer.id) else tr("SSH · 普通会话")

/**
 * The tags a session card shows beside its status, like a host card's: the multiplexer that keeps it (tmux or psmux;
 * the one it attached to, else the host's) and mosh while mosh carries it. A plain SSH shell has none.
 */
internal fun sessionTransportTags(mode: SessionMode, attached: HostPlugin?, hostMultiplexer: HostPlugin,
    transport: TerminalTransport?): List<String> = listOfNotNull(
    (attached ?: hostMultiplexer).id.takeIf { mode == SessionMode.TMUX },
    HostPlugin.MOSH.id.takeIf { transport == TerminalTransport.MOSH })

/**
 * Sort key for session lists: live or coming back, then needing the user, then closed. A session restored from the
 * journal keeps its place among the live ones, so cards do not reorder while the app reconnects them one by one.
 */
internal fun activityRank(state: ConnectionState) = when {
    state.active || reconnectsOnOpen(state) -> 0
    state.phase == Phase.DISCONNECTED -> 3
    else -> 2
}

internal fun phaseLabel(phase: Phase) = when (phase) {
    Phase.CONNECTING -> tr("连接中")
    Phase.VERIFYING -> tr("待验证")
    Phase.AUTHENTICATING -> tr("认证中")
    Phase.CONNECTED -> tr("已连接")
    Phase.DISCONNECTED -> tr("已断开")
    Phase.AUTH_FAILED -> tr("认证失败")
    Phase.HOST_KEY_CHANGED -> tr("指纹变化")
    Phase.TMUX_MISSING -> tr("缺少 tmux 或 psmux")
    Phase.FAILED -> tr("连接失败")
}

/** The server [Host] reaches: sessions keep the copy they were opened with, so an edited host's old endpoint differs. */
internal val Host.endpointKey get() = Triple(id, address, port)
