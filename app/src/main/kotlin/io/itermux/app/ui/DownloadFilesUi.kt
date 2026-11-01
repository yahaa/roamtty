package io.itermux.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.delay

@Composable
internal fun DownloadFilesUi(session: TerminalConnection, actions: DownloadActions = rememberDownloadActions(session)) {
    val transfer = session.transfers
    val connection by session.state.collectAsStateWithLifecycle()
    val status by transfer.status.collectAsStateWithLifecycle()
    if (transfer.downloadDialog) DownloadFilesSheet(session,
        enabled = connection.phase == Phase.CONNECTED && !status.running && !actions.busy,
        directoryLabel = actions.directoryLabel,
        canChangeDirectory = !actions.busy,
        onChangeDirectory = actions.changeDirectory,
        onDismiss = transfer::dismissDownloads,
        onDownload = { actions.start(transfer.selectedDownloads.toList(), false) },
        onSaveAs = { actions.start(transfer.selectedDownloads.toList(), true) })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DownloadFilesSheet(session: TerminalConnection, enabled: Boolean, directoryLabel: String,
    canChangeDirectory: Boolean, onChangeDirectory: () -> Unit, onDismiss: () -> Unit, onDownload: () -> Unit, onSaveAs: () -> Unit) {
    val transfer = session.transfers
    val options = rememberRemoteFileOptions()
    val compact = isLandscape()
    val listState = rememberLazyListState()
    var listing by remember { mutableStateOf<RemoteSearchDirectories?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    val search = remember(transfer.downloadDirectory, query, selectedOnly) {
        remotePathSearch(transfer.downloadDirectory, if (selectedOnly) "" else query)
    }
    val prefix = if (selectedOnly) "" else query.substring(0, query.lastIndexOf('/') + 1)
    val directories = remember(session.id, transfer.downloadDirectory, retry, enabled) {
        linkedMapOf<String, RemoteDirectory>()
    }
    LaunchedEffect(transfer.downloadDirectory, prefix, options.showHiddenFiles, retry, enabled, transfer.resolvingDownloadDirectory) {
        listing = null
        error = null
        loading = true
        try {
            if (enabled && !transfer.resolvingDownloadDirectory) {
                if (prefix.isNotEmpty()) delay(200)
                listing = resolveRemoteSearchDirectories(transfer.downloadDirectory, prefix, options.showHiddenFiles) { path ->
                    directories[path] ?: session.listRemoteDirectory(path).also {
                        directories[path] = it
                        if (directories.size > 64) directories.remove(directories.keys.first())
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: tr("无法读取目录") }
        finally { loading = false }
    }
    val selected = transfer.selectedDownloads
    val selectedPaths = remember(selected) { selected.mapTo(HashSet()) { it.path } }
    val currentPath = listing?.directories?.singleOrNull()?.path ?: listing?.basePath ?: search.directory
    val entries = remember(listing, options.showHiddenFiles, search.name) {
        listing?.directories.orEmpty().flatMap { directory -> directory.entries.mapNotNull { entry ->
            if (!options.showHiddenFiles && entry.name.startsWith('.')) null
            else remoteNameMatchRank(entry.name, search.name)?.let { rank ->
                Triple(joinRemotePath(directory.path, entry.name), entry, rank)
            }
        } }.distinctBy { it.first }.sortedWith(compareBy<Triple<String, RemoteEntry, Int>> { it.third }
            // Newest first, files and folders alike: the file just produced in the terminal is on top.
            .thenByDescending { it.second.mtimeSeconds }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.name })
    }
    val selectedMatches = remember(selected, query) {
        selected.mapNotNull { file -> remoteNameMatchRank(file.path, query)?.let { file to it } }
            .sortedBy { it.second }.map { it.first }
    }
    LaunchedEffect(currentPath, query, selectedOnly, options.showHiddenFiles) { listState.scrollToItem(0) }
    fun toggle(file: DownloadItem) {
        transfer.selectedDownloads = if (file.path in selectedPaths) selected.filterNot { it.path == file.path }
        else selected + file
    }
    fun selectAll() {
        transfer.selectedDownloads = (selected + entries.filterNot { it.second.isDirectory }
            .map { DownloadItem(it.first, it.second.size) }).distinctBy { it.path }
    }
    AppSheet(onDismiss) {
        // Resolve focus/IME inside the sheet's window, not the underlying Activity.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val editingCompact = compact && WindowInsets.isImeVisible
        fun finishSearch() { focus.clearFocus(force = true); keyboard?.hide() }
        LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
        if (!editingCompact) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val canNavigate = enabled && !transfer.resolvingDownloadDirectory && !selectedOnly
            fun navigate(path: String) { transfer.downloadDirectory = path; query = "" }
            // Back only where there is somewhere to go; the breadcrumb jumps further up in one tap.
            if (currentPath.isNotEmpty() && currentPath != "/") IconButton(
                onClick = { navigate(parentRemotePath(currentPath)) }, enabled = canNavigate,
                modifier = Modifier.testTag("download-parent")) {
                Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = tr("↑ 上级目录"))
            } else Spacer(Modifier.width(12.dp))
            FileBrowserPathBar(currentPath.ifBlank { "/" }, session.host.name, canNavigate, ::navigate,
                Modifier.weight(1f), tagPrefix = "download")
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
                    }, enabled = selectedOnly || (enabled && !loading && entries.any { !it.second.isDirectory }),
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
            AppSearchField(query, { query = it }, tr("文件名或路径"),
                modifier = Modifier.weight(1f).focusRequester(searchFocus).testTag("download-search"),
                // Autocorrect and suggestions rewrite long, word-like file names before they reach the filter.
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { finishSearch() }))
            if (editingCompact) TextButton(onClick = ::finishSearch) { Text(tr("完成")) }
        }
        HorizontalDivider()
        if (!selectedOnly && listing?.limited == true) DirectoryMessage(tr("匹配较多，请补充路径以缩小范围"))
        if (!selectedOnly && listing?.unreadable == true) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("部分目录无法读取"), Modifier.weight(1f).padding(start = 24.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { retry++ }) { Text(tr("重试")) }
        }
        val failure = error
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("download-files-list"), state = listState) {
            when {
                selectedOnly -> {
                    if (selectedMatches.isEmpty()) item { DirectoryMessage(tr(if (selected.isEmpty()) "尚未选择文件" else "无匹配文件")) }
                    items(selectedMatches, key = { it.path }) { file ->
                        DownloadFileRow(file.name, "${formatRemoteSize(file.total)} · ${file.path.substringBeforeLast('/').ifBlank { "/" }}",
                            directory = false, selected = true, enabled = enabled, tag = "download-entry-${file.name}") { toggle(file) }
                    }
                }
                !enabled -> item { DirectoryMessage(tr("下载未开始，请重新连接后再试。")) }
                loading || transfer.resolvingDownloadDirectory -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
                failure != null -> item {
                    DirectoryMessage(tr(failure))
                    TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 24.dp)) { Text(tr("重试")) }
                    TextButton(onClick = { transfer.downloadDirectory = ""; query = "" }) { Text(tr("返回主目录")) }
                }
                entries.isEmpty() -> item { DirectoryMessage(tr(when {
                    query.isNotBlank() -> "无匹配文件"
                    listing?.directories.orEmpty().all { it.entries.isEmpty() } -> "此目录为空"
                    else -> "没有可显示的项目"
                })) }
                else -> items(entries, key = { it.first }) { (path, entry) ->
                    val type = if (entry.isDirectory) tr("目录") else entry.name.substringAfterLast('.', "").uppercase().ifBlank { tr("文件") }
                    val location = if (listing?.directories.orEmpty().size > 1) {
                        path.substringBeforeLast('/').removePrefix("${listing?.basePath?.trimEnd('/')}/")
                    } else ""
                    val meta = listOf(location, type, if (entry.isDirectory) "" else formatRemoteSize(entry.size),
                        formatRemoteMtime(entry.mtimeSeconds)).filter { it.isNotBlank() }.joinToString(" · ")
                    DownloadFileRow(entry.name, meta, entry.isDirectory, path in selectedPaths, enabled,
                        "download-entry-${entry.name}") {
                        if (entry.isDirectory) { transfer.downloadDirectory = path; query = "" }
                        else toggle(DownloadItem(path, entry.size))
                    }
                }
            }
        }
        if (!editingCompact) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            val changeLabel = tr(if (directoryLabel.isEmpty()) "选择下载目录" else "更换下载目录")
            val summary: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedOnly = !selectedOnly; finishSearch() },
                        enabled = selected.isNotEmpty() || selectedOnly, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).testTag("download-selected")) {
                        Text(trf("已选 %d 个 · %s", selected.size,
                            if (selected.any { it.total < 0 }) tr("大小未知") else formatRemoteSize(selected.sumOf { it.total })),
                            color = if (selectedOnly || selected.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (selected.isNotEmpty() || selectedOnly) Icon(painterResource(R.drawable.ic_chevron_right),
                            contentDescription = tr(if (selectedOnly) "返回文件列表" else "查看已选文件"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(if (selectedOnly) 90f else -90f))
                        Spacer(Modifier.weight(0.001f))
                    }
                    if (selected.isNotEmpty()) TextButton(onClick = { transfer.selectedDownloads = emptyList() }) { Text(tr("清空")) }
                }
            }
            val confirm: @Composable (Modifier) -> Unit = { modifier ->
                Button(onClick = onDownload, enabled = enabled && selected.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 20.dp), modifier = modifier.testTag("download-confirm")) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr(if (directoryLabel.isEmpty()) "选择下载目录" else "下载"), maxLines = 1)
                }
            }
            if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                summary(Modifier.weight(1f))
                IconButton(onClick = onChangeDirectory, enabled = canChangeDirectory,
                    modifier = Modifier.testTag("download-destination")) {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = changeLabel)
                }
                confirm(Modifier)
            } else Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
                summary(Modifier.fillMaxWidth().padding(start = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The local destination is tappable so a download can go elsewhere without visiting Settings.
                    OutlinedButton(onClick = onChangeDirectory, enabled = canChangeDirectory,
                        contentPadding = PaddingValues(start = 14.dp, end = 10.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("download-destination")) {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(directoryLabel.ifEmpty { tr("尚未设置下载目录") }, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    confirm(Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

@Composable
internal fun DownloadFileRow(name: String, metadata: String, directory: Boolean, selected: Boolean,
    enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(MaterialTheme.shapes.medium)
        .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
        .then(if (directory) Modifier.clickable(enabled = enabled, onClick = onClick)
            else Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() }))
        .testTag(tag).heightIn(min = 60.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteFileIcon(name, directory)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(breakableFileName(name), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(metadata, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (directory) Icon(painterResource(R.drawable.ic_chevron_right), tr("打开"),
            tint = colors.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp).size(20.dp))
        else Checkbox(checked = selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = 10.dp))
    }
}

/**
 * Lets long names wrap at separators. Line breaking forbids a break between "-" and a digit, so
 * "probe-20261005" would otherwise split mid-number.
 */
private fun breakableFileName(name: String) = name.replace(fileNameBreak, "\u200B")
private val fileNameBreak = Regex("(?<=[-_.])(?=.)")
