package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode

/** The dock's sessions button and its menu: this session's actions, then every session to switch to. */
@Composable
internal fun SessionSwitcher(session: TerminalConnection, title: String, phase: Phase, sessions: List<TerminalConnection>,
    expanded: Boolean, modifier: Modifier, onExpand: () -> Unit, onDismiss: () -> Unit, onHostDetail: () -> Unit,
    onClose: () -> Unit, onRestart: () -> Unit, onSelect: (String) -> Unit) {
    Box(modifier) {
        TextButton(onClick = onExpand, modifier = Modifier.fillMaxWidth().semantics { contentDescription = trf("切换会话：%s", session.titleForDisplay(title)) }.testTag("session-switcher"), contentPadding = PaddingValues(horizontal = 8.dp)) {
            // The dot on the sessions icon doubles as the connection indicator.
            Box {
                Icon(painterResource(R.drawable.ic_sessions), contentDescription = null, modifier = Modifier.size(22.dp))
                Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(8.dp).clip(CircleShape)
                    .background(if (phase == Phase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
            }
        }
        DropdownMenu(expanded, onDismiss) {
            DropdownMenuItem(text = { Text(tr("返回主机详情")) }, onClick = onHostDetail)
            DropdownMenuItem(text = { Text(tr(if (session.external) "断开当前会话" else "关闭当前会话")) }, onClick = onClose,
                modifier = Modifier.testTag("close-session"))
            // A tmux pane restarts over the live connection; a plain shell restarts by reconnecting.
            DropdownMenuItem(
                text = { Text(tr("重启当前会话")) },
                onClick = onRestart,
                enabled = session.mode == SessionMode.SHELL || phase == Phase.CONNECTED,
                modifier = Modifier.testTag("restart-session")
            )
            HorizontalDivider()
            sessions.forEach { other ->
                val otherState by other.state.collectAsStateWithLifecycle()
                val otherTitle by other.title.collectAsStateWithLifecycle()
                DropdownMenuItem(text = { Text("${if (other.id == session.id) "✓ " else ""}${other.titleForDisplay(otherTitle)} · ${phaseLabel(otherState.phase)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onSelect(other.id) })
            }
        }
    }
}
