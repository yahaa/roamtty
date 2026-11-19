package io.itermux.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TextStyle
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase

/**
 * The terminal page: the session's screen with its cursor-row keys and notices, the dock (pinned beside the
 * screen, floating over it, or put away behind the pet), the draft editor, the host's desktop in a small window,
 * and the page's dialogs. [TerminalPaneState] holds what they share.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalPane(
    session: TerminalConnection,
    modifier: Modifier,
    sessions: List<TerminalConnection>,
    lightTheme: Boolean,
    onSelectSession: (String) -> Unit,
    onHostDetail: () -> Unit,
    onReconnect: (() -> Unit)?,
    onRestart: () -> Unit,
    onShell: () -> Unit,
    onReverify: () -> Unit,
    onClose: () -> Unit,
    /** Shows the host's desktop small over the terminal, or closes it; null when it has none to open. */
    onDesktop: (() -> Unit)? = null,
    /** Leaves a connection notice without closing the local or remote session. */
    onLeave: () -> Unit = onHostDetail,
    /** The host's desktop in a small window over the terminal, and the dock button's long press. */
    pip: TerminalPip = TerminalPip(),
) {
    val state by session.state.collectAsStateWithLifecycle()
    val connected = state.phase == Phase.CONNECTED
    val pane = rememberTerminalPaneState(session, connected)
    val outputRevision = session.revision.collectAsStateWithLifecycle()
    // The renderer's fill colour, re-read on each output but read only while drawing, so the workspace (and the
    // screen around it) is redrawn when the colour changes (OSC 11, reverse video, theme), not on every output frame.
    val background = remember(session, outputRevision, lightTheme) {
        derivedStateOf { checkNotNull(outputRevision.value); terminalBackground(session) }
    }
    val landscape = isLandscape()
    val imeVisible = WindowInsets.isImeVisible
    // A line starting with `/` brings the pet's arrows for its command menu, and keeps them up for as long as it does.
    val slashCommandLine by session.slashCommandLine.collectAsStateWithLifecycle()
    val petArrowsShown = (pane.petArrowsAwake || slashCommandLine != 0) && pane.petRing.center == null

    TerminalPaneEffects(pane, state.phase, imeVisible, pipShown = pip.desktop != null)
    BackHandler(pane.editor || imeVisible) { pane.closeEditor() }
    // Back (once the keyboard is down) gives the terminal its input back from the desktop's small window.
    BackHandler(pane.pipFocused && !imeVisible) { pane.unfocusPip() }

    val dock: @Composable (Dp) -> Unit = { availableHeight ->
        TerminalPaneDock(pane, availableHeight, landscape, imeVisible, sessions, state.phase, pip,
            DockActions(onSelectSession, onHostDetail, onClose, onDesktop))
    }
    // The workspace extends under the transparent status bar in the terminal's own background
    // colour (including remote OSC 11 changes). In portrait the text runs under the bar too, faded
    // by a scrim so the clock and icons stay legible; controls and notices stay below the bar.
    // The keyboard inset jumps to its final height instead of following the slide animation:
    // every intermediate height would reflow the emulator and send the remote a resize, which
    // made opening and closing the keyboard stutter. The background still fills the area the
    // keyboard slides over.
    BoxWithConstraints(modifier.fillMaxWidth().testTag("terminal-workspace")
        .drawBehind { drawRect(background.value) }
        .windowInsetsPadding(WindowInsets.imeAnimationTarget)
        .onGloballyPositioned { pane.workspaceOrigin = it.positionInRoot() }.workspaceTouches(pane)) {
        val safeDockHeight = with(LocalDensity.current) {
            val edges = WindowInsets.displayCutout.union(WindowInsets.statusBars)
            maxHeight - edges.getTop(this).toDp() - edges.getBottom(this).toDp() - 8.dp
        }
        val pinnedDockWidth = if (terminalDockTwoColumns(landscape, imeVisible, safeDockHeight)) 96.dp else 48.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TerminalViewport(pane, Modifier.weight(1f).fillMaxWidth(), lightTheme, background, outputRevision,
                    landscape, imeVisible, petArrowsShown) {
                    TerminalNotices(session, state, onReconnect, onShell, onReverify, onLeave)
                }
                if (pane.editor) DraftEditor(pane.draft, pane::updateDraft, landscape, imeVisible, connected = connected,
                    onCollapse = pane::closeEditor, onEnter = { session.write("\r") }, onSend = { pane.paste(pane.draft.text) })
            }
            if (pane.dockReserved) TerminalDockPlacement(
                Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)).width(pinnedDockWidth),
                endInset = 0.dp, content = dock)
        }
        pip.desktop?.let { desktop ->
            TerminalPipOverlay(pane, desktop, pip, landscape, imeVisible, if (pane.dockReserved) pinnedDockWidth else 0.dp)
        }
        TerminalPetRing(pane)
        if (pane.floatingDock) FloatingDock(pane, landscape, imeVisible, dock)
        else if (pane.petAvailable) TerminalPet(pane, petArrowsShown)
    }
    TerminalPaneDialogs(pane, onRestart)
}

