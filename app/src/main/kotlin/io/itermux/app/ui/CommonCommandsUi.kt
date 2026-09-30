package io.itermux.app.ui

import io.itermux.app.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.CommonCommandsState
import io.itermux.app.session.ShellHistoryCommands
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase

@Composable
internal fun CommonCommandsDialog(session: TerminalConnection, onDismiss: () -> Unit) {
    val connection by session.state.collectAsStateWithLifecycle()
    val history by session.commonCommands.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val filtered = remember(history, query) {
        when (val state = history) {
            is CommonCommandsState.Ready -> ShellHistoryCommands.fuzzyFilter(state.commands, query)
            else -> emptyList()
        }
    }
    val connected = connection.phase == Phase.CONNECTED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("常用命令")) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).testTag("common-commands-search"),
                        singleLine = true,
                        enabled = history is CommonCommandsState.Ready,
                        label = { Text(tr("搜索命令")) },
                        placeholder = { Text(tr("过滤常用命令")) }
                    )
                    TextButton(
                        onClick = { session.refreshCommonCommands() },
                        enabled = connected,
                        modifier = Modifier.testTag("common-commands-refresh")
                    ) { Text(tr("刷新")) }
                }
                when (val state = history) {
                    CommonCommandsState.Idle, CommonCommandsState.Loading -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text(tr("正在读取 shell 历史…"), fontSize = 13.sp)
                        }
                    }
                    is CommonCommandsState.Unavailable -> {
                        Text(tr(state.reason), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    is CommonCommandsState.Ready -> {
                        Text(tr("来源：") + state.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (filtered.isEmpty()) {
                            Text(
                                if (query.isBlank()) tr("未找到可用命令") else tr("无匹配命令"),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("common-commands-list")) {
                                items(filtered, key = { it }) { command ->
                                    TextButton(
                                        onClick = {
                                            session.executeCommonCommand(command)
                                            onDismiss()
                                        },
                                        enabled = connected,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            command,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("关闭")) } }
    )
}
