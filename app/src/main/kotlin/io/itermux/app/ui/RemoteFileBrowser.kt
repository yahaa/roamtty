package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase
import io.itermux.ssh.RemoteDirectory
import io.itermux.ssh.RemoteEntry
import io.itermux.ssh.formatRemoteMtime
import io.itermux.ssh.formatRemoteSize
import io.itermux.ssh.joinRemotePath
import io.itermux.ssh.parentRemotePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Remote SFTP browser: list / enter dirs / download / rename / delete file or directory.
 * Move and mkdir are deferred to a later batch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RemoteFileBrowserPage(
    session: TerminalConnection,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by session.state.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val transfer = session.transfers
    val transferStatus by transfer.status.collectAsStateWithLifecycle()

    var directory by remember(session.id) { mutableStateOf("") }
    var listing by remember(session.id) { mutableStateOf<RemoteDirectory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var menuEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }

    val downloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val path = pendingDownloadPath
        pendingDownloadPath = null
        if (uri != null && path != null) transfer.download(context, session, uri, path)
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun reload() { refreshKey++ }

    // Track which path the current listing belongs to so refresh keeps content,
    // while navigation clears immediately for a skeleton/first-paint feel.
    var listingPath by remember(session.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(session.id, directory, refreshKey, connection.phase) {
        if (connection.phase != Phase.CONNECTED) {
            listing = null
            listingPath = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        val requestDirectory = directory
        val requestRefresh = refreshKey
        loading = true
        error = null
        // Drop stale rows when navigating to another directory (not on same-path refresh).
        if (listingPath != null && listingPath != requestDirectory) {
            listing = null
        }
        try {
            val result = session.listRemoteDirectory(requestDirectory)
            if (directory == requestDirectory && refreshKey == requestRefresh) {
                listing = result
                listingPath = requestDirectory
                error = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (directory == requestDirectory && refreshKey == requestRefresh) {
                listing = null
                listingPath = null
                error = e.message ?: tr("无法读取目录")
            }
        } finally {
            if (directory == requestDirectory && refreshKey == requestRefresh) {
                loading = false
            }
        }
    }

    Column(modifier.fillMaxSize().testTag("remote-file-browser")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("files-back")) {
                Text(tr("返回主机列表"))
            }
            Row(
                Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tr("服务器文件"),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    session.host.name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("files-host-subtitle")
                )
            }
            TextButton(
                onClick = { reload() },
                enabled = connection.phase == Phase.CONNECTED && !loading && !actionBusy,
                modifier = Modifier.testTag("files-refresh")
            ) { Text(tr("刷新")) }
        }

        when (connection.phase) {
            Phase.CONNECTING, Phase.VERIFYING, Phase.AUTHENTICATING -> {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(Modifier.padding(bottom = 16.dp))
                    Text(phaseLabel(connection.phase), fontSize = 16.sp)
                    if (connection.message.isNotBlank()) {
                        Text(
                            tr(connection.message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            Phase.CONNECTED -> {
                val current = listing
                Column(Modifier.fillMaxSize()) {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        current?.path ?: if (directory.isBlank()) tr("正在读取远端目录…") else directory,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("files-breadcrumb"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                when {
                    loading && current == null -> {
                        Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text(tr("正在读取远端目录…"))
                        }
                    }
                    error != null && current == null -> {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(trf("读取失败：%s，点击重试", tr(error!!.take(120))))
                            TextButton(onClick = { reload() }, modifier = Modifier.padding(top = 12.dp)) {
                                Text(tr("刷新"))
                            }
                        }
                    }
                    else -> {
                        val allEntries = current?.entries.orEmpty()
                        // Bind the first screen immediately; append the rest in chunks so huge
                        // dirs (cargo registry, node_modules) paint the first page sooner.
                        var bindCount by remember(current?.path, allEntries) {
                            mutableIntStateOf(minOf(REMOTE_LIST_INITIAL_BIND, allEntries.size))
                        }
                        LaunchedEffect(current?.path, allEntries) {
                            bindCount = minOf(REMOTE_LIST_INITIAL_BIND, allEntries.size)
                            while (bindCount < allEntries.size) {
                                yield()
                                bindCount = minOf(bindCount + REMOTE_LIST_BIND_CHUNK, allEntries.size)
                            }
                        }
                        val density = LocalDensity.current
                        val currentPathForSwipe = current?.path
                        val parentSwipeEnabled = RemoteBrowserSwipeNav.canNavigateToParent(currentPathForSwipe)
                        LazyColumn(
                            Modifier
                                .weight(1f)
                                .pointerInput(currentPathForSwipe, parentSwipeEnabled) {
                                    if (!parentSwipeEnabled || currentPathForSwipe == null) return@pointerInput
                                    val edgeWidthPx = with(density) {
                                        RemoteBrowserSwipeNav.EDGE_WIDTH_DP.dp.toPx()
                                    }
                                    val minDxPx = with(density) {
                                        RemoteBrowserSwipeNav.MIN_RIGHT_DX_DP.dp.toPx()
                                    }
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (!RemoteBrowserSwipeNav.isLeftEdgeStart(down.position.x, edgeWidthPx)) {
                                            return@awaitEachGesture
                                        }
                                        var totalDx = 0f
                                        val slopPointer = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                            totalDx += overSlop
                                            change.consume()
                                        } ?: return@awaitEachGesture
                                        horizontalDrag(slopPointer.id) { change ->
                                            totalDx += change.positionChange().x
                                            change.consume()
                                        }
                                        if (RemoteBrowserSwipeNav.shouldGoToParent(totalDx, minDxPx)) {
                                            directory = parentRemotePath(currentPathForSwipe)
                                        }
                                    }
                                }
                                .testTag("files-parent-swipe"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (loading && current != null) {
                                item(key = "_loading") {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
                                }
                            }
                            if (current != null && current.path != "/") {
                                item(key = "..") {
                                    ListItem(
                                        headlineContent = { Text(tr("↑ 上级目录")) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                directory = parentRemotePath(current.path)
                                            }
                                            .testTag("files-parent")
                                    )
                                    HorizontalDivider()
                                }
                            }
                            items(
                                count = bindCount,
                                key = { index -> allEntries[index].name }
                            ) { index ->
                                val entry = allEntries[index]
                                RemoteFileRow(
                                    entry = entry,
                                    onOpen = {
                                        if (entry.isDirectory && current != null) {
                                            directory = joinRemotePath(current.path, entry.name)
                                        }
                                    },
                                    onLongPress = { menuEntry = entry }
                                )
                                HorizontalDivider()
                            }
                            if (current != null && current.entries.isEmpty() && !loading) {
                                item {
                                    Text(
                                        tr("此目录为空"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                } // Column for CONNECTED
            }
            else -> {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(phaseLabel(connection.phase), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (connection.message.isNotBlank()) {
                        Text(
                            tr(connection.message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                        Text(tr("返回主机列表"))
                    }
                }
            }
        }
    }

    pending?.let { PromptDialog(it) }

    if (menuEntry != null) {
        val entry = menuEntry!!
        val currentPath = listing?.path
        AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        buildString {
                            if (entry.permissions.isNotBlank()) append(entry.permissions)
                            if (!entry.isDirectory) {
                                if (isNotEmpty()) append("  ·  ")
                                append(formatRemoteSize(entry.size))
                            }
                            val mtime = formatRemoteMtime(entry.mtimeSeconds)
                            if (mtime.isNotEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append(mtime)
                            }
                        }.ifBlank { if (entry.isDirectory) tr("目录") else tr("文件") },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (entry.isDirectory) {
                        TextButton(onClick = {
                            menuEntry = null
                            if (currentPath != null) directory = joinRemotePath(currentPath, entry.name)
                        }) { Text(tr("打开")) }
                    } else {
                        TextButton(onClick = {
                            menuEntry = null
                            if (currentPath == null) return@TextButton
                            pendingDownloadPath = joinRemotePath(currentPath, entry.name)
                            downloadPicker.launch(entry.name)
                        }, modifier = Modifier.testTag("files-action-download")) { Text(tr("下载")) }
                        TextButton(onClick = {
                            menuEntry = null
                            renameDraft = entry.name
                            renameEntry = entry
                        }, modifier = Modifier.testTag("files-action-rename")) { Text(tr("重命名")) }
                    }
                    TextButton(onClick = {
                        menuEntry = null
                        deleteEntry = entry
                    }, modifier = Modifier.testTag("files-action-delete")) {
                        Text(tr("删除"), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { menuEntry = null }) { Text(tr("取消")) }
                }
            }
        )
    }

    renameEntry?.let { entry ->
        val currentPath = listing?.path
        AlertDialog(
            onDismissRequest = { if (!actionBusy) renameEntry = null },
            title = { Text(tr("重命名")) },
            text = {
                OutlinedTextField(
                    renameDraft,
                    { renameDraft = it },
                    label = { Text(tr("新文件名")) },
                    singleLine = true,
                    enabled = !actionBusy,
                    modifier = Modifier.fillMaxWidth().testTag("files-rename-input")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy && renameDraft.isNotBlank() && renameDraft != entry.name &&
                        '/' !in renameDraft && '\\' !in renameDraft && renameDraft != "." && renameDraft != "..",
                    onClick = {
                        if (currentPath == null) return@TextButton
                        val oldPath = joinRemotePath(currentPath, entry.name)
                        val newPath = joinRemotePath(currentPath, renameDraft.trim())
                        actionBusy = true
                        scope.launch {
                            try {
                                session.renameRemoteFile(oldPath, newPath)
                                renameEntry = null
                                reload()
                            } catch (e: Exception) {
                                toast(trf("重命名失败：%s", tr(e.message ?: "请检查路径和 SFTP 服务")))
                            } finally {
                                actionBusy = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("files-rename-confirm")
                ) { Text(tr("保存")) }
            },
            dismissButton = {
                TextButton(onClick = { renameEntry = null }, enabled = !actionBusy) { Text(tr("取消")) }
            }
        )
    }

    deleteEntry?.let { entry ->
        val currentPath = listing?.path
        AlertDialog(
            onDismissRequest = { if (!actionBusy) deleteEntry = null },
            title = { Text(trf("删除 %s？", entry.name)) },
            text = {
                Text(
                    if (entry.isDirectory) tr("将永久删除远端目录及其全部内容，此操作不可撤销。")
                    else tr("将永久删除远端文件，此操作不可撤销。")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = {
                        if (currentPath == null) return@TextButton
                        val path = joinRemotePath(currentPath, entry.name)
                        actionBusy = true
                        scope.launch {
                            try {
                                session.deleteRemoteFile(path)
                                deleteEntry = null
                                reload()
                            } catch (e: Exception) {
                                val message = e.message ?: "请检查路径和 SFTP 服务"
                                toast(trf("删除失败：%s", tr(message)))
                                deleteEntry = null
                            } finally {
                                actionBusy = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("files-delete-confirm")
                ) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }, enabled = !actionBusy) { Text(tr("取消")) }
            }
        )
    }

    if (transferStatus.running || transferStatus.result != null) {
        TransferStatusDialog(
            transferStatus.name,
            transferStatus.bytes,
            transferStatus.total,
            transferStatus.running,
            transferStatus.result,
            onCancel = transfer::cancel,
            onDismiss = transfer::dismiss
        )
    }
}

private const val REMOTE_LIST_INITIAL_BIND = 64
private const val REMOTE_LIST_BIND_CHUNK = 128

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteFileRow(entry: RemoteEntry, onOpen: () -> Unit, onLongPress: () -> Unit) {
    // Defer/caches heavy-ish formatting so LazyColumn scrapes stay cheap on recompose.
    val meta = remember(entry.name, entry.isDirectory, entry.size, entry.mtimeSeconds, entry.permissions) {
        buildString {
            if (entry.permissions.isNotBlank()) append(entry.permissions)
            if (!entry.isDirectory) {
                if (isNotEmpty()) append("  ·  ")
                append(formatRemoteSize(entry.size))
            }
            val mtime = formatRemoteMtime(entry.mtimeSeconds)
            if (mtime.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(mtime)
            }
        }
    }
    ListItem(
        headlineContent = {
            Text(
                if (entry.isDirectory) "▸ ${entry.name}" else entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal
            )
        },
        supportingContent = {
            if (meta.isNotEmpty()) {
                Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            }
        },
        trailingContent = {
            TextButton(onClick = onLongPress, modifier = Modifier.testTag("files-row-menu-${entry.name}")) {
                Text("⋯")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .testTag("files-entry-${entry.name}")
    )
}
