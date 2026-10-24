package io.itermux.desktop

/** USB HID keyboard usages (page 0x07): keys go by physical position, whatever the remote layout. */
object HidUsage {
    const val A = 0x04
    const val C = 0x06
    const val D = 0x07
    const val V = 0x19
    const val X = 0x1B
    const val Z = 0x1D
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val F1 = 0x3A
    const val PRINT_SCREEN = 0x46
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val LEFT_CTRL = 0xE0
    const val LEFT_SHIFT = 0xE1
    const val LEFT_ALT = 0xE2
    const val LEFT_GUI = 0xE3
    const val RIGHT_CTRL = 0xE4
    const val RIGHT_SHIFT = 0xE5
    const val RIGHT_ALT = 0xE6
    const val RIGHT_GUI = 0xE7

    fun letter(c: Char): Int = A + (c.lowercaseChar() - 'a')
    fun f(n: Int): Int = if (n <= 12) F1 + n - 1 else 0x68 + n - 13

    /** Android `KeyEvent.KEYCODE_*` to a HID usage, for hardware keyboards; null for keys a desktop has no use for. */
    fun fromAndroid(keyCode: Int): Int? = when (keyCode) {
        in 29..54 -> A + (keyCode - 29)
        7 -> 0x27
        in 8..16 -> 0x1E + (keyCode - 8)
        66 -> ENTER
        111 -> ESCAPE
        67 -> BACKSPACE
        61 -> TAB
        62 -> SPACE
        69 -> 0x2D
        70 -> 0x2E
        71 -> 0x2F
        72 -> 0x30
        73 -> 0x31
        74 -> 0x33
        75 -> 0x34
        68 -> 0x35
        55 -> 0x36
        56 -> 0x37
        76 -> 0x38
        115 -> 0x39
        in 131..142 -> F1 + (keyCode - 131)
        120 -> PRINT_SCREEN
        116 -> 0x47
        121 -> 0x48
        124 -> INSERT
        122 -> HOME
        92 -> PAGE_UP
        112 -> DELETE
        123 -> END
        93 -> PAGE_DOWN
        22 -> RIGHT
        21 -> LEFT
        20 -> DOWN
        19 -> UP
        143 -> 0x53
        154 -> 0x54
        155 -> 0x55
        156 -> 0x56
        157 -> 0x57
        160 -> 0x58
        in 145..153 -> 0x59 + (keyCode - 145)
        144 -> 0x62
        158 -> 0x63
        82 -> 0x65
        113 -> LEFT_CTRL
        59 -> LEFT_SHIFT
        57 -> LEFT_ALT
        117 -> LEFT_GUI
        114 -> RIGHT_CTRL
        60 -> RIGHT_SHIFT
        58 -> RIGHT_ALT
        118 -> RIGHT_GUI
        else -> null
    }
}

/**
 * The on-screen computer keyboard's modifiers (plan §5.2): a tap latches one for the next key, a long
 * press holds it until tapped again.
 */
class ModifierLatch {
    enum class State { OFF, ONCE, LOCKED }

    private val states = linkedMapOf<Int, State>()
    fun state(hid: Int): State = states[hid] ?: State.OFF

    fun tap(hid: Int) {
        states[hid] = when (state(hid)) { State.OFF -> State.ONCE; else -> State.OFF }
    }

    fun hold(hid: Int) { states[hid] = State.LOCKED }

    /**
     * A key was tapped: the (key, down) events to send. Latched modifiers wrap the key; once-latched ones then release.
     */
    fun press(hid: Int): List<Pair<Int, Boolean>> {
        val mods = states.filterValues { it != State.OFF }.keys.filter { it != hid }
        states.entries.removeAll { it.value == State.ONCE }
        return mods.map { it to true } + listOf(hid to true, hid to false) + mods.reversed().map { it to false }
    }
}
