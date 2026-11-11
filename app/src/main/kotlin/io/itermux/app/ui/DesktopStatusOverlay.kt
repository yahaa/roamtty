package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.tr
import io.itermux.desktop.*
import kotlinx.coroutines.delay

/** Connecting steps, needs, failures, the network figures and the recovering card, over the picture. */
@Composable
internal fun BoxScope.DesktopStatusOverlay(connection: DesktopConnection, phase: DesktopPhase, hasPicture: Boolean,
    showStats: Boolean, statsBox: StatsBox, statsHint: Boolean, onCloseStats: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val log by connection.log.collectAsStateWithLifecycle()
    val notice by connection.notice.collectAsStateWithLifecycle()
    val showCard = !hasPicture || phase is DesktopPhase.Failed || phase is DesktopPhase.NeedsAction
    if (showCard) when (phase) {
        is DesktopPhase.Failed -> StatusCard(connection.host.name, tr("无法打开桌面"), footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRetry, Modifier.testTag("desktop-retry")) { Text(tr("重试")) }
                TextButton(onClick = onClose) { Text(tr("关闭"), color = DesktopInkDim) }
            }
        }) {
            Text(phase.message, color = DesktopInk, fontSize = 14.sp, modifier = Modifier.testTag("desktop-error"))
        }
        is DesktopPhase.NeedsAction -> StatusCard(connection.host.name, tr("需要在电脑上操作"),
            footer = { CardLine(tr("满足后会自动连接"), progress = true) }) {
            Text(needsText(phase.needs), color = DesktopInk, fontSize = 14.sp, modifier = Modifier.testTag("desktop-needs"))
            if (connection.windowsHost && phase.needs.firstOrNull() == "not-logged-in") WindowsServiceHint()
        }
        else -> {
            val step = (phase as? DesktopPhase.Connecting)?.step
            StatusCard(connection.host.name, tr("正在打开桌面"), footer = {
                // One line, the latest: the card keeps its size however much the install says.
                Text(log.lastOrNull().orEmpty(), color = DesktopInkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag("desktop-log"))
            }) {
                StepLine(tr("登录主机"), step == DesktopPhase.Step.LOGIN, step != null && step != DesktopPhase.Step.LOGIN)
                StepLine(if (step == DesktopPhase.Step.INSTALL) tr("安装桌面插件") else tr("启动桌面插件"),
                    step == DesktopPhase.Step.INSTALL || step == DesktopPhase.Step.START, step == DesktopPhase.Step.FIRST_FRAME)
                StepLine(tr("等待画面"), step == DesktopPhase.Step.FIRST_FRAME, false)
            }
        }
    }
    if (hasPicture) {
        NetworkStats(connection, phase, showStats, statsBox, onCloseStats)
        val recovering = phase as? DesktopPhase.Recovering
        var longEnough by remember(recovering?.sinceMs) { mutableStateOf(false) }
        LaunchedEffect(recovering?.sinceMs) {
            longEnough = false
            if (recovering != null) { delay((2000 - (System.currentTimeMillis() - recovering.sinceMs)).coerceAtLeast(0)); longEnough = true }
        }
        if (recovering != null && longEnough) StatusCard(connection.host.name, tr("连接中断"), tag = "desktop-recovering",
            footer = { CardLine(tr("网络恢复后画面会自动回来"), progress = true) }) {
            Text(tr("正在恢复…"), color = DesktopInk, fontSize = 14.sp)
        }
    }
    (notice ?: tr("网络状态已隐藏，可在「操作」中重新打开").takeIf { statsHint })?.let { Text(it, Modifier.align(Alignment.TopCenter).padding(10.dp).clip(CircleShape).background(DesktopPanelColor)
        .padding(horizontal = 14.dp, vertical = 6.dp).testTag("desktop-notice"), color = DesktopInk, fontSize = 13.sp) }
}

/**
 * The one shape of every card over the picture: the host, a title, a body that scrolls when long and a
 * footer line. Fixed in size, so the card never jumps as steps and log lines come and go.
 */
@Composable
private fun BoxScope.StatusCard(host: String, title: String, tag: String = "desktop-status",
    footer: @Composable () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.align(Alignment.Center).padding(16.dp).size(STATUS_CARD_WIDTH, STATUS_CARD_HEIGHT).testTag(tag),
        colors = CardDefaults.cardColors(containerColor = DesktopPanelColor.copy(alpha = 1f)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
            Text(host, color = DesktopInkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, color = DesktopInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
            Box(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(top = 8.dp), contentAlignment = Alignment.CenterStart) { footer() }
        }
    }
}

/** A footer's one quiet line, with a small spinner when something is still going on. */
@Composable
private fun CardLine(text: String, progress: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress) {
            CircularProgressIndicator(Modifier.size(12.dp), color = DesktopAccent, strokeWidth = 1.5.dp, trackColor = DesktopAccent.copy(alpha = 0.25f))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = DesktopInkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val STATUS_CARD_WIDTH = 296.dp
private val STATUS_CARD_HEIGHT = 244.dp

/** Windows without the plugin's service: one administrator install reaches the sign-in and lock screens. */
@Composable
private fun WindowsServiceHint() {
    val context = LocalContext.current
    Text(tr("在这台电脑上用管理员 PowerShell 运行一次下面的命令，之后电脑重启或锁屏时也能直接连接："), color = DesktopInkDim, fontSize = 13.sp)
    Text(io.itermux.ssh.WindowsDesktop.SERVICE_INSTALL, color = DesktopInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag("desktop-service-command"))
    TextButton(onClick = { context.copyText(io.itermux.ssh.WindowsDesktop.SERVICE_INSTALL) },
        Modifier.testTag("desktop-service-copy")) { Text(tr("复制命令")) }
}

private fun needsText(needs: List<String>): String = when (needs.firstOrNull()) {
    "not-logged-in" -> tr("请先在这台电脑上登录桌面")
    "screen-recording" -> tr("请在 Mac 上允许“屏幕录制”：系统设置 › 隐私与安全性 › 屏幕与系统音频录制，打开 Roamtty Desktop")
    "accessibility" -> tr("请在 Mac 上允许“辅助功能”：系统设置 › 隐私与安全性 › 辅助功能，打开 Roamtty Desktop")
    "locked" -> tr("请在本机解锁")
    "portal-approval" -> tr("请在电脑上点“允许”共享屏幕")
    else -> tr("需要在电脑上操作")
}

@Composable
private fun StepLine(label: String, current: Boolean, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = StatusColors.success)
                current -> CircularProgressIndicator(Modifier.size(16.dp), color = DesktopAccent, strokeWidth = 2.dp, trackColor = DesktopAccent.copy(alpha = 0.25f))
                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(DesktopInkDim))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (current || done) DesktopInk else DesktopInkDim, fontSize = 14.sp)
    }
}
