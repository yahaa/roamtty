package io.itermux.app.ui

import io.itermux.app.tr

import android.provider.OpenableColumns
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.ssh.AuthMethod
import io.itermux.ssh.SessionMode
import io.itermux.ssh.Target
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostEditor(app: ItermuxApp, original: Host?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().first()
    }
    val draft = remember(activity) { ViewModelProvider(activity)[HostEditorDraft::class.java] }
    draft.open(original)
    val id = draft.id
    var name by draft.name
    var address by draft.address
    var port by draft.port
    var username by draft.username
    var auth by draft.auth
    var mode by draft.mode
    var os by draft.os
    var savePassword by draft.savePassword
    var password by draft.password
    var imported by draft.imported
    var keyLabel by draft.keyLabel
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
                            require(buffer.size() + count <= 256 * 1024) { tr("私钥文件超过 256 KiB") }
                            buffer.write(chunk, 0, count)
                        }
                        buffer.toByteArray()
                    } ?: error(tr("无法读取文件"))
                    val header = bytes.take(100).toByteArray().toString(Charsets.US_ASCII)
                    require(header.contains("PRIVATE KEY-----")) { tr("请选择 OpenSSH 或 PEM 格式的私钥文件") }
                    val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: tr("已导入私钥")
                    bytes to label.take(120)
                }
                imported?.fill(0)
                imported = result.first
                keyLabel = result.second
                error = null
            } catch (e: Exception) { error = tr("无法导入，请选择不超过 256 KiB 的 OpenSSH/PEM 私钥。") }
            finally { busy = false }
        }
    }
    val connectionSection: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("连接信息"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(name, { name = it }, label = { Text(tr("名称")) }, placeholder = { Text(tr("例如：开发工作站")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("host-name"))
            OutlinedTextField(address, { address = it }, label = { Text(tr("地址 / IP")) }, placeholder = { Text(tr("例如：dev.example.com")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("host-address"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(username, { username = it }, label = { Text(tr("用户名")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(tr("端口")) }, singleLine = true, modifier = Modifier.width(100.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
    }
    val systemSection: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("远端系统"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LINUX" to "Linux", "WINDOWS" to "Windows", "MACOS" to "macOS", "OTHER" to tr("其他")).forEach { (value, label) ->
                    FilterChip(os == value, { os = value }, label = { Text(label) })
                }
            }
            if (os == "UNKNOWN") Text(tr("旧主机尚未设置系统类型，请选择后保存。"), style = MaterialTheme.typography.bodySmall)
        }
    }
    val authenticationSection: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("认证方式"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PASSWORD", "KEY", "INTERACTIVE").forEach { value ->
                    FilterChip(auth == value, { auth = value }, label = { Text(authLabel(value)) })
                }
            }
            if (auth == "PASSWORD") {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(savePassword, { savePassword = it }, modifier = Modifier.testTag("save-password")); Text(tr("在本机加密保存密码")) }
                if (savePassword) OutlinedTextField(password, { password = it }, label = { Text(if (original?.encryptedPassword != null) tr("新密码（留空保留原密码）") else tr("密码")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("host-password"))
                Text(tr("默认每次连接时输入密码。"), style = MaterialTheme.typography.bodySmall)
            } else if (auth == "KEY") {
                OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(if (keyLabel == null) tr("导入 Ed25519 / RSA 私钥") else tr("更换私钥")) }
                keyLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(tr("支持 OpenSSH、PEM 私钥。文件会加密保存；私钥口令仅在连接时输入，不会保存。"), style = MaterialTheme.typography.bodySmall)
            } else Text(tr("连接时显示服务器的认证问题，支持一次性验证码和多轮交互。"), style = MaterialTheme.typography.bodySmall)
        }
    }
    val modeSection: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("会话模式"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "TMUX", { mode = "TMUX" }, label = { Text(tr("tmux · 推荐")) })
                FilterChip(mode == "SHELL", { mode = "SHELL" }, label = { Text(tr("普通 SSH")) })
            }
            Text(if (mode == "TMUX") tr("附着远端固定会话 itermux，适合 AI 编程长任务。服务器需要已安装 tmux。") else tr("直接打开远端 shell。断线后需重新连接，原 shell 不会被恢复。"), style = MaterialTheme.typography.bodySmall)
        }
    }
    Dialog(onDismissRequest = { if (!busy) { draft.close(); onDismiss() } }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("host-editor")) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft.close(); onDismiss() }, enabled = !busy) { Text(tr("取消")) }
                    Text(if (original == null) tr("添加主机") else tr("编辑主机"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                require(name.trim().isNotEmpty() && name.length <= 80) { tr("名称需为 1–80 个字符") }
                                val target = Target(address.trim(), port.toIntOrNull() ?: 0, username.trim(), AuthMethod.valueOf(auth), SessionMode.valueOf(mode))
                                require(auth != "KEY" || imported != null || original?.encryptedKey != null) { tr("请先导入私钥") }
                                require(!savePassword || auth != "PASSWORD" || password.isNotEmpty() || original?.encryptedPassword != null) { tr("请输入要保存的密码") }
                                withContext(Dispatchers.IO) {
                                    val encryptedPassword = if (auth == "PASSWORD" && savePassword) {
                                        if (password.isEmpty()) original?.encryptedPassword else password.toByteArray().let { bytes -> try { app.vault.encrypt(bytes, "$id:password") } finally { bytes.fill(0) } }
                                    } else null
                                    val encryptedKey = if (auth == "KEY") imported?.let { app.vault.encrypt(it, "$id:key") } ?: original?.encryptedKey else null
                                    app.database.hosts().save(Host(id, name.trim(), target.address, target.port, target.username, auth, mode, encryptedPassword, encryptedKey, keyLabel.takeIf { auth == "KEY" }, os))
                                }
                                draft.close()
                                onSaved()
                            } catch (e: IllegalArgumentException) { error = e.message }
                            catch (e: Exception) { error = tr("保存失败，无法使用本机加密存储。请重试。") }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) tr("处理中") else tr("保存")) }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 600.dp
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                connectionSection()
                                systemSection()
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                authenticationSection()
                                modeSection()
                            }
                        } else {
                            connectionSection()
                            systemSection()
                            authenticationSection()
                            HorizontalDivider()
                            modeSection()
                        }
                        error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
