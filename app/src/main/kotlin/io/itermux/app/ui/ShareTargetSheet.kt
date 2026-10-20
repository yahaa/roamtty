package io.itermux.app.ui

import io.itermux.app.tr

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase

/** Picks the connected session that receives images shared to the app while several are connected. */
@Composable
internal fun ShareTargetSheet(choices: List<TerminalConnection>, onDismiss: () -> Unit, onPick: (TerminalConnection) -> Unit) {
    AppSheet(onDismiss = onDismiss) {
        LazyColumn(Modifier.weight(1f)) {
            item { SheetHeader(tr("选择会话以上传图片"), tr("选择接收图片的主机和会话。"), onDismiss) }
            items(choices.size, key = { choices[it].id }) { index ->
                val session = choices[index]
                val title by session.title.collectAsStateWithLifecycle()
                val state by session.state.collectAsStateWithLifecycle()
                GroupedRow(session.titleForDisplay(title),
                    Modifier.padding(horizontal = Spacing.Tight).clip(MaterialTheme.shapes.small)
                        .testTag("share-session-${session.id}"),
                    subtitle = "${session.host.username}@${session.host.address}:${session.host.port}",
                    leading = { SessionIcon(session, 40.dp) },
                    trailing = { Chevron() },
                    enabled = state.phase == Phase.CONNECTED,
                    onClick = { onPick(session) })
            }
        }
    }
}
