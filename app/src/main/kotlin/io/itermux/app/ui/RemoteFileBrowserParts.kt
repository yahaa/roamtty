package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.breadcrumbSegments
import io.itermux.ssh.*

/**
 * One-line path title: host and parent folders as quiet tappable crumbs, ending in the current folder
 * (the host at the root) in title weight. Long paths scroll to their end and fade at the leading edge
 * instead of being clipped.
 */
@Composable
internal fun FileBrowserPathBar(path: String, host: String, enabled: Boolean, onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier, tagPrefix: String = "files") {
    val folders = remember(path) { breadcrumbSegments(path).drop(if (path.startsWith('/')) 1 else 0) }
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    val colors = MaterialTheme.colorScheme
    val fadeWidth = 24.dp
    Row(modifier.heightIn(min = 48.dp).testTag("$tagPrefix-breadcrumbs")
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (scroll.value > 0) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black),
                endX = fadeWidth.toPx()), blendMode = BlendMode.DstIn)
        }
        .horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Crumb(0, host, current = folders.isEmpty(), enabled, tagPrefix, onClick = { onNavigate("/") }) {
            Icon(painterResource(R.drawable.ic_os_server), contentDescription = null, Modifier.size(16.dp),
                tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        folders.forEachIndexed { index, (name, target) ->
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                modifier = Modifier.size(14.dp), tint = colors.outline)
            Crumb(index + 1, name, current = index == folders.lastIndex, enabled, tagPrefix, onClick = { onNavigate(target) })
        }
    }
}

@Composable
private fun Crumb(index: Int, label: String, current: Boolean, enabled: Boolean, tagPrefix: String, onClick: () -> Unit,
    leading: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(enabled = enabled && !current, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 10.dp)
        .then(if (current) Modifier.semantics { heading() } else Modifier)
        .testTag(if (current) "$tagPrefix-title" else "$tagPrefix-crumb-$index"), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Text(label, maxLines = 1, softWrap = false,
            style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FilterField(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppSearchField(value, onValueChange, tr("筛选当前目录"), leadingIcon = R.drawable.ic_filter,
        trailing = { IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), tr("关闭筛选")) } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus).testTag("files-filter"))
}

@Composable
internal fun ListingSummary(listing: RemoteDirectory, shown: List<RemoteEntry>, options: RemoteFileOptions) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summaryText(listing, shown),
            Modifier.weight(1f).testTag("files-summary"), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SortButton(options)
    }
}

/** What is shown and what is hidden: `33 个文件夹 · 31 个文件 · 64 项未显示`. */
internal fun summaryText(listing: RemoteDirectory, shown: List<RemoteEntry>): String {
    val folders = shown.count { it.isDirectory }
    val files = shown.size - folders
    val hidden = listing.entries.size - shown.size
    return listOfNotNull(if (folders > 0) trf("%d 个文件夹", folders) else null, if (files > 0) trf("%d 个文件", files) else null,
        if (hidden > 0) trf("%d 项未显示", hidden) else null).joinToString(" · ")
}

@Composable
internal fun SortButton(options: RemoteFileOptions) {
    var sortMenu by remember { mutableStateOf(false) }
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

/** The landscape bar's filter: a capsule field that is always there, in place of the toggled row under the bar. */
@Composable
internal fun BarSearchField(value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value, onValueChange, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.padding(start = 4.dp).width(210.dp).height(34.dp).testTag("files-filter")) { field ->
        Row(Modifier.fillMaxSize().clip(CircleShape).background(colors.onSurface.copy(alpha = 0.08f)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Box(Modifier.weight(1f).padding(horizontal = 7.dp)) {
                if (value.isEmpty()) Text(tr("筛选当前目录"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(28.dp)) {
                Icon(painterResource(R.drawable.ic_clear), contentDescription = tr("清除"), Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun BrowserMessage(message: String, busy: Boolean, modifier: Modifier, icon: Boolean = false,
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
    onSaveAs: () -> Unit = onDownload, onCopyPath: (() -> Unit)? = null, onOpenWithAgent: (() -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 200.dp, max = 280.dp).testTag("files-actions-list")) {
        DropdownMenuItem(text = { Text(tr(if (entry.isDirectory) "打开" else "查看内容")) },
            onClick = onOpen, enabled = !busy, modifier = Modifier.testTag("files-action-open"))
        if (onOpenTerminal != null) DropdownMenuItem(text = { Text(tr("打开终端")) }, onClick = onOpenTerminal,
            trailingIcon = { Icon(painterResource(R.drawable.ic_sessions), contentDescription = null, Modifier.size(18.dp)) },
            modifier = Modifier.testTag("files-action-open-terminal"))
        if (onOpenWithAgent != null) DropdownMenuItem(text = { Text(tr("Agent")) }, onClick = onOpenWithAgent,
            trailingIcon = { Icon(painterResource(R.drawable.ic_agent), contentDescription = null, Modifier.size(18.dp)) },
            modifier = Modifier.testTag("files-action-open-agent"))
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
internal fun RemoteFileRow(entry: RemoteEntry, onOpen: () -> Unit, onMenu: () -> Unit, compact: Boolean = false,
    menu: @Composable () -> Unit = {}) {
    if (compact) return CompactFileRow(entry, onOpen, onMenu, menu)
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

/** A landscape table row: the name takes the width, then the size and the modified time in their own columns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactFileRow(entry: RemoteEntry, onOpen: () -> Unit, onMenu: () -> Unit, menu: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val link = entry.permissions.startsWith('l')
    val hidden = entry.name.startsWith('.')
    val meta = MaterialTheme.typography.bodySmall
    Column {
        Row(Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClick = onOpen,
            onLongClickLabel = tr("文件操作"), onLongClick = onMenu).heightIn(min = 44.dp)
            .padding(start = 12.dp, end = 4.dp).testTag("files-entry-${entry.name}"), verticalAlignment = Alignment.CenterVertically) {
            RemoteFileIcon(entry.name, entry.isDirectory, entry.permissions, size = 24.dp, modifier = if (hidden) Modifier.alpha(0.6f) else Modifier)
            Spacer(Modifier.width(12.dp))
            Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface.copy(alpha = if (hidden) 0.7f else 1f))
            Text(when { link -> tr("链接"); entry.isDirectory -> ""; else -> formatRemoteSize(entry.size) }, Modifier.width(84.dp),
                style = meta, color = colors.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.End)
            Text(formatRemoteMtime(entry.mtimeSeconds), Modifier.width(140.dp), style = meta, color = colors.onSurfaceVariant,
                maxLines = 1, textAlign = TextAlign.End)
            Box {
                IconButton(onClick = onMenu, modifier = Modifier.testTag("files-row-menu-${entry.name}")) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = trf("文件操作：%s", entry.name), tint = colors.onSurfaceVariant)
                }
                menu()
            }
        }
        GroupedDivider(start = 48.dp)
    }
}
