package io.itermux.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceCallPhase
import io.itermux.desktop.VoiceMode
import io.itermux.desktop.VoiceStateMachine
import io.itermux.desktop.VoiceUiState

/** Actions and state supplied to [TerminalPane] for `agy /voice` controls. */
internal data class TerminalVoice(
    val ui: VoiceUiState = VoiceUiState(),
    val hostName: String = "",
    val pluginInstalled: Boolean = true,
    val desktopPipAudioActive: Boolean = false,
    val bluetoothAvailable: Boolean = false,
    val checkBluetoothAvailable: () -> Boolean = { bluetoothAvailable },
    val onStart: (VoiceMode, AudioRoute) -> Unit = { _, _ -> },
    val onToggleMute: () -> Unit = {},
    val onCycleRoute: () -> Unit = {},
    val onHangup: () -> Unit = {},
)

/**
 * Compact phone-in-call icon tag for a session card's bottom-right status row (matching [IconTag] / [DesktopCardButton]).
 * Hidden completely when [ui] is not in a call so non-calling cards stay unchanged.
 */
@Composable
internal fun SessionVoiceIconTag(ui: VoiceUiState, tag: String, modifier: Modifier = Modifier) {
    if (!ui.inCall) return
    val unstable = ui.networkUnstable
    val muted = ui.isMuted
    val tint = if (unstable) StatusColors.warning else StatusColors.success
    val description = when {
        unstable -> tr("网络不稳定")
        muted -> tr("语音通话已静音")
        else -> tr("语音通话中")
    }
    val dotAlpha = if (!muted || unstable) {
        rememberInfiniteTransition(label = "session-voice-dot").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dotAlpha",
        ).value
    } else 1f
    Box(
        modifier
            .size(width = 30.dp, height = 24.dp)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 28.dp, height = 20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_phone_fill),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp),
            )
        }
        val badgeTag = when {
            unstable -> "$tag-unstable"
            muted -> "$tag-muted"
            else -> "$tag-active"
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 1.dp, y = 0.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = dotAlpha))
                .border(1.dp, MaterialTheme.colorScheme.groupedContainer, CircleShape)
                .testTag(badgeTag),
        )
    }
}

/**
 * Minimal circular phone-in-call icon for dense lists (terminal session switcher and navigation drawer).
 * Hidden completely when [ui] is not in a call.
 */
@Composable
internal fun SessionVoiceMiniIcon(ui: VoiceUiState, tag: String, modifier: Modifier = Modifier) {
    if (!ui.inCall) return
    val unstable = ui.networkUnstable
    val muted = ui.isMuted
    val tint = if (unstable) StatusColors.warning else StatusColors.success
    val description = when {
        unstable -> tr("网络不稳定")
        muted -> tr("语音通话已静音")
        else -> tr("语音通话中")
    }
    val dotAlpha = if (!muted || unstable) {
        rememberInfiniteTransition(label = "session-voice-mini-dot").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dotAlpha",
        ).value
    } else 1f
    Box(
        modifier
            .size(22.dp)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_phone_fill),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 1.dp, y = (-1).dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = dotAlpha)),
        )
    }
}

/** Tiny status micro-pill pinned beneath the floating pet during a call while the popover is closed. */
@Composable
internal fun VoicePetStatusTag(ui: VoiceUiState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val duration = VoiceStateMachine.formatDuration(ui.elapsedSeconds)
    val text = when {
        ui.resuming -> tr("重连中…")
        ui.networkPoor -> tr("网络不稳定")
        else -> when (ui.phase) {
            is VoiceCallPhase.Connecting -> tr("连接中…")
            VoiceCallPhase.Muted -> tr("已静音")
            VoiceCallPhase.Speaking -> tr("AI 回复中")
            VoiceCallPhase.Listening -> duration
            else -> ""
        }
    }
    val tint = if (ui.networkUnstable) VoiceWarn else VoiceGreen
    Surface(
        shape = CircleShape,
        color = colors.background.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
        shadowElevation = 3.dp,
        modifier = modifier.testTag("voice-pet-status-tag"),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/**
 * Compact call-control bubble sliding out to the left of the phone-holding pet (`180ms ease-out`).
 * Provides status + timer, Mute (`F6`), Speaker/Earpiece/Bluetooth cycle, Open full Dock, and red Hangup (`F7`).
 */
@Composable
internal fun VoiceCallPopover(
    visible: Boolean,
    ui: VoiceUiState,
    onToggleMute: () -> Unit,
    onCycleRoute: () -> Unit,
    onOpenDock: () -> Unit,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
            scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0.5f)),
        exit = fadeOut(tween(140)) +
            scaleOut(tween(140), targetScale = 0.94f, transformOrigin = TransformOrigin(1f, 0.5f)),
    ) {
        VoiceCallPopoverCard(
            ui = ui,
            onToggleMute = onToggleMute,
            onCycleRoute = onCycleRoute,
            onOpenDock = onOpenDock,
            onHangup = onHangup,
        )
    }
}

