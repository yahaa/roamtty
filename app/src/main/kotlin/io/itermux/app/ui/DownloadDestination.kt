package io.itermux.app.ui

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.itermux.app.DownloadDirectory
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.ssh.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberDownloadDirectory(): DownloadDirectory {
    val context = LocalContext.current.applicationContext
    val directory = remember(context) { DownloadDirectory(context) }
    DisposableEffect(directory) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> directory.refresh() }
        directory.preferences.registerOnSharedPreferenceChangeListener(listener)
        directory.refresh()
        onDispose { directory.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return directory
}

private fun initialDirectory(directory: DownloadDirectory): Uri = directory.uri
    ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

internal class DownloadActions(val busy: Boolean, val directoryLabel: String,
    val start: (files: List<DownloadItem>, saveAs: Boolean) -> Unit)

/** Both download entry points use the same pending request and destination policy. */
@Composable
internal fun rememberDownloadActions(session: TerminalConnection): DownloadActions {
    val context = LocalContext.current
    val directory = rememberDownloadDirectory()
    val transfer = session.transfers
    var savedPaths by rememberSaveable(session.id) { mutableStateOf(arrayListOf<String>()) }
    var mode by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var pickerOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    var pickedUri by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    fun pendingFiles() = transfer.pendingDownloads.ifEmpty { savedPaths.map { DownloadItem(it) } }
    fun clearPending() {
        transfer.pendingDownloads = emptyList()
        savedPaths = arrayListOf()
        mode = null
        pickedUri = null
        pickerOpen = false
    }
    fun canStart() = session.state.value.phase == Phase.CONNECTED && !transfer.status.value.running
    fun message(text: String) { Toast.makeText(context, tr(text), Toast.LENGTH_LONG).show() }
    fun onPicked(uri: Uri?) {
        if (uri == null) {
            clearPending()
            transfer.downloadDialog = true
        } else {
            pickedUri = uri.toString()
            pickerOpen = false
        }
    }
    val singlePicker = rememberLauncherForActivityResult(CreateDownloadDocument(initialDirectory(directory)), ::onPicked)
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::onPicked)

    // Saveable stages resume validation/result handling after rotation; an open picker is not relaunched.
    LaunchedEffect(mode, pickedUri, pickerOpen) {
        withContext(Dispatchers.Main.immediate) request@ {
            val requestMode = mode ?: return@request
            if (pickerOpen) return@request
            val files = pendingFiles()
            val selected = pickedUri?.let(Uri::parse)
            if (files.isEmpty() || !canStart()) {
                if (selected != null && requestMode == "save-as" && files.size == 1) withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, selected) }
                }
                clearPending()
                message("下载未开始，请重新连接后再试。")
                return@request
            }
            if (selected != null) {
                if (requestMode == "default" && !directory.save(selected)) {
                    clearPending()
                    transfer.downloadDialog = true
                    message("无法保存此目录的访问权限，请选择其他目录或使用另存为。")
                    return@request
                }
                // The connection may have changed while querying the document provider.
                if (canStart()) {
                    if (requestMode == "save-as" && files.size == 1) transfer.download(context, session, selected, files.single().path)
                    else transfer.downloadFiles(context, session, selected, files)
                } else message("下载未开始，请重新连接后再试。")
                clearPending()
            } else if (requestMode == "default") {
                val saved = directory.uri
                if (saved != null && directory.isAvailable(saved)) {
                    if (canStart()) transfer.downloadFiles(context, session, saved, files)
                    else message("下载未开始，请重新连接后再试。")
                    clearPending()
                } else {
                    if (saved != null) message("默认下载目录不可用，请重新选择。")
                    pickerOpen = true
                    folderPicker.launch(initialDirectory(directory))
                }
            } else {
                pickerOpen = true
                if (files.size == 1) singlePicker.launch(files.single().name)
                else folderPicker.launch(initialDirectory(directory))
            }
        }
    }
    return DownloadActions(mode != null, directory.label) { files, saveAs ->
        if (mode == null && files.isNotEmpty() && canStart()) {
            transfer.pendingDownloads = files.toList()
            transfer.selectedDownloads = files.toList()
            savedPaths = ArrayList(files.map { it.path })
            transfer.downloadDialog = false
            mode = if (saveAs) "save-as" else "default"
        }
    }
}

@Composable
internal fun DownloadDirectorySetting() {
    val directory = rememberDownloadDirectory()
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { pickedUri = uri.toString(); error = false }
    }
    LaunchedEffect(pickedUri) {
        val uri = pickedUri?.let(Uri::parse) ?: return@LaunchedEffect
        error = !directory.save(uri)
        pickedUri = null
    }
    Text(tr("下载"), style = MaterialTheme.typography.titleMedium)
    Text(tr("首次选择目录后，后续下载自动保存到此处。"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    Text(directory.label.ifEmpty { tr("尚未设置下载目录") }, modifier = Modifier.testTag("settings-download-directory"))
    if (error) Text(tr("无法保存此目录的访问权限，请选择其他目录或使用另存为。"), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = { picker.launch(initialDirectory(directory)) }, enabled = pickedUri == null,
        modifier = Modifier.testTag("settings-download-change")) {
        Text(tr(if (directory.uri == null) "选择下载目录" else "更换下载目录"))
    }
}
