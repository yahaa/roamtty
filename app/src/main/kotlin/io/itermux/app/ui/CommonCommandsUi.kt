package io.itermux.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.session.CommonCommandsState
import io.itermux.app.session.ShellHistoryCommands
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase

@Composable
internal fun CommonCommandsSheet(session: TerminalConnection, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val connection by session.state.collectAsStateWithLifecycle()
    val history by session.commonCommands.collectAsStateWithLifecycle()
    CommonCommandsContent(history, connection.phase == Phase.CONNECTED,
        session::refreshCommonCommands, onSelect, onDismiss)
}

@Composable
internal fun CommonCommandsContent(history: CommonCommandsState, connected: Boolean,
    onRefresh: () -> Unit, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(history, query) {
        (history as? CommonCommandsState.Ready)?.let { ShellHistoryCommands.fuzzyFilter(it.commands, query) }.orEmpty()
    }
    val ready = history as? CommonCommandsState.Ready
    val terminalFont = rememberTerminalFontFamily()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val refresh: @Composable () -> Unit = {
        ToolbarIcon(R.drawable.ic_refresh, tr("刷新"), Modifier.testTag("common-commands-refresh"),
            enabled = connected && history !is CommonCommandsState.Loading,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onRefresh)
    }
    val caption = ready?.takeIf { filtered.isNotEmpty() }?.let { trf("%d 条 · %s", filtered.size, it.source) }
    AppSheet(onDismiss, fullScreen = landscape, wide = landscape) {
        // Landscape: one flat bar with the count after the title and the search in it, so the list keeps the height.
        if (landscape) LandscapeBar(tr("常用命令"), detail = caption, leading = {
            ToolbarIcon(R.drawable.ic_close, tr("关闭"), onClick = onDismiss)
        }) {
            CompactSearchField(query, { query = it }, tr("搜索命令"), Modifier.width(260.dp).testTag("common-commands-search"))
            refresh()
        } else {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.Page + Spacing.Hairline, end = Spacing.Tight, bottom = Spacing.Row),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("常用命令"), style = MaterialTheme.typography.titleLarge)
                    Text(tr("点按即执行"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                refresh()
                ToolbarIcon(R.drawable.ic_close, tr("关闭"), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onDismiss)
            }
            AppSearchField(query, { query = it }, tr("搜索命令"),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).testTag("common-commands-search"))
        }
        LazyColumn(Modifier.fillMaxSize().testTag("common-commands-list"),
            contentPadding = PaddingValues(start = Spacing.Page, end = Spacing.Page, top = if (landscape) Spacing.Hairline else Spacing.Page, bottom = Spacing.Section)) {
            when (history) {
                CommonCommandsState.Idle, CommonCommandsState.Loading -> item {
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.Section), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Row, Alignment.CenterHorizontally)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr("正在读取 shell 历史…"), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is CommonCommandsState.Unavailable -> item { EmptyNote(tr(history.reason)) }
                is CommonCommandsState.Ready -> {
                    if (filtered.isEmpty()) item { EmptyNote(tr(if (query.isBlank()) "未找到可用命令" else "无匹配命令")) }
                    else if (!landscape) item(key = "caption") {
                        Text(trf("%d 条 · %s", filtered.size, history.source), style = MaterialTheme.typography.sectionHeader,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Tight))
                    }
                    itemsIndexed(filtered, key = { _, command -> "command:$command" }) { index, command ->
                        CommandRow(command, terminalFont, first = index == 0, last = index == filtered.lastIndex,
                            enabled = connected) { onSelect(command) }
                    }
                }
            }
        }
    }
}

/** One row of a single inset panel: the rows share a surface and hairlines instead of each being a card. */
@Composable
private fun CommandRow(command: String, font: FontFamily, first: Boolean, last: Boolean, enabled: Boolean,
    onClick: () -> Unit) {
    val radius = 12.dp
    val shape = RoundedCornerShape(topStart = if (first) radius else 0.dp, topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp, bottomEnd = if (last) radius else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.groupedContainer)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = Spacing.Page, vertical = 11.dp).testTag("common-command-$command"),
            verticalAlignment = Alignment.Top) {
            // A dim prompt reads as "run this" without a chevron on every line.
            Text("\u276F", fontFamily = font, fontSize = 13.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), modifier = Modifier.padding(end = 10.dp))
            Text(command, fontFamily = font, fontSize = 13.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
        if (!last) GroupedDivider(start = Spacing.Page + 18.dp)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Section))
}
