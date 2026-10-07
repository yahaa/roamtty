package io.itermux.app.ui

import io.itermux.app.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
    hostCount: Int,
    sessions: List<TerminalConnection>,
    onHosts: () -> Unit,
    onSessions: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSettings: () -> Unit,
    onGuide: () -> Unit,
    onClose: () -> Unit
) {
    // Reserve a visible, tappable scrim even on phones narrower than the default 360 dp sheet.
    val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.82f).coerceAtMost(360.dp)
    ModalDrawerSheet(modifier = Modifier.width(drawerWidth).fillMaxHeight().testTag("navigation-drawer")) {
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ItermuxLogo(Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Roamtty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(tr("远程终端"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolbarIcon(R.drawable.ic_close, tr("关闭"), tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onClose)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                NavigationDrawerItem(label = { Text(tr("主机")) }, selected = page == "hosts", onClick = onHosts,
                    icon = { Icon(painterResource(R.drawable.ic_hosts), contentDescription = null) },
                    badge = { Text("$hostCount", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-hosts"))
                NavigationDrawerItem(label = { Text(tr("会话")) }, selected = page == "sessions", onClick = onSessions,
                    icon = { Icon(painterResource(R.drawable.ic_sessions), contentDescription = null) },
                    badge = { Text("${sessions.size}", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-sessions"))
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                Text(tr("当前会话"), style = MaterialTheme.typography.sectionHeader,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                if (sessions.isEmpty()) {
                    Text(tr("暂无会话"), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                }
                sessions.forEach { session ->
                    val title by session.title.collectAsStateWithLifecycle()
                    val state by session.state.collectAsStateWithLifecycle()
                    NavigationDrawerItem(icon = { SessionSystemIcon(session, 32.dp) },
                        label = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(session.titleForDisplay(title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text("${session.host.username}@${session.host.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } }, selected = false, onClick = { onOpenSession(session.id) },
                        badge = { StatusLabel(state.phase) },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-session-${session.id}"))
                }
            }
            HorizontalDivider()
            NavigationDrawerItem(label = { Text(tr("设置")) }, selected = page == "settings", onClick = onSettings,
                icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).testTag("drawer-settings"))
            NavigationDrawerItem(label = { Text(tr("帮助")) }, selected = false, onClick = onGuide,
                icon = { Icon(painterResource(R.drawable.ic_help), contentDescription = null) },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).testTag("drawer-guide"))
        }
    }
}
