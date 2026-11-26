package io.itermux.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.ScreenApp
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.desktop.VoiceUiState
import io.itermux.ssh.Phase

/** Where the dock's buttons lead outside the terminal page. */
internal class DockActions(
    val onSelectSession: (String) -> Unit,
    val onHostDetail: () -> Unit,
    val onClose: () -> Unit,
    /** Shows the host's desktop small over the terminal, or closes it; null when it has none to open. */
    val onDesktop: (() -> Unit)?,
)

/** The terminal page's dock: collapse, sessions, the host's desktop, voice (for agy), files, common commands and the settings menu. */
@Composable
internal fun TerminalPaneDock(pane: TerminalPaneState, availableHeight: Dp, landscape: Boolean, imeVisible: Boolean,
    sessions: List<TerminalConnection>, phase: Phase, pip: TerminalPip, actions: DockActions,
    voice: TerminalVoice = TerminalVoice()) {
    val session = pane.session
    val sessionTitle by session.title.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    val agentStatus by session.agentStatus.collectAsStateWithLifecycle()
    val agentPolled by session.agentPolled.collectAsStateWithLifecycle()
    val launchedAgent by session.launchedAgent.collectAsStateWithLifecycle()
    val revision by session.revision.collectAsStateWithLifecycle()
    val isAgentSession = agentStatus != null || (launchedAgent != null && !agentPolled)
    val screenApp = remember(session, revision) { session.screenApp() }
    val isAgySession = voice.ui.inCall ||
        CodingAgent.fromCommand(agentStatus?.agent) == CodingAgent.AGY ||
        (launchedAgent == CodingAgent.AGY && (!agentPolled || agentStatus == null)) ||
        screenApp == ScreenApp.AGY
    val connected = phase == Phase.CONNECTED
    TerminalDock(availableHeight, landscape, imeVisible,
        modifier = Modifier.onGloballyPositioned { pane.dockBounds = it.boundsInRoot() },
        collapseControl = { DockCollapseButton(pane) },
        sessionControl = { sessionModifier ->
            SessionSwitcher(session, sessionTitle, phase, sessions, pane.sessionMenu, sessionModifier,
                onExpand = { pane.hideKeyboard(); pane.sessionMenu = true }, onDismiss = pane::dismissDockMenus,
                onHostDetail = { pane.sessionMenu = false; actions.onHostDetail() },
                onClose = { pane.sessionMenu = false; actions.onClose() },
                onRestart = { pane.sessionMenu = false; pane.restartConfirm = true },
                onSelect = { pane.sessionMenu = false; actions.onSelectSession(it) })
        },
        primaryControls = {
            if (actions.onDesktop != null) DockDesktopButton(pane, pip, actions.onDesktop)
            if (isAgySession) {
                DockVoiceButton(
                    voiceUi = voice.ui,
                    enabled = connected || voice.ui.inCall,
                    onClick = {
                        pane.hideKeyboard()
                        if (voice.ui.inCall) {
                            pane.menu = false
                            pane.sessionMenu = false
                            pane.dockExpanded = false
                            pane.voiceSuppressesPinnedDock = true
                            pane.voicePopoverOpen = true
                        } else {
                            pane.dismissDockMenus()
                            pane.voiceSheetOpen = true
                        }
                    },
                )
            }
            TerminalFileActions(enabled = connected && !transferStatus.running) { action ->
                pane.hideKeyboard()
                pane.fileAction = action
            }
            if (!isAgentSession) {
                IconButton(onClick = { pane.hideKeyboard(); pane.commonCommandsOpen = true }, enabled = connected,
                    modifier = Modifier.testTag("common-commands")) {
                    Icon(painterResource(R.drawable.ic_commands), contentDescription = tr("常用命令"))
                }
            }
            DockSettingsMenu(pane)
        })
}

