package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.*
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.desktop.*

/** The desktop button at the end of a session card's bottom row (plan §4.3): an icon tag beside its other tags. */
@Composable
internal fun DesktopCardButton(state: DesktopButton, tag: String, onClick: () -> Unit) {
    if (state == DesktopButton.HIDDEN) return
    // Always the desktop's icon, never a spinner: lit while a desktop is open or on its way.
    val live = state == DesktopButton.CONNECTED || state == DesktopButton.POOR || state == DesktopButton.RECOVERING ||
        state == DesktopButton.CONNECTING
    val badge = when (state) {
        DesktopButton.CONNECTED -> StatusColors.success
        DesktopButton.POOR, DesktopButton.NEEDS_ACTION, DesktopButton.RECOVERING -> StatusColors.warning
        else -> null
    }
    if (live) IconTag(R.drawable.ic_desktop, tr("打开桌面"), tag, onClick, tint = DesktopAccent, wash = DesktopAccent.copy(alpha = 0.16f),
        badge = badge, badgeTag = "$tag-badge-${state.name.lowercase()}")
    else IconTag(R.drawable.ic_desktop, tr("打开桌面"), tag, onClick, badge = badge, badgeTag = "$tag-badge-${state.name.lowercase()}")
}
