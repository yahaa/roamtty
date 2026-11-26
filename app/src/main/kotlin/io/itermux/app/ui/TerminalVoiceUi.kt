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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import io.itermux.app.trf
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
 * Confirmation bottom sheet opened from the terminal dock's phone button (`AppShapes.extraLarge`).
 * Shows the enlarged phone-holding pet preview, dialogue mode selection (`实时通话` / `语音听写`),
 * audio route pills (`扬声器` / `听筒` / `蓝牙`), contextual notices, and a sage-green confirm button.
 */
@Composable
internal fun VoiceStartSheet(
    hostName: String,
    hasMicPermission: Boolean,
    desktopPipAudioActive: Boolean,
    pluginInstalled: Boolean,
    bluetoothAvailable: Boolean,
    micPermissionPermanentlyDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (VoiceMode, AudioRoute) -> Unit,
) {
    var modeName by rememberSaveable { mutableStateOf(VoiceMode.LIVE.name) }
    var routeName by rememberSaveable {
        mutableStateOf((if (bluetoothAvailable) AudioRoute.BLUETOOTH else AudioRoute.SPEAKER).name)
    }
    val mode = runCatching { VoiceMode.valueOf(modeName) }.getOrDefault(VoiceMode.LIVE)
    val route = runCatching { AudioRoute.valueOf(routeName) }.getOrDefault(AudioRoute.SPEAKER)
    // Only fall back BLUETOOTH -> SPEAKER when Bluetooth disappears; never reset a manual EARPIECE choice.
    LaunchedEffect(bluetoothAvailable) {
        if (!bluetoothAvailable && route == AudioRoute.BLUETOOTH) {
            routeName = AudioRoute.SPEAKER.name
        }
    }
    AppSheet(onDismiss = onDismiss, fillHeight = false) {
        VoiceStartSheetContent(
            hostName = hostName,
            mode = mode,
            route = route,
            hasMicPermission = hasMicPermission,
            desktopPipAudioActive = desktopPipAudioActive,
            pluginInstalled = pluginInstalled,
            bluetoothAvailable = bluetoothAvailable,
            micPermissionPermanentlyDenied = micPermissionPermanentlyDenied,
            onOpenAppSettings = onOpenAppSettings,
            onModeChange = { modeName = it.name },
            onRouteChange = { routeName = it.name },
            onCancel = onDismiss,
            onConfirm = { onConfirm(mode, route) },
        )
    }
}

