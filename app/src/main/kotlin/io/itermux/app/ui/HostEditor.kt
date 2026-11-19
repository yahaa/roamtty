package io.itermux.app.ui

import io.itermux.app.tr

import android.provider.OpenableColumns
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
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
import java.io.ByteArrayOutputStream
import android.content.Context
import android.net.Uri

/** Adds or edits a host. With [forSession], it fills in a new host on the way to a new session, then hands over its id. */
@Composable
fun HostEditor(app: ItermuxApp, original: Host?, onDismiss: () -> Unit, onSaved: (hostId: String) -> Unit,
    forSession: Boolean = false) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().first()
    }
    val draft = remember(activity) { ViewModelProvider(activity)[HostEditorDraft::class.java] }
    // A session started from here would otherwise ask for the password again right after saving.
    draft.open(original, savePasswordByDefault = forSession)
    val busy by draft.busy
    val saved by draft.saved
    LaunchedEffect(saved) { if (saved) { val id = draft.id; draft.close(); onSaved(id) } }
    val pickKey = rememberKeyPicker(draft)
    val sections = EditorSections(connectionSection(draft), systemSection(draft),
        authenticationSection(draft, replacesPassword = original?.encryptedPassword != null, pickKey), modeSection(draft))
    val cancel = { draft.close(); onDismiss() }
    Dialog(onDismissRequest = { if (!busy) cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SheetSystemBars(MaterialTheme.colorScheme.background)
        Surface(Modifier.fillMaxSize().testTag("host-editor"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val landscape = isLandscape()
                EditorBar(if (forSession) tr("新建会话") else if (original == null) tr("添加主机") else tr("编辑主机"),
                    saveLabel = if (busy) tr("处理中") else if (forSession) tr("下一步") else tr("保存"), busy, landscape, cancel) {
                    focusManager.clearFocus(); keyboard?.hide(); draft.save(app, original)
                }
                EditorForm(sections, draft.error.value, landscape, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    draft.pending.value?.let { PromptDialog(it) }
}

/** One grouped section of the form, laid out alone in portrait or beside another in landscape. */
private class SectionParts(val header: String, val footer: String?, val content: @Composable ColumnScope.() -> Unit)

private class EditorSections(val connection: SectionParts, val system: SectionParts, val authentication: SectionParts,
    val mode: SectionParts)

/** The form's bar: cancel and save on opposite edges, the title between them. */
@Composable
private fun EditorBar(title: String, saveLabel: String, busy: Boolean, landscape: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    val cancel: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = onCancel, enabled = !busy, modifier = modifier) { Text(tr("取消")) }
    }
    val save: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(enabled = !busy, modifier = modifier.testTag("save-host"), onClick = onSave) {
            Text(saveLabel, fontWeight = FontWeight.SemiBold)
        }
    }
    // In landscape, where height is short, the bar is the one of every other landscape page.
    if (landscape) LandscapeBar(title, leading = { cancel(Modifier) }) { save(Modifier) }
    // Modal form bar: cancel and save stay on opposite edges, the title is centered.
    else Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        cancel(Modifier.align(Alignment.CenterStart))
        Text(title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        save(Modifier.align(Alignment.CenterEnd))
    }
}

/** The four sections under what went wrong, one under the other or, with the width for it, as two rows of two. */
@Composable
private fun EditorForm(sections: EditorSections, error: String?, landscape: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        // Landscape and wide screens: the four sections as two rows of two, so every field is in view at once.
        val wide = landscape || maxWidth >= 600.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Page, vertical = if (wide) Spacing.Hairline else Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(if (wide) Spacing.Page else Spacing.Section)) {
            error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.Page)) }
            if (wide) {
                SectionPair(sections.connection, sections.authentication)
                SectionPair(sections.system, sections.mode)
            } else listOf(sections.connection, sections.system, sections.authentication, sections.mode).forEach { section ->
                GroupedSection(header = section.header, footer = section.footer, content = section.content)
            }
        }
    }
}