/**
 * Observes the first press without consuming it: the same tap still reaches the terminal, including its keyboard,
 * selection and scrolling gestures. A press outside a floating dock puts it away; pinned controls stay visible.
 */
private fun Modifier.workspaceTouches(pane: TerminalPaneState): Modifier {
    val dockAvailable = pane.dockAvailable
    val floatingDock = pane.floatingDock
    return pointerInput(dockAvailable, floatingDock) {
        if (dockAvailable) awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val presses = event.changes.filter { it.pressed && !it.previousPressed }
                if (presses.isEmpty()) continue
                pane.touches++
                if (floatingDock && presses.any { !pane.dockBounds.contains(it.position + pane.workspaceOrigin) }) {
                    pane.dismissDockMenus()
                }
            }
        }
    }
}

/** The dock the pet opened, over the terminal; a touch on it starts its quiet spell over. */
@Composable
private fun FloatingDock(pane: TerminalPaneState, landscape: Boolean, imeVisible: Boolean, dock: @Composable (Dp) -> Unit) {
    TerminalDockPlacement { height ->
        Surface(modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); pane.dockTouches++ } }
        }, shape = terminalDockShape(terminalDockTwoColumns(landscape, imeVisible, height)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f), shadowElevation = 4.dp
        ) { dock(height) }
    }
}

/** Idle timeout of the collapsed toolbox button; 0 keeps it visible (instrumentation tests). */
internal object TerminalToolbox {
    @Volatile var autoHideMillis = 5_000L
    /** Idle timeout of a shortcut ring left open after the pet is let go; the pet leaves with it. 0 keeps it. */
    @Volatile var petRingHideMillis = 4_000L
    /** Idle timeout of the cursor-row Enter key; 0 keeps it until the pane leaves. */
    @Volatile var enterKeyHideMillis = 3_000L
    /** How long typing must pause before the cursor-row Enter key appears. */
    @Volatile var enterKeyTypingPauseMillis = 700L
    /** Idle timeout of the Up/Down keys a double tap brings above and below the pet; 0 keeps them. */
    @Volatile var petArrowsHideMillis = 4_000L
    /** Idle timeout of the dock the pet opens (a pinned one stays); 0 keeps it. */
    @Volatile var dockHideMillis = 5_000L
}

internal const val KeyboardHideAfterEnterMillis = 15_000L

/** The colour the terminal renderer fills its canvas with, so the status bar area matches it. */
private fun terminalBackground(session: TerminalConnection): Color {
    val emulator = session.getEmulator()
    return Color(emulator.mColors.mCurrentColors[if (emulator.isReverseVideo) TextStyle.COLOR_INDEX_FOREGROUND
        else TextStyle.COLOR_INDEX_BACKGROUND])
}

/**
 * The terminal page's small window: the host's [desktop] (null for none), what a tap on it does ([onTap]: the
 * desktop page takes the screen), what closes it, and [onFull], the long press on the dock's desktop button that
 * opens the desktop page (null when the host has no desktop to show).
 */
internal data class TerminalPip(
    val desktop: io.itermux.app.desktop.DesktopConnection? = null,
    val onTap: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onFull: (() -> Unit)? = null,
)
