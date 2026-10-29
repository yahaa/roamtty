package io.itermux.app.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.itermux.app.R
import io.itermux.app.session.RemoteFolders
import io.itermux.app.session.StartDirectory
import io.itermux.app.tr
import io.itermux.app.trf
import kotlinx.coroutines.launch

internal fun folderName(directory: String) = if (StartDirectory.isHome(directory)) tr("主目录")
    else directory.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/**
 * Where the session will start, as tappable crumbs (`~ › work › app`), so any folder above is one tap away; the arrow
 * steps up past home. The magnifier filters long folder lists.
 */
@Composable
internal fun PathBar(segments: List<Pair<String, String>>, parent: String?, canFilter: Boolean,
    onNavigate: (String) -> Unit, onFilter: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(segments, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { parent?.let(onNavigate) }, enabled = parent != null, modifier = Modifier.size(40.dp).testTag("launch-parent")) {
            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = tr("上一级"), Modifier.size(20.dp))
        }
        Row(Modifier.weight(1f).horizontalScroll(scroll).testTag("launch-path"), verticalAlignment = Alignment.CenterVertically) {
            segments.forEachIndexed { index, (name, path) ->
                val last = index == segments.lastIndex
                if (index > 0) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    Modifier.size(14.dp), tint = colors.outline)
                Text(name, maxLines = 1, softWrap = false,
                    style = if (last) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (last) colors.onSurface else colors.primary,
                    modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(enabled = !last) { onNavigate(path) }
                        .padding(horizontal = 6.dp, vertical = 10.dp).testTag("launch-crumb-$index"))
            }
        }
        if (canFilter) IconButton(onClick = onFilter, modifier = Modifier.size(40.dp).testTag("launch-filter-toggle")) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = tr("筛选当前目录"),
                Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
    }
}

/** Takes the path bar's place while filtering, so the folders keep the room; Enter opens the first match. */
@Composable
internal fun FolderFilter(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit, onGo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp).testTag("launch-filter-close")) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = tr("关闭筛选"), Modifier.size(20.dp))
        }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(tr("筛选当前目录"), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            BasicTextField(value, onValueChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("launch-filter"))
        }
        if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_clear), contentDescription = tr("清除"), Modifier.size(18.dp),
                tint = colors.onSurfaceVariant)
        }
    }
}

/** Recently used folders, one tap each; the cross forgets one. */
@Composable
internal fun RecentFolders(recent: List<String>, current: String, onPick: (String) -> Unit, onForget: (String) -> Unit) {
    LazyRow(Modifier.padding(bottom = Spacing.Tight), contentPadding = PaddingValues(horizontal = Spacing.Page),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        items(recent, key = { it }) { dir ->
            FilterChip(current == dir, onClick = { onPick(dir) }, label = { Text(folderName(dir), maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null, Modifier.size(16.dp)) },
                trailingIcon = {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = trf("移除 %s", dir),
                        Modifier.size(16.dp).clickable { onForget(dir) })
                },
                modifier = Modifier.testTag("launch-recent-${recent.indexOf(dir)}"))
        }
    }
}

/** The recent folders as a list, for the landscape side column; tapping one opens it, the cross forgets it. */
@Composable
internal fun RecentFolderList(recent: List<String>, current: String, onPick: (String) -> Unit, onForget: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.groupedContainer) {
        Column {
            recent.forEachIndexed { index, dir ->
                if (index > 0) GroupedDivider()
                Row(Modifier.fillMaxWidth().background(if (dir == current) colors.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { onPick(dir) }.height(40.dp).padding(start = Spacing.Row).testTag("launch-recent-$index"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_history), contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    Text(folderName(dir), Modifier.weight(1f).padding(horizontal = 9.dp), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    IconButton(onClick = { onForget(dir) }, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_clear), contentDescription = trf("移除 %s", dir), Modifier.size(16.dp),
                            tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** [folders] of [listing] (all, or those matching the filter); [busy] dims them while the next folder loads. */
@Composable
internal fun FolderList(listing: RemoteFolders, folders: List<String>, busy: Boolean, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().alpha(if (busy) 0.5f else 1f).testTag("launch-folders")) {
            if (folders.isEmpty()) item(key = "empty") {
                Text(if (listing.folders.isEmpty()) tr("这里没有子文件夹") else tr("没有匹配的文件夹"),
                    Modifier.fillMaxWidth().padding(Spacing.Section), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
            items(folders, key = { "f:$it" }) { name ->
                FolderRow(name, enabled = !busy) { onOpen(listing.child(name)) }
                GroupedDivider(start = 60.dp)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun FolderRow(name: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 44.dp)
        .padding(horizontal = Spacing.Page, vertical = 6.dp).testTag("launch-folder-$name"),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteFileIcon(name, directory = true, size = 22.dp)
        Spacer(Modifier.width(Spacing.Row))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Chevron()
    }
}

internal fun SharedPreferences.recentStartDirectories(hostId: String): List<String> =
    StartDirectory.sanitizeRecent(getString("$RECENT_KEY:$hostId", null).orEmpty().split('\n'))

internal fun SharedPreferences.saveRecentStartDirectories(hostId: String, directories: List<String>) =
    edit { putString("$RECENT_KEY:$hostId", StartDirectory.sanitizeRecent(directories).joinToString("\n")) }

private const val RECENT_KEY = "startDirectories"