@Composable
internal fun VoiceCallPopoverCard(
    ui: VoiceUiState,
    onToggleMute: () -> Unit,
    onCycleRoute: () -> Unit,
    onOpenDock: () -> Unit,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dotColor = if (ui.networkUnstable) VoiceWarn else VoiceGreen
    val stateText = when {
        ui.resuming -> tr("正在重连…")
        ui.networkPoor -> tr("网络不稳定")
        else -> when (ui.phase) {
            is VoiceCallPhase.Connecting -> tr("正在接通…")
            VoiceCallPhase.Listening -> tr("聆听中")
            VoiceCallPhase.Speaking -> tr("AI 语音回复中")
            VoiceCallPhase.Muted -> tr("麦克风已静音")
            else -> ""
        }
    }
    val routeLabel = when (ui.audioRoute) {
        AudioRoute.SPEAKER -> tr("扬声器")
        AudioRoute.EARPIECE -> tr("听筒")
        AudioRoute.BLUETOOTH -> tr("蓝牙")
    }
    val routeIcon = when (ui.audioRoute) {
        AudioRoute.SPEAKER -> R.drawable.ic_speaker
        AudioRoute.EARPIECE -> R.drawable.ic_earpiece
        AudioRoute.BLUETOOTH -> R.drawable.ic_bluetooth
    }
    val pulse = rememberInfiniteTransition(label = "call-dot").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha",
    )

    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.09f)),
        shadowElevation = 10.dp,
        modifier = modifier.width(236.dp).testTag("voice-call-popover"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor.copy(alpha = if (ui.isMuted && !ui.networkUnstable) 1f else pulse.value)),
                    )
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("voice-popover-state"),
                    )
                }
                Text(
                    text = VoiceStateMachine.formatDuration(ui.elapsedSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("voice-call-timer"),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PopoverActionButton(
                    icon = if (ui.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                    label = if (ui.isMuted) tr("取消静音") else tr("静音"),
                    background = if (ui.isMuted) VoiceWarn.copy(alpha = 0.22f) else colors.surfaceContainerHigh,
                    contentColor = if (ui.isMuted) VoiceWarn else colors.onSurface,
                    tag = "voice-popover-mute",
                    enabled = !ui.isConnecting && !ui.resuming,
                    onClick = onToggleMute,
                    modifier = Modifier.weight(1f),
                )
                PopoverActionButton(
                    icon = routeIcon,
                    label = routeLabel,
                    background = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    tag = "voice-popover-route",
                    onClick = onCycleRoute,
                    modifier = Modifier.weight(1f),
                )
                PopoverActionButton(
                    icon = R.drawable.ic_dock,
                    label = tr("操作栏"),
                    background = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    tag = "voice-popover-dock",
                    onClick = onOpenDock,
                    modifier = Modifier.weight(1f),
                )
                PopoverActionButton(
                    icon = R.drawable.ic_hangup,
                    label = tr("挂断"),
                    background = VoiceDanger.copy(alpha = 0.22f),
                    contentColor = VoiceDangerText,
                    tag = "voice-popover-hangup",
                    onClick = onHangup,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PopoverActionButton(
    icon: Int,
    label: String,
    background: Color,
    contentColor: Color,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = background,
        contentColor = contentColor,
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(17.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
