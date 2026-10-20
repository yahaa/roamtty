package io.itermux.app.ui

import android.content.Context
import androidx.core.content.edit

/** The terminal's saved settings: font size and whether the dock is pinned. [io.itermux.app.TerminalFonts] shares the file. */
internal class TerminalSettings(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var fontSize: Int
        get() = preferences.getInt(FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = preferences.edit { putInt(FONT_SIZE, value) }

    var dockPinned: Boolean
        get() = preferences.getBoolean(DOCK_PINNED, false)
        set(value) = preferences.edit { putBoolean(DOCK_PINNED, value) }

    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 28
        private const val DEFAULT_FONT_SIZE = 13
        private const val FILE = "terminal-settings"
        private const val FONT_SIZE = "fontSize"
        private const val DOCK_PINNED = "dockPinned"
    }
}
