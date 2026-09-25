package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.TerminalConnection

@Composable
internal fun ItermuxDrawer(
    page: String,
    sessions: List<TerminalConnection>,
    onHosts: () -> Unit,
    onSessions: () -> Unit,
    onOpenSession: (String) -> Unit,
    onGuide: () -> Unit,
    onClose: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.fillMaxHeight().testTag("navigation-drawer")) {
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_terminal), contentDescription = null,
                    tint = Color.Unspecified, modifier = Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("iTermux", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("远程终端", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Text("×", style = MaterialTheme.typography.headlineSmall) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                NavigationDrawerItem(label = { Text("主机") }, selected = page == "hosts", onClick = onHosts,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-hosts"))
                NavigationDrawerItem(label = { Text("会话") }, selected = page == "sessions", onClick = onSessions,
                    badge = { Text("${sessions.size}") },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-sessions"))
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                Text("当前会话", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                if (sessions.isEmpty()) {
                    Text("暂无会话", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                }
                sessions.forEach { session ->
                    val title by session.title.collectAsStateWithLifecycle()
                    val state by session.state.collectAsStateWithLifecycle()
                    NavigationDrawerItem(label = { Column {
                        Text(title.ifBlank { session.host.name }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${session.host.name} · ${phaseLabel(state.phase)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } }, selected = false, onClick = { onOpenSession(session.id) },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-session-${session.id}"))
                }
            }
            HorizontalDivider()
            NavigationDrawerItem(label = { Text("使用指南") }, selected = false, onClick = onGuide,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("drawer-guide"))
        }
    }
}
