package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.content.edit
import com.termux.terminal.KeyHandler
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import io.itermux.app.TerminalFonts
import io.itermux.app.session.*
import io.itermux.app.R
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPane(session: TerminalConnection, modifier: Modifier, sessions: List<TerminalConnection>, lightTheme: Boolean, onSelectSession: (String) -> Unit, onHostDetail: () -> Unit, onReconnect: (() -> Unit)?, onRestart: () -> Unit, onShell: () -> Unit, onReverify: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val warning by session.inputWarning.collectAsStateWithLifecycle()
    val sessionTitle by session.title.collectAsStateWithLifecycle()
    // Read only while drawing: output redraws the background without recomposing the pane.
    val outputRevision = session.revision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inputScope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember { context.getSharedPreferences("terminal-settings", Context.MODE_PRIVATE) }
    var fontSize by rememberSaveable { mutableIntStateOf(prefs.getInt("fontSize", 13)) }
    // Snapshot reads: changing font in Settings recomposes this pane and AndroidView.update.
    val terminalFontKey = TerminalFonts.selection to TerminalFonts.revision
    var editor by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue(session.draft, TextRange(session.draft.length))) }
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    var confirmPaste by remember { mutableStateOf<String?>(null) }
    var restartConfirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    var commonCommandsOpen by rememberSaveable { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var shiftArmed by remember { mutableStateOf(false) }
    var dockPinned by rememberSaveable { mutableStateOf(prefs.getBoolean("dockPinned", false)) }
    var dockExpanded by rememberSaveable { mutableStateOf(false) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }
    var workspaceOrigin by remember { mutableStateOf(Offset.Zero) }
    val focus = LocalFocusManager.current
    val composeView = LocalView.current
    val keyboard = remember(composeView) { SoftwareKeyboardControllerCompat(composeView) }
    val imeVisible = WindowInsets.isImeVisible
    // Only explicit pinning reserves terminal width; placement follows the IME-sized viewport.
    // Connection notices use the same golden-section anchor; show controls only when ready.
    val dockAvailable = state.phase == Phase.CONNECTED && !editor
    val dockVisible = dockAvailable && (dockPinned || dockExpanded)
    val dockReserved = dockVisible && dockPinned
    val floatingDock = dockVisible && !dockReserved
    fun dismissDockMenus() {
        menu = false
        sessionMenu = false
        if (!dockPinned) dockExpanded = false
        ctrlArmed = false
        shiftArmed = false
    }
    // The collapsed toolbox fades out after a quiet spell and returns on the next touch.
    var toolboxAwake by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(touches, dockAvailable, dockVisible) {
        toolboxAwake = true
        val hideAfter = TerminalToolbox.autoHideMillis
        if (dockAvailable && !dockVisible && hideAfter > 0) {
            delay(hideAfter)
            toolboxAwake = false
        }
    }
    // Holding the pet opens a ring of agent shortcuts around it; dragging onto one and letting go sends it.
    var petCenter by remember { mutableStateOf<Offset?>(null) }
    var petArc by remember { mutableStateOf<PetArc?>(null) }
    var petHovered by remember { mutableStateOf<Int?>(null) }
    // The ring matches the program in front, read from the screen when the hold starts; Fn opens a sub-ring.
    var petApp by remember { mutableStateOf(ScreenApp.SHELL) }
    var petMenu by remember { mutableStateOf<List<PetShortcut>?>(null) }
    // Letting go away from every item leaves the ring open for taps until it times out.
    var petPinned by remember { mutableStateOf(false) }
    val petShortcuts = petMenu ?: petShortcuts(petApp)
    val haptics = LocalHapticFeedback.current
    fun closePetRing() { petCenter = null; petArc = null; petHovered = null; petMenu = null; petPinned = false }
    LaunchedEffect(dockAvailable, dockVisible) {
        if (!dockAvailable || dockVisible) closePetRing()
    }
    // An open ring keeps itself and the pet up until a tap, or until it has been left alone for a long spell; then
    // both go together.
    fun dismissPetRing() { closePetRing(); if (TerminalToolbox.autoHideMillis > 0) toolboxAwake = false }
    LaunchedEffect(petPinned, touches) {
        val hideAfter = TerminalToolbox.petRingHideMillis
        if (petPinned && hideAfter > 0) {
            delay(hideAfter)
            dismissPetRing()
        }
    }
    // Resting on a menu item unfolds its sub-ring.
    LaunchedEffect(petHovered, petShortcuts) {
        val menu = petHovered?.let(petShortcuts::getOrNull)?.action as? PetAction.Menu ?: return@LaunchedEffect
        delay(350)
        petHovered = null
        petArc = null
        petMenu = menu.items
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // The cursor-row Enter key stays out of the way while typing and appears once input pauses,
    // or at once on a double tap; it fades after a quiet spell.
    var enterKeyAwake by remember { mutableStateOf(false) }
    var enterKeyTouches by remember { mutableIntStateOf(0) }
    var enterKeyTyping by remember { mutableStateOf(false) }
    // A double tap (Tab) brings Up/Down keys above and below the pet and Left to its left, to move through
    // the completions or menu it opens; they fade after a quiet spell, and each press restarts it. A line
    // starting with `/` brings them too, for its command menu, and keeps them up for as long as it does.
    val slashCommandLine by session.slashCommandLine.collectAsStateWithLifecycle()
    var petArrowsAwake by remember { mutableStateOf(false) }
    var petArrowTouches by remember { mutableIntStateOf(0) }
    // Where the pet and its arrows sit (root coordinates), so the cursor-row Enter/Esc key can step aside.
    var petArrowBounds by remember { mutableStateOf<Rect?>(null) }
    val petArrowsShown = (petArrowsAwake || slashCommandLine != 0) && petCenter == null
    LaunchedEffect(petArrowTouches) {
        if (petArrowTouches == 0) return@LaunchedEffect
        petArrowsAwake = true
        val hideAfter = TerminalToolbox.petArrowsHideMillis
        if (hideAfter > 0) {
            delay(hideAfter)
            petArrowsAwake = false
        }
    }
    LaunchedEffect(enterKeyTouches) {
        if (enterKeyTouches == 0) return@LaunchedEffect
        if (enterKeyTyping) {
            enterKeyAwake = false
            delay(TerminalToolbox.enterKeyTypingPauseMillis)
        }
        enterKeyAwake = true
        val hideAfter = TerminalToolbox.enterKeyHideMillis
        if (hideAfter > 0) {
            delay(hideAfter)
            enterKeyAwake = false
        }
    }
    // Observe the first press without consuming it: the same tap still reaches the terminal,
    // including its keyboard, selection and scrolling gestures. Pinned controls stay visible.
    val workspaceTouch = Modifier.pointerInput(dockAvailable, floatingDock) {
        if (dockAvailable) awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val presses = event.changes.filter { it.pressed && !it.previousPressed }
                if (presses.isEmpty()) continue
                touches++
                if (floatingDock && presses.any { !dockBounds.contains(it.position + workspaceOrigin) }) {
                    dismissDockMenus()
                }
            }
        }
    }
    fun updateDraft(value: TextFieldValue) {
        draft = if (value.text.length <= 128 * 1024) value else TextFieldValue(value.text.take(128 * 1024))
        session.draft = draft.text
    }
    val arrowKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    fun terminalKey(keyCode: Int) {
        // Sticky modifiers apply to arrows (e.g. Shift+Left → CSI ESC[1;2D); other keys are sent unmodified.
        if (keyCode !in arrowKeys) {
            ctrlArmed = false
            shiftArmed = false
        }
        terminal?.sendInputControlKey(keyCode)
        ctrlArmed = false
        shiftArmed = false
    }
    fun sendPetShortcut(shortcut: PetShortcut) {
        when (val action = shortcut.action) {
            is PetAction.Keys -> {
                ctrlArmed = false
                shiftArmed = false
                terminal?.scrollToBottom()
                session.write(action.keys)
                if (action.repeat) inputScope.launch {
                    delay(120)
                    session.write(action.keys)
                }
            }
            is PetAction.Key -> {
                val modifiers = (if (ctrlArmed) KeyHandler.KEYMOD_CTRL else 0) or (if (shiftArmed) KeyHandler.KEYMOD_SHIFT else 0)
                ctrlArmed = false
                shiftArmed = false
                terminal?.scrollToBottom()
                terminal?.sendInputControlKey(action.keyCode, modifiers)
            }
            // Like the dock's Ctrl and Shift keys: armed for the next key, Ctrl with the keyboard up.
            PetAction.Ctrl -> {
                shiftArmed = false
                ctrlArmed = !ctrlArmed
                if (ctrlArmed) terminal?.let { view ->
                    view.requestFocus()
                    SoftwareKeyboardControllerCompat(view).show()
                }
            }
            PetAction.Shift -> {
                ctrlArmed = false
                shiftArmed = !shiftArmed
                if (shiftArmed) terminal?.requestFocus()
            }
            is PetAction.Menu -> Unit
        }
    }
    /** Swipe-and-hold jumps to the line start (Ctrl+A) or end (Ctrl+E); armed modifiers are dropped, not applied. */
    fun lineEdge(control: String) {
        terminal?.sendInputText(control)
        ctrlArmed = false
        shiftArmed = false
    }
    fun hideKeyboard() {
        // Clear Compose/native focus before hiding so focus transfer cannot reopen the IME.
        ctrlArmed = false
        shiftArmed = false
        focus.clearFocus(force = true)
        terminal?.clearFocus()
        keyboard.hide()
    }
    // Enter puts the keyboard away after 15s without typing, so a follow-up line can still be typed.
    var keyboardHide by remember { mutableStateOf<Job?>(null) }
    fun hideKeyboardSoon() {
        keyboardHide?.cancel()
        keyboardHide = inputScope.launch { delay(KeyboardHideAfterEnterMillis); hideKeyboard() }
    }
    fun sendPaste(text: String) {
        terminal?.scrollToBottom()
        session.paste(text)
    }
    fun paste(text: String) {
        if ((text.contains('\n') || text.contains('\r')) && !session.getEmulator().isBracketedPasteEnabled) confirmPaste = text
        else sendPaste(text)
    }
    fun clipboardText(): String? = context.getSystemService(ClipboardManager::class.java).primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    fun showClipboard() {
        // Stage clipboard content in the editor; reading clipboard requires a user gesture.
        clipboardText()?.let { updateDraft(insertDraftText(draft, it, 128 * 1024)); editor = true; hideKeyboard() }
    }
    // AndroidView can outlive a recomposition/reconnection. Always paste into the current session.
    val pasteClipboard by rememberUpdatedState {
        if (session.state.value.phase == Phase.CONNECTED) clipboardText()?.let { text ->
            ctrlArmed = false
            shiftArmed = false
            paste(text)
        }
    }
    DisposableEffect(session) {
        session.copyToClipboard = { text -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(tr("终端选区"), text)) }
        session.pasteFromClipboard = { showClipboard() }
        onDispose {
            session.onScreenChanged = null; session.copyToClipboard = null; session.pasteFromClipboard = null
        }
    }
    LaunchedEffect(fontSize) { prefs.edit { putInt("fontSize", fontSize) } }
    LaunchedEffect(session.id) { hideKeyboard() }
    LaunchedEffect(state.phase) {
        if (state.phase != Phase.CONNECTED) {
            ctrlArmed = false; shiftArmed = false; confirmPaste = null
            menu = false; sessionMenu = false
        }
        else session.inputWarning.value = null
    }
    // Opening the arrow menu can hide the IME. Keep Shift armed until the arrow is sent.
    // A keyboard closed some other way drops a pending hide, so reopening it is not undone.
    LaunchedEffect(imeVisible) { if (!imeVisible) { ctrlArmed = false; keyboardHide?.cancel() } }
    BackHandler(editor || imeVisible) { editor = false; hideKeyboard() }
    val fileControls: @Composable () -> Unit = {
        TerminalFileActions(enabled = state.phase == Phase.CONNECTED && !transferStatus.running) { action ->
            hideKeyboard()
            fileAction = action
        }
    }
    val dock: @Composable (Dp) -> Unit = { availableHeight ->
        TerminalDock(availableHeight, landscape, imeVisible, modifier = Modifier.onGloballyPositioned { dockBounds = it.boundsInRoot() }, collapseControl = {
            IconButton(onClick = {
                ctrlArmed = false
                shiftArmed = false
                dockExpanded = false
                dockPinned = false
                prefs.edit { putBoolean("dockPinned", false) }
            }, modifier = Modifier.testTag("collapse-dock")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = tr("收起操作栏"), modifier = Modifier.size(20.dp))
            }
        }, sessionControl = { sessionModifier ->
            Box(sessionModifier) {
                TextButton(onClick = { hideKeyboard(); sessionMenu = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = trf("切换会话：%s", session.titleForDisplay(sessionTitle)) }.testTag("session-switcher"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    // The dot on the sessions icon doubles as the connection indicator.
                    Box {
                        Icon(painterResource(R.drawable.ic_sessions), contentDescription = null, modifier = Modifier.size(22.dp))
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(8.dp).clip(CircleShape)
                            .background(if (state.phase == Phase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
                    }
                }
                DropdownMenu(sessionMenu, ::dismissDockMenus) {
                    DropdownMenuItem(text = { Text(tr("返回主机详情")) }, onClick = { sessionMenu = false; onHostDetail() })
                    DropdownMenuItem(text = { Text(tr("关闭当前会话")) }, onClick = { sessionMenu = false; onClose() },
                        modifier = Modifier.testTag("close-session"))
                    // A tmux pane restarts over the live connection; a plain shell restarts by reconnecting.
                    DropdownMenuItem(
                        text = { Text(tr("重启当前会话")) },
                        onClick = { sessionMenu = false; restartConfirm = true },
                        enabled = session.mode == SessionMode.SHELL || state.phase == Phase.CONNECTED,
                        modifier = Modifier.testTag("restart-session")
                    )
                    HorizontalDivider()
                    sessions.forEach { other ->
                        val otherState by other.state.collectAsStateWithLifecycle()
                        val otherTitle by other.title.collectAsStateWithLifecycle()
                        DropdownMenuItem(text = { Text("${if (other.id == session.id) "✓ " else ""}${other.titleForDisplay(otherTitle)} · ${phaseLabel(otherState.phase)}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { sessionMenu = false; onSelectSession(other.id) })
                    }
                }
            }
        }, primaryControls = {
            fileControls()
            IconButton(
                onClick = { hideKeyboard(); commonCommandsOpen = true },
                enabled = state.phase == Phase.CONNECTED,
                modifier = Modifier.testTag("common-commands")
            ) {
                Icon(painterResource(R.drawable.ic_commands), contentDescription = tr("常用命令"))
            }
            Box {
                IconButton(onClick = { ctrlArmed = false; shiftArmed = false; menu = true }, modifier = Modifier.testTag("terminal-menu")) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = tr("终端设置"))
                }
                DropdownMenu(menu, ::dismissDockMenus) {
                    DropdownMenuItem(text = { Text(tr("编辑文本")) }, onClick = { menu = false; editor = true; hideKeyboard() })
                    DropdownMenuItem(text = { Text(if (dockPinned) tr("取消固定操作栏") else tr("固定操作栏")) }, onClick = {
                        menu = false; dockPinned = !dockPinned; dockExpanded = true
                        prefs.edit { putBoolean("dockPinned", dockPinned) }
                    })
                    DropdownMenuItem(text = { Text(tr("隐藏操作栏")) }, onClick = {
                        menu = false; dockPinned = false; dockExpanded = false; editor = false; hideKeyboard()
                        prefs.edit { putBoolean("dockPinned", false) }
                    })
                    DropdownMenuItem(text = { Text(tr("粘贴")) }, onClick = { menu = false; showClipboard() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(trf("增大字号 · %d sp", fontSize)) }, onClick = { fontSize = (fontSize + 1).coerceAtMost(28); menu = false })
                    DropdownMenuItem(text = { Text(trf("减小字号 · %d sp", fontSize)) }, onClick = { fontSize = (fontSize - 1).coerceAtLeast(8); menu = false })
                }
            }
        })
    }
    // The workspace extends under the transparent status bar in the terminal's own background
    // colour (including remote OSC 11 changes). In portrait the text runs under the bar too, faded
    // by a scrim so the clock and icons stay legible; controls and notices stay below the bar.
    // The keyboard inset jumps to its final height instead of following the slide animation:
    // every intermediate height would reflow the emulator and send the remote a resize, which
    // made opening and closing the keyboard stutter. The background still fills the area the
    // keyboard slides over.
    BoxWithConstraints(modifier.fillMaxWidth().testTag("terminal-workspace")
        .drawBehind { drawRect(terminalBackground(session, outputRevision.value)) }
        .windowInsetsPadding(WindowInsets.imeAnimationTarget)
        .onGloballyPositioned { workspaceOrigin = it.positionInRoot() }.then(workspaceTouch)) {
        val workspaceHeight = maxHeight
        val safeDockHeight = with(LocalDensity.current) {
            val edges = WindowInsets.displayCutout.union(WindowInsets.statusBars)
            workspaceHeight - edges.getTop(this).toDp() - edges.getBottom(this).toDp() - 8.dp
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                var viewportHeight by remember { mutableIntStateOf(0) }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("terminal-viewport").onSizeChanged { viewportHeight = it.height }) {
                AndroidView(modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp, top = 6.dp).clipToBounds()
                    .testTag("terminal"), factory = { ctx ->
                    // Session, theme, font and history are bound in update, which also runs after creation.
                    TerminalView(ctx, null).apply {
                        terminal = this
                        setTerminalViewClient(MobileTerminalClient(this,
                            onScale = { factor -> fontSize = (fontSize * factor).toInt().coerceIn(8, 28) },
                            controlArmed = { ctrlArmed }, consumeControl = { ctrlArmed = false },
                            shiftArmed = { shiftArmed }, consumeShift = { shiftArmed = false },
                            onDoubleTap = {
                                terminalKey(KeyEvent.KEYCODE_TAB)
                                enterKeyTyping = false
                                enterKeyTouches++
                                touches++
                                petArrowTouches++
                            }, onTripleTap = { pasteClipboard() },
                            // Swipes send plain arrows; an armed Shift still makes Shift+Left for Codex questions.
                            onSwipeLeft = { terminalKey(KeyEvent.KEYCODE_DPAD_LEFT) }, onSwipeRight = { terminalKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
                            onSwipeHoldLeft = { lineEdge("\u0001") }, onSwipeHoldRight = { lineEdge("\u0005") },
                            // Enter usually runs or submits the line: put the keyboard away soon so the output shows.
                            onSoftKeyboardEnter = { hideKeyboardSoon() }))
                        // Typing again keeps the keyboard open; the Enter that follows restarts the wait.
                        setUserInputListener { keyboardHide?.cancel(); enterKeyTyping = true; enterKeyTouches++ }
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        isFocusableInTouchMode = true
                    }
                }, update = { view ->
                    // Compose may reuse the view: always rebind the session and its screen callback.
                    view.attachSession(session)
                    view.setCursorDraggingEnabled(state.phase == Phase.CONNECTED)
                    session.onScreenChanged = { view.onScreenUpdated() }
                    if (session.getEmulator().mColors.setLightTheme(lightTheme)) view.onScreenUpdated()
                    view.setTextSize((fontSize * view.resources.displayMetrics.scaledDensity).toInt())
                    view.setTypeface(TerminalFonts.resolveTypeface(view.context))
                    checkNotNull(terminalFontKey)
                })
                val cursorKeysAllowed = state.phase == Phase.CONNECTED && !editor
                androidx.compose.animation.AnimatedVisibility(enterKeyAwake && cursorKeysAllowed,
                    modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(120)), exit = fadeOut(tween(400))) {
                    terminal?.let { view ->
                        CursorRowKeys(view, outputRevision, viewportHeight, CursorKeyHeight, avoid = petArrowBounds.takeIf { petArrowsShown }) {
                            CursorKey(R.drawable.ic_return, tr("回车"), "cursor-enter") {
                                enterKeyTyping = false
                                enterKeyTouches++
                                ctrlArmed = false
                                shiftArmed = false
                                view.scrollToBottom()
                                session.write("\r")
                                // Like the soft keyboard's Enter: put an open keyboard away so the output shows.
                                if (imeVisible) hideKeyboardSoon()
                            }
                        }
                    }
                }
                // Esc holds the Enter key's place whenever Enter is hidden.
                androidx.compose.animation.AnimatedVisibility(!enterKeyAwake && cursorKeysAllowed,
                    modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                    terminal?.let { view ->
                        CursorRowKeys(view, outputRevision, viewportHeight, CursorKeyHeight, avoid = petArrowBounds.takeIf { petArrowsShown }) {
                            CursorKey(null, tr("发送 Esc"), "cursor-escape", label = "Esc") {
                                ctrlArmed = false
                                shiftArmed = false
                                view.scrollToBottom()
                                session.write("\u001b")
                            }
                        }
                    }
                }
                // Hidden (landscape) status bars have no inset, so the scrim only appears under a visible bar.
                val statusBars = WindowInsets.statusBars
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(statusBars.add(WindowInsets(top = 12.dp)))
                    .testTag("status-bar-scrim").drawBehind {
                        val statusBar = statusBars.getTop(this).toFloat()
                        if (statusBar <= 0f) return@drawBehind
                        val background = terminalBackground(session, outputRevision.value)
                        drawRect(Brush.verticalGradient(0f to background.copy(alpha = 0.85f),
                            statusBar / size.height to background.copy(alpha = 0.55f), 1f to Color.Transparent))
                    })
                if (pending == null) {
                    if (state.phase != Phase.CONNECTED) TerminalNoticeOverlay {
                        if (state.active) TerminalConnectionProgress(state.phase, onCancel = session::disconnect)
                        else TerminalConnectionNotice(state, onReconnect, onShell, onReverify)
                    }
                    else warning?.let { message -> TerminalNoticeOverlay {
                        TerminalInputNotice(message) { session.inputWarning.value = null }
                    } }
                }
                }
                if (editor && landscape) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(draft, ::updateDraft,
                            placeholder = { Text(tr("输入文本后，在这里确认"), maxLines = 1) },
                            minLines = 1, maxLines = if (imeVisible) 2 else 3,
                            modifier = Modifier.weight(1f).testTag("prompt-editor"))
                        DraftInputControls(draft, ::updateDraft)
                        TextButton(onClick = { editor = false; hideKeyboard() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("收起")) }
                        TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("回车 ↵")) }
                        Button(onClick = { paste(draft.text) }, enabled = draft.text.isNotEmpty() && state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(tr("发送文本")) }
                    }
                } else if (editor) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        OutlinedTextField(draft, ::updateDraft, placeholder = { Text(tr("输入文本后，在这里确认")) }, minLines = 1, maxLines = if (imeVisible) 2 else 4, modifier = Modifier.fillMaxWidth().testTag("prompt-editor"))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DraftInputControls(draft, ::updateDraft)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { editor = false; hideKeyboard() }, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(4.dp)) { Text(tr("收起")) }
                            TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED, modifier = Modifier.width(48.dp).semantics { contentDescription = tr("回车") }, contentPadding = PaddingValues(4.dp)) { Text("↵", fontSize = 22.sp) }
                            Button(onClick = { paste(draft.text) }, enabled = draft.text.isNotEmpty() && state.phase == Phase.CONNECTED, modifier = Modifier.width(88.dp), contentPadding = PaddingValues(8.dp)) { Text(tr("发送文本")) }
                        }
                    }
                }
            }
            if (dockReserved) TerminalDockPlacement(
                Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                    .width(if (terminalDockTwoColumns(landscape, imeVisible, safeDockHeight)) 96.dp else 48.dp),
                endInset = 0.dp, content = dock)
        }
        val ringCenter = petCenter
        if (ringCenter != null && dockAvailable && !dockVisible) {
            val topInset = WindowInsets.displayCutout.union(WindowInsets.statusBars).getTop(LocalDensity.current).toFloat()
            PetShortcutArc(petShortcuts, ringCenter, topInset, petHovered, armed = { action ->
                action == PetAction.Ctrl && ctrlArmed || action == PetAction.Shift && shiftArmed
            }, onTap = if (petPinned) { index ->
                val picked = index?.let(petShortcuts::getOrNull)
                val menu = picked?.action as? PetAction.Menu
                if (menu != null) {
                    petArc = null
                    petMenu = menu.items
                } else {
                    dismissPetRing()
                    if (picked != null && state.phase == Phase.CONNECTED) sendPetShortcut(picked)
                }
            } else null) { petArc = it }
        }
        if (floatingDock) {
            TerminalDockPlacement { height ->
                Surface(shape = terminalDockShape(terminalDockTwoColumns(landscape, imeVisible, height)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f), shadowElevation = 4.dp
                ) { dock(height) }
            }
        } else if (dockAvailable && !dockVisible) {
            // Leave the composition while hidden so taps on that edge reach the terminal.
            // The ring opens around the pet, so the arrows step aside while it is up.
            AnimatedVisibility(toolboxAwake || petCenter != null || petArrowsShown, enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 2 },
                exit = fadeOut(tween(400)) + slideOutHorizontally(tween(400)) { it / 3 }) {
                // Up above the pet, Down below it and Left to its left; the pet stays put between them.
                TerminalDockPlacement { Column(Modifier.onGloballyPositioned { petArrowBounds = it.boundsInRoot() },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.animation.AnimatedVisibility(petArrowsShown, enter = fadeIn(tween(120)), exit = fadeOut(tween(200))) {
                        CursorKey(R.drawable.ic_expand_up, tr("发送上方向键"), "pet-arrow-up") {
                            petArrowTouches++
                            terminal?.scrollToBottom()
                            terminalKey(KeyEvent.KEYCODE_DPAD_UP)
                        }
                    }
                    Box(contentAlignment = Alignment.CenterStart) {
                        ToolboxPet(tr("显示操作栏"), onHoldStart = { center ->
                            petHovered = null
                            petMenu = null
                            petPinned = false
                            petApp = session.screenApp()
                            petCenter = center - workspaceOrigin
                        }, onHoldMove = { drag ->
                            val arc = petArc
                            // Back over the pet leaves a sub-ring.
                            if (petMenu != null && arc != null && drag.getDistance() < arc.radius * 0.25f) {
                                petMenu = null
                                petArc = null
                            }
                            val hovered = petArc?.hoveredItem(drag)
                            if (hovered != petHovered) {
                                petHovered = hovered
                                if (hovered != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }, onHoldEnd = { released ->
                            val picked = petHovered?.let(petShortcuts::getOrNull)
                            val menu = picked?.action as? PetAction.Menu
                            touches++
                            if (released && (picked == null || menu != null)) {
                                // Let go between items: keep the ring up for a tap. A menu not yet unfolded opens now.
                                petHovered = null
                                if (menu != null) { petArc = null; petMenu = menu.items }
                                petPinned = true
                            } else {
                                closePetRing()
                                if (released && picked != null && state.phase == Phase.CONNECTED) sendPetShortcut(picked)
                            }
                        }) { dockExpanded = true }
                        // Takes no width, so the column stays centred on the pet; the key hangs off its left side.
                        Box(Modifier.layout { measurable, constraints ->
                            val key = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(0, 0) { key.placeRelative(-key.width, -key.height / 2) }
                        }) {
                            androidx.compose.animation.AnimatedVisibility(petArrowsShown, enter = fadeIn(tween(120)), exit = fadeOut(tween(200))) {
                                CursorKey(R.drawable.ic_expand_left, tr("发送左方向键"), "pet-arrow-left") {
                                    petArrowTouches++
                                    terminal?.scrollToBottom()
                                    terminalKey(KeyEvent.KEYCODE_DPAD_LEFT)
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(petArrowsShown, enter = fadeIn(tween(120)), exit = fadeOut(tween(200))) {
                        CursorKey(R.drawable.ic_expand_down, tr("发送下方向键"), "pet-arrow-down") {
                            petArrowTouches++
                            terminal?.scrollToBottom()
                            terminalKey(KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                    }
                } }
            }
        }
    }
    pending?.let { request -> PromptDialog(request) }
    FileTransferUi(session, fileAction, onInsertPath = { path ->
        updateDraft(insertDraftText(draft, CodexImageAttach.pathForPaste(path), 128 * 1024)); editor = true; hideKeyboard()
    }) { fileAction = null }
    if (commonCommandsOpen) CommonCommandsSheet(session, onSelect = { command ->
        commonCommandsOpen = false
        editor = false
        hideKeyboard()
        terminal?.scrollToBottom()
        session.executeHistoryCommand(command)
    }, onDismiss = { commonCommandsOpen = false })
    confirmPaste?.let { text -> AlertDialog(onDismissRequest = { confirmPaste = null }, title = { Text(tr("发送多行文本？")) }, text = { Text(tr("远端尚未启用 bracketed paste。文本中的换行可能立即执行命令，请确认当前程序能安全接收多行输入。")) }, confirmButton = { TextButton(onClick = { sendPaste(text); confirmPaste = null }, enabled = state.phase == Phase.CONNECTED) { Text(tr("发送")) } }, dismissButton = { TextButton(onClick = { confirmPaste = null }) { Text(tr("取消")) } }) }
    if (restartConfirm) AlertDialog(onDismissRequest = { restartConfirm = false }, title = { Text(tr("重启当前会话？")) },
        text = { Text(if (session.mode == SessionMode.TMUX) tr("正在运行的程序会被结束，然后在同一目录重新启动。") else tr("当前 shell 会结束并重新连接。")) },
        confirmButton = { TextButton(onClick = { restartConfirm = false; onRestart() }, modifier = Modifier.testTag("restart-session-confirm")) { Text(tr("重启")) } },
        dismissButton = { TextButton(onClick = { restartConfirm = false }) { Text(tr("取消")) } })
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
}

/** Keys at the right end of the cursor row, [height] tall and centred on it; hidden while the cursor is scrolled away. */
@Composable
private fun CursorRowKeys(view: TerminalView, outputRevision: State<Long>, viewportHeight: Int, height: Dp,
    avoid: Rect? = null, keys: @Composable () -> Unit) {
    // Output moves the cursor: follow it on every screen revision.
    checkNotNull(outputRevision.value)
    val rowTop = view.getCursorRowTop()
    if (rowTop < 0) return
    val density = LocalDensity.current
    // The terminal view sits 6dp below the viewport top; centre the keys on the cursor row.
    val keyTop = with(density) {
        val top = 6.dp.roundToPx() + rowTop + view.lineHeight / 2 - height.roundToPx() / 2
        top.coerceIn(0, (viewportHeight - height.roundToPx()).coerceAtLeast(0))
    }
    // The pet's Up/Down/Left keys sit at the same right edge: when the row falls within their span, slide left of them.
    var containerTop by remember { mutableFloatStateOf(0f) }
    val keyShift = if (avoid != null && containerTop + keyTop < avoid.bottom && containerTop + keyTop + with(density) { height.roundToPx() } > avoid.top)
        with(density) { (avoid.width + 8.dp.toPx() + 40.dp.toPx()).roundToInt() } else 0
    Box(Modifier.offset { IntOffset(-keyShift, keyTop) }.onGloballyPositioned { containerTop = it.positionInRoot().y - keyTop }) { keys() }
}

private val CursorKeyHeight = 32.dp
internal const val KeyboardHideAfterEnterMillis = 15_000L

/** A slim key drawn inside a larger touch area, tinted on press; shows [icon], or [label] when there is none. */
@Composable
private fun CursorKey(icon: Int?, description: String, tag: String, label: String = "", onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.size(40.dp, CursorKeyHeight)
        .clickable(interaction, indication = null, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.size(28.dp, 20.dp), shape = RoundedCornerShape(6.dp),
            color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp))
                else Text(label, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The colour the terminal renderer fills its canvas with, so the status bar area matches it. */
private fun terminalBackground(session: TerminalConnection, @Suppress("UNUSED_PARAMETER") revision: Long): Color {
    val emulator = session.getEmulator()
    return Color(emulator.mColors.mCurrentColors[if (emulator.isReverseVideo) TextStyle.COLOR_INDEX_FOREGROUND
        else TextStyle.COLOR_INDEX_BACKGROUND])
}
