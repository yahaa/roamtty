package io.itermux.app.ui

import io.itermux.app.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.session.TerminalConnection
import io.itermux.app.voice.VoiceConnection

@Composable
internal fun ItermuxDrawer(
    page: AppPage,
    hostCount: Int,
    sessions: List<TerminalConnection>,
    voices: Map<String, VoiceConnection> = emptyMap(),
    onHosts: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSettings: () -> Unit,
    onGuide: () -> Unit,
    onClose: () -> Unit
) {
    // Reserve a visible, tappable scrim even on phones narrower than the default 360 dp sheet.
    val drawerWidth = (with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } * 0.82f).coerceAtMost(360.dp)
    // In landscape, where height is short, the header is one line and Settings and Help one row of icons, so the open
    // sessions keep most of the height.
    val compact = isLandscape()
    ModalDrawerSheet(modifier = Modifier.width(drawerWidth).fillMaxHeight().testTag("navigation-drawer")) {
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = if (compact) 4.dp else 24.dp,
                bottom = if (compact) 0.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
                ItermuxLogo(Modifier.size(if (compact) 30.dp else 48.dp))
                Column(Modifier.weight(1f).padding(start = if (compact) 12.dp else 14.dp)) {
                    Text("Roamtty", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    if (!compact) Text(tr("远程终端"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolbarIcon(R.drawable.ic_close, tr("关闭"), tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onClose)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // Hosts is the home page, so a selected pill would sit lit almost every time the drawer opens:
                // keep it a plain row like Settings and carry the count in a small muted chip.
                NavigationDrawerItem(label = { Text(tr("主机")) }, selected = false, onClick = onHosts,
                    icon = { Icon(painterResource(R.drawable.ic_hosts), contentDescription = null) },
                    badge = {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                            Text("$hostCount", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-hosts"))
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = if (compact) 6.dp else 16.dp))
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
                    val name by session.name.collectAsStateWithLifecycle()
                    val state by session.state.collectAsStateWithLifecycle()
                    val activeVoice = voices.values.firstOrNull { it.sessionId == session.id }
                    // Compose keeps the last tapped node (and its click lambda) until the next touch: capture only the id,
                    // or a session closed right after being opened from here keeps its scrollback alive.
                    val id = session.id
                    NavigationDrawerItem(icon = { SessionIcon(session, 32.dp) },
                        label = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(session.titleForDisplay(title, name), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text("${session.host.username}@${session.host.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } }, selected = false, onClick = { onOpenSession(id) },
                        badge = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (activeVoice != null) {
                                    val voiceUi by activeVoice.ui.collectAsStateWithLifecycle()
                                    SessionVoiceMiniIcon(voiceUi, "drawer-session-voice-${session.id}")
                                }
                                StatusLabel(state.phase)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer-session-${session.id}"))
                }
            }
            HorizontalDivider()
            if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                DrawerIconItem(tr("设置"), R.drawable.ic_settings, Modifier.weight(1f).testTag("drawer-settings"), onSettings)
                DrawerIconItem(tr("帮助"), R.drawable.ic_help, Modifier.weight(1f).testTag("drawer-guide"), onGuide)
            } else {
                NavigationDrawerItem(label = { Text(tr("设置")) }, selected = page == AppPage.SETTINGS, onClick = onSettings,
                    icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).testTag("drawer-settings"))
                NavigationDrawerItem(label = { Text(tr("帮助")) }, selected = false, onClick = onGuide,
                    icon = { Icon(painterResource(R.drawable.ic_help), contentDescription = null) },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).testTag("drawer-guide"))
            }
        }
    }
}

/** A footer item in landscape: the icon over a short label, sharing the row with the others. */
@Composable
private fun DrawerIconItem(title: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
