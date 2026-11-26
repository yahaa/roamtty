package io.itermux.app.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr

/** The ring of shortcuts that holding the pet opens around it; left open, its items take taps. */
@Composable
internal fun TerminalPetRing(pane: TerminalPaneState) {
    val petRing = pane.petRing
    val center = petRing.center
    if (center == null || !pane.petAvailable) return
    val shortcuts = petRing.shortcuts
    val topInset = WindowInsets.displayCutout.union(WindowInsets.statusBars).getTop(LocalDensity.current).toFloat()
    PetShortcutArc(shortcuts, center, topInset, petRing.hovered, armed = { action ->
        action == PetAction.Ctrl && pane.sticky.ctrl || action == PetAction.Shift && pane.sticky.shift
    }, onTap = if (petRing.pinned) { index ->
        val picked = index?.let(shortcuts::getOrNull)
        val menu = picked?.action as? PetAction.Menu
        if (menu != null) {
            petRing.unfold(menu.items)
        } else {
            pane.dismissPetRing()
            if (picked != null && pane.connected) pane.sendPetShortcut(picked)
        }
    } else null) { petRing.arc = it }
}

/**
 * The pet that stands in for a dock put away: a tap opens the dock (or toggles the voice call
 * control popover while a voice call is active), a hold ALWAYS opens its shortcut ring. Up above
 * the pet, Down below it and Left to its left ([arrowsShown]); the pet stays put between them.
 * While a voice call is active, the pet swaps its mini keyboard for a telephone handset and never
 * auto-hides.
 */
@Composable
internal fun TerminalPet(pane: TerminalPaneState, arrowsShown: Boolean, voice: TerminalVoice = TerminalVoice()) {
    val petRing = pane.petRing
    val haptics = LocalHapticFeedback.current
    val inCall = voice.ui.inCall
    val petVoiceState = PetVoiceState.from(voice.ui)
    val showPopover = inCall && pane.voicePopoverOpen && petRing.center == null
    // Leave the composition while hidden so taps on that edge reach the terminal.
    // Never auto-hide while a voice call is active.
    AnimatedVisibility(pane.toolboxAwake || petRing.center != null || arrowsShown || inCall,
        enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 2 },
        exit = fadeOut(tween(400)) + slideOutHorizontally(tween(400)) { it / 3 }) {
        // Held in place while the pet is held, so its ring stays around it as the keyboard comes or goes.
        TerminalDockPlacement(held = petRing.center != null) {
            Column(Modifier.onGloballyPositioned { pane.petArrowBounds = it.boundsInRoot() },
                horizontalAlignment = Alignment.CenterHorizontally) {
                PetArrowKey(arrowsShown && !showPopover, R.drawable.ic_expand_up, tr("发送上方向键"), "pet-arrow-up") {
                    pane.petArrow(KeyEvent.KEYCODE_DPAD_UP)
                }
                Box(contentAlignment = Alignment.CenterStart) {
                    ToolboxPet(
                        description = if (inCall) tr("通话控制") else tr("显示操作栏"),
                        voiceState = petVoiceState,
                        onHoldStart = { center ->
                            pane.voicePopoverOpen = false
                            pane.openPetRing(center)
                        },
                        onHoldMove = { drag -> pane.dragPetRing(drag, haptics) },
                        onHoldEnd = { released -> pane.releasePetRing(released) },
                    ) {
                        if (inCall) {
                            pane.voicePopoverOpen = !pane.voicePopoverOpen
                        } else {
                            pane.dockExpanded = true
                        }
                    }
                    // Takes no width, so the column stays centred on the pet; arrows and call popover hang off its left side.
                    Box(Modifier.layout { measurable, constraints ->
                        val key = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(0, 0) { key.placeRelative(-key.width, -key.height / 2) }
                    }) {
                        PetArrowKey(arrowsShown && !showPopover, R.drawable.ic_expand_left, tr("发送左方向键"), "pet-arrow-left") {
                            pane.petArrow(KeyEvent.KEYCODE_DPAD_LEFT)
                        }
                    }
                    Box(Modifier.layout { measurable, constraints ->
                        val pop = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val gap = 8.dp.roundToPx()
                        layout(0, 0) { pop.placeRelative(-pop.width - gap, -pop.height / 2) }
                    }) {
                        VoiceCallPopover(
                            visible = showPopover,
                            ui = voice.ui,
                            onToggleMute = voice.onToggleMute,
                            onCycleRoute = voice.onCycleRoute,
                            onOpenDock = {
                                pane.voicePopoverOpen = false
                                pane.dockExpanded = true
                            },
                            onHangup = {
                                pane.voicePopoverOpen = false
                                pane.voiceSuppressesPinnedDock = false
                                voice.onHangup()
                            },
                        )
                    }
                }
                if (inCall && !showPopover && !arrowsShown) {
                    Spacer(Modifier.height(2.dp))
                    VoicePetStatusTag(voice.ui)
                }
                PetArrowKey(arrowsShown && !showPopover, R.drawable.ic_expand_down, tr("发送下方向键"), "pet-arrow-down") {
                    pane.petArrow(KeyEvent.KEYCODE_DPAD_DOWN)
                }
            }
        }
    }
}

/** A hold on the pet opens the ring for the program in front, around [center] (root coordinates). */
private fun TerminalPaneState.openPetRing(center: Offset) {
    petRing.hovered = null
    petRing.menu = null
    petRing.pinned = false
    petRing.app = session.screenApp()
    petRing.center = center - workspaceOrigin
}

/** The held finger moved by [drag] from the pet: the item under it is hovered. */
private fun TerminalPaneState.dragPetRing(drag: Offset, haptics: HapticFeedback) {
    val arc = petRing.arc
    // Back over the pet leaves a sub-ring.
    if (petRing.menu != null && arc != null && drag.getDistance() < arc.radius * 0.25f) {
        petRing.menu = null
        petRing.arc = null
    }
    val hovered = petRing.arc?.hoveredItem(drag)
    if (hovered != petRing.hovered) {
        petRing.hovered = hovered
        if (hovered != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

/** The hold ended: [released] on an item sends it; let go elsewhere keeps the ring up for a tap. */
private fun TerminalPaneState.releasePetRing(released: Boolean) {
    val picked = petRing.hovered?.let(petRing.shortcuts::getOrNull)
    val menu = picked?.action as? PetAction.Menu
    touches++
    if (released && (picked == null || menu != null)) {
        // Let go between items: keep the ring up for a tap. A menu not yet unfolded opens now.
        petRing.hovered = null
        if (menu != null) petRing.unfold(menu.items)
        petRing.pinned = true
    } else {
        petRing.close()
        if (released && picked != null && connected) sendPetShortcut(picked)
    }
}
