package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.ImportedHost
import io.itermux.app.session.SshImport
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager

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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import io.itermux.ssh.HostSystem
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostEditor(app: ItermuxApp, original: Host?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().first()
    }
    val draft = remember(activity) { ViewModelProvider(activity)[HostEditorDraft::class.java] }
    draft.open(original)
    var name by draft.name
    var address by draft.address
    var port by draft.port
    var username by draft.username
    var auth by draft.auth
    var mode by draft.mode
    val os by draft.os
    var savePassword by draft.savePassword
    var password by draft.password
    var passphrase by draft.passphrase
    var imported by draft.imported
    var keyLabel by draft.keyLabel
    var error by draft.error
    var busy by draft.busy
    val detecting by draft.detecting
    val saved by draft.saved
    LaunchedEffect(saved) { if (saved) { draft.close(); onSaved() } }
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
    var importText by rememberSaveable { mutableStateOf("") }
    var importNote by rememberSaveable { mutableStateOf<String?>(null) }
    var importChoices by remember { mutableStateOf<List<ImportedHost>>(emptyList()) }
    val clipboard = LocalClipboardManager.current
    fun apply(host: ImportedHost) {
        name = host.name
        address = host.address
        port = host.port.toString()
        if (host.username.isNotEmpty()) username = host.username
        if (host.identityFile != null) auth = "KEY"
        importChoices = emptyList()
        importNote = host.identityFile?.let { trf("已填入 %s。该主机使用私钥 %s，请在下方导入。", host.name, it) }
            ?: trf("已填入 %s，请检查后保存。", host.name)
    }
    fun import(text: String) {
        val hosts = SshImport.parse(text)
        when {
            hosts.isEmpty() -> importNote = tr("未识别到主机。请粘贴 ssh 命令、ssh:// 地址或 ~/.ssh/config 片段。")
            hosts.size == 1 -> apply(hosts.single())
            else -> { importNote = null; importChoices = hosts }
        }
    }
    val importSection: @Composable () -> Unit = {
        GroupedSection(header = tr("快速填写"), footer = importNote ?: tr("粘贴 ssh 命令（如 ssh -p 2222 me@dev）、ssh:// 地址或 ~/.ssh/config 片段，自动填入下方信息。")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormField(importText, { importText = it }, tr("ssh 命令或配置"), tr("ssh me@dev.example.com"),
                    // A pasted ~/.ssh/config keeps its lines.
                    Modifier.weight(1f).testTag("host-import"), KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy,
                    singleLine = false)
                if (importText.isBlank()) TextButton(enabled = !busy, modifier = Modifier.testTag("host-import-paste"), onClick = {
                    importText = clipboard.getText()?.text.orEmpty().take(16 * 1024)
                    import(importText)
                }) { Text(tr("粘贴")) }
                else TextButton(enabled = !busy, modifier = Modifier.testTag("host-import-apply"), onClick = {
                    focusManager.clearFocus(); keyboard?.hide(); import(importText)
                }) { Text(tr("识别")) }
            }
        }
    }
    val connectionSection: @Composable () -> Unit = {
        GroupedSection(header = tr("连接信息")) {
            FormField(name, { name = it }, tr("名称"), tr("例如：开发工作站"), Modifier.testTag("host-name"), enabled = !busy)
            GroupedDivider()
            FormField(address, { address = it }, tr("地址 / IP"), tr("例如：dev.example.com"), Modifier.testTag("host-address"),
                KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy)
            GroupedDivider()
            Row(Modifier.height(IntrinsicSize.Min)) {
                FormField(username, { username = it }, tr("用户名"), modifier = Modifier.weight(1f).testTag("host-username"), enabled = !busy)
                VerticalDivider(Modifier.padding(vertical = 10.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                FormField(port, { port = it.filter(Char::isDigit).take(5) }, tr("端口"), modifier = Modifier.width(96.dp).testTag("host-port"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
            }
        }
    }
    val systemSection: @Composable () -> Unit = {
        GroupedSection(header = tr("远端系统"),
            footer = tr("保存时自动检测系统。未填写凭据或暂时无法连接时，将在下次连接成功后更新。")) {
            GroupedRow(if (detecting) tr("正在检测系统…") else if (os == "UNKNOWN") tr("自动检测") else tr(HostSystem.fromStored(os).label),
                leading = { HostSystemIcon(os, 32.dp) },
                trailing = { if (detecting) TextButton(onClick = draft::skipDetection) { Text(tr("跳过检测")) } })
        }
    }
    val authenticationSection: @Composable () -> Unit = {
        GroupedSection(header = tr("认证方式"), footer = when (auth) {
            "PASSWORD" -> tr("密码用于检测系统；仅勾选后才会在本机保存。留空可在连接时输入。")
            "KEY" -> tr("支持 OpenSSH、PEM 私钥。文件会加密保存；私钥口令仅用于本次检测或连接，不会保存。")
            else -> tr("连接时显示服务器的认证问题，支持一次性验证码和多轮交互。")
        }) {
            SegmentedControl(listOf("PASSWORD", "KEY", "INTERACTIVE").map { value ->
                Segment(authLabel(value), auth == value) { if (!busy) auth = value }
            }, Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hairline))
            if (auth == "PASSWORD") {
                GroupedDivider()
                GroupedRow(tr("在本机加密保存密码"), trailing = {
                    Switch(savePassword, { savePassword = it }, modifier = Modifier.testTag("save-password"), enabled = !busy)
                })
                GroupedDivider()
                FormField(password, { password = it }, if (original?.encryptedPassword != null) tr("新密码（留空保留原密码）") else tr("密码"),
                    modifier = Modifier.testTag("host-password"), visualTransformation = PasswordVisualTransformation(), enabled = !busy)
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
                GroupedDivider()
                FormField(passphrase, { passphrase = it }, tr("私钥口令（仅用于检测，可留空）"),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("host-passphrase"), enabled = !busy)
            }
        }
    }
    val modeSection: @Composable () -> Unit = {
        GroupedSection(header = tr("会话模式"), footer = if (mode == "TMUX") tr("每次连接创建独立的远端 tmux 会话，可同时开多个；重连附着同一会话。服务器需要已安装 tmux（Windows 主机为 psmux）。") else tr("直接打开远端 shell。断线后需重新连接，原 shell 不会被恢复。")) {
            SegmentedControl(listOf(
                Segment(tr("tmux · 推荐"), mode == "TMUX") { if (!busy) mode = "TMUX" },
                Segment(tr("普通 SSH"), mode == "SHELL") { if (!busy) mode = "SHELL" },
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
                    TextButton(enabled = !busy, modifier = Modifier.align(Alignment.CenterEnd).testTag("save-host"),
                        onClick = { focusManager.clearFocus(); keyboard?.hide(); draft.save(app, original) }) {
                        Text(if (busy) tr("处理中") else tr("保存"), fontWeight = FontWeight.SemiBold)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 600.dp
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.Page, vertical = Spacing.Tight),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                        error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = Spacing.Page)) }
                        if (original == null) importSection()
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
    draft.pending.value?.let { PromptDialog(it) }
    if (importChoices.isNotEmpty()) AlertDialog(onDismissRequest = { importChoices = emptyList() },
        title = { Text(trf("找到 %d 台主机", importChoices.size)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                importChoices.forEach { host ->
                    GroupedRow(host.name, Modifier.testTag("host-import-choice-${host.name}"),
                        subtitle = "${host.username.ifEmpty { "?" }}@${host.address}:${host.port}", onClick = { apply(host) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { importChoices = emptyList() }) { Text(tr("取消")) } })
}

/** A borderless field for grouped forms: the panel supplies the shape, dividers separate rows. */
@Composable
private fun FormField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String? = null,
    modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None, enabled: Boolean = true, singleLine: Boolean = true) {
    TextField(value, onValueChange, label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled, singleLine = singleLine, maxLines = if (singleLine) 1 else 4, keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent))
}