/** Puts the dock away. A floating dock's button counts down the quiet spell before the dock goes on its own. */
@Composable
private fun DockCollapseButton(pane: TerminalPaneState) {
    IconButton(onClick = pane::collapseDock, modifier = Modifier.testTag("collapse-dock")) {
        val ring = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        Box(Modifier.size(32.dp).drawBehind {
            if (pane.dockPinned || TerminalToolbox.dockHideMillis <= 0) return@drawBehind
            val stroke = Stroke(2.dp.toPx())
            drawCircle(track, style = stroke)
            drawArc(ring, -90f, 360f * pane.dockCountdown.floatValue, false, style = stroke)
        }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = tr("收起操作栏"), modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * This host's desktop, beside the session it belongs to. A tap shows the desktop small over the terminal (or closes
 * it), tinted like the sessions button while it shows; a long press opens the desktop page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockDesktopButton(pane: TerminalPaneState, pip: TerminalPip, onDesktop: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val shown = pip.desktop != null
    Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(
        onClick = { pane.dismissDockMenus(); onDesktop() },
        onClickLabel = if (shown) tr("关闭桌面小窗") else tr("小窗查看桌面"), role = Role.Button,
        onLongClickLabel = pip.onFull?.let { tr("打开全屏桌面") },
        onLongClick = pip.onFull?.let { full -> {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            pane.hideKeyboard()
            full()
        } }).testTag("terminal-desktop"), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_desktop), contentDescription = tr("打开桌面"),
            tint = if (shown) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

/**
 * Phone button in the terminal dock for `agy` sessions: outline phone when idle,
 * sage green `#7FD19B` filled phone + breathing dot while in call (amber `#F2C46D` when muted).
 */
@Composable
internal fun DockVoiceButton(
    voiceUi: VoiceUiState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inCall = voiceUi.inCall
    val muted = voiceUi.isMuted
    val tint = when {
        !inCall -> LocalContentColor.current
        muted -> VoiceWarn
        else -> VoiceGreen
    }
    val wash = when {
        !inCall -> androidx.compose.ui.graphics.Color.Transparent
        muted -> VoiceWarn.copy(alpha = 0.16f)
        else -> VoiceGreen.copy(alpha = 0.16f)
    }
    val description = when {
        !inCall -> tr("语音对话")
        muted -> tr("语音通话已静音")
        else -> tr("语音通话中")
    }
    val dotAlpha = if (inCall && !muted) {
        rememberInfiniteTransition(label = "dock-voice-dot").animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dotAlpha",
        ).value
    } else 1f

    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(wash)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics { contentDescription = description }
            .testTag("terminal-voice"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (inCall) R.drawable.ic_phone_fill else R.drawable.ic_phone),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        if (inCall) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = 10.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = dotAlpha))
                    .testTag(if (muted) "terminal-voice-badge-muted" else "terminal-voice-badge-active"),
            )
        }
    }
}

@Composable
private fun DockSettingsMenu(pane: TerminalPaneState) {
    @Composable fun Item(label: String, onClick: () -> Unit) =
        DropdownMenuItem(text = { Text(label) }, onClick = { pane.menu = false; onClick() })
    Box {
        IconButton(onClick = { pane.sticky.clear(); pane.menu = true }, modifier = Modifier.testTag("terminal-menu")) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = tr("终端设置"))
        }
        DropdownMenu(pane.menu, pane::dismissDockMenus) {
            Item(tr("编辑文本"), pane::openEditor)
            Item(if (pane.dockPinned) tr("取消固定操作栏") else tr("固定操作栏")) {
                pane.pinDock(!pane.dockPinned)
                pane.dockExpanded = true
            }
            Item(tr("隐藏操作栏"), pane::hideDock)
            Item(tr("粘贴"), pane::showClipboard)
            HorizontalDivider()
            Item(trf("增大字号 · %d sp", pane.fontSize)) { pane.changeFontSize(pane.fontSize + 1) }
            Item(trf("减小字号 · %d sp", pane.fontSize)) { pane.changeFontSize(pane.fontSize - 1) }
        }
    }
}
