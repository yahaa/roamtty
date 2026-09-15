package io.itermux.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.ssh.*
import kotlinx.coroutines.*
import java.util.UUID

@Composable
fun HostEditor(app: ItermuxApp, original: Host?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = remember { original?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var address by remember { mutableStateOf(original?.address.orEmpty()) }
    var port by remember { mutableStateOf(original?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(original?.username.orEmpty()) }
    var auth by remember { mutableStateOf(original?.auth ?: "PASSWORD") }
    var mode by remember { mutableStateOf(original?.mode ?: "TMUX") }
    var savePassword by remember { mutableStateOf(original?.encryptedPassword != null) }
    var password by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf<ByteArray?>(null) }
    var keyLabel by remember { mutableStateOf(original?.keyLabel) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            val count = stream.read(chunk)
                            if (count < 0) break
                            require(buffer.size() + count <= 256 * 1024) { "私钥文件超过 256 KiB" }
                            buffer.write(chunk, 0, count)
                        }
                        buffer.toByteArray()
                    } ?: error("无法读取文件")
                    val header = bytes.take(100).toByteArray().toString(Charsets.US_ASCII)
                    require(header.contains("PRIVATE KEY-----")) { "请选择 OpenSSH 或 PEM 格式的私钥文件" }
                    val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "已导入私钥"
                    bytes to label.take(120)
                }
                imported?.fill(0)
                imported = result.first
                keyLabel = result.second
                error = null
            } catch (e: Exception) { error = "无法导入，请选择不超过 256 KiB 的 OpenSSH/PEM 私钥。" }
            finally { busy = false }
        }
    }
    DisposableEffect(Unit) { onDispose { imported?.fill(0); password = "" } }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Text(if (original == null) "添加主机" else "编辑主机", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                require(name.trim().isNotEmpty() && name.length <= 80) { "名称需为 1–80 个字符" }
                                val target = Target(address.trim(), port.toIntOrNull() ?: 0, username.trim(), AuthMethod.valueOf(auth), SessionMode.valueOf(mode))
                                require(auth != "KEY" || imported != null || original?.encryptedKey != null) { "请先导入私钥" }
                                require(!savePassword || auth != "PASSWORD" || password.isNotEmpty() || original?.encryptedPassword != null) { "请输入要保存的密码" }
                                withContext(Dispatchers.IO) {
                                    val encryptedPassword = if (auth == "PASSWORD" && savePassword) {
                                        if (password.isEmpty()) original?.encryptedPassword else password.toByteArray().let { bytes -> try { app.vault.encrypt(bytes, "$id:password") } finally { bytes.fill(0) } }
                                    } else null
                                    val encryptedKey = if (auth == "KEY") imported?.let { app.vault.encrypt(it, "$id:key") } ?: original?.encryptedKey else null
                                    app.database.hosts().save(Host(id, name.trim(), target.address, target.port, target.username, auth, mode, encryptedPassword, encryptedKey, keyLabel.takeIf { auth == "KEY" }))
                                }
                                imported?.fill(0); password = ""
                                onSaved()
                            } catch (e: IllegalArgumentException) { error = e.message }
                            catch (e: Exception) { error = "保存失败，无法使用本机加密存储。请重试。" }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) "处理中" else "保存") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("连接信息", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, placeholder = { Text("例如：开发工作站") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(address, { address = it }, label = { Text("地址 / IP") }, placeholder = { Text("例如：dev.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") }, singleLine = true, modifier = Modifier.width(100.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("认证方式", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("PASSWORD", "KEY", "INTERACTIVE").forEach { value -> FilterChip(auth == value, { auth = value }, label = { Text(authLabel(value)) }) }
                    }
                    if (auth == "PASSWORD") {
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(savePassword, { savePassword = it }); Text("在本机加密保存密码") }
                        if (savePassword) OutlinedTextField(password, { password = it }, label = { Text(if (original?.encryptedPassword != null) "新密码（留空保留原密码）" else "密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("默认每次连接时输入密码。", style = MaterialTheme.typography.bodySmall)
                    } else if (auth == "KEY") {
                        OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(if (keyLabel == null) "导入 Ed25519 / RSA 私钥" else "更换私钥") }
                        keyLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text("支持 OpenSSH、PEM 私钥。文件会加密保存；私钥口令仅在连接时输入，不会保存。", style = MaterialTheme.typography.bodySmall)
                    } else Text("连接时显示服务器的认证问题，支持一次性验证码和多轮交互。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("会话模式", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(mode == "TMUX", { mode = "TMUX" }, label = { Text("tmux · 推荐") })
                        FilterChip(mode == "SHELL", { mode = "SHELL" }, label = { Text("普通 SSH") })
                    }
                    Text(if (mode == "TMUX") "附着远端固定会话 itermux，适合 AI 编程长任务。服务器需要已安装 tmux。" else "直接打开远端 shell。断线后需重新连接，原 shell 不会被恢复。", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
