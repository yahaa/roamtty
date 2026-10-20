package io.itermux.app.ui

import io.itermux.app.tr

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.itermux.ssh.SessionMode

/** Confirms a multi-line paste into a program that has not enabled bracketed paste. */
@Composable
internal fun PasteConfirmDialog(enabled: Boolean, onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("发送多行文本？")) },
        text = { Text(tr("远端尚未启用 bracketed paste。文本中的换行可能立即执行命令，请确认当前程序能安全接收多行输入。")) },
        confirmButton = { TextButton(onClick = onSend, enabled = enabled) { Text(tr("发送")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } })
}

/** Confirms restarting a session of [mode]: tmux restarts the pane, a plain shell reconnects. */
@Composable
internal fun RestartSessionDialog(mode: SessionMode, onRestart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("重启当前会话？")) },
        text = { Text(if (mode == SessionMode.TMUX) tr("正在运行的程序会被结束，然后在同一目录重新启动。") else tr("当前 shell 会结束并重新连接。")) },
        confirmButton = { TextButton(onClick = onRestart, modifier = Modifier.testTag("restart-session-confirm")) { Text(tr("重启")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } })
}
