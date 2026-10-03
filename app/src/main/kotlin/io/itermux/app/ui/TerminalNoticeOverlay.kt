package io.itermux.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import kotlin.math.roundToInt

/** In-window dialog placement preserves terminal/IME focus and never changes the PTY grid.
 * The parent already consumes IME insets; measure again on every keyboard/layout frame.
 */
@Composable
internal fun TerminalNoticeOverlay(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize().displayCutoutPadding()
        .testTag("terminal-notice-viewport")) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 16.dp.roundToPx().coerceAtMost(minOf(width, height) / 2)
        val child = measurables.single().measure(Constraints(
            maxWidth = (width - 2 * margin).coerceAtMost(400.dp.roundToPx()),
            maxHeight = height - 2 * margin,
        ))
        val top = (height * 0.382f - child.height / 2f).roundToInt()
            .coerceIn(margin, (height - margin - child.height).coerceAtLeast(margin))
        layout(width, height) { child.placeRelative((width - child.width) / 2, top) }
    }
}

@Composable
internal fun TerminalConnectionNotice(
    state: ConnectionState,
    onReconnect: () -> Unit,
    onShell: () -> Unit,
    onReverify: () -> Unit,
) {
    TerminalNoticeCard(phaseLabel(state.phase), "terminal-connection-notice", actions = {
        if (state.phase == Phase.HOST_KEY_CHANGED) {
            Button(onClick = onReverify) { Text(tr("重新核验…")) }
        } else {
            if (state.phase == Phase.TMUX_MISSING) TextButton(onClick = onShell) { Text(tr("改用普通 SSH")) }
            Button(onClick = onReconnect) { Text(tr("手动重连")) }
        }
    }) {
        if (state.message.isNotBlank()) Text(tr(state.message), style = MaterialTheme.typography.bodyMedium)
        if (state.phase == Phase.HOST_KEY_CHANGED) {
            SelectionContainer {
                Text(trf("原指纹：%s\n新指纹：%s", state.oldFingerprint.orEmpty(), state.newFingerprint.orEmpty()),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
internal fun TerminalInputNotice(message: String, onDismiss: () -> Unit) {
    TerminalNoticeCard(tr("提示"), "terminal-input-notice", actions = {
        TextButton(onClick = onDismiss) { Text(tr("知道了")) }
    }) { Text(tr(message), style = MaterialTheme.typography.bodyMedium) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalNoticeCard(
    title: String,
    tag: String,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().testTag(tag).semantics { paneTitle = title },
        shape = MaterialTheme.shapes.large, color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)), shadowElevation = 6.dp) {
        BoxWithConstraints {
            // Landscape IMEs can leave less than 160dp. Put actions beside the text there,
            // so a footer cannot consume the entire text viewport or clip the heading.
            if (maxHeight < 240.dp) {
                Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        content()
                    }
                    Column(Modifier.widthIn(max = 160.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = androidx.compose.ui.Alignment.End, content = { actions() })
                }
            } else Column(Modifier.padding(20.dp)) {
                // Long errors/fingerprints scroll while actions remain above the keyboard.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    content()
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp), content = { actions() })
            }
        }
    }
}
