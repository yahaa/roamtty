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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import io.itermux.app.R
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
        GroupedSection(header = tr("连接信息")) {
            FormField(name, { name = it }, tr("名称"), tr("例如：开发工作站"), Modifier.testTag("host-name"))
            GroupedDivider()
            FormField(address, { address = it }, tr("地址 / IP"), tr("例如：dev.example.com"), Modifier.testTag("host-address"),
                KeyboardOptions(keyboardType = KeyboardType.Uri))
            GroupedDivider()
            Row(Modifier.height(IntrinsicSize.Min)) {
                FormField(username, { username = it }, tr("用户名"), modifier = Modifier.weight(1f))
                VerticalDivider(Modifier.padding(vertical = 10.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                FormField(port, { port = it.filter(Char::isDigit).take(5) }, tr("端口"), modifier = Modifier.width(96.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
    }
    val systemSection: @Composable () -> Unit = {
        GroupedSection(header = tr("远端系统"),
            footer = if (os == "UNKNOWN") tr("旧主机尚未设置系统类型，请选择后保存。") else null) {
            SegmentedControl(listOf("LINUX" to "Linux", "WINDOWS" to "Windows", "MACOS" to "macOS", "OTHER" to tr("其他")).map { (value, label) ->
                Segment(label, os == value) { os = value }
            }, Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hairline))
        }
    }
    val authenticationSection: @Composable () -> Unit = {
        GroupedSection(header = tr("认证方式"), footer = when (auth) {
            "PASSWORD" -> tr("默认每次连接时输入密码。")
            "KEY" -> tr("支持 OpenSSH、PEM 私钥。文件会加密保存；私钥口令仅在连接时输入，不会保存。")
            else -> tr("连接时显示服务器的认证问题，支持一次性验证码和多轮交互。")
        }) {
            SegmentedControl(listOf("PASSWORD", "KEY", "INTERACTIVE").map { value ->
                Segment(authLabel(value), auth == value) { auth = value }
            }, Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hairline))
            if (auth == "PASSWORD") {
                GroupedDivider()
                GroupedRow(tr("在本机加密保存密码"), trailing = {
                    Switch(savePassword, { savePassword = it }, modifier = Modifier.testTag("save-password"))
                })
                if (savePassword) {
                    GroupedDivider()
                    FormField(password, { password = it }, if (original?.encryptedPassword != null) tr("新密码（留空保留原密码）") else tr("密码"),
                        modifier = Modifier.testTag("host-password"), visualTransformation = PasswordVisualTransformation())
                }
            } else if (auth == "KEY") {
                GroupedDivider()
                GroupedRow(keyLabel ?: tr("尚未导入私钥"), subtitle = if (keyLabel == null) null else tr("已导入私钥"),
                    leading = { Icon(painterResource(R.drawable.ic_keys), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
                    trailing = {
                        TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                            Text(if (keyLabel == null) tr("导入 Ed25519 / RSA 私钥") else tr("更换私钥"))
                        }
                    })
            }
        }
    }
    val modeSection: @Composable () -> Unit = {
        GroupedSection(header = tr("会话模式"), footer = if (mode == "TMUX") tr("每次连接创建独立的远端 tmux 会话，可同时开多个；重连附着同一会话。服务器需要已安装 tmux。") else tr("直接打开远端 shell。断线后需重新连接，原 shell 不会被恢复。")) {
            SegmentedControl(listOf(
                Segment(tr("tmux · 推荐"), mode == "TMUX") { mode = "TMUX" },
                Segment(tr("普通 SSH"), mode == "SHELL") { mode = "SHELL" },
            ), Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hairline))
        }
    }
    Dialog(onDismissRequest = { if (!busy) { draft.close(); onDismiss() } }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SheetSystemBars(MaterialTheme.colorScheme.background)
        Surface(Modifier.fillMaxSize().testTag("host-editor"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                // Modal form bar: cancel and save stay on opposite edges, the title is centered.
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { draft.close(); onDismiss() }, enabled = !busy, modifier = Modifier.align(Alignment.CenterStart)) { Text(tr("取消")) }
                    Text(if (original == null) tr("添加主机") else tr("编辑主机"), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = !busy, modifier = Modifier.align(Alignment.CenterEnd), onClick = {
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
                    }) { Text(if (busy) tr("处理中") else tr("保存"), fontWeight = FontWeight.SemiBold) }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 600.dp
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.Page, vertical = Spacing.Tight),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                        error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = Spacing.Page)) }
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                                connectionSection()
                                systemSection()
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                                authenticationSection()
                                modeSection()
                            }
                        } else {
                            connectionSection()
                            systemSection()
                            authenticationSection()
                            modeSection()
                        }
                    }
                }
            }
        }
    }
    }
}

/** A borderless field for grouped forms: the panel supplies the shape, dividers separate rows. */
@Composable
private fun FormField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String? = null,
    modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None) {
    TextField(value, onValueChange, label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } },
        singleLine = true, keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent))
}
