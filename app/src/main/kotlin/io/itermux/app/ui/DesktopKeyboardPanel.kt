package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.tr
import io.itermux.desktop.*
import kotlinx.coroutines.delay

/** A key of the shortcut bar or the 更多 page: what it shows and the HID usage it sends. */
private data class PadKey(val label: String, val hid: Int)

/** A computer's modifier names: macOS shows ⌃ ⌥ ⌘, Windows Win, Linux Super. */
private fun modifierKeys(os: String): List<PadKey> = when (os.lowercase()) {
    "macos" -> listOf(PadKey("⌃", HidUsage.LEFT_CTRL), PadKey("⌥", HidUsage.LEFT_ALT), PadKey("⌘", HidUsage.LEFT_GUI))
    "windows" -> listOf(PadKey("Ctrl", HidUsage.LEFT_CTRL), PadKey("Alt", HidUsage.LEFT_ALT), PadKey("Win", HidUsage.LEFT_GUI))
    else -> listOf(PadKey("Ctrl", HidUsage.LEFT_CTRL), PadKey("Alt", HidUsage.LEFT_ALT), PadKey("Super", HidUsage.LEFT_GUI))
}

/** The 更多 page's shortcuts for each system: label and keys pressed together. */
private fun comboKeys(os: String): List<Pair<String, List<Int>>> = when (os.lowercase()) {
    "macos" -> listOf("⌘Tab" to listOf(HidUsage.LEFT_GUI, HidUsage.TAB), "⌘Space" to listOf(HidUsage.LEFT_GUI, HidUsage.SPACE),
        "⌘Q" to listOf(HidUsage.LEFT_GUI, HidUsage.letter('q')))
    "windows" -> listOf("Alt+Tab" to listOf(HidUsage.LEFT_ALT, HidUsage.TAB),
        "Ctrl+Shift+Esc" to listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_SHIFT, HidUsage.ESCAPE), "Win+E" to listOf(HidUsage.LEFT_GUI, HidUsage.letter('e')))
    else -> listOf("Alt+Tab" to listOf(HidUsage.LEFT_ALT, HidUsage.TAB),
        "Ctrl+Alt+T" to listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_ALT, HidUsage.letter('t')), "Alt+F4" to listOf(HidUsage.LEFT_ALT, HidUsage.f(4)))
}

/** A typed character as the key that makes it on a US layout, for shortcuts like Ctrl+C; null if none. */
private fun charKey(c: Char): Int? = when (c) {
    in 'a'..'z', in 'A'..'Z' -> HidUsage.letter(c)
    in '1'..'9' -> 0x1E + (c - '1')
    '0' -> 0x27
    ' ' -> HidUsage.SPACE
    else -> null
}

