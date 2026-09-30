package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import android.net.Uri
import android.provider.OpenableColumns
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.RemoteDirectory

internal enum class FileAction { UPLOAD, DOWNLOAD, SELECT_IMAGE }

@Composable
internal fun FileTransferUi(session: TerminalConnection, action: FileAction?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val transfer = session.transfers
    val status by transfer.status.collectAsStateWithLifecycle()
    var uploadUri by transfer::uploadUri
    var uploadName by transfer::uploadName
    var uploadPath by transfer::uploadPath
    var downloadDialog by transfer::downloadDialog
    var downloadPath by transfer::downloadPath

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            uploadUri = uri
            uploadName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "upload"
            uploadPath = uploadName.replace('/', '_').replace('\\', '_')
        }
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val path = downloadPath.trim()
            transfer.download(context, session, uri, path)
        }
    }
    // Independent of OpenDocument upload: Photo Picker / gallery images only.
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) transfer.uploadImageForCli(context, session, uri)
    }
    LaunchedEffect(action) {
        when (action) {
            FileAction.UPLOAD -> { onConsumed(); uploadPicker.launch(arrayOf("*/*")) }
            FileAction.DOWNLOAD -> { onConsumed(); downloadDialog = true }
            FileAction.SELECT_IMAGE -> {
                onConsumed()
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            null -> Unit
        }
    }

    uploadUri?.let { uri -> AlertDialog(onDismissRequest = { uploadUri = null },
        title = { Text(tr("上传文件")) },
        text = { Column {
            Text(trf("本地：%s", uploadName))
            OutlinedTextField(uploadPath, { uploadPath = it }, label = { Text(tr("远端文件路径")) },
                supportingText = { Text(tr("进入目录会更新目标路径；也可手动填写完整路径，不会覆盖同名文件")) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("upload-remote-path"))
            RemotePathDropdown(session, FileAction.UPLOAD, uploadName) { uploadPath = it }
        } },
        confirmButton = { TextButton(onClick = {
            val path = uploadPath.trim()
            uploadUri = null
            val size = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                }
            }.getOrNull() ?: -1L
            transfer.upload(context, session, uri, path, uploadName, size)
        }, enabled = uploadPath.isNotBlank()) { Text(tr("上传")) } },
        dismissButton = { TextButton(onClick = { uploadUri = null }) { Text(tr("取消")) } }) }

    if (downloadDialog) AlertDialog(onDismissRequest = { downloadDialog = false },
        title = { Text(tr("下载文件")) },
        text = { Column {
            OutlinedTextField(downloadPath, { downloadPath = it }, label = { Text(tr("远端文件路径")) },
                supportingText = { Text(tr("选择远端文件，或手动填写路径")) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("download-remote-path"))
            RemotePathDropdown(session, FileAction.DOWNLOAD) { downloadPath = it }
        } },
        confirmButton = { TextButton(onClick = {
            val name = downloadPath.trim().substringAfterLast('/').ifBlank { "download" }
            downloadDialog = false
            downloadPicker.launch(name)
        }, enabled = downloadPath.isNotBlank() && !downloadPath.trim().endsWith('/')) { Text(tr("选择保存位置")) } },
        dismissButton = { TextButton(onClick = { downloadDialog = false }) { Text(tr("取消")) } })

    LaunchedEffect(status.result) {
        val result = status.result ?: return@LaunchedEffect
        if ("itermux-img-" in result) {
            Toast.makeText(context, result, Toast.LENGTH_LONG).show()
        }
    }

    if (status.running || status.result != null) TransferStatusDialog(status.name, status.bytes, status.total,
        status.running, status.result, onCancel = transfer::cancel, onDismiss = transfer::dismiss)
}

@Composable
internal fun TransferStatusDialog(name: String, transferred: Long, totalBytes: Long,
    transferring: Boolean, result: String?, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!transferring) onDismiss() }, modifier = Modifier.testTag("transfer-dialog"),
        title = { Text(name) },
        text = { Column {
            if (transferring) {
                if (totalBytes >= 0) LinearProgressIndicator(
                    progress = { if (totalBytes == 0L) 1f else (transferred.toFloat() / totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("transfer-progress"))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("transfer-progress"))
            }
            Text(result ?: if (totalBytes >= 0) "${sizeLabel(transferred)} / ${sizeLabel(totalBytes)}" else trf("已传输 %s", sizeLabel(transferred)),
                modifier = Modifier.padding(top = 12.dp).testTag("transfer-status"))
        } },
        confirmButton = { TextButton(onClick = {
            if (transferring) onCancel() else onDismiss()
        }) { Text(if (transferring) tr("取消传输") else tr("完成")) } })
}

