package io.itermux.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.itermux.app.session.ScreenApp

/** Ctrl and Shift armed from the dock or the pet ring, applied to the next key and then dropped. */
@Stable
internal class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var shift by mutableStateOf(false)

    fun clear() {
        ctrl = false
        shift = false
    }
}

/** The ring of agent shortcuts that holding the pet opens around it; dragging onto one and letting go sends it. */
@Stable
internal class PetRingState {
    /** Where the ring opens, in workspace coordinates; null while it is closed. */
    var center by mutableStateOf<Offset?>(null)
    var arc by mutableStateOf<PetArc?>(null)
    var hovered by mutableStateOf<Int?>(null)
    /** The program in front, read from the screen when the hold starts: the ring matches it. */
    var app by mutableStateOf(ScreenApp.SHELL)
    /** A sub-ring unfolded from a menu item, shown in place of [app]'s shortcuts. */
    var menu by mutableStateOf<List<PetShortcut>?>(null)
    /** Let go away from every item: the ring stays open for taps until it times out. */
    var pinned by mutableStateOf(false)

    val shortcuts: List<PetShortcut> get() = menu ?: petShortcuts(app)

    /** Replaces the ring with the sub-ring [items]. */
    fun unfold(items: List<PetShortcut>) {
        arc = null
        menu = items
    }

    fun close() {
        center = null
        arc = null
        hovered = null
        menu = null
        pinned = false
    }
}