/**
 * The keyboard, as in the terminal: 键盘 brings up the phone's own input method straight away, and
 * whatever it commits is typed on the host (any language), Backspace and Enter as keys. A shortcut bar
 * (Esc, Tab, modifiers, arrows) is hidden until asked for; a modifier tapped there latches for the next
 * key or typed letter, a long press keeps it on. ⋯ swaps the input method for F-keys and navigation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.KeyboardPanel(connection: DesktopConnection, os: String, landscape: Boolean,
    /** The shortcut bar always shows (the small window over the terminal): its ▾ closes the keyboard instead. */
    alwaysBar: Boolean = false, onDismiss: () -> Unit) {
    val state = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var bar by rememberSaveable { mutableStateOf(alwaysBar) }
    var more by rememberSaveable { mutableStateOf(false) }
    val latch = remember { ModifierLatch() }
    var latchRevision by remember { mutableIntStateOf(0) }
    val mods = remember(os) { modifierKeys(os) }
    fun send(events: List<Pair<Int, Boolean>>) { events.forEach { (k, d) -> connection.input(DesktopProtocol.key(k, d)) }; latchRevision++ }
    fun key(hid: Int) = send(latch.press(hid))
    fun latched() = mods.any { latch.state(it.hid) != ModifierLatch.State.OFF } || latch.state(HidUsage.LEFT_SHIFT) != ModifierLatch.State.OFF
    LaunchedEffect(more) {
        if (more) keyboard?.hide() else { runCatching { focus.requestFocus() }; keyboard?.show() }
    }
    // The input method's own hide button closes the keyboard too (not when ⋯ hid it for the 更多 page).
    val imeVisible = WindowInsets.isImeVisible
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, more) {
        if (imeVisible) imeShown = true
        else if (imeShown && !more) { delay(150); onDismiss() }
    }
    // Committed text goes out and the field is cleared in the same main-thread step as the IME's edits,
    // so the next key always lands in an empty field (a cleared TextFieldValue only took effect at the
    // next recomposition, and fast input in between was doubled or lost).
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() to state.composition }.collect {
            if (state.composition != null || state.text.isEmpty()) return@collect
            val text = state.text.toString()
            state.clearText()
            // With a modifier latched, a typed letter is a shortcut (Ctrl + c), not text.
            val k = text.singleOrNull()?.let(::charKey)
            if (k != null && latched()) key(k)
            // The input method's Enter commits a newline into the multi-line field: it is the Enter key.
            else text.split('\n').forEachIndexed { i, line ->
                if (i > 0) key(HidUsage.ENTER)
                if (line.isNotEmpty()) connection.input(DesktopProtocol.text(line))
            }
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(if (landscape) Modifier.padding(end = 72.dp) else Modifier)
        .imePadding().testTag("desktop-keyboard")) {
        // The text being composed (pinyin) shows here until the input method commits it.
        if (state.composition != null) Text(state.text.toString(), Modifier.padding(start = 12.dp, bottom = 6.dp).clip(CircleShape)
            .background(DesktopPanelColor).border(1.dp, DesktopAccent.copy(alpha = 0.5f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("desktop-composing"), color = DesktopAccent, fontSize = 14.sp)
        if (!bar && !more) Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            Row(Modifier.clip(CircleShape).background(DesktopPanelColor).clickable { bar = true }.padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("desktop-kb-toggle"), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.size(16.dp), tint = DesktopInk)
                Spacer(Modifier.width(6.dp))
                Text(tr("快捷键") + " ▴", color = DesktopInk, fontSize = 12.sp)
            }
        } else key(latchRevision) {
            Row(Modifier.fillMaxWidth().background(DesktopPanelColor.copy(alpha = 1f)).padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    BarKey("Esc", "desktop-sk-esc") { key(HidUsage.ESCAPE) }
                    BarKey("Tab", "desktop-sk-tab") { key(HidUsage.TAB) }
                    mods.forEach { m ->
                        BarKey(m.label, "desktop-sk-mod-${m.hid}", latch.state(m.hid), onLongPress = { latch.hold(m.hid); latchRevision++ }) {
                            latch.tap(m.hid); latchRevision++
                        }
                    }
                    listOf("←" to HidUsage.LEFT, "↑" to HidUsage.UP, "↓" to HidUsage.DOWN, "→" to HidUsage.RIGHT).forEach { (label, hid) ->
                        BarKey(label, "desktop-sk-$label", narrow = true) { key(hid) }
                    }
                }
                BarKey(if (more) "ABC" else "⋯", "desktop-kb-more", if (more) ModifierLatch.State.LOCKED else ModifierLatch.State.OFF) { more = !more }
                if (!more) BarKey("▾", "desktop-kb-hide-bar", narrow = true) { if (alwaysBar) onDismiss() else bar = false }
            }
        }
        if (more) key(latchRevision) { MoreKeys(os, latch, onKey = ::key, onShift = { latch.tap(HidUsage.LEFT_SHIFT); latchRevision++ },
            onCombo = { keys -> send(keys.map { it to true } + keys.reversed().map { it to false }) }) }
        // Off screen in all but name: the input method needs a focused field to type into.
        BasicTextField(state, modifier = Modifier.fillMaxWidth().height(1.dp).alpha(0f).focusRequester(focus)
            .onPreviewKeyEvent { e ->
                // Back reaches the field only once the IME is hidden: it closes the panel (the field would
                // otherwise take it to drop its focus, and a second Back was needed).
                if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onDismiss(); return@onPreviewKeyEvent true }
                // On an empty field the IME sends Backspace and Enter as key events.
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Backspace -> if (state.text.isEmpty()) { key(HidUsage.BACKSPACE); true } else false
                    Key.Enter, Key.NumPadEnter -> { key(HidUsage.ENTER); true }
                    else -> false
                }
            }.testTag("desktop-ime-field"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
            // Multi-line with no action, as in the terminal: the input method shows a plain ↵ that always works
            // (a Send action on an empty single-line field is greyed out by some input methods and does nothing).
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default, autoCorrectEnabled = false))
    }
}

