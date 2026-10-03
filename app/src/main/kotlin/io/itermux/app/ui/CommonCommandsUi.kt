package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.tr
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
    AppSheet(onDismiss) {
        LazyColumn(Modifier.fillMaxSize().testTag("common-commands-list"),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { SheetHeader(tr("常用命令"), tr("最近使用在前，点选后立即执行。"), onDismiss) }
            item(key = "search") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        placeholder = { Text(tr("搜索命令")) }, shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(20.dp)) },
                        modifier = Modifier.weight(1f).testTag("common-commands-search"))
                    IconButton(onClick = onRefresh, enabled = connected && history !is CommonCommandsState.Loading,
                        modifier = Modifier.padding(start = 8.dp).testTag("common-commands-refresh")) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = tr("刷新"))
                    }
                }
            }
            item(key = "spacing") { Spacer(Modifier.height(12.dp)) }
            when (history) {
                CommonCommandsState.Idle, CommonCommandsState.Loading -> item {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(tr("正在读取 shell 历史…"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is CommonCommandsState.Unavailable -> item {
                    Text(tr(history.reason), modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is CommonCommandsState.Ready -> {
                    if (filtered.isEmpty()) item {
                        Text(tr(if (query.isBlank()) "未找到可用命令" else "无匹配命令"), Modifier.padding(24.dp))
                    }
                    items(filtered, key = { "command:$it" }) { command ->
                        // A prompt-styled row reads as "run this", without a chevron on every line.
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(enabled = connected) { onSelect(command) }
                            .padding(horizontal = 14.dp, vertical = 12.dp).testTag("common-command-$command")) {
                            Text("$", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                            Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
