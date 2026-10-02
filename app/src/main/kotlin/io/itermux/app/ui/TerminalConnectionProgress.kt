package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    Surface(modifier.testTag("terminal-connection-progress"), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(40.dp).testTag("terminal-connection-spinner")
                .semantics { contentDescription = label }, strokeWidth = 3.dp)
            Text(label, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel, modifier = Modifier.testTag("terminal-connection-cancel")) {
                Text(tr("取消连接"))
            }
        }
    }
}
