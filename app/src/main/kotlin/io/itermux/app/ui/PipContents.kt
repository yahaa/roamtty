package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopSettings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.itermux.app.tr
import io.itermux.desktop.DesktopPhase
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

private val MiniInk = Color(0xFFF2F2F7)
private val MiniInkDim = Color(0xFF9A9AA1)
private val MiniAccent = Color(0xFFFF9F0A)

/** The owner name the small window registers with the desktop connection ([DesktopConnection.shown]). */
private const val PIP_OWNER = "pip"

/**
 * A host's desktop in the small window: the desktop page's own picture and gestures ([DesktopScreen]), on the same
 * connection and decoder. Not [focused], the window takes the touches (to be placed) and the host sends the picture
 * at a lower rate; [focused], the picture takes every touch, at full rate, and a hardware keyboard types on the
 * host unless the [keyboard] panel holds the input. Before the first picture (or after a failure) a line says where
 * the desktop is.
 */
@Composable
internal fun DesktopMiniContent(connection: DesktopConnection, focused: Boolean = false, keyboard: Boolean = false) {
    val context = LocalContext.current
    val video by connection.videoSize.collectAsStateWithLifecycle()
    val phase by connection.phase.collectAsStateWithLifecycle()
    // A question the login asks (a new host key, a second factor) is answered here as on the desktop page.
    val pending by connection.pending.collectAsStateWithLifecycle()
    pending?.let { PromptDialog(it) }
    LaunchedEffect(connection, focused) { connection.shown(PIP_OWNER, small = !focused) }
    DisposableEffect(connection) { onDispose { connection.hidden(PIP_OWNER) } }
    // As the desktop page has it for this host (屏幕触控 or 鼠标指针), read again on each focus.
    val mode = remember(connection, focused) { DesktopSettings(context).pointerMode(connection.host.id) }
    val stats = remember { StatsBox(0f to 0f) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (video.first > 0) DesktopScreen(connection, mode, keys = focused && !keyboard, stats, onStatsMoved = {}, small = true)
        val status = when (phase) {
            is DesktopPhase.Failed -> tr("桌面已断开")
            is DesktopPhase.NeedsAction -> tr("需要在电脑上操作")
            is DesktopPhase.Recovering -> tr("连接中断")
            else -> if (video.first <= 0) tr("正在打开桌面") else null
        }
        if (status != null) Column(Modifier.fillMaxSize().background(Color(0xFF1F1F21)).padding(10.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(connection.host.name, color = MiniInkDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val busy = phase !is DesktopPhase.Failed
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = MiniAccent, strokeWidth = 1.5.dp,
                trackColor = MiniAccent.copy(alpha = 0.25f))
            Text(status, color = MiniInk, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}
