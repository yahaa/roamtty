package io.itermux.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.itermux.app.session.TerminalConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal enum class FileAction { UPLOAD, DOWNLOAD }

@Composable
internal fun FileTransferUi(session: TerminalConnection, action: FileAction?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploadUri by remember { mutableStateOf<Uri?>(null) }
    var uploadName by remember { mutableStateOf("") }
    var uploadPath by remember { mutableStateOf("") }
    var downloadDialog by remember { mutableStateOf(false) }
    var downloadPath by remember { mutableStateOf("") }
    var transferName by remember { mutableStateOf("") }
    var transferred by remember { mutableLongStateOf(0L) }
    var transferring by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var transferJob by remember { mutableStateOf<Job?>(null) }

    fun startTransfer(name: String, operation: suspend ((Long) -> Unit) -> Unit) {
        transferName = name
        transferred = 0
        result = null
        transferring = true
        transferJob = scope.launch {
            var lastReported = 0L
            try {
                operation { bytes ->
                    if (bytes - lastReported >= 256 * 1024) {
                        transferred = bytes
                        lastReported = bytes
                    }
                }
                result = "传输完成"
            } catch (_: CancellationException) {
                result = "传输已取消"
            } catch (e: Exception) {
                result = when (e) {
                    is java.nio.file.FileAlreadyExistsException -> "远端文件已存在，请换一个路径"
                    else -> "传输失败：${e.message ?: "请检查路径和 SFTP 服务"}"
                }
            } finally {
                transferring = false
                transferJob = null
            }
        }
    }

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
            startTransfer("下载 $path") { progress ->
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("无法打开本地文件")
                        output.use { session.download(path, it, progress) }
                    }
                } catch (e: Exception) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    throw e
                }
            }
        }
    }
    LaunchedEffect(action) {
        when (action) {
            FileAction.UPLOAD -> { onConsumed(); uploadPicker.launch(arrayOf("*/*")) }
            FileAction.DOWNLOAD -> { onConsumed(); downloadDialog = true }
            null -> Unit
        }
    }

    uploadUri?.let { uri -> AlertDialog(onDismissRequest = { uploadUri = null },
        title = { Text("上传文件") },
        text = { Column {
            Text("本地：$uploadName")
            OutlinedTextField(uploadPath, { uploadPath = it }, label = { Text("远端文件路径") },
                supportingText = { Text("相对 SFTP 主目录，或填写绝对路径；不会覆盖同名文件") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("upload-remote-path"))
        } },
        confirmButton = { TextButton(onClick = {
            val path = uploadPath.trim()
            uploadUri = null
            startTransfer("上传 $uploadName") { progress ->
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开本地文件")
                    input.use { session.upload(path, it, progress) }
                }
            }
        }, enabled = uploadPath.isNotBlank()) { Text("上传") } },
        dismissButton = { TextButton(onClick = { uploadUri = null }) { Text("取消") } }) }

    if (downloadDialog) AlertDialog(onDismissRequest = { downloadDialog = false },
        title = { Text("下载文件") },
        text = { OutlinedTextField(downloadPath, { downloadPath = it }, label = { Text("远端文件路径") },
            supportingText = { Text("相对 SFTP 主目录，或填写绝对路径") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("download-remote-path")) },
        confirmButton = { TextButton(onClick = {
            val name = downloadPath.trim().substringAfterLast('/').ifBlank { "download" }
            downloadDialog = false
            downloadPicker.launch(name)
        }, enabled = downloadPath.isNotBlank()) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { downloadDialog = false }) { Text("取消") } })

    if (transferring || result != null) AlertDialog(onDismissRequest = { if (!transferring) result = null },
        title = { Text(transferName) },
        text = { Column {
            if (transferring) CircularProgressIndicator()
            Text(result ?: "已传输 ${transferred / 1024} KiB", modifier = Modifier.padding(top = 12.dp).testTag("transfer-status"))
        } },
        confirmButton = { TextButton(onClick = {
            if (transferring) transferJob?.cancel() else result = null
        }) { Text(if (transferring) "取消传输" else "完成") } })
}
