package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr

/**
 * The home page's first section: every session the app holds, sessions that rang for the user first, so getting
 * back to a running agent is one tap. Hidden when there are none.
 */
@Composable
internal fun ActiveSessionsSection(sessions: List<TerminalConnection>, onOpen: (TerminalConnection) -> Unit,
    modifier: Modifier = Modifier) {
    GroupedSection(modifier.testTag("home-sessions"), header = tr("进行中")) {
        sessions.forEachIndexed { index, session ->
            if (index > 0) GroupedDivider(start = Spacing.Page + 36.dp + Spacing.Row)
            key(session.id) { ActiveSessionRow(session) { onOpen(session) } }
        }
    }
}

@Composable
private fun ActiveSessionRow(session: TerminalConnection, onClick: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val attention by session.attention.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = tr("打开终端"), role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(start = Spacing.Page, end = Spacing.Row, top = 10.dp, bottom = 10.dp)
        .testTag("home-session-${session.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        SessionSystemIcon(session, 36.dp)
        Column(Modifier.weight(1f).padding(start = Spacing.Row)) {
            Text(session.titleForDisplay(title), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight), verticalAlignment = Alignment.CenterVertically) {
                StatusLabel(state.phase)
                if (attention) AttentionTag(Modifier.testTag("home-session-attention-${session.id}"))
            }
        }
        Chevron()
    }
}

/** The remote rang the bell or posted a notification since the terminal was last on screen. */
@Composable
internal fun AttentionTag(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Text(tr("有提醒"), style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(colors.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp))
}
