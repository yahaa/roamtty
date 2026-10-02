package io.itermux.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.ssh.Phase

/** Overlay the preserved terminal grid so reconnecting never resizes the remote PTY. */
@Composable
internal fun TerminalConnectionProgress(phase: Phase, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (phase) {
        Phase.CONNECTING -> tr("连接中")
        Phase.VERIFYING -> tr("等待核对主机指纹")
        Phase.AUTHENTICATING -> tr("正在进行 SSH 认证")
        else -> return
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier.widthIn(max = 300.dp).fillMaxWidth().testTag("terminal-connection-progress"),
        shape = RoundedCornerShape(20.dp), color = colors.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)), shadowElevation = 2.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ConnectionPulse(label)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SSH", color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp, letterSpacing = 1.5.sp)
                Crossfade(label, animationSpec = tween(180), label = "connection-phase") { text ->
                    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp).testTag("terminal-connection-cancel")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = tr("取消连接"),
                    tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ConnectionPulse(label: String) {
    ItermuxLogo(Modifier.size(52.dp).testTag("terminal-connection-motion").semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }, animated = true)
}
