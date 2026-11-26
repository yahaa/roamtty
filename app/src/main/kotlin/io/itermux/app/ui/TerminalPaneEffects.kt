package io.itermux.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import io.itermux.app.tr
import io.itermux.ssh.Phase
import kotlinx.coroutines.delay

/** The page's timers and the resets that follow the session, its connection, the keyboard and the small window. */
@Composable
internal fun TerminalPaneEffects(pane: TerminalPaneState, phase: Phase, imeVisible: Boolean, pipShown: Boolean, voiceActive: Boolean = false) {
    DockTimers(pane, voiceActive)
    PetRingTimers(pane)
    PetKeyTimers(pane)
    SessionEffects(pane, phase)
    // Opening the arrow menu can hide the IME. Keep Shift armed until the arrow is sent.
    // A keyboard closed some other way drops a pending hide, so reopening it is not undone.
    LaunchedEffect(imeVisible) { if (!imeVisible) { pane.sticky.ctrl = false; pane.cancelKeyboardHide() } }
    LaunchedEffect(pipShown) { if (!pipShown) pane.dropPipFocus() }
    LaunchedEffect(voiceActive) {
        if (!voiceActive) {
            pane.voicePopoverOpen = false
            pane.voiceSuppressesPinnedDock = false
        }
    }
}

@Composable
private fun DockTimers(pane: TerminalPaneState, voiceActive: Boolean) {
    // A dock the pet opened (not pinned) goes back to the pet after a quiet spell; a touch on it starts the spell over,
    // and an open menu holds it.
    // Timed by frames, not an animation: with animations turned off in the system settings one would end at once.
    val dockCountsDown = pane.floatingDock && !pane.menu && !pane.sessionMenu && TerminalToolbox.dockHideMillis > 0
    LaunchedEffect(dockCountsDown, pane.dockTouches) {
        val countdown = pane.dockCountdown
        countdown.floatValue = 1f
        if (!dockCountsDown) return@LaunchedEffect
        val hideAfter = TerminalToolbox.dockHideMillis.toFloat()
        val start = withFrameMillis { it }
        while (countdown.floatValue > 0f) {
            countdown.floatValue = (1f - (withFrameMillis { it } - start) / hideAfter).coerceAtLeast(0f)
        }
        pane.sticky.clear()
        pane.dockExpanded = false
    }
    // The collapsed toolbox fades out after a quiet spell and returns on the next touch.
    // Never auto-hide while a voice call is active.
    val dockAvailable = pane.dockAvailable
    val dockVisible = pane.dockVisible
    LaunchedEffect(pane.touches, dockAvailable, dockVisible, voiceActive) {
        pane.toolboxAwake = true
        val hideAfter = TerminalToolbox.autoHideMillis
        if (dockAvailable && !dockVisible && !voiceActive && hideAfter > 0) {
            delay(hideAfter)
            pane.toolboxAwake = false
        }
    }
}

@Composable
private fun PetRingTimers(pane: TerminalPaneState) {
    val petRing = pane.petRing
    val shortcuts = petRing.shortcuts
    val haptics = LocalHapticFeedback.current
    val petAvailable = pane.petAvailable
    LaunchedEffect(petAvailable) { if (!petAvailable) petRing.close() }
    // An open ring keeps itself and the pet up until a tap, or until it has been left alone for a long spell; then
    // both go together.
    LaunchedEffect(petRing.pinned, pane.touches) {
        val hideAfter = TerminalToolbox.petRingHideMillis
        if (petRing.pinned && hideAfter > 0) {
            delay(hideAfter)
            pane.dismissPetRing()
        }
    }
    // Resting on a menu item unfolds its sub-ring.
    LaunchedEffect(petRing.hovered, shortcuts) {
        val menu = petRing.hovered?.let(shortcuts::getOrNull)?.action as? PetAction.Menu ?: return@LaunchedEffect
        delay(350)
        petRing.hovered = null
        petRing.unfold(menu.items)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
private fun PetKeyTimers(pane: TerminalPaneState) {
    // A double tap (Tab) brings Up/Down keys above and below the pet and Left to its left, to move through
    // the completions or menu it opens; they fade after a quiet spell, and each press restarts it.
    LaunchedEffect(pane.petArrowTouches) {
        if (pane.petArrowTouches == 0) return@LaunchedEffect
        pane.petArrowsAwake = true
        val hideAfter = TerminalToolbox.petArrowsHideMillis
        if (hideAfter > 0) {
            delay(hideAfter)
            pane.petArrowsAwake = false
        }
    }
    // The cursor-row Enter key stays out of the way while typing and appears once input pauses,
    // or at once on a double tap; it fades after a quiet spell.
    LaunchedEffect(pane.enterKeyTouches) {
        if (pane.enterKeyTouches == 0) return@LaunchedEffect
        if (pane.enterKeyTyping) {
            pane.enterKeyAwake = false
            delay(TerminalToolbox.enterKeyTypingPauseMillis)
        }
        pane.enterKeyAwake = true
        val hideAfter = TerminalToolbox.enterKeyHideMillis
        if (hideAfter > 0) {
            delay(hideAfter)
            pane.enterKeyAwake = false
        }
    }
}

@Composable
private fun SessionEffects(pane: TerminalPaneState, phase: Phase) {
    val context = LocalContext.current
    val session = pane.session
    DisposableEffect(session) {
        session.copyToClipboard = { text -> context.copyText(text, tr("终端选区")) }
        session.pasteFromClipboard = { pane.showClipboard() }
        onDispose {
            session.onScreenChanged = null; session.copyToClipboard = null; session.pasteFromClipboard = null
        }
    }
    LaunchedEffect(pane.fontSize) { pane.settings.fontSize = pane.fontSize }
    LaunchedEffect(session.id) { pane.hideKeyboard(); pane.closeTappedFile() }
    LaunchedEffect(phase) {
        if (phase != Phase.CONNECTED) {
            pane.sticky.clear(); pane.confirmPaste = null
            pane.menu = false; pane.sessionMenu = false
        }
        else session.inputWarning.value = null
    }
}