/** Pure sheet body extracted for direct Compose UI tests without modal window chrome. */
@Composable
internal fun VoiceStartSheetContent(
    hostName: String,
    mode: VoiceMode,
    route: AudioRoute,
    hasMicPermission: Boolean,
    desktopPipAudioActive: Boolean,
    pluginInstalled: Boolean,
    onModeChange: (VoiceMode) -> Unit,
    onRouteChange: (AudioRoute) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    bluetoothAvailable: Boolean = true,
    micPermissionPermanentlyDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val okColor = StatusColors.success
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 22.dp)
            .testTag("voice-start-sheet"),
    ) {
        // Hero: enlarged pet holding curved handset + title/subtitle
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(VoiceGreen.copy(alpha = 0.2f), colors.surfaceContainerLowest.copy(alpha = 0.7f)),
                        )
                    )
                    .border(1.dp, VoiceGreen.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                VoicePetPreview(PetVoiceState.Listening(0.35f), size = 56.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(tr("开启语音对话"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    trf("agy · %s（通过主机插件低延迟通道）", hostName.ifBlank { tr("当前主机") }),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Mode selection cards
        Column(
            Modifier.fillMaxWidth().selectableGroup().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoiceModeCard(
                title = tr("实时通话"),
                badge = tr("推荐"),
                description = tr("双向连续语音对话，AI 语音回复，支持随时开口打断"),
                selected = mode == VoiceMode.LIVE,
                okColor = okColor,
                tag = "voice-mode-live",
                onClick = { onModeChange(VoiceMode.LIVE) },
            )
            VoiceModeCard(
                title = tr("语音听写"),
                badge = null,
                description = tr("说话转文字输入到终端，不播放语音"),
                selected = mode == VoiceMode.DICTATE,
                okColor = okColor,
                tag = "voice-mode-dictate",
                onClick = { onModeChange(VoiceMode.DICTATE) },
            )
        }

        // Audio route pills (only for live voice mode)
        if (mode == VoiceMode.LIVE) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("voice-route-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr("音频出口"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioRoutePill(tr("扬声器"), route == AudioRoute.SPEAKER, "voice-route-speaker") {
                        onRouteChange(AudioRoute.SPEAKER)
                    }
                    AudioRoutePill(tr("听筒"), route == AudioRoute.EARPIECE, "voice-route-earpiece") {
                        onRouteChange(AudioRoute.EARPIECE)
                    }
                    AudioRoutePill(
                        label = tr("蓝牙"),
                        selected = route == AudioRoute.BLUETOOTH,
                        tag = "voice-route-bluetooth",
                        enabled = bluetoothAvailable,
                    ) {
                        onRouteChange(AudioRoute.BLUETOOTH)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // Contextual notices (hidden when not needed)
        val notices = buildList {
            if (!hasMicPermission) {
                val msg = if (micPermissionPermanentlyDenied) {
                    tr("麦克风权限已被拒绝，请在系统设置中开启")
                } else {
                    tr("开始后将请求麦克风权限")
                }
                add("voice-notice-permission" to msg)
            }
            if (desktopPipAudioActive) add("voice-notice-desktop-mute" to tr("通话期间将自动静音远程桌面"))
            if (!pluginInstalled) add("voice-notice-install" to tr("首次使用将自动安装主机插件"))
        }
        if (notices.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                notices.forEach { (tag, text) ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = colors.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().testTag(tag),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (tag == "voice-notice-permission" && micPermissionPermanentlyDenied) {
                                TextButton(
                                    onClick = onOpenAppSettings,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.testTag("voice-open-settings"),
                                ) {
                                    Text(tr("前往设置"), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Action buttons: Cancel + Green Confirm
        val confirmText = when {
            !pluginInstalled && mode == VoiceMode.LIVE -> tr("安装插件并通话")
            !pluginInstalled && mode == VoiceMode.DICTATE -> tr("安装插件并听写")
            mode == VoiceMode.LIVE -> tr("开始通话")
            else -> tr("开始听写")
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.weight(0.9f).height(46.dp).testTag("voice-sheet-cancel"),
            ) {
                Text(tr("取消"), fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VoiceGreen, contentColor = VoiceGreenInk),
                modifier = Modifier.weight(1.7f).height(46.dp).testTag("voice-sheet-confirm"),
            ) {
                Icon(painterResource(R.drawable.ic_phone_fill), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(confirmText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun VoiceModeCard(
    title: String,
    badge: String?,
    description: String,
    selected: Boolean,
    okColor: Color,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) okColor.copy(alpha = 0.12f) else colors.surfaceContainer,
        border = BorderStroke(1.5.dp, if (selected) okColor else colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
                    .border(2.dp, if (selected) okColor else colors.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(okColor))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    if (badge != null) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = okColor,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(okColor.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AudioRoutePill(label: String, selected: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) colors.surfaceContainerHigh else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.38f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
        )
    }
}

/** Tiny status micro-pill pinned beneath the floating pet during a call while the popover is closed. */
@Composable
internal fun VoicePetStatusTag(ui: VoiceUiState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val duration = VoiceStateMachine.formatDuration(ui.elapsedSeconds)
    val text = if (ui.resuming) {
        tr("重连中…")
    } else when (ui.phase) {
        is VoiceCallPhase.Connecting -> tr("连接中…")
        VoiceCallPhase.Muted -> tr("已静音")
        VoiceCallPhase.Speaking -> tr("AI 回复中")
        VoiceCallPhase.Listening -> if (ui.mode == VoiceMode.DICTATE) {
            if (ui.elapsedSeconds > 0) trf("听写 %s", duration) else tr("听写中")
        } else {
            duration
        }
        else -> ""
    }
    val tint = if (ui.resuming) {
        colors.onSurfaceVariant
    } else when (ui.phase) {
        VoiceCallPhase.Speaking -> VoicePeach
        VoiceCallPhase.Muted -> VoiceWarn
        is VoiceCallPhase.Connecting -> colors.onSurfaceVariant
        else -> VoiceGreen
    }
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
    val dotColor = if (ui.resuming) {
        colors.onSurfaceVariant
    } else when (ui.phase) {
        VoiceCallPhase.Speaking -> VoicePeach
        VoiceCallPhase.Muted -> VoiceWarn
        is VoiceCallPhase.Connecting -> colors.onSurfaceVariant
        else -> VoiceGreen
    }
    val stateText = if (ui.resuming) {
        tr("重连中…")
    } else when (ui.phase) {
        is VoiceCallPhase.Connecting -> tr("正在接通…")
        VoiceCallPhase.Listening -> if (ui.mode == VoiceMode.DICTATE) tr("正在听写") else tr("聆听中")
        VoiceCallPhase.Speaking -> tr("AI 语音回复中")
        VoiceCallPhase.Muted -> tr("麦克风已静音")
        else -> ""
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
                            .background(dotColor.copy(alpha = if (ui.isMuted) 1f else pulse.value)),
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
                if (ui.mode == VoiceMode.LIVE) {
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
                }
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
