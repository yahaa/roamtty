package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.itermux.app.data.Host

/** Confirms deleting [host], its saved credentials and its connections. */
@Composable
internal fun DeleteHostDialog(host: Host, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(trf("删除 %s？", host.name)) },
        text = { Text(tr("将删除这台主机及保存的凭据，并关闭它的连接。服务器信任记录会独立保留，可通过重新核验移除。远端 tmux 任务不受影响。")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } })
}

/** Confirms dropping the trust record of [endpoint], so the next connection shows its fingerprint again. */
@Composable
internal fun ReverifyHostDialog(endpoint: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("重新核验主机指纹")) },
        text = { Text(trf("请先向服务器管理员确认新指纹。继续会移除此地址原有的信任记录，关闭该地址的连接，并在重连时重新展示指纹。\n\n%s", endpoint)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("移除旧记录并重连")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } })
}
