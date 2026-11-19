package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.tr
import io.itermux.desktop.DesktopPhase

/**
 * The host's desktop in a small window over the terminal. A tap focuses it: its touches and typing then go to the
 * desktop, with the desktop page's keyboard and shortcut bar over the terminal. [pinnedDockWidth] is the room a
 * pinned dock takes at the end edge.
 */
@Composable
internal fun BoxWithConstraintsScope.TerminalPipOverlay(pane: TerminalPaneState, desktop: DesktopConnection, pip: TerminalPip,
    landscape: Boolean, imeVisible: Boolean, pinnedDockWidth: Dp) {
    val video by desktop.videoSize.collectAsStateWithLifecycle()
    val phase by desktop.phase.collectAsStateWithLifecycle()
    val stats by desktop.stats.collectAsStateWithLifecycle()
    val state = rememberPipState("${pane.session.host.id}.terminal${if (landscape) ".land" else ""}", if (landscape) 0.4f else 0.7f)
    val focused = pane.pipFocused
    with(LocalDensity.current) {
        val w = maxWidth.toPx()
        val h = maxHeight.toPx()
        val edges = WindowInsets.displayCutout.union(WindowInsets.statusBars)
        val margin = 8.dp.toPx()
        // Anywhere it is dragged, clear of the status bar, cutouts, a pinned dock and the gesture bar.
        val end = margin + edges.getRight(this, LayoutDirection.Ltr) + pinnedDockWidth.toPx()
        val bottom = margin + if (imeVisible) 0 else WindowInsets.navigationBars.getBottom(this)
        val room = Rect(margin + edges.getLeft(this, LayoutDirection.Ltr), margin + edges.getTop(this), w - end, h - bottom)
        PipWindow(state, room, w, if (video.second > 0) video.first / video.second.toFloat() else 1.6f,
            tag = "pip-desktop", description = tr("桌面小窗，点按后在小窗里操作桌面"),
            onTap = { if (!focused) pane.focusPip() }, onClose = { pane.unfocusPip(); pip.onClose() },
            focused = focused, label = desktop.host.name, keyboardOpen = pane.pipKeyboard,
            status = when (phase) {
                is DesktopPhase.Connected -> StatusColors.success
                is DesktopPhase.Failed -> StatusColors.danger
                else -> MaterialTheme.colorScheme.primary
            }, detail = stats.rttMs?.takeIf { phase is DesktopPhase.Connected }?.let { "$it ms" }.orEmpty(),
            onKeyboard = { pane.pipKeyboard = !pane.pipKeyboard },
            onFull = { pane.unfocusPip(); pane.hideKeyboard(); pip.onTap() }) {
            DesktopMiniContent(desktop, focused = focused, keyboard = pane.pipKeyboard)
        }
    }
    // Typing on the desktop, as its page's 键盘: the input method and the shortcut bar, over the terminal.
    if (focused && pane.pipKeyboard) {
        val hello by desktop.hello.collectAsStateWithLifecycle()
        KeyboardPanel(desktop, hello?.os ?: desktop.host.os.lowercase(), landscape = false, alwaysBar = true) { pane.pipKeyboard = false }
    }
}