private fun connectionSection(draft: HostEditorDraft) = SectionParts(tr("连接信息"), null) {
    var name by draft.name
    var address by draft.address
    var port by draft.port
    var username by draft.username
    val busy by draft.busy
    FormField(name, { name = it }, tr("名称"), Modifier.testTag("host-name"), tr("例如：开发工作站"), enabled = !busy)
    GroupedDivider()
    FormField(address, { address = it }, tr("地址 / IP"), Modifier.testTag("host-address"), tr("例如：dev.example.com"),
        KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy)
    GroupedDivider()
    Row(Modifier.height(IntrinsicSize.Min)) {
        FormField(username, { username = it }, tr("用户名"), modifier = Modifier.weight(1f).testTag("host-username"), enabled = !busy)
        VerticalDivider(Modifier.padding(vertical = 10.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        FormField(port, { port = it.filter(Char::isDigit).take(5) }, tr("端口"), modifier = Modifier.width(96.dp).testTag("host-port"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
    }
}

private fun systemSection(draft: HostEditorDraft) = SectionParts(tr("远端系统"),
    tr("保存时自动检测系统。未填写凭据或暂时无法连接时，将在下次连接成功后更新。")) {
    val os by draft.os
    val detecting by draft.detecting
    GroupedRow(if (detecting) tr("正在检测系统…") else if (os == "UNKNOWN") tr("自动检测") else tr(HostSystem.fromStored(os).label),
        leading = { HostSystemIcon(os, 32.dp) },
        trailing = { if (detecting) TextButton(onClick = draft::skipDetection) { Text(tr("跳过检测")) } })
}

/** How the host is logged in to. [replacesPassword]: a password is saved already, and typing one replaces it. */
private fun authenticationSection(draft: HostEditorDraft, replacesPassword: Boolean, pickKey: () -> Unit) = SectionParts(tr("认证方式"),
    when (draft.auth.value) {
        "PASSWORD" -> tr("密码用于检测系统；仅勾选后才会在本机保存。留空可在连接时输入。")
        "KEY" -> tr("支持 OpenSSH、PEM 私钥。文件会加密保存；私钥口令仅用于本次检测或连接，不会保存。")
        else -> tr("连接时显示服务器的认证问题，支持一次性验证码和多轮交互。")
    }) {
    var auth by draft.auth
    var savePassword by draft.savePassword
    var password by draft.password
    var passphrase by draft.passphrase
    val keyLabel by draft.keyLabel
    val busy by draft.busy
    SegmentedControl(listOf("PASSWORD", "KEY", "INTERACTIVE").map { value ->
        Segment(authLabel(value), auth == value) { if (!busy) auth = value }
    }, Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hairline))
    if (auth == "PASSWORD") {
        GroupedDivider()
        GroupedRow(tr("在本机加密保存密码"), trailing = {
            Switch(savePassword, { savePassword = it }, modifier = Modifier.testTag("save-password"), enabled = !busy)
        })
        GroupedDivider()
        FormField(password, { password = it }, if (replacesPassword) tr("新密码（留空保留原密码）") else tr("密码"),
            modifier = Modifier.testTag("host-password"), visualTransformation = PasswordVisualTransformation(), enabled = !busy)
    } else if (auth == "KEY") {
        GroupedDivider()
        // The trailing action stays one short word, so the title keeps its width in any language.
        GroupedRow(keyLabel ?: tr("尚未导入私钥"), Modifier.testTag("host-key"),
            subtitle = if (keyLabel == null) tr("支持 Ed25519 / RSA 私钥") else tr("已导入私钥"),
            leading = { Icon(painterResource(R.drawable.ic_keys), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
            trailing = {
                TextButton(onClick = pickKey, enabled = !busy) { Text(if (keyLabel == null) tr("导入") else tr("更换"), maxLines = 1) }
            }, enabled = !busy, onClick = pickKey)
        GroupedDivider()
        FormField(passphrase, { passphrase = it }, tr("私钥口令（仅用于检测，可留空）"),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("host-passphrase"), enabled = !busy)
    }
}

private fun modeSection(draft: HostEditorDraft) = SectionParts(tr("会话模式"),
    if (draft.mode.value == "TMUX") tr("每次连接创建独立的远端 tmux 会话，可同时开多个；重连附着同一会话。服务器需要已安装 tmux（Windows 主机为 psmux）。")
    else tr("直接打开远端 shell。断线后需重新连接，原 shell 不会被恢复。")) {
    var mode by draft.mode
    val busy by draft.busy
    SegmentedControl(listOf(
        Segment(tr("tmux · 推荐"), mode == "TMUX") { if (!busy) mode = "TMUX" },
        Segment(tr("普通 SSH"), mode == "SHELL") { if (!busy) mode = "SHELL" },
    ), Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hairline))
}

/** Opens the system's file picker for a private key and puts the chosen one in [draft]. */
@Composable
private fun rememberKeyPicker(draft: HostEditorDraft): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            draft.busy.value = true
            try {
                val (key, label) = withContext(Dispatchers.IO) { readPrivateKey(context, uri) }
                draft.imported.value?.fill(0)
                draft.imported.value = key
                draft.keyLabel.value = label
                draft.error.value = null
            } catch (e: Exception) { draft.error.value = tr("无法导入，请选择不超过 256 KiB 的 OpenSSH/PEM 私钥。") }
            finally { draft.busy.value = false }
        }
    }
    return { picker.launch(arrayOf("*/*")) }
}

/** The private key at [uri], at most 256 KiB, with the file's name to show for it. */
private fun readPrivateKey(context: Context, uri: Uri): Pair<ByteArray, String> {
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val buffer = ByteArrayOutputStream()
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
    val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: tr("已导入私钥")
    return bytes to label.take(120)
}

/**
 * Two sections side by side: headers, panels and footers each on one line, and the two panels as tall as the taller
 * one, so the columns line up however much the authentication panel holds. The panels are measured first and their
 * backgrounds after, at the shared height: the segmented controls inside cannot answer intrinsic measurements.
 */
@Composable
private fun SectionPair(left: SectionParts, right: SectionParts) {
    val gap = Spacing.Page
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(left, right).forEach {
                Text(it.header, style = MaterialTheme.typography.sectionHeader, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Tight).semantics { heading() })
            }
        }
        val shape = MaterialTheme.shapes.medium
        val color = MaterialTheme.colorScheme.groupedContainer
        Layout(contents = listOf(
            { repeat(2) { Box(Modifier.clip(shape).background(color).testTag("section-panel")) } },
            { listOf(left, right).forEach { Column(Modifier.clip(shape), content = it.content) } },
        )) { (backgrounds, panels), constraints ->
            val space = gap.roundToPx()
            val width = (constraints.maxWidth - space) / 2
            val placed = panels.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val height = placed.maxOf { it.height }
            val behind = backgrounds.map { it.measure(Constraints.fixed(width, height)) }
            layout(constraints.maxWidth, height) {
                behind.forEachIndexed { index, panel -> panel.place(index * (width + space), 0) }
                placed.forEachIndexed { index, panel -> panel.place(index * (width + space), 0) }
            }
        }
        if (left.footer != null || right.footer != null) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(left.footer, right.footer).forEach {
                Text(it.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = Spacing.Page, end = Spacing.Page, top = Spacing.Tight))
            }
        }
    }
}

/** A borderless field for grouped forms: the panel supplies the shape, dividers separate rows. */
@Composable
private fun FormField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    placeholder: String? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None, enabled: Boolean = true) {
    TextField(value, onValueChange, label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent))
}