private fun sizeLabel(bytes: Long): String = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KiB"

@Composable
internal fun RemotePathDropdown(
    session: TerminalConnection,
    action: FileAction,
    uploadName: String = "",
    onPathSelected: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val availableHeight = if (windowHeightPx > 0) with(LocalDensity.current) {
        (windowHeightPx * 0.45f).toDp()
    } else 320.dp
    val menuMaxHeight = minOf(
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 112.dp else 320.dp,
        availableHeight
    )
    var expanded by remember { mutableStateOf(false) }
    var directory by remember(session.id) { mutableStateOf("") }
    var listing by remember(session.id) { mutableStateOf<RemoteDirectory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableLongStateOf(0L) }
    LaunchedEffect(expanded, directory, retry, session.id) {
        if (!expanded) return@LaunchedEffect
        loading = true
        error = null
        listing = null
        try {
            listing = session.listRemoteDirectory(directory)
        } catch (e: Exception) {
            error = e.message ?: tr("无法读取目录")
        } finally {
            loading = false
        }
    }
    Box(Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            expanded = true
        }, modifier = Modifier.fillMaxWidth().testTag("remote-path-dropdown")) {
            Text(listing?.let { trf("当前目录：%s ▾", it.path) }
                ?: if (action == FileAction.UPLOAD) tr("选择远端目录 ▾") else tr("选择远端文件或目录 ▾"),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 360.dp).heightIn(max = menuMaxHeight).testTag("remote-path-menu")) {
            if (loading) DropdownMenuItem(text = { Text(tr("正在读取远端目录…")) }, onClick = {}, enabled = false)
            error?.let { message ->
                DropdownMenuItem(text = { Text(trf("读取失败：%s，点击重试", tr(message.take(120)))) }, onClick = { retry++ },
                    modifier = Modifier.testTag("remote-path-retry"))
                if (directory.isNotEmpty()) DropdownMenuItem(text = { Text(tr("返回主目录")) },
                    onClick = {
                        directory = ""
                        if (action == FileAction.UPLOAD) onPathSelected(uploadName.replace('/', '_').replace('\\', '_'))
                    })
            }
            listing?.let { current ->
                DropdownMenuItem(text = { Text(if (action == FileAction.UPLOAD) tr("使用此目录") else tr("选择此目录并输入文件名")) },
                    onClick = {
                        onPathSelected(remoteChildPath(current.path, if (action == FileAction.UPLOAD) uploadName.replace('/', '_').replace('\\', '_') else ""))
                        expanded = false
                    }, modifier = Modifier.testTag("remote-use-directory"))
                if (current.path != "/") DropdownMenuItem(text = { Text(tr("↑ 上级目录")) },
                    onClick = {
                        directory = current.path.substringBeforeLast('/', "").ifEmpty { "/" }
                        if (action == FileAction.UPLOAD) onPathSelected(remoteChildPath(directory, uploadName.replace('/', '_').replace('\\', '_')))
                    },
                    modifier = Modifier.testTag("remote-parent-directory"))
                current.entries.filter { it.isDirectory || action == FileAction.DOWNLOAD }.take(200).forEach { entry ->
                    DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (entry.isDirectory) "▸" else "•")
                        Spacer(Modifier.width(8.dp))
                        Text(entry.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } },
                        onClick = {
                            val path = remoteChildPath(current.path, entry.name)
                            if (entry.isDirectory) {
                                directory = path
                                if (action == FileAction.UPLOAD) onPathSelected(remoteChildPath(path, uploadName.replace('/', '_').replace('\\', '_')))
                            }
                            else { onPathSelected(path); expanded = false }
                        }, modifier = Modifier.testTag("remote-entry-${entry.name}"))
                }
                if (current.entries.size > 200) DropdownMenuItem(text = { Text(tr("仅显示前 200 项，可手动输入路径")) }, onClick = {}, enabled = false)
            }
        }
    }
}

private fun remoteChildPath(directory: String, name: String): String =
    if (name.isEmpty()) "${directory.trimEnd('/')}/" else "${directory.trimEnd('/')}/$name"
