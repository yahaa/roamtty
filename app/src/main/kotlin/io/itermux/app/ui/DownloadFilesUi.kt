package io.itermux.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun DownloadFilesUi(session: TerminalConnection, actions: DownloadActions = rememberDownloadActions(session)) {
    val transfer = session.transfers
    val connection by session.state.collectAsStateWithLifecycle()
    val status by transfer.status.collectAsStateWithLifecycle()
    if (transfer.downloadDialog) DownloadFilesSheet(session,
        enabled = connection.phase == Phase.CONNECTED && !status.running && !actions.busy,
        directoryLabel = actions.directoryLabel,
        onDismiss = { transfer.downloadDialog = false },
        onDownload = { actions.start(transfer.selectedDownloads.toList(), false) },
        onSaveAs = { actions.start(transfer.selectedDownloads.toList(), true) })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DownloadFilesSheet(session: TerminalConnection, enabled: Boolean, directoryLabel: String,
    onDismiss: () -> Unit, onDownload: () -> Unit, onSaveAs: () -> Unit) {
    val transfer = session.transfers
    val options = rememberRemoteFileOptions()
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val listState = rememberLazyListState()
    var listing by remember { mutableStateOf<RemoteDirectory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(transfer.downloadDirectory, retry, enabled) {
        listing = null
        error = null
        loading = true
        try {
            if (enabled) listing = session.listRemoteDirectory(transfer.downloadDirectory)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: tr("无法读取目录") }
        finally { loading = false }
    }
    val selected = transfer.selectedDownloads
    val selectedPaths = selected.map { it.path }.toSet()
    val currentPath = listing?.path ?: transfer.downloadDirectory
    val entries = listing?.entries.orEmpty().filter {
        (options.showHiddenFiles || !it.name.startsWith('.')) && it.name.contains(query, ignoreCase = true)
    }
    LaunchedEffect(currentPath, query, selectedOnly, options.showHiddenFiles) { listState.scrollToItem(0) }
    fun toggle(file: DownloadItem) {
        transfer.selectedDownloads = if (file.path in selectedPaths) selected.filterNot { it.path == file.path }
        else selected + file
    }
    fun selectAll() {
        transfer.selectedDownloads = (selected + entries.filterNot { it.isDirectory }
            .map { DownloadItem(joinRemotePath(currentPath, it.name), it.size) }).distinctBy { it.path }
    }
    AppSheet(onDismiss) {
        // Resolve focus/IME inside the sheet's window, not the underlying Activity.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val editingCompact = compact && WindowInsets.isImeVisible
        fun finishSearch() { focus.clearFocus(force = true); keyboard?.hide() }
        LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
        if (!editingCompact) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { transfer.downloadDirectory = parentRemotePath(currentPath); query = "" },
                enabled = enabled && currentPath.isNotEmpty() && currentPath != "/" && !selectedOnly,
                modifier = Modifier.testTag("download-parent")) {
                Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = tr("↑ 上级目录"))
            }
            Text(currentPath.ifBlank { tr("主目录") }, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).testTag("download-directory"))
            AppSearchToggle(searchExpanded, onToggle = {
                if (searchExpanded) { query = ""; finishSearch() }
                searchExpanded = !searchExpanded
            }, searchDescription = tr("搜索文件"), modifier = Modifier.testTag("download-search-toggle"))
            RemoteFileOptionsMenu(options, tagPrefix = "download") { dismissMenu ->
                DropdownMenuItem(text = { Text(tr("刷新")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                    onClick = { retry++; finishSearch(); dismissMenu() }, enabled = enabled && !loading,
                    modifier = Modifier.testTag("download-refresh"))
                DropdownMenuItem(text = { Text(tr(if (selectedOnly) "全部文件" else "全选")) },
                    onClick = {
                        if (selectedOnly) selectedOnly = false else selectAll()
                        finishSearch()
                        dismissMenu()
                    }, enabled = selectedOnly || (enabled && !loading && entries.any { !it.isDirectory }),
                    modifier = Modifier.testTag(if (selectedOnly) "download-show-all" else "download-select-all"))
                DropdownMenuItem(text = { Text(tr("另存为…")) }, onClick = { dismissMenu(); onSaveAs() },
                    enabled = enabled && selected.isNotEmpty(), modifier = Modifier.testTag("download-save-as"))
                HorizontalDivider()
            }
            if (!searchExpanded) IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = tr("关闭"))
            }
        }
        if (searchExpanded) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppSearchField(query, { query = it }, tr("搜索文件"),
                modifier = Modifier.weight(1f).focusRequester(searchFocus).testTag("download-search"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { finishSearch() }))
            if (editingCompact) TextButton(onClick = ::finishSearch) { Text(tr("完成")) }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("download-files-list"), state = listState) {
            when {
                selectedOnly -> {
                    if (selected.isEmpty()) item { DownloadMessage(tr("尚未选择文件")) }
                    items(selected.filter { it.path.contains(query, ignoreCase = true) }, key = { it.path }) { file ->
                        DownloadFileRow(file.name, "${formatRemoteSize(file.total)} · ${file.path.substringBeforeLast('/').ifBlank { "/" }}",
                            directory = false, selected = true, enabled = enabled, tag = "download-entry-${file.name}") { toggle(file) }
                    }
                }
                !enabled -> item { DownloadMessage(tr("下载未开始，请重新连接后再试。")) }
                loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
                error != null -> item {
                    DownloadMessage(tr(error!!))
                    TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 24.dp)) { Text(tr("重试")) }
                    TextButton(onClick = { transfer.downloadDirectory = "" }) { Text(tr("返回主目录")) }
                }
                entries.isEmpty() -> item { DownloadMessage(tr(when {
                    query.isNotBlank() -> "无匹配文件"
                    listing?.entries.isNullOrEmpty() -> "此目录为空"
                    else -> "没有可显示的项目"
                })) }
                else -> items(entries, key = { it.name }) { entry ->
                    val path = joinRemotePath(currentPath, entry.name)
                    val type = if (entry.isDirectory) tr("目录") else entry.name.substringAfterLast('.', "").uppercase().ifBlank { tr("文件") }
                    val meta = listOf(type, if (entry.isDirectory) "" else formatRemoteSize(entry.size),
                        formatRemoteMtime(entry.mtimeSeconds)).filter { it.isNotBlank() }.joinToString(" · ")
                    DownloadFileRow(entry.name, meta, entry.isDirectory, path in selectedPaths, enabled,
                        "download-entry-${entry.name}") {
                        if (entry.isDirectory) { transfer.downloadDirectory = path; query = "" }
                        else toggle(DownloadItem(path, entry.size))
                    }
                }
            }
        }
        if (!editingCompact) Surface(shadowElevation = 4.dp) {
            val summary: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedOnly = !selectedOnly; finishSearch() },
                        enabled = selected.isNotEmpty() || selectedOnly, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).testTag("download-selected")) {
                        Text(trf("已选 %d 个 · %s", selected.size,
                            if (selected.any { it.total < 0 }) tr("大小未知") else formatRemoteSize(selected.sumOf { it.total })),
                            color = if (selectedOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (selected.isNotEmpty() || selectedOnly) Icon(painterResource(R.drawable.ic_chevron_right),
                            contentDescription = tr(if (selectedOnly) "返回文件列表" else "查看已选文件"),
                            modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(if (selectedOnly) 90f else -90f))
                    }
                    if (selected.isNotEmpty()) TextButton(onClick = { transfer.selectedDownloads = emptyList() }) { Text(tr("清空")) }
                }
            }
            val confirm: @Composable (Modifier) -> Unit = { modifier ->
                Button(onClick = onDownload, enabled = enabled && selected.isNotEmpty(),
                    modifier = modifier.testTag("download-confirm")) {
                    Text(tr(if (directoryLabel.isEmpty()) "选择下载目录" else "下载"))
                }
            }
            if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                summary(Modifier.weight(1f))
                confirm(Modifier)
            } else Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                summary(Modifier.fillMaxWidth())
                Text(if (directoryLabel.isEmpty()) tr("首次选择后将记住此目录") else trf("保存到：%s", directoryLabel),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                confirm(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun DownloadFileRow(name: String, metadata: String, directory: Boolean, selected: Boolean,
    enabled: Boolean, tag: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(metadata, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(if (directory) R.drawable.ic_folder else R.drawable.ic_file), null) },
        trailingContent = {
            if (directory) Icon(painterResource(R.drawable.ic_chevron_right), tr("打开"))
            else Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        }, colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().then(if (directory) Modifier.clickable(enabled = enabled, onClick = onClick)
            else Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })).testTag(tag))
}

@Composable
private fun DownloadMessage(message: String) {
    Text(message, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
