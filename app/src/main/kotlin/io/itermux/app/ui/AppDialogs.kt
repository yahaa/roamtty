package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.AppLocale
import io.itermux.app.AppLanguage

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun CredentialsDialog(request: ConnectRequest, dismiss: () -> Unit, connect: (String, String) -> Unit) {
    var secret by remember { mutableStateOf("") }
    val key = request.host.auth == "KEY"
    AlertDialog(onDismissRequest = dismiss, title = { Text(trf("连接 %s", request.host.name)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (key) tr("私钥口令仅用于本次连接；未加密私钥可留空。") else tr("密码仅用于本次连接，默认不保存。"))
            OutlinedTextField(secret, { secret = it }, label = { Text(if (key) tr("私钥口令（可留空）") else tr("SSH 密码")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { if (key) connect("", secret) else connect(secret, ""); secret = "" }) { Text(tr("连接")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr("取消")) } })
}

@Composable internal fun AboutDialog(context: Context, dismiss: () -> Unit) {
    var licenses by remember { mutableStateOf(false) }
    val licenseText = remember { runCatching { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault(tr("许可文件请参阅源码中的 THIRD_PARTY_NOTICES.md。")) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (licenses) tr("第三方许可") else "iTermux 0.1.0") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (licenses) licenseText else guideText())
            TextButton(onClick = { licenses = !licenses }) { Text(if (licenses) tr("返回指南") else tr("查看第三方许可")) }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(tr("关闭")) } })
}

private fun guideText(): String = if (AppLocale.language == AppLanguage.ENGLISH)
    "1. Add a host. For key authentication, import an Ed25519 or RSA key in OpenSSH or PEM format. Verify the SHA-256 fingerprint before trusting a host.\n\n2. The terminal controls start hidden. Tap the toolbox button on the right edge to open them. Tap the terminal to show the keyboard; triple-tap it to paste the clipboard.\n\n3. Use the menu to edit text, paste, or adjust font size. Review draft text before sending; Send text and Enter are separate. Hold the current command line to show a magnifier and drag the cursor horizontally or across lines. Hold other output to select and copy it. The arrow buttons move the cursor. Tap ⌫ to delete a character or hold it to clear input. Clearing a remote line requires Ctrl+U/Ctrl+K support.\n\n4. Terminal sessions use the full screen. Swipe from an edge to reveal system bars temporarily. Use the session button to switch sessions. You can pin or hide the control bar in the menu. Drag the far-right scrollbar to read earlier output; scrolling to the bottom resumes following new output.\n\n5. In tmux mode, each new connection gets its own remote tmux session, and reconnecting reattaches it. You can disconnect all sessions from the notification."
else
    "1. 添加主机；使用私钥时导入 OpenSSH 或 PEM 格式的 Ed25519/RSA 私钥。首次连接先核对 SHA-256 指纹。\n\n2. 连接后操作栏默认隐藏，点击右侧工具箱按钮展开。键盘默认收起；点击终端输入区自动弹出，三击终端粘贴剪贴板文字。\n\n3. 更多菜单中可打开“编辑文本”、粘贴或调整字号。编辑框中的文字先留在草稿，确认后发送；发送文本与回车分开。长按当前命令行显示放大镜，可左右或上下跨行拖动光标；长按其他输出可选择复制。右侧和编辑框中的 ←/→ 可移动光标，⌫ 点按删除字符、长按清空当前输入。终端内持续按住输入法删除键，连续退格约 1 秒后清空当前行；快速连续点按也可能触发。清空需远端支持 Ctrl+U/Ctrl+K。\n\n4. 连接页横竖屏都默认全屏，边缘滑动可临时显示系统栏。会话图标用于切换会话。更多菜单可将操作栏固定在右侧，固定偏好会保留；选择“隐藏操作栏”后，点击右侧工具箱按钮恢复。编辑文字时暂时收起操作栏，关闭编辑框后恢复。拖动最右侧滚动条可查看本地历史，停止后自动隐藏；阅读旧内容时保持位置，拖到底部继续跟随输出。\n\n5. tmux 模式为每个新连接创建独立的远端会话；断线后手动重连即可附着原会话，继续远端任务。通知栏可全部断开连接。"
