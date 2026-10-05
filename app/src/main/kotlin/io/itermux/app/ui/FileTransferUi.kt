package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase
import io.itermux.ssh.RemoteDirectory
import io.itermux.ssh.formatRemoteMtime
import io.itermux.ssh.formatRemoteSize
import io.itermux.ssh.joinRemotePath
import io.itermux.ssh.parentRemotePath
import kotlinx.coroutines.CancellationException

internal enum class FileAction { UPLOAD, DOWNLOAD, SELECT_IMAGE }

@Composable
internal fun FileTransferUi(session: TerminalConnection, action: FileAction?, onInsertPath: ((String) -> Unit)? = null, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val transfer = session.transfers
    val status by transfer.status.collectAsStateWithLifecycle()

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (name, size) = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to (if (cursor.isNull(1)) -1L else cursor.getLong(1)) else null
                }
            }.getOrNull() ?: (null to -1L)
            transfer.uploadName = name?.takeIf { it.isNotBlank() } ?: "upload"
            transfer.uploadSize = size
            transfer.uploadPath = safeUploadName(transfer.uploadName)
            transfer.uploadDirectory = null
            transfer.uploadUri = uri
        }
    }
    // Independent of OpenDocument upload: Photo Picker / gallery images only, several at once.
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        if (uris.isNotEmpty() && !transfer.uploadSharedImages(context, session, uris))
            Toast.makeText(context, tr("已有传输进行中，请稍后再试"), Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(action) {
        when (action) {
            FileAction.UPLOAD -> { onConsumed(); uploadPicker.launch(arrayOf("*/*")) }
            FileAction.DOWNLOAD -> { onConsumed(); session.openTerminalDownloads() }
            FileAction.SELECT_IMAGE -> {
                onConsumed()
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            null -> Unit
        }
    }

    transfer.uploadUri?.let { uri -> UploadFileSheet(session, uri) }
    DownloadFilesUi(session)
    // A finished image insert needs no dialog: the paths are already in the prompt, so confirm with a toast.
    val imagesDone = status.imageTotal > 0 && !status.running && (status.succeeded == true || status.cancelled)
    LaunchedEffect(imagesDone) {
        if (!imagesDone) return@LaunchedEffect
        if (status.succeeded == true) Toast.makeText(context, status.result, Toast.LENGTH_SHORT).show()
        transfer.dismiss()
    }
    if (status.imageTotal > 0) {
        if (status.running || (status.result != null && !imagesDone))
            ImageInsertDialog(status, onCancel = transfer::cancel, onDismiss = transfer::dismiss, onInsertPath = onInsertPath)
    } else if (status.running || status.result != null) TransferStatusDialog(status,
        onCancel = transfer::cancel, onDismiss = transfer::dismiss)
}

/** The Photo Picker's selection cap for one insert. */
private const val MAX_IMAGES = 9

/** A local display name can contain separators; never let it select another remote directory. */
internal fun safeUploadName(name: String) = name.replace('/', '_').replace('\\', '_')

/** A bare name lands in [directory]; an absolute or `~/` path is used as typed. */
internal fun uploadTargetPath(directory: String, name: String): String {
    val value = name.trim()
    return when {
        value.isEmpty() -> ""
        value.startsWith('/') -> value
        value.startsWith("~/") -> value.removePrefix("~/")
        directory.isEmpty() -> value
        else -> joinRemotePath(directory, value)
    }
}

/** Pick a target directory first, then confirm the name: the same shape as the download sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UploadFileSheet(session: TerminalConnection, uri: Uri) {
    val context = LocalContext.current
    val transfer = session.transfers
    val connection by session.state.collectAsStateWithLifecycle()
    val status by transfer.status.collectAsStateWithLifecycle()
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    fun dismiss() { transfer.uploadUri = null }
    LaunchedEffect(uri) {
        // Start where the terminal is, like downloads; fall back to the SFTP home.
        if (transfer.uploadDirectory == null) transfer.uploadDirectory = try {
            session.currentWorkingDirectory().orEmpty()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
    }
    AppSheet(::dismiss) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val editingCompact = compact && WindowInsets.isImeVisible
        val directory = transfer.uploadDirectory
        val target = uploadTargetPath(directory.orEmpty(), transfer.uploadPath)
        val enabled = connection.phase == Phase.CONNECTED && !status.running
        fun upload() {
            if (!enabled || target.isBlank() || directory == null) return
            focus.clearFocus(force = true)
            keyboard?.hide()
            transfer.uploadPath = target
            transfer.uploadUri = null
            transfer.upload(context, session, uri, target, transfer.uploadName, transfer.uploadSize)
        }
        if (!editingCompact) {
            SheetHeader(tr("上传文件"), onDismiss = ::dismiss)
            UploadSourceCard(transfer.uploadName, transfer.uploadSize,
                Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp))
            Text(tr("上传到"), style = MaterialTheme.typography.sectionHeader,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        }
        RemoteDirectoryPicker(session, directory, onDirectoryChange = { transfer.uploadDirectory = it },
            modifier = Modifier.fillMaxWidth().weight(1f), compact = editingCompact)
        Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            val nameField: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedTextField(transfer.uploadPath, { transfer.uploadPath = it }, label = { Text(tr("文件名")) },
                    singleLine = true, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(force = true); keyboard?.hide() }),
                    modifier = modifier.testTag("upload-remote-path"))
            }
            val confirm: @Composable (Modifier) -> Unit = { modifier ->
                Button(onClick = ::upload, enabled = enabled && directory != null && target.isNotBlank(),
                    modifier = modifier.heightIn(min = 48.dp).testTag("upload-confirm")) {
                    Icon(painterResource(R.drawable.ic_upload), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("上传"))
                }
            }
            if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                nameField(Modifier.weight(1f))
                confirm(Modifier)
            } else Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)) {
                nameField(Modifier.fillMaxWidth())
                Text(if (target.isBlank()) tr("请输入文件名") else trf("将保存为 %s · 不会覆盖同名文件", target),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (target.isBlank()) null else FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp, start = 4.dp).testTag("upload-target"))
                confirm(Modifier.fillMaxWidth())
            }
        }
    }
}

/** The local file being sent: what it is matters more than where it will go. */
@Composable
private fun UploadSourceCard(name: String, size: Long, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.dialogPanel) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { RemoteFileIcon(name, size = 28.dp) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("upload-source-name"))
                Text(listOf(tr("本机文件"), formatRemoteSize(size)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Browse remote directories inline. A null [directory] is still being resolved; "" is the SFTP home,
 * reported back as its absolute path once listed.
 */
@Composable
internal fun RemoteDirectoryPicker(session: TerminalConnection, directory: String?, onDirectoryChange: (String) -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    val options = rememberRemoteFileOptions()
    var retry by remember { mutableIntStateOf(0) }
    val cache = remember(session.id, retry) { HashMap<String, RemoteDirectory>() }
    var listing by remember(session.id) { mutableStateOf<RemoteDirectory?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var visibleCount by remember(directory, options.showHiddenFiles) { mutableIntStateOf(200) }
    val listState = rememberLazyListState()
    LaunchedEffect(directory, retry, session.id) {
        if (directory == null) { loading = true; return@LaunchedEffect }
        error = null
        cache[directory]?.let { cached -> listing = cached; loading = false; return@LaunchedEffect }
        loading = true
        try {
            val result = session.listRemoteDirectory(directory)
            cache[directory] = result
            cache[result.path] = result
            listing = result
            if (result.path != directory) onDirectoryChange(result.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listing = null
            error = e.message ?: tr("无法读取目录")
        } finally {
            loading = false
        }
    }
    LaunchedEffect(listing?.path, options.showHiddenFiles) { listState.scrollToItem(0) }
    val currentPath = listing?.path ?: directory.orEmpty()
    val folders = remember(listing, options.showHiddenFiles) {
        listing?.entries.orEmpty().filter { it.isDirectory && (options.showHiddenFiles || !it.name.startsWith('.')) }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (compact) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDirectoryChange(parentRemotePath(currentPath)) },
                enabled = !loading && currentPath.startsWith('/') && currentPath != "/",
                modifier = Modifier.testTag("remote-parent-directory")) {
                Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = tr("↑ 上级目录"))
            }
            Text(currentPath.ifBlank { tr("主目录") }, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).testTag("upload-directory"))
            RemoteFileOptionsMenu(options, tagPrefix = "remote") { dismissMenu ->
                DropdownMenuItem(text = { Text(tr("刷新")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                    onClick = { retry++; dismissMenu() }, enabled = !loading, modifier = Modifier.testTag("remote-refresh"))
                if (currentPath.isNotEmpty()) DropdownMenuItem(text = { Text(tr("返回主目录")) },
                    onClick = { onDirectoryChange(""); dismissMenu() })
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("remote-path-menu"), state = listState) {
            when {
                loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
                error != null -> item {
                    DirectoryMessage(trf("读取失败：%s", tr(error!!.take(120))))
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { retry++ }, modifier = Modifier.testTag("remote-path-retry")) { Text(tr("重试")) }
                        if (currentPath.isNotEmpty()) TextButton(onClick = { onDirectoryChange("") }) { Text(tr("返回主目录")) }
                    }
                }
                folders.isEmpty() -> item {
                    DirectoryMessage(tr(if (listing?.entries.isNullOrEmpty()) "此目录为空" else "没有子目录，将上传到当前目录"))
                }
                else -> {
                    items(folders.take(visibleCount), key = { it.name }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable { onDirectoryChange(joinRemotePath(currentPath, entry.name)) }
                            .heightIn(min = 52.dp).padding(horizontal = 24.dp, vertical = 8.dp)
                            .testTag("remote-entry-${entry.name}"), verticalAlignment = Alignment.CenterVertically) {
                            RemoteFileIcon(entry.name, directory = true, permissions = entry.permissions)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                formatRemoteMtime(entry.mtimeSeconds).takeIf { it.isNotEmpty() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Chevron()
                        }
                    }
                    if (folders.size > visibleCount) item {
                        TextButton(onClick = { visibleCount += 200 }, modifier = Modifier.padding(horizontal = 12.dp)
                            .testTag("remote-show-more")) { Text(tr("显示更多")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryMessage(message: String) {
    Text(message, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
