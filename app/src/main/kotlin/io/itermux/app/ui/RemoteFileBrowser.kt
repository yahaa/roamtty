package io.itermux.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.session.AgentLaunch
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.itermux.app.session.RemoteBrowserState

/** Where the file browser leads outside itself; null for what the page it is on does not offer. */
internal class FileBrowserTargets(
    val onBack: () -> Unit,
    val onOpenTerminal: ((String) -> Unit)?,
    /** Asks which agent to start in a folder. */
    val onAgent: ((String) -> Unit)?,
)

/** The folder the browser is in as the server lists it: null until it is read, with why it could not be. */
internal class FileListing(val directory: RemoteDirectory?, val loading: Boolean, val error: String?)

@Composable
internal fun RemoteFileBrowserPage(session: TerminalConnection, onBack: () -> Unit, modifier: Modifier = Modifier,
    onLaunchAgent: ((AgentLaunch) -> Unit)? = null, onOpenTerminal: ((String) -> Unit)? = null) {
    val connection by session.state.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val transfer = session.transfers
    val transferStatus by transfer.status.collectAsStateWithLifecycle()
    val browser = session.fileBrowser
    val options = rememberRemoteFileOptions()
    val downloads = rememberDownloadActions(session)
    val connected = connection.phase == Phase.CONNECTED
    var agentDirectory by remember { mutableStateOf<String?>(null) }
    BackHandler { if (browser.filtering) browser.clearFilter() else if (!browser.goBack()) onBack() }
    DisposableEffect(browser) { onDispose { browser.cancelPendingOpen() } }

    val listing = rememberFileListing(session, connection.phase)
    val entries = rememberShownEntries(listing.directory, options, browser.filter)
    val currentPath = listing.directory?.path ?: browser.directory
    val targets = FileBrowserTargets(onBack, onOpenTerminal, onAgent = if (onLaunchAgent != null) { { agentDirectory = it } } else null)
    // Landscape, like the host page there: one 48dp bar that also carries the summary, the sort and the search field,
    // and compact rows with the size and the time in columns.
    val landscape = isLandscape()
    Column(modifier.fillMaxSize().testTag("remote-file-browser")) {
        FileBrowserBar(session, listing, entries, options, currentPath, landscape, targets)
        AnimatedVisibility(browser.filtering && !landscape) { FilterField(browser.filter, { browser.filter = it }, browser::clearFilter) }
        FileBrowserStatus(browser)
        val current = listing.directory
        when {
            !connected -> BrowserMessage(tr(connection.message).ifBlank { phaseLabel(connection.phase) }, connection.active, Modifier.weight(1f))
            current == null && listing.loading -> BrowserMessage(tr("正在读取远端目录…"), true, Modifier.weight(1f))
            listing.error != null || current == null -> BrowserMessage(tr(listing.error ?: "无法读取目录"), false, Modifier.weight(1f)) {
                TextButton(onClick = browser::refresh) { Text(tr("重试")) }
            }
            else -> {
                if (!landscape) ListingSummary(current, entries, options)
                FileBrowserList(session, current, entries, listing.loading, downloads, landscape, targets, Modifier.weight(1f).fillMaxWidth())
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
    if (onLaunchAgent != null) agentDirectory?.let { directory ->
        AgentLaunchSheet(directory, onDismiss = { agentDirectory = null }) { launch ->
            agentDirectory = null
            onLaunchAgent(launch)
        }
    }
    pending?.let { PromptDialog(it) }
    FileBrowserDialogs(session, connected, listing.directory, currentPath)
    if (transferStatus.running || transferStatus.result != null) {
        TransferStatusDialog(transferStatus, transfer::cancel, transfer::dismiss)
    }
}

/** Reads the browser's folder again whenever it changes, is refreshed, or the connection comes back. */
@Composable
private fun rememberFileListing(session: TerminalConnection, phase: Phase): FileListing {
    val browser = session.fileBrowser
    var listing by remember(session.id) { mutableStateOf<RemoteDirectory?>(null) }
    var listingKey by remember(session.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session.id, browser.directory, browser.revision, phase) {
        error = null
        // A refresh of the same folder keeps the old rows on screen until the new listing arrives.
        if (listingKey != browser.directory) listing = null
        if (phase != Phase.CONNECTED) { listing = null; loading = false; return@LaunchedEffect }
        loading = true
        try {
            listing = session.listRemoteDirectory(browser.directory)
            listingKey = browser.directory
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { listing = null; error = e.message ?: tr("无法读取目录") }
        finally { loading = false }
    }
    return FileListing(listing, loading, error)
}

/** The rows of [listing] that the options and the [filter] leave, sorted off the main thread. */
@Composable
private fun rememberShownEntries(listing: RemoteDirectory?, options: RemoteFileOptions, filter: String): List<RemoteEntry> {
    val entries by produceState(
        initialValue = emptyList<RemoteEntry>(),
        listing, options.showHiddenFiles, options.sort, options.sortDescending, filter
    ) {
        val snapshot = listing?.entries.orEmpty()
        val showHidden = options.showHiddenFiles
        val sort = options.sort
        val descending = options.sortDescending
        val needle = filter.trim()
        // Debounce filter typing so each keystroke does not sort a huge directory on the UI thread.
        if (needle.isNotEmpty()) kotlinx.coroutines.delay(120)
        value = withContext(Dispatchers.Default) {
            val visible = snapshot.filter {
                (showHidden || !it.name.startsWith('.')) && (needle.isEmpty() || it.name.contains(needle, ignoreCase = true))
            }
            sortRemoteEntries(visible, sort, descending)
        }
    }
    return entries
}

/** The page's bar: back, the path, the search (with the summary and the sort in landscape) and the menu. */
@Composable
private fun FileBrowserBar(session: TerminalConnection, listing: FileListing, entries: List<RemoteEntry>, options: RemoteFileOptions,
    currentPath: String, landscape: Boolean, targets: FileBrowserTargets) {
    val browser = session.fileBrowser
    val connection by session.state.collectAsStateWithLifecycle()
    val connected = connection.phase == Phase.CONNECTED
    val shownListing = listing.directory
    Row(Modifier.fillMaxWidth().then(if (landscape) Modifier.height(HOME_COMPACT_BAR_HEIGHT) else Modifier.heightIn(min = 56.dp))
        .padding(horizontal = 4.dp).testTag("files-bar"), verticalAlignment = Alignment.CenterVertically) {
        // The toolbar arrow leaves the browser; system back still walks the folder history first.
        IconButton(onClick = targets.onBack, modifier = Modifier.testTag("files-back")) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = tr("返回主机详情"))
        }
        FileBrowserPathBar(currentPath, session.host.name, enabled = connected, onNavigate = browser::navigate,
            modifier = Modifier.weight(1f))
        if (landscape) {
            if (shownListing != null) {
                Text(summaryText(shownListing, entries), Modifier.widthIn(max = 220.dp).padding(start = 8.dp).testTag("files-summary"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                SortButton(options)
            }
            BarSearchField(browser.filter, enabled = shownListing != null, onValueChange = {
                browser.filter = it
                browser.filtering = it.isNotEmpty()
            })
        } else IconButton(onClick = { if (browser.filtering) browser.clearFilter() else browser.filtering = true },
            enabled = shownListing != null, modifier = Modifier.testTag("files-search")) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = tr("筛选当前目录"),
                tint = if (browser.filtering) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        RemoteFileOptionsMenu(options) { dismissMenu ->
            FileBrowserMenuItems(session, connected, listing, currentPath, targets, dismissMenu)
            HorizontalDivider()
        }
    }
}

/** The bar menu's own items, above the listing options: what to do with the current folder. */
@Composable
private fun FileBrowserMenuItems(session: TerminalConnection, connected: Boolean, listing: FileListing, currentPath: String,
    targets: FileBrowserTargets, dismissMenu: () -> Unit) {
    val context = LocalContext.current
    val browser = session.fileBrowser
    val transfer = session.transfers
    val transferStatus by transfer.status.collectAsStateWithLifecycle()
    val listed = listing.directory != null
    @Composable fun Item(label: String, icon: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) =
        DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
            onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag))
    val onOpenTerminal = targets.onOpenTerminal
    val onAgent = targets.onAgent
    if (onOpenTerminal != null) Item(tr("打开终端"), R.drawable.ic_sessions, "files-open-terminal",
        enabled = currentPath.isNotEmpty() && listed) { dismissMenu(); onOpenTerminal(currentPath) }
    if (onAgent != null) Item(tr("Agent"), R.drawable.ic_agent, "files-open-agent",
        enabled = currentPath.isNotEmpty() && listed) { onAgent(currentPath); dismissMenu() }
    if (onOpenTerminal != null || onAgent != null) HorizontalDivider()
    Item(tr("刷新"), R.drawable.ic_refresh, "files-refresh",
        enabled = connected && !listing.loading && browser.operation == null) { browser.refresh(); dismissMenu() }
    Item(tr("新建文件夹"), R.drawable.ic_folder_add, "files-new-folder",
        enabled = connected && listed && browser.operation == null) { browser.newFolderDraft = ""; dismissMenu() }
    Item(tr("批量下载"), R.drawable.ic_download, "files-batch-download", enabled = connected && !transferStatus.running) {
        transfer.selectedDownloads = emptyList()
        transfer.openDownloads { currentPath }
        dismissMenu()
    }
    Item(tr("复制当前路径"), R.drawable.ic_copy, "files-copy-path", enabled = currentPath.isNotEmpty()) {
        context.copyText(currentPath); dismissMenu()
    }
    Item(tr("返回主机详情"), R.drawable.ic_close, "files-close") { dismissMenu(); targets.onBack() }
}

/** The file operation that is running, with its cancel, and the last one's failure. */
@Composable
private fun FileBrowserStatus(browser: RemoteBrowserState) {
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
}

/** The rows of [current], pulled down to refresh; each directory keeps its own scroll position. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileBrowserList(session: TerminalConnection, current: RemoteDirectory, entries: List<RemoteEntry>, loading: Boolean,
    downloads: DownloadActions, landscape: Boolean, targets: FileBrowserTargets, modifier: Modifier) {
    val browser = session.fileBrowser
    val listStates = rememberSaveableStateHolder()
    PullToRefreshBox(isRefreshing = loading, onRefresh = browser::refresh, modifier = modifier) {
        if (entries.isEmpty()) BrowserMessage(tr(when {
            current.entries.isEmpty() -> "此目录为空"
            browser.filter.isNotBlank() -> "没有匹配的项目"
            else -> "没有可显示的项目"
        }), false, Modifier.fillMaxSize(), icon = true)
        else listStates.SaveableStateProvider(current.path) {
            // Each directory gets its own scroll position; returning restores it within the page.
            LazyColumn(Modifier.fillMaxSize().testTag("files-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(entries, key = { it.name }) { entry ->
                    FileBrowserEntry(session, current.path, entry, downloads, landscape, targets)
                }
            }
        }
    }
}

/** One row with its menu: a tap opens the folder, follows the link or previews the file. */
@Composable
private fun FileBrowserEntry(session: TerminalConnection, directory: String, entry: RemoteEntry, downloads: DownloadActions,
    landscape: Boolean, targets: FileBrowserTargets) {
    val context = LocalContext.current
    val browser = session.fileBrowser
    val connection by session.state.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    val path = joinRemotePath(directory, entry.name)
    fun open() {
        browser.cancelPendingOpen()
        when {
            entry.isDirectory -> browser.navigate(path)
            entry.permissions.startsWith('l') -> browser.openLink(path, session::isRemoteDirectory)
            else -> browser.previewPath = path
        }
    }
    /** Closes the row's menu, then does what its item asked. */
    fun picked(action: () -> Unit): () -> Unit = { browser.dismissMenu(); action() }
    val onAgent = targets.onAgent
    val onOpenTerminal = targets.onOpenTerminal
    RemoteFileRow(entry, onOpen = ::open, onMenu = { browser.showMenu(directory, entry) }, compact = landscape) {
        FileActionsMenu(entry, expanded = browser.menuPath == path,
            busy = browser.operation != null || transferStatus.running || downloads.busy || connection.phase != Phase.CONNECTED,
            onOpen = picked(::open),
            onDownload = picked { downloads.start(listOf(DownloadItem(path, entry.size)), false) },
            onSaveAs = picked { downloads.start(listOf(DownloadItem(path, entry.size)), true) },
            onRename = picked { browser.renamePath = path; browser.renameDraft = entry.name },
            onDelete = picked { browser.deletePath = path; browser.deleteIsDirectory = entry.isDirectory },
            onCopyPath = picked { context.copyText(path) },
            onOpenWithAgent = if (onAgent != null && entry.isDirectory) picked { onAgent(path) } else null,
            onOpenTerminal = if (onOpenTerminal != null && entry.isDirectory) picked { onOpenTerminal(path) } else null,
            onDismiss = browser::dismissMenu)
    }
}
