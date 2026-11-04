package io.itermux.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.tr
import io.itermux.ssh.ListeningPort
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The host's listening ports, read over the page's login: each by the command line and folder of the process behind
 * it, not a guess at the service. Whether it serves a web page shows once tapped, which opens it in [PortPreviewActivity].
 * Hidden until the list is read; read again whenever the page comes back, as from a preview.
 */
@Composable
internal fun HostPortsSection(monitor: HostMonitor, modifier: Modifier = Modifier) {
    val ready by monitor.ready.collectAsStateWithLifecycle()
    var ports by remember(monitor) { mutableStateOf<List<ListeningPort>?>(null) }
    var reads by remember(monitor) { mutableIntStateOf(0) }
    val context = LocalContext.current
    // Back from a preview, or from anywhere: a server may have started or stopped meanwhile.
    LifecycleResumeEffect(monitor) { reads++; onPauseOrDispose { } }
    LaunchedEffect(monitor, ready, reads) { if (ready) monitor.listPorts()?.let { ports = it } }
    val list = ports ?: return
    GroupedSection(modifier.testTag("host-ports"), header = tr("端口")) {
        if (list.isEmpty()) GroupedRow(tr("没有在监听的端口"))
        list.forEachIndexed { index, port ->
            if (index > 0) GroupedDivider()
            PortRow(port) { openPortPreview(context, port.url, monitor.host, monitor::openSocket) }
        }
    }
}

@Composable
private fun PortRow(port: ListeningPort, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp)
        .padding(horizontal = Spacing.Page, vertical = 8.dp).testTag("host-port-${port.port}"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(port.port.toString(), style = mono.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.widthIn(min = 56.dp))
        Column(Modifier.weight(1f).padding(horizontal = Spacing.Tight)) {
            if (port.pid == null) Text(tr("其他用户的进程（root / Docker）"), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else {
                Text(port.shortCommand.ifEmpty { "PID ${port.pid}" }, style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (port.directory.isNotEmpty()) Text(port.directory, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Chevron()
    }
}
