package io.itermux.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.validRemoteRename
import io.itermux.ssh.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun RemoteFileBrowserPage(session: TerminalConnection, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val connection by session.state.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val transfer = session.transfers
    val transferStatus by transfer.status.collectAsStateWithLifecycle()
    val browser = session.fileBrowser
    val options = rememberRemoteFileOptions()
    val listStates = rememberSaveableStateHolder()
    var listing by remember(session.id) { mutableStateOf<RemoteDirectory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloads = rememberDownloadActions(session)
    fun navigateBack() { if (!browser.goBack()) onBack() }
    BackHandler(onBack = ::navigateBack)

    LaunchedEffect(session.id, browser.directory, browser.revision, connection.phase) {
        listing = null
        error = null
        if (connection.phase != Phase.CONNECTED) { loading = false; return@LaunchedEffect }
        loading = true
        try { listing = session.listRemoteDirectory(browser.directory) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: tr("无法读取目录") }
        finally { loading = false }
    }

    val entries = remember(listing, options.showHiddenFiles) {
        listing?.entries.orEmpty().filter { options.showHiddenFiles || !it.name.startsWith('.') }
    }
    val currentPath = listing?.path ?: browser.directory
    Column(modifier.fillMaxSize().testTag("remote-file-browser")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::navigateBack, modifier = Modifier.testTag("files-back")) {
                Icon(painterResource(R.drawable.ic_chevron_left),
                    contentDescription = tr(if (browser.canGoBack) "返回上一目录" else "返回主机列表"))
            }
            IconButton(onClick = { browser.navigate(parentRemotePath(currentPath)) },
                enabled = connection.phase == Phase.CONNECTED && currentPath.isNotEmpty() && currentPath != "/",
                modifier = Modifier.testTag("files-parent")) {
                Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = tr("↑ 上级目录"))
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(session.host.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("files-host-title"))
                SelectionContainer {
                    Text(currentPath.ifBlank { tr("主目录") }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("files-breadcrumb"))
                }
            }
            RemoteFileOptionsMenu(options) { dismissMenu ->
                DropdownMenuItem(text = { Text(tr("刷新")) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                    onClick = { browser.refresh(); dismissMenu() },
                    enabled = connection.phase == Phase.CONNECTED && !loading && browser.operation == null,
                    modifier = Modifier.testTag("files-refresh"))
                DropdownMenuItem(text = { Text(tr("批量下载")) }, onClick = {
                    transfer.downloadDirectory = currentPath
                    transfer.selectedDownloads = emptyList()
                    transfer.downloadDialog = true
                    dismissMenu()
                }, enabled = connection.phase == Phase.CONNECTED && !transferStatus.running,
                    modifier = Modifier.testTag("files-batch-download"))
                HorizontalDivider()
            }
            IconButton(onClick = onBack, modifier = Modifier.testTag("files-close")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = tr("返回主机列表"))
            }
        }
        browser.operation?.let { label ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("files-operation"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(label, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = browser::cancel) { Text(tr("取消")) }
            }
        }
        browser.actionError?.let { message ->
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { browser.actionError = null }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = tr("关闭"))
                }
            }
        }
        when {
            connection.phase != Phase.CONNECTED -> BrowserMessage(
                tr(connection.message).ifBlank { phaseLabel(connection.phase) }, connection.active, Modifier.weight(1f))
            loading -> BrowserMessage(tr("正在读取远端目录…"), true, Modifier.weight(1f))
            error != null -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(tr(error!!), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = browser::refresh) { Text(tr("重试")) }
            }
            entries.isEmpty() -> BrowserMessage(tr(if (listing?.entries.isNullOrEmpty()) "此目录为空" else "没有可显示的项目"),
                false, Modifier.weight(1f))
            else -> {
                val current = listing!!
                // Each directory gets its own scroll position; returning restores it within the page.
                listStates.SaveableStateProvider(current.path) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("files-list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        items(entries, key = { it.name }) { entry ->
                            val path = joinRemotePath(current.path, entry.name)
                            RemoteFileRow(entry,
                                onOpen = {
                                    if (entry.isDirectory) browser.navigate(path)
                                    else browser.showMenu(current.path, entry)
                                }, onMenu = { browser.showMenu(current.path, entry) }) {
                                FileActionsMenu(entry, expanded = browser.menuPath == path,
                                    busy = browser.operation != null || transferStatus.running || downloads.busy || connection.phase != Phase.CONNECTED,
                                    onOpen = { browser.dismissMenu(); browser.navigate(path) },
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
                                    }, onDismiss = browser::dismissMenu)
                            }
                            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    DownloadFilesUi(session, downloads)
    pending?.let { PromptDialog(it) }
    browser.renamePath?.let { path ->
        AlertDialog(onDismissRequest = { browser.renamePath = null }, title = { Text(tr("重命名")) },
            text = { OutlinedTextField(browser.renameDraft, { browser.renameDraft = it }, singleLine = true,
                label = { Text(tr("新文件名")) }, modifier = Modifier.fillMaxWidth().testTag("files-rename-input")) },
            confirmButton = { TextButton(onClick = { browser.rename(session::renameRemoteFile) },
                enabled = connection.phase == Phase.CONNECTED && browser.operation == null &&
                    validRemoteRename(browser.renameDraft.trim()) && browser.renameDraft.trim() != path.substringAfterLast('/'),
                modifier = Modifier.testTag("files-rename-confirm")) { Text(tr("保存")) } },
            dismissButton = { TextButton(onClick = { browser.renamePath = null }) { Text(tr("取消")) } })
    }
    browser.deletePath?.let { path ->
        AlertDialog(onDismissRequest = { browser.deletePath = null }, title = { Text(tr("删除文件夹？").takeIf { browser.deleteIsDirectory } ?: tr("删除文件？")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(path, style = MaterialTheme.typography.bodyMedium) }
                Text(tr(if (browser.deleteIsDirectory) "将永久删除远端目录及其全部内容，此操作不可撤销。" else "将永久删除远端文件，此操作不可撤销。"))
            } },
            confirmButton = { TextButton(onClick = { browser.delete(session::deleteRemoteFile) },
                enabled = connection.phase == Phase.CONNECTED && browser.operation == null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("files-delete-confirm")) { Text(tr("删除")) } },
            dismissButton = { TextButton(onClick = { browser.deletePath = null }) { Text(tr("取消")) } })
    }
    if (transferStatus.running || transferStatus.result != null) {
        TransferStatusDialog(transferStatus, transfer::cancel, transfer::dismiss)
    }
}

