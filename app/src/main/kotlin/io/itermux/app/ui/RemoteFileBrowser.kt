package io.itermux.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.breadcrumbSegments
import io.itermux.app.session.validRemoteRename
import io.itermux.ssh.*
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteFileBrowserPage(session: TerminalConnection, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val connection by session.state.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val transfer = session.transfers
    val transferStatus by transfer.status.collectAsStateWithLifecycle()
    val browser = session.fileBrowser
    val options = rememberRemoteFileOptions()
    val listStates = rememberSaveableStateHolder()
    val clipboard = LocalClipboardManager.current
    var listing by remember(session.id) { mutableStateOf<RemoteDirectory?>(null) }
    var listingKey by remember(session.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloads = rememberDownloadActions(session)
    val connected = connection.phase == Phase.CONNECTED
    fun navigateBack() {
        if (browser.filtering) browser.clearFilter() else if (!browser.goBack()) onBack()
    }
    BackHandler(onBack = ::navigateBack)

    LaunchedEffect(session.id, browser.directory, browser.revision, connection.phase) {
        error = null
        // A refresh of the same folder keeps the old rows on screen until the new listing arrives.
        if (listingKey != browser.directory) listing = null
        if (!connected) { listing = null; loading = false; return@LaunchedEffect }
        loading = true
        try {
            listing = session.listRemoteDirectory(browser.directory)
            listingKey = browser.directory
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { listing = null; error = e.message ?: tr("无法读取目录") }
        finally { loading = false }
    }

    val entries = remember(listing, options.showHiddenFiles, options.sort, options.sortDescending, browser.filter) {
        val visible = listing?.entries.orEmpty().filter {
            (options.showHiddenFiles || !it.name.startsWith('.')) && (browser.filter.isBlank() || it.name.contains(browser.filter.trim(), ignoreCase = true))
        }
        sortRemoteEntries(visible, options.sort, options.sortDescending)
    }
    val currentPath = listing?.path ?: browser.directory
    Column(modifier.fillMaxSize().testTag("remote-file-browser")) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::navigateBack, modifier = Modifier.testTag("files-back")) {
                Icon(painterResource(R.drawable.ic_chevron_left),
                    contentDescription = tr(if (browser.canGoBack) "返回上一目录" else "返回主机列表"))
            }
            FileBrowserTitle(currentPath.ifBlank { tr("主目录") }, session.host.name, Modifier.weight(1f).padding(horizontal = 4.dp))
            IconButton(onClick = { if (browser.filtering) browser.clearFilter() else browser.filtering = true },
                enabled = listing != null, modifier = Modifier.testTag("files-search")) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = tr("筛选当前目录"),
                    tint = if (browser.filtering) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            RemoteFileOptionsMenu(options) { dismissMenu ->
                DropdownMenuItem(text = { Text(tr("刷新")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                    onClick = { browser.refresh(); dismissMenu() },
                    enabled = connected && !loading && browser.operation == null,
                    modifier = Modifier.testTag("files-refresh"))
                DropdownMenuItem(text = { Text(tr("新建文件夹")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder_add), contentDescription = null) },
                    onClick = { browser.newFolderDraft = ""; dismissMenu() },
                    enabled = connected && listing != null && browser.operation == null,
                    modifier = Modifier.testTag("files-new-folder"))
                DropdownMenuItem(text = { Text(tr("批量下载")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    onClick = {
                        transfer.selectedDownloads = emptyList()
                        transfer.openDownloads { currentPath }
                        dismissMenu()
                    }, enabled = connected && !transferStatus.running,
                    modifier = Modifier.testTag("files-batch-download"))
                DropdownMenuItem(text = { Text(tr("复制当前路径")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                    onClick = { clipboard.setText(AnnotatedString(currentPath)); dismissMenu() },
                    enabled = currentPath.isNotEmpty(), modifier = Modifier.testTag("files-copy-path"))
                DropdownMenuItem(text = { Text(tr("返回主机列表")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = null) },
                    onClick = { dismissMenu(); onBack() }, modifier = Modifier.testTag("files-close"))
                HorizontalDivider()
            }
        }
        if (currentPath.isNotEmpty()) Breadcrumbs(currentPath, enabled = connected) { browser.navigate(it) }
        AnimatedVisibility(browser.filtering) { FilterField(browser.filter, { browser.filter = it }, browser::clearFilter) }
        browser.operation?.let { label ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("files-operation"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(label, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = browser::cancel) { Text(tr("取消")) }
            }
        }
        browser.actionError?.let { message ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { browser.actionError = null }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = tr("关闭"))
                }
            }
        }
        val current = listing
        when {
            !connected -> BrowserMessage(tr(connection.message).ifBlank { phaseLabel(connection.phase) }, connection.active, Modifier.weight(1f))
            current == null && loading -> BrowserMessage(tr("正在读取远端目录…"), true, Modifier.weight(1f))
            error != null || current == null -> BrowserMessage(tr(error ?: "无法读取目录"), false, Modifier.weight(1f)) {
                TextButton(onClick = browser::refresh) { Text(tr("重试")) }
            }
            else -> {
                ListingSummary(current, entries, options)
                PullToRefreshBox(isRefreshing = loading, onRefresh = browser::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (entries.isEmpty()) BrowserMessage(tr(when {
                        current.entries.isEmpty() -> "此目录为空"
                        browser.filter.isNotBlank() -> "没有匹配的项目"
                        else -> "没有可显示的项目"
                    }), false, Modifier.fillMaxSize(), icon = true)
                    else listStates.SaveableStateProvider(current.path) {
                        // Each directory gets its own scroll position; returning restores it within the page.
                        LazyColumn(Modifier.fillMaxSize().testTag("files-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(entries, key = { it.name }) { entry ->
                                val path = joinRemotePath(current.path, entry.name)
                                fun open() {
                                    when {
                                        entry.isDirectory -> browser.navigate(path)
                                        entry.permissions.startsWith('l') -> browser.openLink(path, session::isRemoteDirectory)
                                        else -> browser.previewPath = path
                                    }
                                }
                                RemoteFileRow(entry, onOpen = ::open, onMenu = { browser.showMenu(current.path, entry) }) {
                                    FileActionsMenu(entry, expanded = browser.menuPath == path,
                                        busy = browser.operation != null || transferStatus.running || downloads.busy || !connected,
                                        onOpen = { browser.dismissMenu(); open() },
                                        onDownload = {
                                            browser.dismissMenu()
                                            downloads.start(listOf(DownloadItem(path, entry.size)), false)
                                        }, onSaveAs = {
                                            browser.dismissMenu()
                                            downloads.start(listOf(DownloadItem(path, entry.size)), true)
                                        }, onRename = {
                                            browser.renamePath = path; browser.renameDraft = entry.name; browser.dismissMenu()
                                        }, onDelete = {
                                            browser.deletePath = path; browser.deleteIsDirectory = entry.isDirectory; browser.dismissMenu()
                                        }, onCopyPath = {
                                            clipboard.setText(AnnotatedString(path)); browser.dismissMenu()
                                        }, onDismiss = browser::dismissMenu)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    browser.previewPath?.let { path ->
        RemoteFilePreview(session, path, connected,
            canDownload = connected && !transferStatus.running && !downloads.busy,
            onDownload = {
                browser.previewPath = null
                downloads.start(listOf(DownloadItem(path)), false)
            }, onDismiss = { browser.previewPath = null })
    }
    DownloadFilesUi(session, downloads)
    pending?.let { PromptDialog(it) }
    browser.renamePath?.let { path ->
        AlertDialog(onDismissRequest = { browser.renamePath = null }, title = { Text(tr("重命名")) },
            text = { OutlinedTextField(browser.renameDraft, { browser.renameDraft = it }, singleLine = true,
                label = { Text(tr("新文件名")) }, modifier = Modifier.fillMaxWidth().testTag("files-rename-input")) },
            confirmButton = { TextButton(onClick = { browser.rename(session::renameRemoteFile) },
                enabled = connected && browser.operation == null &&
                    validRemoteRename(browser.renameDraft.trim()) && browser.renameDraft.trim() != path.substringAfterLast('/'),
                modifier = Modifier.testTag("files-rename-confirm")) { Text(tr("保存")) } },
            dismissButton = { TextButton(onClick = { browser.renamePath = null }) { Text(tr("取消")) } })
    }
    browser.newFolderDraft?.let { draft ->
        val name = draft.trim()
        val exists = listing?.entries?.any { it.name == name } == true
        AlertDialog(onDismissRequest = { browser.newFolderDraft = null }, title = { Text(tr("新建文件夹")) },
            text = { OutlinedTextField(draft, { browser.newFolderDraft = it }, singleLine = true,
                label = { Text(tr("文件夹名称")) }, isError = exists,
                supportingText = if (exists) { { Text(tr("已存在同名文件或文件夹")) } } else null,
                modifier = Modifier.fillMaxWidth().testTag("files-new-folder-input")) },
            confirmButton = { TextButton(onClick = { browser.createFolder(currentPath, session::makeRemoteDirectory) },
                enabled = connected && browser.operation == null && validRemoteRename(name) && !exists,
                modifier = Modifier.testTag("files-new-folder-confirm")) { Text(tr("创建")) } },
            dismissButton = { TextButton(onClick = { browser.newFolderDraft = null }) { Text(tr("取消")) } })
    }
    browser.deletePath?.let { path ->
        AlertDialog(onDismissRequest = { browser.deletePath = null }, title = { Text(tr("删除文件夹？").takeIf { browser.deleteIsDirectory } ?: tr("删除文件？")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(path, style = MaterialTheme.typography.bodyMedium) }
                Text(tr(if (browser.deleteIsDirectory) "将永久删除远端目录及其全部内容，此操作不可撤销。" else "将永久删除远端文件，此操作不可撤销。"))
            } },
            confirmButton = { TextButton(onClick = { browser.delete(session::deleteRemoteFile) },
                enabled = connected && browser.operation == null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("files-delete-confirm")) { Text(tr("删除")) } },
            dismissButton = { TextButton(onClick = { browser.deletePath = null }) { Text(tr("取消")) } })
    }
    if (transferStatus.running || transferStatus.result != null) {
        TransferStatusDialog(transferStatus, transfer::cancel, transfer::dismiss)
    }
}

/** Folder name as the headline, host underneath; the full path lives in the breadcrumbs. */
@Composable
internal fun FileBrowserTitle(path: String, host: String, modifier: Modifier = Modifier) {
    val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path.take(1).ifEmpty { path } }
    Column(modifier) {
        Text(name, Modifier.testTag("files-title"), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(host, Modifier.testTag("files-subtitle"), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Breadcrumbs(path: String, enabled: Boolean, onNavigate: (String) -> Unit) {
    val segments = remember(path) { breadcrumbSegments(path) }
    val scroll = rememberScrollState()
    // Keep the current folder in view as the path grows.
    LaunchedEffect(path, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp).testTag("files-breadcrumbs"),
        verticalAlignment = Alignment.CenterVertically) {
        segments.forEachIndexed { index, (name, target) ->
            val last = index == segments.lastIndex
            if (index > 0) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            Surface(onClick = { onNavigate(target) }, enabled = enabled && !last, shape = RoundedCornerShape(8.dp),
                color = if (last) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                modifier = Modifier.testTag("files-crumb-$index")) {
                Text(name, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), maxLines = 1,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (last) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(value, onValueChange, singleLine = true, shape = RoundedCornerShape(12.dp),
        placeholder = { Text(tr("筛选当前目录")) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_filter), contentDescription = null, Modifier.size(20.dp)) },
        trailingIcon = { IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), tr("关闭筛选")) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus).testTag("files-filter"))
}

@Composable
private fun ListingSummary(listing: RemoteDirectory, shown: List<RemoteEntry>, options: RemoteFileOptions) {
    var sortMenu by remember { mutableStateOf(false) }
    val folders = shown.count { it.isDirectory }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(trf("%d 个文件夹 · %d 个文件", folders, shown.size - folders) +
            if (shown.size < listing.entries.size) trf("（另有 %d 项未显示）", listing.entries.size - shown.size) else "",
            Modifier.weight(1f).testTag("files-summary"), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            TextButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.testTag("files-sort")) {
                Icon(painterResource(R.drawable.ic_sort), contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr(options.sort.label) + if (options.sortDescending) " ↓" else " ↑", style = MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                RemoteFileSort.entries.forEach { sort ->
                    DropdownMenuItem(text = { Text(tr(sort.label)) }, onClick = { options.chooseSort(sort); sortMenu = false },
                        trailingIcon = if (sort == options.sort) { { Text(if (options.sortDescending) "↓" else "↑",
                            color = MaterialTheme.colorScheme.primary) } } else null,
                        modifier = Modifier.testTag("files-sort-${sort.name.lowercase()}"))
                }
            }
        }
    }
}

@Composable
private fun BrowserMessage(message: String, busy: Boolean, modifier: Modifier, icon: Boolean = false,
    actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (busy) CircularProgressIndicator(Modifier.padding(bottom = 16.dp).size(28.dp), strokeWidth = 2.dp)
        if (icon) RemoteFileIcon("", directory = true, size = 56.dp, modifier = Modifier.padding(bottom = 12.dp).alpha(0.5f))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

@Composable
internal fun FileActionsMenu(entry: RemoteEntry, expanded: Boolean, busy: Boolean, onOpen: () -> Unit,
    onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    onSaveAs: () -> Unit = onDownload, onCopyPath: (() -> Unit)? = null) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 200.dp, max = 280.dp).testTag("files-actions-list")) {
        DropdownMenuItem(text = { Text(tr(if (entry.isDirectory) "打开" else "查看内容")) },
            onClick = onOpen, enabled = !busy, modifier = Modifier.testTag("files-action-open"))
        if (!entry.isDirectory) DropdownMenuItem(text = { Text(tr("下载")) }, onClick = onDownload,
            enabled = !busy, modifier = Modifier.testTag("files-action-download"))
        if (!entry.isDirectory) DropdownMenuItem(text = { Text(tr("另存为…")) }, onClick = onSaveAs,
            enabled = !busy, modifier = Modifier.testTag("files-action-save-as"))
        if (onCopyPath != null) DropdownMenuItem(text = { Text(tr("复制路径")) }, onClick = onCopyPath,
            modifier = Modifier.testTag("files-action-copy-path"))
        DropdownMenuItem(text = { Text(tr("重命名")) }, onClick = onRename, enabled = !busy,
            modifier = Modifier.testTag("files-action-rename"))
        HorizontalDivider()
        DropdownMenuItem(text = { Text(tr("删除")) }, onClick = onDelete, enabled = !busy,
            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("files-action-delete"))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RemoteFileRow(entry: RemoteEntry, onOpen: () -> Unit, onMenu: () -> Unit,
    menu: @Composable () -> Unit = {}) {
    val meta = remember(entry) {
        val link = entry.permissions.startsWith('l')
        listOfNotNull(if (link) tr("链接") else null,
            if (entry.isDirectory || link) null else formatRemoteSize(entry.size),
            formatRemoteMtime(entry.mtimeSeconds).takeIf { it.isNotEmpty() }).joinToString(" · ")
    }
    val hidden = entry.name.startsWith('.')
    Row(Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClick = onOpen,
        onLongClickLabel = tr("文件操作"), onLongClick = onMenu).heightIn(min = 56.dp)
        .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("files-entry-${entry.name}"),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteFileIcon(entry.name, entry.isDirectory, entry.permissions, size = 28.dp,
            modifier = if (hidden) Modifier.alpha(0.6f) else Modifier)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (hidden) 0.7f else 1f))
            if (meta.isNotEmpty()) Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = onMenu, modifier = Modifier.testTag("files-row-menu-${entry.name}")) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = trf("文件操作：%s", entry.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            menu()
        }
    }
}
