package io.itermux.desktop

/**
 * Three-finger swipes as the host's own shortcuts: up shows every window, down the desktop (the
 * plugin's actions, which know each system), left and right switch to the desktop on that side.
 */
object DesktopSwipeShortcut {
    fun frames(direction: SwipeDirection, os: String): List<ByteArray> {
        fun combo(keys: List<Int>) = keys.map { DesktopProtocol.key(it, true) } + keys.reversed().map { DesktopProtocol.key(it, false) }
        val modifiers = when (os.lowercase()) {
            "macos" -> listOf(HidUsage.LEFT_CTRL)
            "windows" -> listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_GUI)
            else -> listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_ALT)
        }
        return when (direction) {
            SwipeDirection.UP -> listOf(DesktopProtocol.action(DesktopProtocol.ACTION_ALL_WINDOWS))
            SwipeDirection.DOWN -> listOf(DesktopProtocol.action(DesktopProtocol.ACTION_SHOW_DESKTOP))
            // Fingers going left bring in the desktop on the right, as on a trackpad.
            SwipeDirection.LEFT -> combo(modifiers + HidUsage.RIGHT)
            SwipeDirection.RIGHT -> combo(modifiers + HidUsage.LEFT)
        }
    }
}