/** A key of the shortcut bar; a modifier shows whether it is latched (outlined) or held (filled). */
@Composable
private fun BarKey(label: String, tag: String, latched: ModifierLatch.State = ModifierLatch.State.OFF, narrow: Boolean = false,
    onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.height(36.dp).widthIn(min = if (narrow) 32.dp else 42.dp).clip(shape)
        .background(when (latched) { ModifierLatch.State.LOCKED -> DesktopAccent; ModifierLatch.State.ONCE -> DesktopAccent.copy(alpha = 0.25f); else -> Color(0x1AFFFFFF) })
        .border(1.dp, if (latched == ModifierLatch.State.ONCE) DesktopAccent else Color.Transparent, shape)
        .pointerInput(onLongPress) { detectTapGestures(onLongPress = onLongPress?.let { { _ -> it() } }) { onClick() } }
        .padding(horizontal = 10.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = when (latched) { ModifierLatch.State.LOCKED -> Color(0xFF1C1C1E); ModifierLatch.State.ONCE -> DesktopAccent; else -> DesktopInk },
            fontSize = 13.sp, fontWeight = if (latched == ModifierLatch.State.LOCKED) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** The 更多 page in place of the input method: F1–F12, navigation, Shift, arrows and a few shortcuts. */
@Composable
private fun MoreKeys(os: String, latch: ModifierLatch, onKey: (Int) -> Unit, onShift: () -> Unit, onCombo: (List<Int>) -> Unit) {
    val keys = (1..12).map { PadKey("F$it", HidUsage.f(it)) } + listOf(PadKey("Home", HidUsage.HOME), PadKey("End", HidUsage.END),
        PadKey("PgUp", HidUsage.PAGE_UP), PadKey("PgDn", HidUsage.PAGE_DOWN), PadKey("Del", HidUsage.DELETE), PadKey("Ins", HidUsage.INSERT),
        PadKey("Shift", HidUsage.LEFT_SHIFT), PadKey("PrtSc", HidUsage.PRINT_SCREEN), PadKey("←", HidUsage.LEFT), PadKey("↑", HidUsage.UP),
        PadKey("↓", HidUsage.DOWN), PadKey("→", HidUsage.RIGHT))
    Column(Modifier.fillMaxWidth().background(DesktopPanelColor.copy(alpha = 1f)).padding(horizontal = 8.dp).padding(bottom = 10.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    val shift = k.hid == HidUsage.LEFT_SHIFT
                    val s = if (shift) latch.state(k.hid) else ModifierLatch.State.OFF
                    Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(8.dp))
                        .background(when (s) { ModifierLatch.State.OFF -> Color(0x1FFFFFFF); else -> DesktopAccent.copy(alpha = 0.4f) })
                        .clickable { if (shift) onShift() else onKey(k.hid) }.testTag("desktop-key-${k.hid}"), contentAlignment = Alignment.Center) {
                        Text(k.label, color = DesktopInk, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            comboKeys(os).forEach { (label, combo) ->
                Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x29FFFFFF))
                    .clickable { onCombo(combo) }.testTag("desktop-combo-$label"), contentAlignment = Alignment.Center) {
                    Text(label, color = DesktopInk, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}