@Composable
private fun BrowserMessage(message: String, busy: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (busy) CircularProgressIndicator(Modifier.padding(bottom = 16.dp).size(28.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FileActionsMenu(entry: RemoteEntry, expanded: Boolean, busy: Boolean, onOpen: () -> Unit,
    onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    onSaveAs: () -> Unit = onDownload) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 200.dp, max = 280.dp).testTag("files-actions-list")) {
        DropdownMenuItem(text = { Text(tr(if (entry.isDirectory) "打开" else "下载")) },
            onClick = if (entry.isDirectory) onOpen else onDownload, enabled = !busy,
            modifier = Modifier.testTag("files-action-download"))
        if (!entry.isDirectory) DropdownMenuItem(text = { Text(tr("另存为…")) }, onClick = onSaveAs,
            enabled = !busy, modifier = Modifier.testTag("files-action-save-as"))
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
    val meta = remember(entry) { listOfNotNull(if (entry.isDirectory) null else formatRemoteSize(entry.size),
        formatRemoteMtime(entry.mtimeSeconds).takeIf { it.isNotEmpty() }).joinToString(" · ") }
    ListItem(
        leadingContent = { Icon(painterResource(if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
            contentDescription = tr(if (entry.isDirectory) "目录" else "文件"), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (meta.isEmpty()) null else { { Text(meta, maxLines = 2, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = {
            Box {
                IconButton(onClick = onMenu, modifier = Modifier.testTag("files-row-menu-${entry.name}")) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = trf("文件操作：%s", entry.name))
                }
                menu()
            }
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(role = Role.Button, onClick = onOpen,
            onLongClickLabel = tr("文件操作"), onLongClick = onMenu).testTag("files-entry-${entry.name}"))
}
