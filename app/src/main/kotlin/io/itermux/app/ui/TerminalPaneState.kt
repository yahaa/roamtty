package io.itermux.app.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.SoftwareKeyboardControllerCompat
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import io.itermux.app.session.MAX_PASTE_SIZE
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The pane state that survives a configuration change. */
internal class TerminalPaneSaved(
    val fontSize: MutableIntState,
    val editor: MutableState<Boolean>,
    val commonCommandsOpen: MutableState<Boolean>,
    val dockPinned: MutableState<Boolean>,
    val dockExpanded: MutableState<Boolean>,
)

/**
 * What the terminal page remembers while it is up, and what its controls do: the dock, the draft editor, the
 * armed modifiers, the pet and its keys, the keyboard and the desktop's small window. The parts of the page
 * (dock, viewport, pet, small window, dialogs) read and change it; none of them holds state of its own.
 */
@Stable
internal class TerminalPaneState(
    session: TerminalConnection,
    private val context: Context,
    private val scope: CoroutineScope,
    val settings: TerminalSettings,
    saved: TerminalPaneSaved,
) {
    /** The session shown now. The view and its client outlive a switch of session; input goes to this one. */
    var session by mutableStateOf(session)
        private set
    var connected by mutableStateOf(false)
        private set
    private lateinit var focus: FocusManager
    private lateinit var keyboard: SoftwareKeyboardControllerCompat

    /** Called on every composition with what the page shows now. */
    fun update(session: TerminalConnection, connected: Boolean, focus: FocusManager, keyboard: SoftwareKeyboardControllerCompat) {
        this.session = session
        this.connected = connected
        this.focus = focus
        this.keyboard = keyboard
    }

    var fontSize by saved.fontSize
    var editor by saved.editor
    var commonCommandsOpen by saved.commonCommandsOpen
    var dockPinned by saved.dockPinned
        private set
    var dockExpanded by saved.dockExpanded

    var draft by mutableStateOf(TextFieldValue(session.draft, TextRange(session.draft.length)))
        private set
    var terminal by mutableStateOf<TerminalView?>(null)
    var confirmPaste by mutableStateOf<String?>(null)
    var restartConfirm by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var sessionMenu by mutableStateOf(false)
    var fileAction by mutableStateOf<FileAction?>(null)
    val sticky = StickyModifiers()

    // ---- Dock ----

    var dockBounds by mutableStateOf(Rect.Zero)
    var workspaceOrigin by mutableStateOf(Offset.Zero)
    // Only explicit pinning reserves terminal width; placement follows the IME-sized viewport.
    // Connection notices use the same golden-section anchor; show controls only when ready.
    val dockAvailable: Boolean get() = connected && !editor
    val dockVisible: Boolean get() = dockAvailable && (dockPinned || dockExpanded)
    val dockReserved: Boolean get() = dockVisible && dockPinned
    val floatingDock: Boolean get() = dockVisible && !dockReserved
    /** The pet stands in for a dock that is put away. */
    val petAvailable: Boolean get() = dockAvailable && !dockVisible
    /** Touches on a floating dock: each starts its quiet spell over. */
    var dockTouches by mutableIntStateOf(0)
    /** The share of a floating dock's quiet spell that is left; read only while drawing. */
    val dockCountdown = mutableFloatStateOf(1f)

    fun pinDock(pinned: Boolean) {
        dockPinned = pinned
        settings.dockPinned = pinned
    }

    fun dismissDockMenus() {
        menu = false
        sessionMenu = false
        if (!dockPinned) dockExpanded = false
        sticky.clear()
    }

    fun collapseDock() {
        sticky.clear()
        dockExpanded = false
        pinDock(false)
    }

    fun hideDock() {
        collapseDock()
        editor = false
        hideKeyboard()
    }

    fun changeFontSize(size: Int) {
        fontSize = size.coerceIn(TerminalSettings.MIN_FONT_SIZE, TerminalSettings.MAX_FONT_SIZE)
    }

    // ---- Pet, its ring and the keys around it ----

    /** Presses anywhere on the page: each wakes the collapsed toolbox. */
    var touches by mutableIntStateOf(0)
    var toolboxAwake by mutableStateOf(true)
    val petRing = PetRingState()
    var enterKeyAwake by mutableStateOf(false)
    var enterKeyTouches by mutableIntStateOf(0)
    var enterKeyTyping by mutableStateOf(false)
    var petArrowsAwake by mutableStateOf(false)
    var petArrowTouches by mutableIntStateOf(0)
    /** Where the pet and its arrows sit (root coordinates), so the cursor-row Enter/Esc key can step aside. */
    var petArrowBounds by mutableStateOf<Rect?>(null)

    /** An open ring and the pet leave together. */
    fun dismissPetRing() {
        petRing.close()
        if (TerminalToolbox.autoHideMillis > 0) toolboxAwake = false
    }

    /** A double tap sends Tab and brings up the keys that move through what it opens. */
    fun doubleTap() {
        terminalKey(KeyEvent.KEYCODE_TAB)
        showEnterKey()
        touches++
        petArrowTouches++
    }

    /** Shows the cursor-row Enter key at once, as after a tap rather than after typing. */
    fun showEnterKey() {
        enterKeyTyping = false
        enterKeyTouches++
    }

    /** Typing keeps the keyboard open and holds the Enter key back until it pauses. */
    fun userTyped() {
        keyboardHide?.cancel()
        enterKeyTyping = true
        enterKeyTouches++
    }

    // ---- Keys ----

    fun terminalKey(keyCode: Int) {
        // Sticky modifiers apply to arrows (e.g. Shift+Left → CSI ESC[1;2D); other keys are sent unmodified.
        if (keyCode !in ArrowKeys) sticky.clear()
        terminal?.sendInputControlKey(keyCode)
        sticky.clear()
    }

    /** An arrow key beside the pet: keeps the arrows up a while longer and sends the key at the live screen. */
    fun petArrow(keyCode: Int) {
        petArrowTouches++
        terminal?.scrollToBottom()
        terminalKey(keyCode)
    }

    /** Sends [text] at the live screen, dropping armed modifiers. */
    fun sendKeys(text: String) {
        sticky.clear()
        terminal?.scrollToBottom()
        session.write(text)
    }

    fun sendPetShortcut(shortcut: PetShortcut) {
        when (val action = shortcut.action) {
            is PetAction.Keys -> {
                sendKeys(action.keys)
                if (action.repeat) scope.launch {
                    delay(120)
                    session.write(action.keys)
                }
            }
            is PetAction.Key -> {
                val modifiers = (if (sticky.ctrl) KeyHandler.KEYMOD_CTRL else 0) or (if (sticky.shift) KeyHandler.KEYMOD_SHIFT else 0)
                sticky.clear()
                terminal?.scrollToBottom()
                terminal?.sendInputControlKey(action.keyCode, modifiers)
            }
            // Like the dock's Ctrl and Shift keys: armed for the next key, Ctrl with the keyboard up.
            PetAction.Ctrl -> {
                sticky.shift = false
                sticky.ctrl = !sticky.ctrl
                if (sticky.ctrl) terminal?.let { view ->
                    view.requestFocus()
                    SoftwareKeyboardControllerCompat(view).show()
                }
            }
            PetAction.Shift -> {
                sticky.ctrl = false
                sticky.shift = !sticky.shift
                if (sticky.shift) terminal?.requestFocus()
            }
            is PetAction.Menu -> Unit
        }
    }

    /** Swipe-and-hold jumps to the line start (Ctrl+A) or end (Ctrl+E); armed modifiers are dropped, not applied. */
    fun lineEdge(control: String) {
        terminal?.sendInputText(control)
        sticky.clear()
    }

    // ---- Keyboard ----

    fun hideKeyboard() {
        // Clear Compose/native focus before hiding so focus transfer cannot reopen the IME.
        sticky.clear()
        focus.clearFocus(force = true)
        terminal?.clearFocus()
        keyboard.hide()
    }

    // Enter puts the keyboard away after 15s without typing, so a follow-up line can still be typed.
    private var keyboardHide: Job? = null

    fun hideKeyboardSoon() {
        keyboardHide?.cancel()
        keyboardHide = scope.launch { delay(KeyboardHideAfterEnterMillis); hideKeyboard() }
    }

    fun cancelKeyboardHide() {
        keyboardHide?.cancel()
    }

    // ---- Draft and paste ----

    fun updateDraft(value: TextFieldValue) {
        draft = if (value.text.length <= MAX_PASTE_SIZE) value else TextFieldValue(value.text.take(MAX_PASTE_SIZE))
        session.draft = draft.text
    }

    /** Stages [text] in the editor at the caret, and opens the editor. */
    fun stageInEditor(text: String) {
        updateDraft(insertDraftText(draft, text, MAX_PASTE_SIZE))
        openEditor()
    }

    fun openEditor() {
        editor = true
        hideKeyboard()
    }

    fun closeEditor() {
        editor = false
        hideKeyboard()
    }

    fun sendPaste(text: String) {
        terminal?.scrollToBottom()
        session.paste(text)
    }

    /** Sends [text], asking first when its line breaks could run commands. */
    fun paste(text: String) {
        if ((text.contains('\n') || text.contains('\r')) && !session.getEmulator().isBracketedPasteEnabled) confirmPaste = text
        else sendPaste(text)
    }

    private fun clipboardText(): String? = context.clipboardText().ifEmpty { null }

    /** Stages clipboard content in the editor; reading the clipboard requires a user gesture. */
    fun showClipboard() {
        clipboardText()?.let(::stageInEditor)
    }

    /** A triple tap pastes straight into the session shown now. */
    fun pasteClipboard() {
        if (session.state.value.phase != Phase.CONNECTED) return
        clipboardText()?.let { text ->
            sticky.clear()
            paste(text)
        }
    }

    // ---- Tapped paths ----

    private var tapped by mutableStateOf<TappedFile?>(null)
    /** A tapped path that names a file, shown in the file preview at its line; it belongs to the session it was tapped in. */
    val tappedFile: TappedFile? get() = tapped?.takeIf { it.sessionId == session.id }

    fun closeTappedFile() {
        tapped = null
    }

    /** Opens the first of [candidates] (path to line) that names a file, else runs [notFound]. */
    fun openPath(candidates: List<Pair<String, Int>>, notFound: Runnable): Boolean {
        val session = session
        if (session.state.value.phase != Phase.CONNECTED) return false
        // The fallback replays a tap on the view: it must run on the main thread.
        scope.launch(Dispatchers.Main.immediate) {
            // A path a TUI broke across rows comes joined first, then as the tapped piece alone.
            val file = withTimeoutOrNull(PATH_LOOKUP_MILLIS) {
                candidates.firstNotNullOfOrNull { (path, line) -> session.terminalFile(path)?.let { TappedFile(session.id, it, line) } }
            }
            if (file == null) notFound.run() else { hideKeyboard(); tapped = file }
        }
        return true
    }

    // ---- The desktop's small window ----

    /** Focused: its touches and typing go to the desktop, until a touch on the terminal or Back takes them back. */
    var pipFocused by mutableStateOf(false)
        private set
    var pipKeyboard by mutableStateOf(false)

    /** Focusing does not bring up the keyboard: the picture stays clear; the bar's keyboard button opens it. */
    fun focusPip() {
        keyboardHide?.cancel()
        dismissDockMenus()
        hideKeyboard()
        pipFocused = true
        pipKeyboard = false
    }

    fun unfocusPip() {
        dropPipFocus()
        terminal?.requestFocus()
    }

    /** The small window went away: nothing is left to hold the input. */
    fun dropPipFocus() {
        pipFocused = false
        pipKeyboard = false
    }
}

internal data class TappedFile(val sessionId: String, val path: String, val line: Int)

private val ArrowKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)

/**
 * A tap on a path that names no file only does what a plain tap does once this is up, so a slow host would leave the
 * tap without an answer that long; a first lookup that opens SFTP takes about a second. iOS waits as long.
 */
private const val PATH_LOOKUP_MILLIS = 3_000L

@Composable
internal fun rememberTerminalPaneState(session: TerminalConnection, connected: Boolean): TerminalPaneState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { TerminalSettings(context) }
    val saved = TerminalPaneSaved(
        fontSize = rememberSaveable { mutableIntStateOf(settings.fontSize) },
        editor = rememberSaveable { mutableStateOf(false) },
        commonCommandsOpen = rememberSaveable { mutableStateOf(false) },
        dockPinned = rememberSaveable { mutableStateOf(settings.dockPinned) },
        dockExpanded = rememberSaveable { mutableStateOf(false) },
    )
    val focus = LocalFocusManager.current
    val composeView = LocalView.current
    val keyboard = remember(composeView) { SoftwareKeyboardControllerCompat(composeView) }
    return remember { TerminalPaneState(session, context, scope, settings, saved) }
        .also { it.update(session, connected, focus, keyboard) }
}
